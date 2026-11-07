package org.foopgp.foomms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.telephony.SmsManager
import android.widget.Toast
import java.io.File

/**
 * What is on its way: the message as the network will get it, in a file the
 * phone's own MMS service comes and reads, and a line saying what became of
 * each sending -- when, for whom, how heavy. Never what was written.
 */
object Outbox {

    /**
     * What every network takes: 300 kB, the "Video Rich" content class of
     * OMA's MMS conformance document (OMA-TS-MMS-CONF V1.3, table 1); its
     * section 7.1.11 lets a client be set to exactly 300 or 600 kB. An
     * operator may say more, but the one who receives may refuse more.
     */
    const val STANDARD = 300 * 1024

    /**
     * What a message needs beside what it carries: up to ten recipients and
     * oneself locked for (some 90 bytes each with Curve25519), a signature,
     * the MMS's own envelope, and a few kB of text armored beside a part.
     */
    const val MARGIN = 24 * 1024

    /** What the operator's settings say a message may weigh, when they say it. */
    fun operators(context: Context): Int? =
        runCatching { manager(context).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) }.getOrDefault(0).takeIf { it > 0 }

    /** How many SMS the operator says a text may leave in before it is an MMS, when it says: a hint, for the person. */
    fun operatorsSmsMost(context: Context): Int? =
        runCatching { manager(context).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD, -1) }.getOrDefault(-1).takeIf { it > 0 }

    /** How many an MMS may go to, as the operator says, when it says: a hint, for the person -- often a careful one. */
    fun operatorsGroupMost(context: Context): Int? =
        runCatching { manager(context).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_RECIPIENT_LIMIT, 0) }.getOrDefault(0).takeIf { it in 1 until Int.MAX_VALUE }

    /** The heaviest message sent: the lesser of the standard and the operator's; where the operator takes more, what the person set within it. */
    fun heaviest(context: Context): Int {
        val theirs = operators(context) ?: return STANDARD
        if (theirs <= STANDARD) return theirs
        val set = Conf.mmsSize(context)?.let { it * 1024 } ?: if (Conf.operatorSize(context)) theirs else STANDARD
        return set.coerceIn(STANDARD, theirs)
    }

    fun send(context: Context, to: List<String>, pdu: ByteArray, transaction: String, at: Long = System.currentTimeMillis()) {
        // The last word on weight, whatever made the message: heavier than set, it does not leave.
        val most = heaviest(context)
        check(pdu.size <= most) { context.getString(R.string.too_heavy_sent, (pdu.size + 1023) / 1024, most / 1024) }
        File(context.cacheDir, DIR).mkdirs()
        file(context, transaction).writeBytes(pdu)
        // The phone keeps what its messaging application tells it to, and nothing of what another sends: written
        // before it leaves, so that a refusal can be marked on it.
        val written = if (Keep.ours(context)) runCatching { Keep.mmsSent(context, to, pdu, at) }.getOrNull() else null
        val told = PendingIntent.getBroadcast(
            context, transaction.hashCode(),
            Intent(context, Told::class.java).putExtra(EXTRA_TRANSACTION, transaction).putExtra(EXTRA_WRITTEN, written?.toString()),
            // The service adds what the network answered: the intent must let it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val from = Uri.parse("content://${context.packageName}.pdu/$transaction")
        runCatching { manager(context).sendMultimediaMessage(context, from, null, null, told) }.onFailure { failure ->
            written?.let { runCatching { Keep.mmsFailed(context, it) } }
            file(context, transaction).delete()
            throw failure
        }
        // To a group, this phone among them: what left kept, to hold what comes back against it.
        runCatching { Echo.sent(context, to, pdu) }
    }

    /** How many SMS a text would take. */
    fun pieces(context: Context, words: String): Int = manager(context).divideMessage(words).size

    /** A text as SMS, in as many pieces as it takes; says how many. */
    fun text(context: Context, to: String, words: String, at: Long = System.currentTimeMillis()): Int {
        val manager = manager(context)
        val pieces = manager.divideMessage(words)
        // By the nanosecond: several sent in one millisecond -- quick answers to a call -- shared their notice.
        val transaction = "S" + java.lang.Long.toHexString(System.nanoTime())
        // Told once, by the last piece: the others answer nobody.
        // The phone writes what another application sends; its messaging application writes its own.
        val written = if (Keep.ours(context)) runCatching { Keep.smsSent(context, to, words, at) }.getOrNull() else null
        val told = PendingIntent.getBroadcast(
            context, transaction.hashCode(),
            Intent(context, Told::class.java).putExtra(EXTRA_TRANSACTION, transaction).putExtra(EXTRA_WRITTEN, written?.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Refused at once -- no service, a text of nothing --: kept as not sent, as a refusal by the network is.
        runCatching {
            val answers = ArrayList<PendingIntent?>(pieces.map { null }).apply { this[size - 1] = told }
            manager.sendMultipartTextMessage(to, null, pieces, answers, null)
        }.onFailure { failure ->
            written?.let { runCatching { Keep.smsFailed(context, it) } }
            throw failure
        }
        return pieces.size
    }

    /**
     * The operator's server told an MMS it announced as [transaction] was fetched ([Pdu.fetched]): sent as an
     * MMS is, through the phone's service, and said to nobody.
     */
    fun acknowledge(context: Context, transaction: String, subscription: Int) {
        val name = "A" + java.lang.Long.toHexString(System.nanoTime())
        File(context.cacheDir, DIR).mkdirs()
        file(context, name).writeBytes(Pdu.fetched(transaction))
        val told = PendingIntent.getBroadcast(
            context, name.hashCode(),
            Intent(context, Told::class.java).putExtra(EXTRA_TRANSACTION, name).putExtra(EXTRA_QUIET, true),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        runCatching {
            Inbound.manager(context, subscription).sendMultimediaMessage(context, Uri.parse("content://${context.packageName}.pdu/$name"), null, null, told)
        }.onFailure { file(context, name).delete() }
    }

    fun manager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()


    private const val OPENED = "open"
    private const val SHOT = "shot"

    private fun shotFile(context: Context) = File(File(context.cacheDir, SHOT).apply { mkdirs() }, "photo.jpg")

    /** Where the camera is to write the photograph about to be taken. */
    fun shot(context: Context): Uri {
        shotFile(context).delete()
        return Uri.parse("content://${context.packageName}.pdu/$SHOT/photo.jpg")
    }
    private val types = HashMap<String, String>()

    /** What was laid out for another application, gone: it may have been opened from a lock, and that is closed now. */
    fun shut(context: Context) {
        File(context.cacheDir, OPENED).listFiles()?.forEach { it.delete() }
        types.clear()
    }

    private fun opened(context: Context, name: String) = File(File(context.cacheDir, OPENED), name.filter { it.isLetterOrDigit() || it in "._-" })

    /**
     * What a message carries, laid where another application can be let in
     * to read it, under what its bytes say it is: the address to hand over,
     * and the type.
     */
    fun toOpen(context: Context, name: String, bytes: ByteArray, labelled: String?): Pair<Uri, String> {
        val told = Kind.of(bytes.copyOf(minOf(bytes.size, 32)), labelled)
        val safe = name.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "file" }
        val called = if (safe.substringAfterLast('.', "").equals(told.extension, true)) safe else "$safe.${told.extension}"
        File(context.cacheDir, OPENED).apply { mkdirs() ; listFiles()?.forEach { it.delete() } }
        opened(context, called).writeBytes(bytes)
        types[called] = told.type
        return Uri.parse("content://${context.packageName}.pdu/$OPENED/$called") to told.type
    }

    /**
     * Whether [uri] is a path, read with foomms's own rights -- its files, what was opened from its lock among them
     * --: never taken as something handed over to send. Applications share by content addresses since Android 7;
     * a path is nobody's to hand over. What foomms serves itself is: what was opened here, shared back to foomms
     * to be sent on, is the person's own doing.
     */
    fun path(uri: Uri): Boolean = uri.scheme == "file"

    /** The file a message waits in, on its way out or on its way in. */
    fun file(context: Context, transaction: String) =
        File(File(context.cacheDir, DIR), transaction.filter { it.isLetterOrDigit() } + ".pdu")

    /** The phone's MMS service telling what became of a sending. */
    class Told : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val transaction = intent.getStringExtra(EXTRA_TRANSACTION) ?: return
            file(context, transaction).delete()
            // An answer to the operator's server, not a message: nothing to mark, nothing to say.
            if (intent.getBooleanExtra(EXTRA_QUIET, false)) return
            val gone = resultCode == android.app.Activity.RESULT_OK
            // Refused: certainly not sent, and marked so where it is kept. Taken, it is not known to have arrived.
            if (!gone) intent.getStringExtra(EXTRA_WRITTEN)?.let { kept ->
                val where = Uri.parse(kept)
                runCatching { if (where.authority == "mms") Keep.mmsFailed(context, where) else Keep.smsFailed(context, where) }
            }
            val words = if (gone) R.string.sent else R.string.not_sent
            // An MMS left a file behind; an SMS did not, and deleting nothing is no harm.
            Toast.makeText(context, context.getString(words, resultCode), Toast.LENGTH_LONG).show()
        }
    }

    /** The door the MMS service reads a waiting message through: that file, to whom Android let in, and nothing else. */
    class Files : ContentProvider() {
        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val context = context ?: return null
            // A file being opened by another application: read, by whoever Android was told to let in.
            if (uri.pathSegments.firstOrNull() == OPENED) {
                return ParcelFileDescriptor.open(opened(context, uri.lastPathSegment ?: return null), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            // The photograph being taken: written by the camera, read back here. Open Camera writes it
            // ("w"), then opens it again to add its EXIF ("rw"): that second opening must not empty it.
            if (uri.pathSegments.firstOrNull() == SHOT) {
                return ParcelFileDescriptor.open(shotFile(context), ParcelFileDescriptor.parseMode(mode))
            }
            val transaction = uri.lastPathSegment ?: return null
            // Read by the service for a message that leaves, written by it for one it fetched.
            val how = if ('w' in mode) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else ParcelFileDescriptor.MODE_READ_ONLY
            return ParcelFileDescriptor.open(file(context, transaction), how)
        }

        override fun getType(uri: Uri): String =
            if (uri.pathSegments.firstOrNull() == SHOT) "image/jpeg"
            else if (uri.pathSegments.firstOrNull() == OPENED) types[uri.lastPathSegment].orEmpty().ifEmpty { "application/octet-stream" }
            else "application/vnd.wap.mms-message"
        /**
         * The name and size of a file laid out for another application, as
         * Android's documents say they are asked: without them, whoever is
         * handed the file makes up a name -- a forwarded "photo.jpg.pgp" arrived
         * as "sound.pgp-encrypted".
         */
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? {
            val context = context ?: return null
            if (uri.pathSegments.firstOrNull() != OPENED) return null
            val name = uri.lastPathSegment ?: return null
            val asked = p ?: arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
            return android.database.MatrixCursor(asked).apply {
                addRow(
                    asked.map { column ->
                        when (column) {
                            android.provider.OpenableColumns.DISPLAY_NAME -> name
                            android.provider.OpenableColumns.SIZE -> opened(context, name).length()
                            else -> null
                        }
                    },
                )
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    private const val DIR = "out"
    private const val EXTRA_TRANSACTION = "transaction"
    private const val EXTRA_WRITTEN = "written"
    private const val EXTRA_QUIET = "quiet"
}
