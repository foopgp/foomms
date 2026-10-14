package org.foopgp.foomms

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch

/**
 * A switch between its glyph and its verb: "✍ ⊙ Sign". Greyed, the three
 * are; a press on the glyph or the verb flips it as on the switch.
 */
class Flip(context: Context, glyph: String, verb: Int, changed: () -> Unit) : LinearLayout(context) {

    private val mark = Look.words(context, glyph, size = 16f)
    private val word = Look.words(context, context.getString(verb), size = 14f)
    private val switch = Switch(context).apply { setOnCheckedChangeListener { _, _ -> changed() } }

    var isChecked: Boolean
        get() = switch.isChecked
        set(value) { switch.isChecked = value }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(mark)
        addView(switch, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = Look.dp(context, 4) ; marginEnd = Look.dp(context, 4) })
        addView(word)
        setOnClickListener { if (switch.isEnabled) switch.toggle() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        switch.isEnabled = enabled
        mark.alpha = if (enabled) 1f else 0.38f
        word.alpha = if (enabled) 1f else 0.38f
    }
}
