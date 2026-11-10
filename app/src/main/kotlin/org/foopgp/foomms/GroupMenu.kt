package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * A group's menu, wherever the group is: over its conversation, or over
 * the list of conversations when a line is pressed long. What can be done
 * about it -- name it, silence it, save it, delete it --, then its members,
 * each with their face, name, number and what their certificate is worth.
 */
object GroupMenu {

    /** The members of the group of [everyone] but this phone, and who they are. Not on the main thread. */
    fun read(context: Context, everyone: List<String>): List<ThreadActivity.Member> {
        val contacts = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val foodjis = Foodjis.of(context)
        return everyone.filterNot { Me.mine(context, it) }.map { one ->
            val who = if (contacts) Book.who(context, one) else null
            val whose = who?.fingerprint?.let { foodjis.card(it) }
            ThreadActivity.Member(one, who, whose, who?.let { Book.face(context, it) } ?: who?.fingerprint?.let { foodjis.face(it) })
        }
    }

    fun show(
        activity: Activity,
        members: List<ThreadActivity.Member>,
        set: String,
        threads: List<Long>,
        title: String?,
        changed: () -> Unit,
        gone: () -> Unit,
        tie: (String) -> Unit,
    ) = with(activity) {
        // What can be done and the title held at the top; only the members scroll.
        // No margin above the first action: the room its own line keeps above it is the room it keeps above the rule.
        val head = Look.column(this).apply { setPadding(paddingLeft, 0, paddingRight, 0) }
        val column = Look.column(this).apply { setPadding(paddingLeft, 0, paddingRight, paddingBottom) }
        var shown: AlertDialog? = null
        // First what can be done about the conversation, as about one between two: its notifications, saving it, deleting it.
        val key = "group:" + set
        val muted = Marks.muted(this, key)
        val pad = Look.dp(this, 10)
        fun action(words: Int, then: () -> Unit) = head.addView(
            Look.words(this, getString(words), size = 16f).apply {
                setPadding(0, pad, 0, pad)
                setOnClickListener { shown?.dismiss() ; then() }
            },
        )
        val called = GroupNames.of(this, set)
        action(if (called == null) R.string.group_name else R.string.group_rename) { name(activity, set, called, changed) }
        action(if (muted) R.string.unmute else R.string.mute) { Marks.mute(this, key, !muted) ; changed() }
        threads.firstOrNull()?.let { one -> action(R.string.backup_one) { Saving.save(this, one, title) } }
        action(R.string.forget_talk) { forget(activity, threads, title, gone) }
        // Then the members, below a line and their count.
        head.addView(View(this).apply { setBackgroundColor(Look.MUTED) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        head.addView(
            Look.words(this, resources.getQuantityString(R.plurals.group_members, members.size + 1, members.size + 1), size = 20f).apply {
                setPadding(0, pad * 3 / 2, 0, pad / 2)
            },
        )
        // Oneself first: the MMS goes to the others, and comes back to every one of them from all.
        val own = Me.card(this)?.let { Book.own(this, it) }
        val ownStanding = own?.fingerprint?.let { Foodjis.of(this).card(it) }?.validity
        val mine = Me.number(this)
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Look.dp(context, 6), 0, Look.dp(context, 6))
                addView(Look.face(context, 40, own?.photo, null, ownStanding, framable = true, seed = Look.seed(own?.fingerprint, mine)))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 12), 0, 0, 0)
                        addView(Look.words(context, listOfNotNull(own?.name, getString(R.string.me_tag)).joinToString(" "), size = 17f))
                        addView(Look.words(context, mine ?: getString(R.string.me_unknown), size = 13f, muted = true))
                    },
                )
            },
        )
        for (one in members) {
            val standing: CharSequence? = one.card?.let { android.text.SpannableStringBuilder("🔑 ").append(Look.standing(this, it.validity)) }
            column.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Look.dp(context, 6), 0, Look.dp(context, 6))
                    addView(Look.face(context, 40, null, one.face, one.card?.validity, framable = true, seed = one.seed))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(Look.dp(context, 12), 0, 0, 0)
                            addView(Look.words(context, one.name, size = 17f))
                            if (standing != null) {
                                addView(Look.words(context, if (one.name != one.number) android.text.SpannableStringBuilder("${one.number} — ").append(standing) else standing, size = 13f, muted = true))
                            } else {
                                if (one.name != one.number) addView(Look.words(context, one.number, size = 13f, muted = true))
                                // No contact, or one with no certificate: tied here, the contact made if there is none --
                                // from the certificate the group's members part names, when one was read.
                                addView(
                                    Look.small(context, getString(if (one.person == null) R.string.contact_add else R.string.tie)) {
                                        shown?.dismiss()
                                        tie(one.number)
                                    }.apply { isFocusable = false },
                                )
                            }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    setOnClickListener {
                        shown?.dismiss()
                        startActivity(Intent(context, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, one.number))
                    }
                    // Pressed long: the menu of the conversation with them alone, as the list shows it.
                    setOnLongClickListener {
                        shown?.dismiss()
                        TalkMenu.aside(activity, one.number, one.name, one.person, group = true, look = false, changed = changed)
                        true
                    }
                },
            )
        }
        shown = AlertDialog.Builder(this)
            .apply { called?.let { setTitle(it) } }
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(head)
                    addView(android.widget.ScrollView(context).apply { addView(column) })
                },
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** The group named, or named anew from [now]; left empty, unnamed, its members' faces back in the bar. */
    fun name(activity: Activity, set: String, now: String?, changed: () -> Unit) = with(activity) {
        val field = android.widget.EditText(this).apply {
            setText(now.orEmpty())
            setSelection(text.length)
            setHint(R.string.group_name_hint)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = Look.dp(this, 20)
        AlertDialog.Builder(this)
            .setTitle(if (now == null) R.string.group_name else R.string.group_rename)
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, Look.dp(context, 8), pad, 0)
                    addView(field)
                    // Said quietly, under the field: the name is the person's own, not the group's.
                    addView(Look.words(context, getString(R.string.group_name_own), size = 13f, muted = true).apply { setPadding(0, Look.dp(context, 4), 0, 0) })
                },
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                GroupNames.name(this, set, field.text.toString())
                changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { field.requestFocus() ; it.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
    }

    /** Every message of the group's conversations, once the person has said so; [gone] once they are. */
    private fun forget(activity: Activity, threads: List<Long>, title: String?, gone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.forget_talk_ask, title))
            .setPositiveButton(R.string.forget_yes) { _, _ ->
                thread {
                    val went = threads.map { Keep.forgetAll(activity, it) }.all { it }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (went) gone()
                        else Toast.makeText(activity, if (Keep.ours(activity)) R.string.forget_failed else R.string.forget_not_ours, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
