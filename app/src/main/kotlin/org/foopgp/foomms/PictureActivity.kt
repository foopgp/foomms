package org.foopgp.foomms

import android.app.Activity
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * A picture a message carries, on the whole screen: spread or pinched to
 * look closer, moved with a finger, a double press to go in or back out. A
 * button turns it a quarter at a time, as the video's page does, and a stop
 * goes back to the conversation, the share beside it. One opened from its
 * lock is in memory only, and gone with the page.
 */
class PictureActivity : Activity() {

    /** What lies over the picture: whose it is and the turn above, the stop below; a press on the picture shows or hides it. */
    private val over = ArrayList<android.view.View>()

    private lateinit var picture: Zoomed

    /** The picture opened from its lock, taken from [held] once: kept while the page lives, to share. */
    private var opened: ByteArray? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        opened = held
        held = null
        Look.fullScreen(this)
        picture = Zoomed(this) { val shown = over.firstOrNull()?.visibility != android.view.View.VISIBLE ; over.forEach { it.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE } }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(picture, FrameLayout.LayoutParams(-1, -1))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Look.dp(context, 20), Look.dp(context, 28), Look.dp(context, 12), Look.dp(context, 20))
                        background = Look.shade(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM)
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(android.widget.TextView(context).apply {
                                    text = intent.getStringExtra(EXTRA_TITLE).orEmpty() ; textSize = 18f
                                    setTextColor(android.graphics.Color.WHITE) ; setTypeface(typeface, android.graphics.Typeface.BOLD)
                                })
                                addView(android.widget.TextView(context).apply {
                                    text = intent.getStringExtra(EXTRA_SAID).orEmpty() ; textSize = 13f
                                    setTextColor(android.graphics.Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
                                })
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                        addView(Look.knob(context, "▯→▭", getString(R.string.picture_turn)) { picture.turn() }, LinearLayout.LayoutParams(Look.dp(context, 64), Look.dp(context, 52)))
                    }.also { over += it },
                    FrameLayout.LayoutParams(-1, -2, Gravity.TOP),
                )
                // Back to the conversation, where the video's page has its stop: the one way out besides back.
                addView(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(Look.dp(context, 12), Look.dp(context, 12), Look.dp(context, 12), Look.dp(context, 32))
                        background = Look.shade(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP)
                        // At the bottom, a little larger than the video's, where a thumb finds it; the share at its left.
                        val round = Look.dp(context, 64)
                        addView(
                            Look.knob(context, R.drawable.share, getString(R.string.share_picture)) {
                                HandOut.share(this@PictureActivity, opened, intent.data, intent.getStringExtra(EXTRA_NAME) ?: "picture", intent.getStringExtra(EXTRA_TYPE), intent.getBooleanExtra(EXTRA_LOCKED, false))
                            },
                            LinearLayout.LayoutParams(round, round).apply { marginEnd = Look.dp(context, 24) },
                        )
                        addView(Look.knob(context, "■", getString(R.string.picture_close)) { finish() }.apply { textSize = 26f }, LinearLayout.LayoutParams(round, round))
                    }.also { over += it },
                    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
                )
            },
        )
        val bytes = opened
        val from = intent.data
        // Decoded at the screen's longest side, up to twice: enough to look closer, never a photo whole.
        val side = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        thread {
            val made = if (bytes != null) Media.thumbnail(bytes, side) else from?.let { Media.thumbnail(this, it, side) }
            val upright = runCatching {
                (bytes?.inputStream() ?: from?.let { contentResolver.openInputStream(it) })?.use { Media.turnOf(android.media.ExifInterface(it)) }
            }.getOrNull() ?: 0
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (made == null) return@runOnUiThread finish()
                picture.show(made, upright)
            }
        }
    }

    /** The picture, fitted to the screen in its proportions, and from there as close as the person goes. */
    private class Zoomed(context: Context, pressed: () -> Unit) : ImageView(context) {
        private var turned = 0
        private val shown = Matrix()
        private var zoom = 1f

        private val pinching = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    by(detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }
            },
        )

        private val touching = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(first: MotionEvent?, now: MotionEvent, dx: Float, dy: Float): Boolean {
                    shown.postTranslate(-dx, -dy)
                    bound()
                    return true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    if (zoom > 1f) fit() else by(CLOSER, event.x, event.y)
                    return true
                }

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    pressed()
                    return true
                }
            },
        )

        init {
            scaleType = ScaleType.MATRIX
        }

        fun show(made: android.graphics.Bitmap, upright: Int) {
            setImageBitmap(made)
            turned = upright
            fit()
        }

        fun turn() {
            turned = (turned + 90) % 360
            fit()
        }

        /** As large as the screen allows, turned, in its proportions: where zooming starts and comes back to. */
        private fun fit() {
            val made = drawable ?: return
            if (width == 0 || height == 0) return
            val across = turned == 90 || turned == 270
            val seenWide = if (across) made.intrinsicHeight else made.intrinsicWidth
            val seenHigh = if (across) made.intrinsicWidth else made.intrinsicHeight
            val scale = minOf(width.toFloat() / seenWide, height.toFloat() / seenHigh)
            shown.reset()
            shown.postTranslate(-made.intrinsicWidth / 2f, -made.intrinsicHeight / 2f)
            shown.postRotate(turned.toFloat())
            shown.postScale(scale, scale)
            shown.postTranslate(width / 2f, height / 2f)
            zoom = 1f
            imageMatrix = shown
        }

        private fun by(factor: Float, x: Float, y: Float) {
            val now = (zoom * factor).coerceIn(1f, MOST)
            shown.postScale(now / zoom, now / zoom, x, y)
            zoom = now
            bound()
        }

        /** Never moved off the screen: smaller than it, centred; larger, no edge drawn in. */
        private fun bound() {
            val made = drawable ?: return
            val seen = RectF(0f, 0f, made.intrinsicWidth.toFloat(), made.intrinsicHeight.toFloat())
            shown.mapRect(seen)
            fun along(start: Float, end: Float, room: Float) = when {
                end - start <= room -> room / 2 - (start + end) / 2
                start > 0 -> -start
                end < room -> room - end
                else -> 0f
            }
            shown.postTranslate(along(seen.left, seen.right, width.toFloat()), along(seen.top, seen.bottom, height.toFloat()))
            imageMatrix = shown
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            super.onSizeChanged(w, h, oldW, oldH)
            fit()
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            pinching.onTouchEvent(event)
            if (!pinching.isInProgress) touching.onTouchEvent(event)
            return true
        }

        private companion object {
            const val MOST = 8f
            const val CLOSER = 2.5f
        }
    }

    companion object {
        /** A picture opened from its lock, handed over in memory. */
        @Volatile var held: ByteArray? = null

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SAID = "said"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_LOCKED = "locked"

        /** [title]: whose conversation; [said]: the picture's name and when it came; [name], [type], [locked]: to share it. */
        fun look(activity: Activity, bytes: ByteArray?, uri: Uri?, title: String, said: String, name: String, type: String?, locked: Boolean) {
            held = bytes
            activity.startActivity(
                android.content.Intent(activity, PictureActivity::class.java).setData(uri).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SAID, said)
                    .putExtra(EXTRA_NAME, name).putExtra(EXTRA_TYPE, type).putExtra(EXTRA_LOCKED, locked),
            )
        }
    }
}
