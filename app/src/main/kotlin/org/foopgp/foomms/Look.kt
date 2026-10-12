package org.foopgp.foomms

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** The few views these pages are made of, built by hand: there is no layout file to keep in step. */
object Look {

    fun dp(activity: Activity, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()

    fun column(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(activity, 20)
        setPadding(pad, pad, pad, pad)
    }

    fun words(activity: Activity, text: CharSequence, size: Float = 16f, muted: Boolean = false): TextView =
        TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(if (muted) Color.GRAY else Color.rgb(0x18, 0x18, 0x1B))
            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            setTextIsSelectable(true)
        }

    fun button(activity: Activity, label: Int, then: (View) -> Unit): Button = Button(activity).apply {
        setText(label)
        isAllCaps = false
        setOnClickListener(then)
    }
}
