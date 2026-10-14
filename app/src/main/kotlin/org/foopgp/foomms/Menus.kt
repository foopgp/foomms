package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText

/**
 * Where the title bars lead -- the search, the configuration -- and the
 * writing of a new message. No page has a menu: what it offers is in its bar.
 */
object Menus {

    /** Searching every conversation, or only the one with [number]. */
    fun search(activity: Activity, number: String? = null) =
        activity.startActivity(Intent(activity, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_NUMBER, number))

    fun configure(activity: Activity) = activity.startActivity(Intent(activity, ConfActivity::class.java))

    /**
     * A new message begins with whom it is for: a number typed, or somebody
     * found in the address book by a few letters of their name -- one, and
     * their conversation opens; several, and the same message goes to each.
     */
    fun write(activity: Activity) {
        val contacts = activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val chosen = LinkedHashMap<String, String>()
        val asked = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHint(if (contacts) R.string.whom_hint else R.string.number_hint)
            setSingleLine()
        }
        val whom = Look.words(activity, "", size = 15f).apply { visibility = android.view.View.GONE }
        val found = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val pad = Look.dp(activity, 20)
        fun add(number: String, name: String) {
            if (chosen.keys.none { Phone.same(it, number) }) chosen[number] = name
            // Whom it is for so far; pressed, the last one named is taken off.
            whom.text = activity.getString(R.string.whom_so_far, chosen.values.joinToString(", "))
            whom.visibility = if (chosen.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        whom.setOnClickListener {
            chosen.keys.lastOrNull()?.let { chosen.remove(it) }
            whom.text = activity.getString(R.string.whom_so_far, chosen.values.joinToString(", "))
            whom.visibility = if (chosen.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.write)
            .setView(
                android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, 0, pad, 0)
                    addView(whom)
                    addView(asked)
                    addView(android.widget.ScrollView(context).apply { addView(found) })
                },
            )
            .setPositiveButton(R.string.whom_write) { _, _ ->
                // A number typed in full needs no contact to go with it.
                Phone.plain(asked.text.toString())?.let { add(it, it) }
                when (chosen.size) {
                    0 -> Unit
                    1 -> activity.startActivity(
                        Intent(activity, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, chosen.keys.first()),
                    )
                    else -> activity.startActivity(
                        Intent(activity, ManyActivity::class.java).putExtra(ManyActivity.EXTRA_NUMBERS, chosen.keys.toTypedArray()),
                    )
                }
            }
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
                                        setOnClickListener { add(one.number, one.name) ; asked.setText("") }
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
