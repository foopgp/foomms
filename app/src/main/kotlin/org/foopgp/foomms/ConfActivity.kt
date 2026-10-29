package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/** What the person sets, a heading and a line of explanation above each, as foodjis lays its own out. */
class ConfActivity : AskingActivity() {

    private lateinit var column: LinearLayout

    /** Whether foodjis holds an identity to encrypt to: unknown until it has said. */
    private var holdsOne: Boolean? = null

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.configure)
        column = Look.column(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        // The software product and its version, as the About page names them: foomms-dev for a trial build.
        setContentView(Swipes.under(this, ScrollView(this).apply { addView(column) }, "${getString(R.string.app_name)} — $version"))
        Edges.fit(this)
        draw()
        // No identity in foodjis, nothing to encrypt to oneself: that switch is greyed, and off.
        provider.ask(Intent(Provider.GET_SIGN_KEY_ID), null) { answer ->
            holdsOne = answer is Provider.Answer.Done && answer.says.getLongExtra(Provider.EXTRA_SIGN_KEY_ID, 0L) != 0L
            if (!isFinishing && !isDestroyed) draw()
        }
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun heading(words: Int) =
        column.addView(Look.words(this, getString(words), size = 19f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, Look.dp(context, 18), 0, 0) })

    /** A setting among others under one heading: worded as a switch's, and as far from what is above it. */
    private fun subheading(words: Int) =
        column.addView(Look.words(this, getString(words), size = 17f).apply { setPadding(0, Look.dp(context, 12), 0, 0) })

    /** A number from 0 to [most], set by sliding, shown beside. */
    private fun slider(now: Int, most: Int, set: (Int) -> Unit) {
        val shown = Look.words(this, now.toString(), size = 18f)
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(
                    SeekBar(context).apply {
                        max = most
                        progress = now
                        setOnSeekBarChangeListener(
                            object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: SeekBar?, value: Int, byHand: Boolean) {
                                    set(value)
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
    }

    private fun note(words: Int) = note(getString(words))

    private fun note(words: String) =
        column.addView(Look.words(this, words, size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })

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

        // What is locked: how much one use of the key opens, how long it stays open, and for whom it is locked.
        heading(R.string.conf_crypto)
        subheading(R.string.conf_around)
        note(R.string.conf_around_note)
        slider(Conf.around(this), Conf.MOST_AROUND) { Conf.around(this, it) }
        subheading(R.string.conf_unread_all)
        note(R.string.conf_unread_all_note)
        slider(Conf.unreadAll(this), Conf.MOST_UNREAD) { Conf.unreadAll(this, it) }

        subheading(R.string.conf_hold)
        note(R.string.conf_hold_note)
        val choices = Conf.Hold.entries
        val current = Conf.hold(this)
        column.addView(
            Look.button(this, "${getString(R.string.conf_hold)} — ${label(current)}") {
                AlertDialog.Builder(this)
                    .setTitle(R.string.conf_hold_after)
                    .setSingleChoiceItems(choices.map { label(it) }.toTypedArray(), choices.indexOf(current)) { dialog, which ->
                        dialog.dismiss()
                        Conf.hold(this, choices[which])
                        Look.dress(this)
                        draw()
                    }
                    .show()
            },
        )

        column.addView(
            flip(R.string.conf_to_myself, Conf.toMyself(this), usable = holdsOne != false) { Conf.toMyself(this, it) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Look.dp(this@ConfActivity, 12) },
        )
        note(R.string.conf_to_myself_note)

        heading(R.string.backup_all)
        note(R.string.backup_about)
        column.addView(Look.button(this, getString(R.string.backup_save)) { Saving.save(this) })
        column.addView(Look.button(this, getString(R.string.backup_restore)) { if (!Saving.restore(this)) backedUp(getString(R.string.forget_not_ours)) })
        if (backup.isNotEmpty()) column.addView(Look.words(this, backup, size = 15f))

        heading(R.string.conf_mms)
        column.addView(flip(R.string.conf_prefer_mms, Conf.preferMms(this)) { Conf.preferMms(this, it) ; draw() })
        note(R.string.conf_prefer_mms_note)
        // Left to itself, a long text still becomes an MMS: then one can ask to be told first.
        // Heavier than the standard only where the operator says it takes more: greyed, and off, otherwise.
        val theirs = Outbox.operators(this)
        column.addView(
            flip(R.string.conf_mms_size, Conf.operatorSize(this), usable = (theirs ?: 0) > Outbox.STANDARD) { Conf.operatorSize(this, it) }
                .apply { (theirs ?: Outbox.STANDARD).let { most -> text = getString(R.string.conf_mms_size, most / 1024, (most - Outbox.MARGIN) / 1024) } },
        )
        note(getString(R.string.conf_mms_size_note, Outbox.STANDARD / 1024, (Outbox.STANDARD - Outbox.MARGIN) / 1024))
        if (!Conf.preferMms(this)) {
            column.addView(flip(R.string.conf_warn_mms, Conf.warnMms(this)) { Conf.warnMms(this, it) })
            note(R.string.conf_warn_mms_note)
        }
        // How many a group MMS may go to, oneself included.
        subheading(R.string.conf_group_most)
        note(getString(R.string.conf_group_most_note, Conf.GROUP_MOST))
        slider(Conf.groupMost(this), Conf.GROUP_MOST_EVER) { Conf.groupMost(this, it) }

    }

    /** How the last saving or restoring went: the page is drawn again on return, and it is kept for that. */
    private var backup = ""

    private fun backedUp(words: String) { backup = words ; draw() }

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        Saving.done(this, request, outcome, data, ::backedUp)
    }

    /** A switch of the configuration; greyed, it shows off, and what it is set to is kept for when it is usable again. */
    private fun flip(words: Int, on: Boolean, usable: Boolean = true, then: (Boolean) -> Unit) = android.widget.Switch(this).apply {
        text = getString(words)
        textSize = 17f
        setTextColor(Look.INK)
        isChecked = on && usable
        isEnabled = usable
        if (!usable) alpha = 0.38f
        setOnCheckedChangeListener { _, now -> then(now) }
    }

    private fun label(hold: Conf.Hold): String = getString(
        when (hold) {
            Conf.Hold.HALF_MINUTE -> R.string.hold_half_minute
            Conf.Hold.TWO_MINUTES -> R.string.hold_two_minutes
            Conf.Hold.FIVE_MINUTES -> R.string.hold_five_minutes
            Conf.Hold.FIFTEEN_MINUTES -> R.string.hold_fifteen_minutes
            Conf.Hold.HOUR -> R.string.hold_hour
            Conf.Hold.FOUR_HOURS -> R.string.hold_four_hours
            Conf.Hold.TWELVE_HOURS -> R.string.hold_twelve_hours
        },
    )
}
