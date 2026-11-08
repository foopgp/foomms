package org.foopgp.foomms

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ListView
import kotlin.math.abs

/**
 * The conversation's list, a bubble drawn to the right to answer it: it follows the finger a
 * little, and once far enough, let go, it is [answered] and slides back. Up and down stay the
 * list's; a press, and a long press, stay the bubble's -- the swipe is taken only once the finger
 * has clearly gone sideways.
 */
class Swiped(context: Context, private val answered: (Int) -> Unit) : ListView(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val far = Look.dp(context, 72).toFloat()
    private var fromX = 0f
    private var fromY = 0f
    private var at = INVALID_POSITION
    private var row: View? = null
    private var swiping = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fromX = event.x
                fromY = event.y
                swiping = false
                at = pointToPosition(event.x.toInt(), event.y.toInt())
                row = if (at == INVALID_POSITION) null else getChildAt(at - firstVisiblePosition)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - fromX
                if (!swiping && row != null && dx > slop * 2 && dx > abs(event.y - fromY) * 2) {
                    swiping = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(event)
        val shown = row ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> shown.translationX = ((event.x - fromX) / 2).coerceIn(0f, far * 1.2f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val enough = event.actionMasked == MotionEvent.ACTION_UP && shown.translationX >= far / 2
                shown.animate().translationX(0f).setDuration(150).start()
                swiping = false
                if (enough) answered(at)
            }
        }
        return true
    }
}
