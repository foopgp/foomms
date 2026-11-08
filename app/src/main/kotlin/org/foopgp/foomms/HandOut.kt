package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * A picture or a video looked at on its own page, handed to another
 * application through Android's sharing. One that came locked leaves in
 * clear: the person is told so first, and shares it or not.
 */
object HandOut {

    /** [bytes] as opened, or else what [uri] holds as it came; [locked]: it came encrypted. */
    fun share(activity: Activity, bytes: ByteArray?, uri: Uri?, name: String, type: String?, locked: Boolean) {
        if (!locked) return go(activity, bytes, uri, name, type)
        AlertDialog.Builder(activity)
            .setTitle(R.string.share_clear_title)
            .setMessage(R.string.share_clear_says)
            .setPositiveButton(R.string.share_clear_go) { _, _ -> go(activity, bytes, uri, name, type) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
