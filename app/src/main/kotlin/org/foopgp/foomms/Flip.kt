package org.foopgp.foomms

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch

/**
 * A switch between its glyph and its verb: "✍ ⊙ Sign". Greyed, the three
 * are, and the switch shows off whatever it was; enabled again, it shows
 * what it was. A press on the glyph or the verb flips it as on the switch.
 */
class Flip(context: Context, glyph: String, verb: Int, changed: () -> Unit) : LinearLayout(context) {

    private val mark = Look.words(context, glyph, size = 16f)
    // One line, the room left beside the switch, the letters smaller when the verb is long: "Зашифровать" broke in two.
    private val word = Look.words(context, context.getString(verb), size = 14f).apply {
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    /** What the switch is set to, which it shows only while it can be used. */
    private var wanted = false
    private var showing = false

    /** Whether the person set it by hand: what the page would set it to by itself no longer goes over that. */
    var touched = false
        private set

    private val switch = Switch(context).apply {
        setOnCheckedChangeListener { _, on ->
            if (!showing) { wanted = on ; touched = true }
            changed()
        }
    }

    var isChecked: Boolean
        get() = wanted
        set(value) { wanted = value ; show() }

    /** Set by the page, unless the person set it already. */
    fun suggest(value: Boolean) {
        if (!touched) isChecked = value
    }

    private fun show() {
        showing = true
        switch.isChecked = wanted && switch.isEnabled
        showing = false
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(mark)
        addView(switch, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = Look.dp(context, 4) ; marginEnd = Look.dp(context, 4) })
        addView(word, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { if (switch.isEnabled) switch.toggle() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        switch.isEnabled = enabled
        show()
        mark.alpha = if (enabled) 1f else 0.38f
        word.alpha = if (enabled) 1f else 0.38f
    }
}
