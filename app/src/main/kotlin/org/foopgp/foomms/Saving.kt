package org.foopgp.foomms

import android.app.Activity
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Saving the messages to a file, or putting them back from one: every
 * conversation from the configuration page, one from its own menu. The
 * page that asks hands what the file chooser answers to [done].
 */
object Saving {

    const val SAVE = 0x5A1
    const val RESTORE = 0x5A2
    const val SAVE_EACH = 0x5A3

    /**
     * Every conversation saved: in one file -- about [bytes] and a third, what Base64 adds to the parts -- or in
     * a folder, one file each, as if each had been saved from its own menu. Asked first.
     */
    fun saveAll(activity: Activity, bytes: Long?) {
        val ask = bytes?.takeIf { it > 0 }?.let { activity.getString(R.string.backup_all_ask, android.text.format.Formatter.formatShortFileSize(activity, it / 3 * 4)) }
            ?: activity.getString(R.string.backup_all_ask_unknown)
        // The two ways one under the other, in the order they are read; cancelling is the dialog's own, last.
        var shown: android.app.AlertDialog? = null
        val column = Look.column(activity)
        fun way(words: Int, then: () -> Unit) = column.addView(
            Look.button(activity, activity.getString(words)) { shown?.dismiss() ; then() },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        way(R.string.backup_all_one) { save(activity) }
        way(R.string.backup_all_each) {
            @Suppress("DEPRECATION")
            runCatching { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SAVE_EACH) }
        }
        // No title: the question says it all, the button pressed having said it already.
        shown = android.app.AlertDialog.Builder(activity).setMessage(ask).setView(column).setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun day(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())

    /** A name that can stand in a file name: letters and digits, the rest a dash. */
    private fun whose(name: String?): String = name?.let { "-" + it.replace(Regex("[^\\p{L}\\p{N}+]+"), "-").trim('-').take(60).trim('-') }.orEmpty()

    /** The conversation the file being chosen is for; none, and it is for all of them. */
    private var saving: Long? = null

    fun save(activity: Activity, thread: Long? = null, name: String? = null) {
        saving = thread
        val day = day()
        val whose = whose(name)
        @Suppress("DEPRECATION")
        activity.startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/xml")
                .putExtra(Intent.EXTRA_TITLE, "sms$whose-$day.xml"),
            SAVE,
        )
    }

    /** False when the phone does not let this application write messages: nothing is asked then. */
    fun restore(activity: Activity): Boolean {
        // Writing in the phone's store is its messaging application's alone.
        if (!Keep.ours(activity)) return false
        @Suppress("DEPRECATION")
        // Several at once: one saved per conversation is put back whole.
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            RESTORE,
        )
        return true
    }

    /** True when the answer was one of these; [say] hears that it is under way, then how it went. */
    fun done(activity: Activity, request: Int, outcome: Int, data: Intent?, say: (String) -> Unit): Boolean {
        if (request != SAVE && request != RESTORE && request != SAVE_EACH) return false
        if (outcome != Activity.RESULT_OK) return true
        if (request == RESTORE) {
            val files = data?.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
            if (files.isNotEmpty()) restoreAll(activity, files, say)
            return true
        }
        val where = data?.data ?: return true
        val only = saving
        say(activity.getString(R.string.backup_working))
        if (request == SAVE_EACH) {
            thread {
                val words = runCatching { each(activity, where) }.getOrElse { activity.getString(R.string.failed, it.message) }
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) say(words) }
            }
            return true
        }
        thread {
            val words = runCatching {
                val done = activity.contentResolver.openOutputStream(where)?.use { Backup.write(activity, it, only) } ?: error("nowhere to write")
                activity.getString(R.string.backup_saved, done.sms, done.mms)
            }.getOrElse { activity.getString(R.string.failed, it.message) }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) say(words) }
        }
        return true
    }

    /**
     * Each file put back in turn, then how each went -- what came back, or why nothing did -- said together;
     * [say] told once all are done, for what shows the messages to be read again.
     */
    private fun restoreAll(activity: Activity, files: List<android.net.Uri>, say: (String) -> Unit) {
        say(activity.getString(R.string.backup_working))
        thread {
            val said = files.map { file ->
                val name = runCatching {
                    activity.contentResolver.query(file, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: file.lastPathSegment ?: "?"
                val how = runCatching {
                    val done = activity.contentResolver.openInputStream(file)?.use { Backup.read(activity, it) } ?: error("nothing to read")
                    activity.getString(R.string.backup_restored, done.sms, done.mms, done.skipped)
                }.getOrElse { activity.getString(R.string.failed, it.message ?: it.javaClass.simpleName) }
                "$name\n$how"
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                android.app.AlertDialog.Builder(activity).setMessage(said.joinToString("\n\n")).setPositiveButton(R.string.close, null).show()
                say(activity.getString(R.string.backup_restored_all))
            }
        }
    }

    /**
     * Each conversation in its own file, in a folder made in the one chosen -- named as one saved alone is,
     * after whom it is with --: what was saved, said.
     */
    private fun each(activity: Activity, tree: android.net.Uri): String {
        val resolver = activity.contentResolver
        val day = day()
        val top = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val folder = android.provider.DocumentsContract.createDocument(resolver, top, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "sms-$day")
            ?: error("no folder made")
        var files = 0
        var sms = 0
        var mms = 0
        for (one in Backup.threads(activity)) {
            val with = Store.members(activity, one).joinToString("+") { Book.who(activity, it)?.name ?: it }.ifEmpty { one.toString() }
            val file = android.provider.DocumentsContract.createDocument(resolver, folder, "text/xml", "sms${whose(with)}-$day.xml") ?: continue
            val done = resolver.openOutputStream(file)?.use { Backup.write(activity, it, one) } ?: continue
            files++ ; sms += done.sms ; mms += done.mms
        }
        return activity.resources.getQuantityString(R.plurals.backup_saved_each, files, files, sms, mms)
    }
}
