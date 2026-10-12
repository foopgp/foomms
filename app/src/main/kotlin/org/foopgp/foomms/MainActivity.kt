package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.concurrent.thread

/**
 * The PGP messages this phone has received by MMS, newest first, and what
 * became of those sent from here. Everything else the phone holds -- the
 * other messages, the photos -- stays in its messaging application.
 */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.app_title)
        val column = Look.column(this)
        column.addView(Look.words(this, getString(R.string.about), muted = true))
        column.addView(Look.button(this, R.string.write) { startActivity(Intent(this, WriteActivity::class.java)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) fill()
        else {
            list.removeAllViews()
            list.addView(Look.words(this, getString(R.string.why_read)))
            list.addView(Look.button(this, R.string.allow) { requestPermissions(arrayOf(Manifest.permission.READ_SMS), 1) })
            sent()
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) fill()
    }

    private fun fill() {
        thread {
            val letters = runCatching { Inbox.letters(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                list.addView(Look.words(this, getString(R.string.received, letters.size), size = 19f))
                for (letter in letters) {
                    val who = letter.number ?: getString(R.string.unknown_number)
                    val line = getString(if (letter.incoming) R.string.from else R.string.to, who, ago(letter.at))
                    list.addView(
                        Look.button(this, R.string.read) {
                            startActivity(
                                Intent(this, ReadActivity::class.java)
                                    .putExtra(ReadActivity.EXTRA_PART, letter.part)
                                    .putExtra(ReadActivity.EXTRA_NUMBER, letter.number.takeIf { letter.incoming }),
                            )
                        }.apply { text = getString(R.string.read_line, line) },
                    )
                }
                sent()
            }
        }
    }

    /** What became of the messages sent from here: no words of theirs are kept, only these lines. */
    private fun sent() {
        val lines = Outbox.lines(this)
        if (lines.isEmpty()) return
        list.addView(Look.words(this, getString(R.string.sent_from_here, lines.size), size = 19f))
        for (line in lines) {
            val outcome = when (line.outcome) {
                Outbox.GONE -> getString(R.string.outcome_gone)
                Outbox.GOING -> getString(R.string.outcome_going)
                else -> getString(R.string.outcome_refused, line.outcome.substringAfter(' '))
            }
            list.addView(Look.words(this, getString(R.string.sent_line, line.to, ago(line.at), line.weight / 1024 + 1, outcome)))
        }
        list.addView(Look.button(this, R.string.forget) { Outbox.forget(this) ; onResume() })
    }

    private fun ago(at: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
}
