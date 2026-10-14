package org.foopgp.foomms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import kotlin.concurrent.thread

/**
 * Saving the messages to a file, or putting them back from one: every
 * conversation, or the one this page was opened from.
 */
class BackupActivity : Activity() {

    private val thread by lazy { intent.getLongExtra(EXTRA_THREAD, -1L).takeIf { it >= 0 } }
    private val named by lazy { intent.getStringExtra(EXTRA_NAME) }
    private lateinit var said: android.widget.TextView

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(if (thread == null) R.string.backup_all else R.string.backup_one)
        val column = Look.column(this)
        column.addView(Look.words(this, getString(if (thread == null) R.string.backup_about else R.string.backup_about_one, named.orEmpty()), size = 15f, muted = true))
        column.addView(
            Look.button(this, getString(R.string.backup_save)) {
                val day = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/xml")
                        .putExtra(Intent.EXTRA_TITLE, "sms-$day.xml"),
                    SAVE,
                )
            }.apply { (layoutParams as? android.widget.LinearLayout.LayoutParams)?.topMargin = Look.dp(context, 12) },
        )
        column.addView(
            Look.button(this, getString(R.string.backup_restore)) {
                // Writing in the phone's store is its messaging application's alone.
                if (!Keep.ours(this)) return@button say(getString(R.string.forget_not_ours))
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), RESTORE)
            },
        )
        said = Look.words(this, "", size = 15f)
        column.addView(said)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun say(words: String) { said.text = words }

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        val where = data?.data?.takeIf { outcome == RESULT_OK } ?: return
        say(getString(R.string.backup_working))
        thread {
            val words = runCatching {
                if (request == SAVE) {
                    val done = contentResolver.openOutputStream(where)?.use { Backup.write(this, it, thread) } ?: error("nowhere to write")
                    getString(R.string.backup_saved, done.sms, done.mms)
                } else {
                    val done = contentResolver.openInputStream(where)?.use { Backup.read(this, it) } ?: error("nothing to read")
                    getString(R.string.backup_restored, done.sms, done.mms, done.skipped)
                }
            }.getOrElse { getString(R.string.failed, it.message) }
            runOnUiThread { if (!isFinishing && !isDestroyed) say(words) }
        }
    }

    companion object {
        const val EXTRA_THREAD = "thread"
        const val EXTRA_NAME = "name"
        private const val SAVE = 1
        private const val RESTORE = 2
    }
}
