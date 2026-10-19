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

    /** What lies over the picture: whose it is above, the controls below; a press on the picture shows or hides them. */
    private val over = ArrayList<android.view.View>()

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
        // The whole screen to the picture: no title bar, the phone's own bars away until swiped back.
        actionBar?.hide()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        screen = TextureView(this).apply { surfaceTextureListener = this@WatchActivity }
        screen.setOnClickListener { val shown = over.firstOrNull()?.visibility != android.view.View.VISIBLE ; over.forEach { it.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE } }
        playing = bold(Look.button(this, "❚❚") { toggle() })
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
                // Whose it is, over the picture's top, on a shade that keeps it legible.
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 20), Look.dp(context, 28), Look.dp(context, 20), Look.dp(context, 20))
                        background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(android.graphics.Color.argb(0xB0, 0, 0, 0), android.graphics.Color.TRANSPARENT),
                        )
                        addView(android.widget.TextView(context).apply {
                            text = intent.getStringExtra(EXTRA_TITLE).orEmpty() ; textSize = 18f
                            setTextColor(android.graphics.Color.WHITE) ; setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                        addView(android.widget.TextView(context).apply {
                            text = intent.getStringExtra(EXTRA_SAID).orEmpty() ; textSize = 13f
                            setTextColor(android.graphics.Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
                        })
                    }.also { over += it },
                    FrameLayout.LayoutParams(-1, -2, Gravity.TOP),
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(pad, pad, pad, Look.dp(context, 32))
                        // Stopped, the page goes: the one way out before the end, besides the phone's own back.
                        addView(bold(Look.button(context, "■") { finish() }).apply { contentDescription = getString(R.string.listening_stop) })
                        addView(playing)
                        addView(where, LinearLayout.LayoutParams(0, -2, 1f))
                        // Upright to lying, in characters: a round arrow would read as "again".
                        addView(
                            bold(Look.button(context, "▯→▭") { turned = (turned + 90) % 360 ; fit() })
                                .apply { contentDescription = getString(R.string.watch_turn) },
                        )
                        background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                            intArrayOf(android.graphics.Color.argb(0xB0, 0, 0, 0), android.graphics.Color.TRANSPARENT),
                        )
                    }.also { over += it },
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

    /** The player's buttons in bold, so that thin characters weigh as much as a drawn icon. */
    private fun bold(button: android.widget.Button) = button.apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 18f
        // As narrow as what they say: the bar between them is where one moves in the video.
        minWidth = 0 ; minimumWidth = 0
        setPadding(Look.dp(context, 14), paddingTop, Look.dp(context, 14), paddingBottom)
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

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SAID = "said"

        /** [title]: whose conversation; [said]: the video's name and when it came. */
        fun watch(activity: Activity, bytes: ByteArray?, uri: Uri?, title: String, said: String) {
            held = bytes
            activity.startActivity(
                android.content.Intent(activity, WatchActivity::class.java).setData(uri).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SAID, said),
            )
        }
    }
}
