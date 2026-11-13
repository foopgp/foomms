package org.foopgp.foomms

import android.app.Activity
import kotlin.concurrent.thread

/**
 * What is about to be sent, checked before it leaves: a picture shown small
 * and looked at whole, a film by its first picture and watched on its page,
 * a sound listened to here -- pressed again, it stops.
 */
object Preview {

    private var player: android.media.MediaPlayer? = null

    /** [what] shown in [carried]'s row, or nothing when it is none; [title], whose page it is, over a whole view. */
    fun show(activity: Activity, carried: Look.Carried, what: Media.Attachment?, title: String) {
        stop()
        carried.shows(null, false, null)
        if (what == null) return
        val type = what.type
        thread {
            val side = Look.dp(activity, 112)
            val small = when {
                type.startsWith("image/") -> Media.thumbnail(what.bytes, side)
                type.startsWith("video/") -> Media.frame(what.bytes, side)
                else -> null
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    type.startsWith("image/") && small != null -> carried.shows(small, false) {
                        PictureActivity.look(activity, what.bytes, null, title, what.name, what.name, type, false)
                    }
                    type.startsWith("video/") && small != null -> carried.shows(small, true) {
                        WatchActivity.watch(activity, what.bytes, null, title, what.name, what.name, type, false)
                    }
                    // A sound -- or a film without a picture, a voice in its box --: listened to where it is.
                    type.startsWith("audio/") || type.startsWith("video/") -> carried.shows(null, true) { listen(what.bytes) }
                }
            }
        }
    }

    private fun listen(bytes: ByteArray) {
        if (player != null) return stop()
        player = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(Media.source(bytes))
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.getOrNull()
    }

    /** What plays, stopped: the page left, the attachment taken off or sent. */
    fun stop() {
        val playing = player ?: return
        player = null
        runCatching { playing.stop() ; playing.release() }
    }
}
