package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * What a message carries, or its words, handed to another application
 * through Android's sharing: from a picture's or a video's own page, or from
 * a bubble pressed long. What travelled locked leaves in clear: the person is
 * told so first, and shares it or not.
 */
object HandOut {

    /** [bytes] as opened, or else what [uri] holds as it came; [locked]: it travelled encrypted. */
    fun share(activity: Activity, bytes: ByteArray?, uri: Uri?, name: String, type: String?, locked: Boolean) =
        warned(activity, locked, R.string.share_clear_says) { go(activity, bytes, uri, name, type) }

    /** A message's words, as they read. */
    fun shareText(activity: Activity, text: String, locked: Boolean) = warned(activity, locked, R.string.share_clear_says_text) {
        runCatching { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
            .onFailure { Toast.makeText(activity, activity.getString(R.string.failed, it.message), Toast.LENGTH_LONG).show() }
    }

    private fun warned(activity: Activity, locked: Boolean, says: Int, then: () -> Unit) {
        if (!locked) return then()
        AlertDialog.Builder(activity)
            .setTitle(R.string.share_clear_title)
            .setMessage(says)
            .setPositiveButton(R.string.share_clear_go) { _, _ -> then() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The menu's "share", its arrow made the share's own sign, in the colour of the words. */
    fun label(activity: Activity, words: String): CharSequence {
        val sign = activity.getDrawable(R.drawable.share)?.mutate() ?: return words
        val ink = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorAlertDialogListItem)).let { val c = it.getColorStateList(0) ; it.recycle() ; c }
        // As the words are drawn, enabled: a list's colours begin with the greyed one.
        ink?.let { sign.setTint(it.getColorForState(intArrayOf(android.R.attr.state_enabled), it.defaultColor)) }
        val side = Look.dp(activity, 20)
        sign.setBounds(0, 0, side, side)
        val rest = words.removePrefix("⤴").trimStart()
        return android.text.SpannableString("  $rest").apply {
            setSpan(android.text.style.ImageSpan(sign, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun go(activity: Activity, bytes: ByteArray?, uri: Uri?, name: String, type: String?) {
        thread {
            val handed = runCatching {
                val held = bytes ?: uri?.let { activity.contentResolver.openInputStream(it)?.use { from -> from.readBytes() } } ?: error("nothing to share")
                Outbox.toOpen(activity, name, held, type)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val (where, told) = handed.getOrElse {
                    return@runOnUiThread Toast.makeText(activity, activity.getString(R.string.failed, it.message), Toast.LENGTH_LONG).show()
                }
                val asked = Intent(Intent.ACTION_SEND).setType(told).putExtra(Intent.EXTRA_STREAM, where).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                asked.clipData = android.content.ClipData.newRawUri(null, where)
                runCatching { activity.startActivity(Intent.createChooser(asked, null)) }
                    .onFailure { Toast.makeText(activity, activity.getString(R.string.failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }
}
