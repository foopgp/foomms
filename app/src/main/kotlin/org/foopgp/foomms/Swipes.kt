package org.foopgp.foomms

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Moving between pages with a finger, as foodjis does.
 *
 * Every page but the list is left by the top: the head pulled down goes back
 * to where it was opened from. The configuration, what the application is
 * and what changed are a column, a ring walked downwards only: the foot
 * pushed up -- or the line pinned there pressed -- goes to the page under.
 *
 * Fed from dispatchTouchEvent, which sees every gesture first: a selectable
 * text or a list would swallow it otherwise.
 */
object Swipes {

    private const val TRAVEL_DP = 72f
    private const val SPEED_DP = 180f

    /** A gesture watched, and swallowed once acted on: the release would otherwise press what is under the finger. */
    class Watcher internal constructor(private val activity: Activity) {
        private var fired = false
        private var fromTop = false
        private val density = activity.resources.displayMetrics.density

        private val detector = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    // Where it started decides: a flick up a long page arrives at its top, and must not throw one out.
                    fromTop = e.rawY < contentTop() || nothingAbove(activity)
                    return true
                }

                override fun onFling(from: MotionEvent?, to: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (from == null) return false
                    val dx = to.x - from.x
                    val dy = to.y - from.y
                    val travel = TRAVEL_DP * density
                    val speed = SPEED_DP * density
                    if (dy > travel && dy > 2 * Math.abs(dx) && vy > speed && fromTop) {
                        fired = leave(activity)
                        return fired
                    }
                    if (dy < -travel && -dy > 2 * Math.abs(dx) && vy < -speed && below(activity) != null && nothingBelow(activity)) {
                        fired = down(activity)
                        return fired
                    }
                    return false
                }
            },
        )

        /** Whether the page took the gesture: then it goes no further. */
        fun feed(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) fired = false
            detector.onTouchEvent(event)
            return fired
        }

        /** The bar is above what the page holds: a pull that starts on it starts at the top whatever is scrolled. */
        private fun contentTop(): Int {
            val at = IntArray(2)
            activity.findViewById<View>(android.R.id.content)?.getLocationOnScreen(at)
            return at[1]
        }
    }

    fun watch(activity: Activity) = Watcher(activity)

    /** Back to the page underneath, the leaving one sliding down; the list has none, and stays. */
    fun leave(activity: Activity): Boolean {
        if (activity is MainActivity) return false
        if (activity.isTaskRoot) activity.startActivity(Intent(activity, MainActivity::class.java))
        activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, R.anim.slide_out_down)
        return true
    }

    /** The column, a ring walked downwards. */
    private val COLUMN: List<Class<out Activity>> = listOf(ConfActivity::class.java, AboutActivity::class.java, ChangelogActivity::class.java)

    fun below(activity: Activity): Class<out Activity>? {
        val here = COLUMN.indexOf(activity.javaClass)
        return if (here < 0) null else COLUMN[(here + 1) % COLUMN.size]
    }

    /** To the page under this one, arriving from below. */
    fun down(activity: Activity): Boolean {
        val target = below(activity) ?: return false
        val options = ActivityOptions.makeCustomAnimation(activity, R.anim.slide_in_up, R.anim.slide_out_up)
        activity.startActivity(
            Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            options.toBundle(),
        )
        return true
    }

    /**
     * The line pinned at the foot of a page of the column, under a hairline:
     * where it leads, between two chevrons pointing down. Pressed, it goes
     * there, as the foot pushed up does.
     */
    fun door(activity: Activity, label: CharSequence): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(activity).apply { setBackgroundColor(Look.MUTED) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        addView(
            TextView(activity).apply {
                text = "⌄  $label  ⌄"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Look.ORANGE)
                setPadding(0, Look.dp(activity, 4), 0, Look.dp(activity, 8))
                setOnClickListener { down(activity) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    /** The page, with its door held at the bottom of the screen. */
    fun under(activity: Activity, content: View, label: CharSequence): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(door(activity, label))
    }

    /** Every list and scrolling view of the page at its top. */
    fun nothingAbove(activity: Activity): Boolean = !scrolls(activity.window.decorView, -1)

    private fun nothingBelow(activity: Activity): Boolean = !scrolls(activity.window.decorView, 1)

    private fun scrolls(view: View, way: Int): Boolean = when {
        (view is android.widget.ScrollView || view is android.widget.AbsListView) && view.canScrollVertically(way) -> true
        view is ViewGroup -> (0 until view.childCount).any { scrolls(view.getChildAt(it), way) }
        else -> false
    }
}
