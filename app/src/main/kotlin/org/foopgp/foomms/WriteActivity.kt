package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import kotlin.concurrent.thread

/**
 * A new message begins with whom it is for: chosen in a list that starts as
 * the list of conversations does -- the latest first, each behind its face --
 * and narrows to the contacts whose name or number holds what is typed. One
 * chosen, their conversation opens; several, the same message goes to each.
 * A page of its own, as messaging applications have it: a window over the
 * list of conversations sent fingers looking for a recipient behind it.
 */
class WriteActivity : Activity() {

    /** Somebody one can write to: a name, a number, a face when there is one. */
    private class Whom(val number: String, val name: String, val face: Bitmap?, val typed: Boolean = false)

    private val chosen = LinkedHashMap<String, String>()
    private var shown: List<Whom> = emptyList()
    private var recent: List<Whom> = emptyList()
    private var turn = 0

    private lateinit var asked: EditText
    private lateinit var picked: LinearLayout
    private lateinit var go: android.widget.Button
    private lateinit var list: ListView

    private val contacts by lazy { checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    private val foodjis by lazy { Foodjis.of(this) }

    private val swipes by lazy { Swipes.watch(this) }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean = swipes.feed(event) || super.dispatchTouchEvent(event)

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.write)
        val pad = Look.dp(this, 12)
        asked = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHint(if (contacts) R.string.whom_hint else R.string.number_hint)
            setSingleLine()
            addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun afterTextChanged(text: android.text.Editable?) = look(text.toString())
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                },
            )
        }
        // Whom it is for so far, one small button each: pressed, that one is taken off.
        picked = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        go = Look.button(this, getString(R.string.whom_write)) { write() }
        list = ListView(this).apply {
            divider = null
            adapter = rows
            setOnItemClickListener { _, _, at, _ -> shown.getOrNull(at)?.let { toggle(it) } }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, pad / 2)
                addView(asked)
                addView(HorizontalScrollView(context).apply { addView(picked) ; isHorizontalScrollBarEnabled = false })
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
        Edges.fit(this)
        drawPicked()
        // The conversations, the latest first: whom one writes to is most often somebody one writes to.
        thread {
            val found = runCatching { Store.talks(this, MOST) }.getOrDefault(emptyList())
                .mapNotNull { talk -> talk.number?.takeIf { Phone.plain(it) != null } }
                .distinct()
                .map { number -> whom(number, null) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                recent = found
                if (asked.text.isEmpty()) show(recent)
            }
        }
    }

    /** A number as the address book and foodjis know it: the contact's name and picture, else the certificate's. */
    private fun whom(number: String, name: String?): Whom {
        val person = if (contacts) Book.who(this, number) else null
        val face = person?.let { Book.face(this, it) } ?: person?.fingerprint?.let { foodjis.face(it) }
        return Whom(number, name ?: person?.name ?: number, face)
    }

    /** What is typed narrows the list to the contacts that hold it; a number typed in full is offered as it is. */
    private fun look(typed: String) {
        val mine = ++turn
        go.isEnabled = chosen.isNotEmpty() || Phone.plain(typed) != null
        if (typed.isBlank()) return show(recent)
        thread {
            val found = ArrayList<Whom>()
            Phone.plain(typed)?.let { found += Whom(it, it, null, typed = true) }
            if (contacts) {
                for (one in Book.find(this, typed)) if (found.none { Phone.same(it.number, one.number) }) found += whom(one.number, one.name)
            }
            runOnUiThread { if (mine == turn && !isFinishing && !isDestroyed) show(found) }
        }
    }

    private fun show(whom: List<Whom>) {
        shown = whom
        rows.notifyDataSetChanged()
    }

    private fun toggle(one: Whom) {
        val already = chosen.keys.firstOrNull { Phone.same(it, one.number) }
        if (already != null) chosen.remove(already) else chosen[one.number] = one.name
        if (asked.text.isNotEmpty()) asked.setText("")
        drawPicked()
        rows.notifyDataSetChanged()
    }

    private fun drawPicked() {
        picked.removeAllViews()
        for ((number, name) in chosen) {
            picked.addView(
                Look.small(this, "$name   ✕") { chosen.remove(number) ; drawPicked() ; rows.notifyDataSetChanged() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Look.dp(this@WriteActivity, 6) },
            )
        }
        (picked.parent as? View)?.visibility = if (chosen.isEmpty()) View.GONE else View.VISIBLE
        go.isEnabled = chosen.isNotEmpty() || Phone.plain(asked.text.toString()) != null
    }

    /** One chosen: their conversation; several: the same to each, separately, what was shared with this page included. */
    private fun write() {
        Phone.plain(asked.text.toString())?.let { typed -> if (chosen.keys.none { Phone.same(it, typed) }) chosen[typed] = typed }
        if (chosen.isEmpty()) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val carried = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        val next = if (chosen.size == 1) {
            Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, chosen.keys.first())
        } else {
            Intent(this, ManyActivity::class.java).putExtra(ManyActivity.EXTRA_NUMBERS, chosen.keys.toTypedArray())
        }
        next.putExtra(Intent.EXTRA_TEXT, text)
        // What was shared goes on with the page, and the right to read it with it.
        carried?.let { next.putExtra(Intent.EXTRA_STREAM, it) ; next.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) ; next.clipData = android.content.ClipData.newRawUri(null, it) }
        startActivity(next)
        finish()
    }

    private val rows = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(at: Int) = shown[at]
        override fun getItemId(at: Int) = at.toLong()

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val one = shown[at]
            val context = this@WriteActivity
            val pad = Look.dp(context, 8)
            val isChosen = chosen.keys.any { Phone.same(it, one.number) }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
                addView(Look.face(context, 44, null, one.face))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 12), 0, 0, 0)
                        // A number typed in full, as it is: written to whoever it is.
                        val title = if (one.typed) getString(R.string.write_to, one.number) else one.name
                        addView(Look.words(context, title, size = 16f).apply { setTypeface(typeface, Typeface.BOLD) ; maxLines = 1 })
                        if (!one.typed && one.name != one.number) addView(Look.words(context, one.number, size = 14f, muted = true))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                if (isChosen) addView(Look.words(context, "✓", size = 22f).apply { setTextColor(Look.ORANGE) ; setPadding(pad, 0, pad, 0) })
            }
        }
    }

    private companion object {
        /** How many recent conversations open the list. */
        const val MOST = 60
    }
}
