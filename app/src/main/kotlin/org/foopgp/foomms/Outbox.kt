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

    /** The heaviest message the network takes, by what the operator's settings say; 300 kB when they say nothing. */
    fun heaviest(context: Context): Int =
        runCatching { manager(context).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) }
            .getOrDefault(0).takeIf { it > 0 } ?: (300 * 1024)

    fun send(context: Context, to: String, pdu: ByteArray, transaction: String) {
        File(context.cacheDir, DIR).mkdirs()
        file(context, transaction).writeBytes(pdu)
        journal(context).edit().putString(transaction, "${System.currentTimeMillis()}\t$to\t${pdu.size}\t$GOING").apply()
        val told = PendingIntent.getBroadcast(
            context, transaction.hashCode(),
            Intent(context, Told::class.java).putExtra(EXTRA_TRANSACTION, transaction),
            // The service adds what the network answered: the intent must let it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val from = Uri.parse("content://${context.packageName}.pdu/$transaction")
        manager(context).sendMultimediaMessage(context, from, null, null, told)
        // The phone keeps what its messaging application tells it to, and nothing of what another sends.
        if (Keep.ours(context)) runCatching { Keep.mmsSent(context, to, pdu) }
    }

    /** How many SMS a text would take. */
    fun pieces(context: Context, words: String): Int = manager(context).divideMessage(words).size

    /** A text as SMS, in as many pieces as it takes; says how many. */
    fun text(context: Context, to: String, words: String): Int {
        val manager = manager(context)
        val pieces = manager.divideMessage(words)
        val transaction = "S" + java.lang.Long.toHexString(System.currentTimeMillis())
        journal(context).edit()
            .putString(transaction, "${System.currentTimeMillis()}\t$to\t${words.length}\t$GOING").apply()
        // Told once, by the last piece: the others answer nobody.
        // The phone writes what another application sends; its messaging application writes its own.
        val written = if (Keep.ours(context)) runCatching { Keep.smsSent(context, to, words) }.getOrNull() else null
        val told = PendingIntent.getBroadcast(
            context, transaction.hashCode(),
            Intent(context, Told::class.java).putExtra(EXTRA_TRANSACTION, transaction).putExtra(EXTRA_WRITTEN, written?.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answers = ArrayList<PendingIntent?>(pieces.map { null }).apply { this[size - 1] = told }
        manager.sendMultipartTextMessage(to, null, pieces, answers, null)
        return pieces.size
    }

    class Line(val at: Long, val to: String, val weight: Int, val outcome: String)

    fun lines(context: Context): List<Line> = journal(context).all.values.mapNotNull { kept ->
        val said = (kept as? String)?.split('\t') ?: return@mapNotNull null
        if (said.size != 4) return@mapNotNull null
        Line(said[0].toLongOrNull() ?: return@mapNotNull null, said[1], said[2].toIntOrNull() ?: 0, said[3])
    }.sortedByDescending { it.at }

    fun forget(context: Context) = journal(context).edit().clear().apply()

    private fun manager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun journal(context: Context) = context.getSharedPreferences("sent", Context.MODE_PRIVATE)

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

    /** The file a message waits in, on its way out or on its way in. */
    fun file(context: Context, transaction: String) =
        File(File(context.cacheDir, DIR), transaction.filter { it.isLetterOrDigit() } + ".pdu")

    /** The phone's MMS service telling what became of a sending. */
    class Told : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val transaction = intent.getStringExtra(EXTRA_TRANSACTION) ?: return
            file(context, transaction).delete()
            val outcome = if (resultCode == android.app.Activity.RESULT_OK) GONE else "$REFUSED $resultCode"
            if (outcome != GONE) intent.getStringExtra(EXTRA_WRITTEN)?.let { runCatching { Keep.smsFailed(context, Uri.parse(it)) } }
            val kept = journal(context)
            kept.getString(transaction, null)?.let { line ->
                kept.edit().putString(transaction, line.substringBeforeLast('\t') + "\t" + outcome).apply()
            }
            val words = if (outcome == GONE) R.string.sent else R.string.not_sent
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
            // The photograph being taken: written by the camera, read back here.
            if (uri.pathSegments.firstOrNull() == SHOT) {
                val how = if ('w' in mode) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                else ParcelFileDescriptor.MODE_READ_ONLY
                return ParcelFileDescriptor.open(shotFile(context), how)
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
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    const val GOING = "going"
    const val GONE = "gone"
    const val REFUSED = "refused"
    private const val DIR = "out"
    private const val EXTRA_TRANSACTION = "transaction"
    private const val EXTRA_WRITTEN = "written"
}
