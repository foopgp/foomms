package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText

/**
 * The same menu on every page, less the entry of the page one is on: a
 * menu that changes from page to page is one to learn each time.
 */
object Menus {

    private const val TALKS = 0x7001
    private const val WRITE = 0x7002
    private const val CONFIGURE = 0x7003
    private const val SEARCH = 0x7004

    fun build(activity: Activity, menu: Menu) {
        if (activity !is MainActivity) {
            // The application's mark in front of the way back to its first page, in the menu's own ink.
            val words = android.text.SpannableString("\u00A0 " + activity.getString(R.string.talks))
            activity.getDrawable(R.drawable.mark)?.mutate()?.let { mark ->
                val side = Look.dp(activity, 22)
                mark.setBounds(0, 0, side, side)
                mark.setTint(Look.INK)
                val how = if (android.os.Build.VERSION.SDK_INT >= 29) android.text.style.ImageSpan.ALIGN_CENTER else android.text.style.ImageSpan.ALIGN_BOTTOM
                words.setSpan(android.text.style.ImageSpan(mark, how), 0, 1, 0)
            }
            menu.add(0, TALKS, 10, words)
        }
        // In a conversation, what is looked through is that conversation.
        if (activity !is SearchActivity) menu.add(0, SEARCH, 9, if (activity is ThreadActivity) R.string.search_here else R.string.search_all)
        menu.add(0, WRITE, 11, R.string.write)
        if (activity !is ConfActivity) menu.add(0, CONFIGURE, 12, R.string.configure)
    }

    /** True when the entry was one of these, and what it asks has been started. */
    fun select(activity: Activity, item: MenuItem): Boolean {
        when (item.itemId) {
            TALKS -> activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            WRITE -> write(activity)
            SEARCH -> activity.startActivity(
                Intent(activity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_NUMBER, (activity as? ThreadActivity)?.talkingTo),
            )
            CONFIGURE -> activity.startActivity(Intent(activity, ConfActivity::class.java))
            else -> return false
        }
        return true
    }

    /**
     * A new message begins with whom it is for: a number typed, or somebody
     * found in the address book by a few letters of their name.
     */
    private fun write(activity: Activity) {
        fun open(number: String) = activity.startActivity(
            Intent(activity, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number),
        )
        val contacts = activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val asked = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHint(if (contacts) R.string.whom_hint else R.string.number_hint)
            setSingleLine()
        }
        val found = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val pad = Look.dp(activity, 20)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.write)
            .setView(
                android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, 0, pad, 0)
                    addView(asked)
                    addView(android.widget.ScrollView(context).apply { addView(found) })
                },
            )
            // A number typed in full needs no contact to go with it.
            .setPositiveButton(android.R.string.ok) { _, _ -> Phone.plain(asked.text.toString())?.let { open(it) } }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        var turn = 0
        asked.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun afterTextChanged(text: android.text.Editable?) {
                    val typed = text.toString()
                    val mine = ++turn
                    if (!contacts || typed.trim().length < 2) return found.removeAllViews()
                    kotlin.concurrent.thread {
                        val people = Book.find(activity, typed)
                        activity.runOnUiThread {
                            if (mine != turn || !dialog.isShowing) return@runOnUiThread
                            found.removeAllViews()
                            for (one in people) {
                                found.addView(
                                    Look.words(activity, one.name + "\n" + one.number, size = 16f).apply {
                                        setPadding(0, Look.dp(activity, 10), 0, Look.dp(activity, 10))
                                        setOnClickListener { dialog.dismiss() ; open(one.number) }
                                    },
                                )
                            }
                        }
                    }
                }
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            },
        )
        dialog.show()
    }
}
