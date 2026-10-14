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
        Edges.fit(this)
        draw()
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun heading(words: Int) =
        column.addView(Look.words(this, getString(words), size = 19f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, Look.dp(context, 18), 0, 0) })

    private fun note(words: Int) =
        column.addView(Look.words(this, getString(words), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })

    private fun draw() {
        column.removeAllViews()

        heading(R.string.conf_language)
        // The language the pages are in, which is not always the phone's: one
        // the application does not speak falls back to English.
        column.addView(Look.words(this, getString(R.string.language_name), size = 17f))
        note(R.string.conf_language_note)
        // Chosen where Android keeps it, for this application alone: from
        // Android 13 the system has a page for exactly that.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            column.addView(
                Look.button(this, getString(R.string.conf_language_change)) {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null)),
                    )
                },
            )
        }

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
                            if (theme == now) { background = Look.buttonFace(context, Look.ORANGE) ; setTextColor(Look.INDIGO) }
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

        heading(R.string.conf_myself)
        column.addView(flip(R.string.conf_to_myself, Conf.toMyself(this)) { Conf.toMyself(this, it) })
        note(R.string.conf_to_myself_note)

        heading(R.string.conf_mms)
        column.addView(flip(R.string.conf_prefer_mms, Conf.preferMms(this)) { Conf.preferMms(this, it) ; draw() })
        note(R.string.conf_prefer_mms_note)
        // Left to itself, a long text still becomes an MMS: then one can ask to be told first.
        if (!Conf.preferMms(this)) {
            column.addView(flip(R.string.conf_warn_mms, Conf.warnMms(this)) { Conf.warnMms(this, it) })
            note(R.string.conf_warn_mms_note)
        }
    }

    private fun flip(words: Int, on: Boolean, then: (Boolean) -> Unit) = android.widget.Switch(this).apply {
        text = getString(words)
        textSize = 17f
        setTextColor(Look.INK)
        isChecked = on
        setOnCheckedChangeListener { _, now -> then(now) }
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
