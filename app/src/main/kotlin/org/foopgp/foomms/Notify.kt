package org.foopgp.foomms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import org.openintents.openpgp.OpenPgpSignatureResult

/**
 * Saying that a message has arrived: from whom, and its words -- or, for a
 * PGP message, what it is: the words of one are not shown on a locked screen
 * that anybody can read, and cannot be before the security key has opened it.
 */
object Notify {

    /**
     * [form]: what the message travelled as, told from [body] when not given
     * -- an MMS carries it in its parts too. [done] is called once nothing
     * more is to be said of it: a signed message is verified after being
     * announced, and the receiver keeps the process alive until then.
     */
    fun arrived(context: Context, number: String, body: String, form: Form = Form.of(body), group: Long? = null, done: () -> Unit = {}) {
        // Not announced: a sender the person asked not to hear of, or a group.
        if (Marks.muted(context, number)) return done()
        if (group != null && Marks.muted(context, "group:" + Phone.group(Store.members(context, group), Me.all(context)))) return done()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return done()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        val armor = Pgp.armorIn(body)
        // A PGP message arrived is likely opened soon: foodjis woken for it.
        if (form != Form.PLAIN) Foodjis.of(context).wake()
        val said = when {
            armor == null -> body
            Pgp.isLocked(armor) -> context.getString(R.string.locked_message)
            else -> context.getString(R.string.signed_message)
        }
        // A group's message says so first, whatever it holds -- once, a message of parts alone having said it already.
        val among = Members.glyph(Members.PART)
        val words = if (group != null && !said.startsWith(among)) "$among $said" else said
        val person = runCatching { Book.who(context, number) }.getOrNull()
        val who = person?.name ?: number
        // The contact's picture, when the address book has one: read with the name, from the same card.
        val face = person?.let { runCatching { Book.face(context, it) }.getOrNull() }
        // Against the notifications' own background: the phone's theme, not this application's.
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun show(text: String, colour: Int) {
            val open = PendingIntent.getActivity(
                context, group?.hashCode() ?: number.hashCode(),
                Intent(context, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number)
                    .apply { group?.let { putExtra(ThreadActivity.EXTRA_THREAD, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val shown = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.mark)
                .setContentTitle(who)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(open)
                .setAutoCancel(true)
                // Said again once verified: heard once.
                .setOnlyAlertOnce(true)
                .setColor(colour)
                .apply { face?.let { setLargeIcon(it) } }
                .build()
            runCatching { manager.notify(group?.hashCode() ?: number.hashCode(), shown) }
        }
        show(words, Look.colour(form, dark))
        if (armor == null || Pgp.isLocked(armor)) return done()
        // Signed only, foodjis reads it without the key: its words follow the glyph, in the colour of what the signature is worth.
        verify(context.applicationContext, armor, done) { read, certified ->
            show(context.getString(R.string.signed_words, read), Look.colour(if (certified) Verdict.BLUE else Verdict.RED, dark))
        }
    }

    /** foodjis asked to verify [armor]; [then] hears its words when the signature holds; [done] in any case, and at the latest after [PATIENCE]. */
    private fun verify(context: Context, armor: String, done: () -> Unit, then: (String, Boolean) -> Unit) {
        val provider = Provider(context)
        val over = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun end() {
            if (!over.compareAndSet(false, true)) return
            runCatching { provider.close() }
            done()
        }
        main.postDelayed(::end, PATIENCE)
        provider.ask(Intent(Provider.DECRYPT_VERIFY), armor.toByteArray()) { answer ->
            if (answer is Provider.Answer.Done && !over.get()) {
                // Kept as the conversation would keep it: opened there, it is read already.
                val signature = runCatching { Signer.keep(context, armor, answer) }.getOrNull()
                when (signature?.result) {
                    OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> then(String(answer.bytes, Charsets.UTF_8), true)
                    OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> then(String(answer.bytes, Charsets.UTF_8), false)
                }
            }
            end()
        }
    }

    fun seen(context: Context, number: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(number.hashCode())
    }

    /** Every message's notification taken away: everything was just read. */
    fun seenAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
    }

    private const val CHANNEL = "messages"

    /** How long foodjis is waited for, in milliseconds: a receiver has ten seconds. */
    private const val PATIENCE = 8_000L
}
