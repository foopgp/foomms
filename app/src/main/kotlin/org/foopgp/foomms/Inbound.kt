package org.foopgp.foomms

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import java.io.File

/**
 * What the phone hands its messaging application: an SMS that arrived, the
 * announcement of an MMS that waits, and a quick answer to a call. Each is
 * written in the phone's store first, announced second: a message is not to
 * be lost to anything that follows its arrival.
 */
object Inbound {

    private const val TAG = "foomms"

    /** An SMS, whole: the network cuts a long one in pieces, the phone hands them together. */
    class Sms : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
            val pieces = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
            val from = pieces.firstOrNull()?.displayOriginatingAddress ?: return
            val body = pieces.joinToString("") { it.displayMessageBody.orEmpty() }
            val subscription = intent.getIntExtra("subscription", -1)
            runCatching { Keep.smsReceived(context, from, body, pieces.first().timestampMillis, subscription) }
                .onFailure { Log.e(TAG, "an SMS could not be written", it) }
            // Kept alive until a signed message has been verified for its notification.
            val pending = goAsync()
            runCatching { Notify.arrived(context, from, body) { pending.finish() } }.onFailure { pending.finish() }
        }
    }

    /**
     * The announcement of an MMS: the message itself waits on the operator's
     * server, and the phone's MMS service is asked to fetch it into a file of
     * ours. Delivery reports and the like come the same way and are let go.
     */
    class Mms : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
            val pushed = intent.getByteArrayExtra("data") ?: return
            val announced = runCatching { PduRead.read(pushed) }.getOrNull() ?: return
            if (announced.kind != PduRead.NOTIFICATION) return
            val where = announced.location ?: return
            val name = "in" + java.lang.Long.toHexString(System.nanoTime())
            val subscription = intent.getIntExtra("subscription", -1)
            val fetched = PendingIntent.getBroadcast(
                context, name.hashCode(),
                Intent(context, Fetched::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_SUBSCRIPTION, subscription)
                    .putExtra(EXTRA_FROM, announced.from),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            runCatching {
                Outbox.file(context, name).apply { parentFile?.mkdirs() ; createNewFile() }
                manager(context, subscription).downloadMultimediaMessage(
                    context, where, Uri.parse("content://${context.packageName}.pdu/$name"), null, fetched,
                )
            }.onFailure { Log.e(TAG, "an MMS could not be asked for", it) }
        }
    }

    /** The MMS fetched, or not: read, written in the phone's store, announced. */
    class Fetched : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val file: File = Outbox.file(context, intent.getStringExtra(EXTRA_NAME) ?: return)
            try {
                if (resultCode != Activity.RESULT_OK) return Log.e(TAG, "an MMS was not fetched: $resultCode").let { }
                val pdu = file.readBytes()
                val message = PduRead.read(pdu)
                val from = message.from ?: intent.getStringExtra(EXTRA_FROM) ?: return
                // A group's MMS this phone sent, come back to it -- to several, or to this phone alone as some networks deliver
                // a group's, its members part telling it --: kept already as sent, and held against it.
                val ours = message.to.size > 1 || message.parts.any { Members.isPart(it.name ?: it.location) }
                if (ours && Me.mine(context, from)) return runCatching { Echo.back(context, message.to, message) }.let { }
                Keep.mmsReceived(context, message, pdu.size, intent.getIntExtra(EXTRA_SUBSCRIPTION, -1))
                val words = message.parts.filter { it.isText }.joinToString("\n") { it.text() }
                    .ifEmpty { Members.glyphs(message.parts.filter { it.type != "application/smil" }.map { it.name ?: it.location }) }
                // Its form, of its parts: a PGP message carried whole, or in its words.
                val sealed = message.parts.filter { !it.isText && (it.type == Pgp.TYPE || it.name.orEmpty().lowercase().let { n -> n.endsWith(".pgp") || n.endsWith(".gpg") || n.endsWith(".asc") }) }
                val form = Form.of(Form.of(words) == Form.LOCKED || sealed.any { Pgp.isLocked(it.bytes) }, Form.of(words) != Form.PLAIN || sealed.isNotEmpty())
                val pending = goAsync()
                // To several, the group's conversation is the one to open.
                val group = Keep.conversationOf(context, from, message).takeIf { it.size > 1 }
                    ?.let { runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(context, it.toSet()) }.getOrNull() }
                runCatching { Notify.arrived(context, from, words, form, group) { pending.finish() } }.onFailure { pending.finish() }
            } catch (failed: Exception) {
                Log.e(TAG, "an MMS could not be read or written", failed)
            } finally {
                file.delete()
            }
        }
    }

    /** "Answer with a message", pressed on the screen of a call: sent as it is, with no page shown. */
    class Respond : Service() {
        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val to = intent?.data?.schemeSpecificPart?.split(',', ';')?.mapNotNull { Phone.plain(it) }.orEmpty()
            val words = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (words.isNotEmpty()) to.forEach { number -> runCatching { Outbox.text(this, number, words) } }
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    fun manager(context: Context, subscription: Int): SmsManager = when {
        subscription >= 0 -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscription)
        Build.VERSION.SDK_INT >= 31 -> context.getSystemService(SmsManager::class.java)
        else -> @Suppress("DEPRECATION") SmsManager.getDefault()
    }

    private const val EXTRA_NAME = "name"
    private const val EXTRA_SUBSCRIPTION = "subscription"
    private const val EXTRA_FROM = "from"
}
