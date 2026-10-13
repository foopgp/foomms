package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/** What the person sets, a heading and a line of explanation above each, as foodjis lays its own out. */
class ConfActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.configure)
        column = Look.column(this)
        setContentView(ScrollView(this).apply { addView(column) })
        draw()
    }

    private fun heading(words: Int) =
        column.addView(Look.words(this, getString(words), size = 19f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, Look.dp(context, 18), 0, 0) })

    private fun note(words: Int) =
        column.addView(Look.words(this, getString(words), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })

    private fun draw() {
        column.removeAllViews()

        heading(R.string.conf_theme)
        // Three states in a row, the one in force on the orange of what is pressed.
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val labels = mapOf(Conf.Theme.DARK to R.string.theme_dark, Conf.Theme.SYSTEM to R.string.theme_system, Conf.Theme.LIGHT to R.string.theme_light)
                val now = Conf.theme(context)
                for ((theme, label) in labels) {
                    addView(
                        Look.button(context, getString(label)) {
                            Conf.theme(context, theme)
                            recreate()
                        }.apply {
                            if (theme == now) { background = Look.rounded(Look.ORANGE, Look.dp(context, 10)) ; setTextColor(Look.INDIGO) }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Look.dp(context, 6) },
                    )
                }
            },
        )

        heading(R.string.conf_around)
        note(R.string.conf_around_note)
        val shown = Look.words(this, Conf.around(this).toString(), size = 18f)
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(
                    SeekBar(context).apply {
                        max = Conf.MOST_AROUND
                        progress = Conf.around(context)
                        setOnSeekBarChangeListener(
                            object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: SeekBar?, value: Int, byHand: Boolean) {
                                    Conf.around(context, value)
                                    shown.text = value.toString()
                                }
                                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(shown, LinearLayout.LayoutParams(Look.dp(context, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )

        heading(R.string.conf_hold)
        note(R.string.conf_hold_note)
        val choices = Conf.Hold.entries
        val current = Conf.hold(this)
        column.addView(
            Look.button(this, "${getString(R.string.conf_hold)} — ${label(current)}") {
                AlertDialog.Builder(this)
                    .setTitle(R.string.conf_hold)
                    .setSingleChoiceItems(choices.map { label(it) }.toTypedArray(), choices.indexOf(current)) { dialog, which ->
                        dialog.dismiss()
                        Conf.hold(this, choices[which])
                        Look.dress(this)
                        draw()
                    }
                    .show()
            },
        )
        // A setting that only takes effect next time cannot say "not any more".
        if (Opened.holdsAnything()) {
            column.addView(
                Look.button(this, getString(R.string.conf_hold_forget)) {
                    Opened.forget()
                    draw()
                }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Look.dp(context, 6) },
            )
        }
    }

    private fun label(hold: Conf.Hold): String = getString(
        when (hold) {
            Conf.Hold.NEVER -> R.string.hold_never
            Conf.Hold.TWO_MINUTES -> R.string.hold_two_minutes
            Conf.Hold.FIVE_MINUTES -> R.string.hold_five_minutes
            Conf.Hold.FIFTEEN_MINUTES -> R.string.hold_fifteen_minutes
            Conf.Hold.HOUR -> R.string.hold_hour
            Conf.Hold.FOUR_HOURS -> R.string.hold_four_hours
        },
    )

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Menus.build(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = Menus.select(this, item) || super.onOptionsItemSelected(item)
}
