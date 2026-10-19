package org.foopgp.foomms

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import java.io.File

/**
 * TRIAL ONLY (branch mneme/video-trial): a video a message carries, watched
 * here, whole screen, with its controls. One opened from its lock is in
 * memory only: written to the cache while it is watched, and gone with the page.
 */
class WatchActivity : Activity() {

    private val watching by lazy { File(cacheDir, "watching.3gp") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val video = VideoView(this)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(video, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            },
        )
        val opened = held
        held = null
        if (opened != null) {
            watching.writeBytes(opened)
            video.setVideoPath(watching.path)
        } else {
            video.setVideoURI(intent.data ?: return finish())
        }
        video.setMediaController(MediaController(this).apply { setAnchorView(video) })
        video.setOnPreparedListener { video.start() }
    }

    override fun onDestroy() {
        watching.delete()
        super.onDestroy()
    }

    companion object {
        /** A video opened from its lock, handed over in memory. */
        @Volatile var held: ByteArray? = null

        fun watch(activity: Activity, bytes: ByteArray?, uri: Uri?) {
            held = bytes
            activity.startActivity(android.content.Intent(activity, WatchActivity::class.java).setData(uri))
        }
    }
}
