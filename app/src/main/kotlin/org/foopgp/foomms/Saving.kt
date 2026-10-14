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

    /** The conversation the file being chosen is for; none, and it is for all of them. */
    private var saving: Long? = null

    fun save(activity: Activity, thread: Long? = null, name: String? = null) {
        saving = thread
        val day = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
        // A name that can stand in a file name: letters and digits, the rest a dash.
        val whose = name?.let { "-" + it.replace(Regex("[^\\p{L}\\p{N}+]+"), "-").trim('-') }.orEmpty()
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
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), RESTORE)
        return true
    }

    /** True when the answer was one of these; [say] hears that it is under way, then how it went. */
    fun done(activity: Activity, request: Int, outcome: Int, data: Intent?, say: (String) -> Unit): Boolean {
        if (request != SAVE && request != RESTORE) return false
        val where = data?.data?.takeIf { outcome == Activity.RESULT_OK } ?: return true
        val only = saving
        say(activity.getString(R.string.backup_working))
        thread {
            val words = runCatching {
                if (request == SAVE) {
                    val done = activity.contentResolver.openOutputStream(where)?.use { Backup.write(activity, it, only) } ?: error("nowhere to write")
                    activity.getString(R.string.backup_saved, done.sms, done.mms)
                } else {
                    val done = activity.contentResolver.openInputStream(where)?.use { Backup.read(activity, it) } ?: error("nothing to read")
                    activity.getString(R.string.backup_restored, done.sms, done.mms, done.skipped)
                }
            }.getOrElse { activity.getString(R.string.failed, it.message) }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) say(words) }
        }
        return true
    }
}
