package org.foopgp.foomms

import android.app.Activity
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import java.io.File

/**
 * TRIAL ONLY (branch mneme/video-trial): a video a message carries, watched
 * here, the screen upright or on its side, the picture never stretched. A
 * button turns it a quarter at a time: what the file says of which way up it
 * is may be wrong, or missing. One opened from its lock is in memory only:
 * written to the cache while it is watched, and gone with the page.
 */
class WatchActivity : Activity(), TextureView.SurfaceTextureListener {

    private val watching by lazy { File(cacheDir, "watching.3gp") }
    private lateinit var screen: TextureView
    private lateinit var playing: android.widget.TextView
    private lateinit var where: SeekBar
    private var player: MediaPlayer? = null
    private var wide = 0
    private var high = 0

    /** The quarter turns the person added, in degrees. */
    private var turned = 0

    private val following = object : Runnable {
        override fun run() {
            val player = player ?: return
            runCatching { where.progress = player.currentPosition }
            where.postDelayed(this, 250)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        screen = TextureView(this).apply { surfaceTextureListener = this@WatchActivity }
        playing = Look.button(this, "❚❚") { toggle() }
        where = SeekBar(this).apply {
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, at: Int, byPerson: Boolean) { if (byPerson) player?.seekTo(at) }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        val pad = Look.dp(this, 12)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(screen, FrameLayout.LayoutParams(-1, -1))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(pad, pad, pad, Look.dp(context, 32))
                        addView(playing)
                        addView(where, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(Look.button(context, "⟳") { turned = (turned + 90) % 360 ; fit() })
                    },
                    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
                )
            },
        )
        // Fitted again whenever the screen turns, or anything else changes its size.
        screen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val opened = held
        held = null
        player = runCatching {
            MediaPlayer().apply {
                if (opened != null) {
                    watching.writeBytes(opened)
                    setDataSource(watching.path)
                } else {
                    setDataSource(this@WatchActivity, intent.data ?: error("nothing to watch"))
                }
                setSurface(Surface(texture))
                // The size as it is to be seen, the file's own turn already taken into account.
                setOnVideoSizeChangedListener { _, w, h -> wide = w ; high = h ; fit() }
                // Watched to the end, the page goes: back to the conversation.
                setOnCompletionListener { finish() }
                prepare()
                where.max = duration
                start()
            }
        }.getOrElse { finish() ; null }
        where.post(following)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fit()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /**
     * The picture as large as the screen allows, in its proportions, turned
     * by the person: a texture fills its view unless told otherwise, and that
     * is what stretched it.
     */
    private fun fit() {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        if (w == 0f || h == 0f || wide == 0 || high == 0) return
        val across = turned == 90 || turned == 270
        val seenWide = if (across) high else wide
        val seenHigh = if (across) wide else high
        val scale = minOf(w / seenWide, h / seenHigh)
        screen.setTransform(
            Matrix().apply {
                postScale(wide * scale / w, high * scale / h, w / 2, h / 2)
                postRotate(turned.toFloat(), w / 2, h / 2)
            },
        )
    }

    private fun toggle() {
        val player = player ?: return
        if (player.isPlaying) player.pause() else player.start()
        playing.text = if (player.isPlaying) "❚❚" else "▶"
    }

    override fun onPause() {
        super.onPause()
        player?.let { if (it.isPlaying) { it.pause() ; playing.text = "▶" } }
    }

    override fun onDestroy() {
        player?.release()
        player = null
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
