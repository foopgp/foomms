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
 * chosen, their conversation opens; several, the same message goes to each,
 * or -- up to the group limit set -- one MMS goes to all of them, a group's.
 * A page of its own, as messaging applications have it: a window over the
 * list of conversations sent fingers looking for a recipient behind it.
 */
class WriteActivity : Activity() {

    /**
     * Somebody one can write to: a name, a number, a face when there is one.
     *
     * [header]: not somebody, the heading of those that follow. [light]: only a name and a number, the rest read when shown.
     */
    private class Whom(
        val number: String, val name: String, val face: Bitmap?, val typed: Boolean = false, val fingerprint: String? = null, val standing: String? = null,
        val header: String? = null, val light: Boolean = false,
    )

    /** The contacts of the A to Z, read in full when first shown. */
    private val full = java.util.concurrent.ConcurrentHashMap<String, Whom>()
    private val reading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** How the draft to those chosen was to leave, when there is one: its button stands out. */
    private var drafted: Drafts.How? = null

    /** The people last looked up for a draft: it is found once for them, not again at each drawing. */
    private var draftOf: String? = null

    private val chosen = LinkedHashMap<String, String>()
    private var shown: List<Whom> = emptyList()
    private var recent: List<Whom> = emptyList()
    private var turn = 0

    private lateinit var asked: EditText
    private lateinit var picked: LinearLayout
    private lateinit var go: android.widget.Button

    /** At the foot, when there are several: what each way means, then a button each -- to each, or as a group. */
    private lateinit var toEach: android.widget.Button
    private lateinit var toGroup: android.widget.Button
    private lateinit var howSaid: android.widget.TextView
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
        go = Look.button(this, getString(R.string.whom_write)) { write(group = false) }
        // The pencil of "Write", which these two take the place of.
        val pencil = getString(R.string.whom_write).substringBefore(' ')
        toEach = Look.button(this, "$pencil ${getString(R.string.send_each)}") { write(group = false) }
        toGroup = Look.button(this, "$pencil ${getString(R.string.send_group)}") { write(group = true) }
        howSaid = Look.words(this, "", size = 13f, muted = true).apply { setPadding(Look.dp(context, 4), Look.dp(context, 4), Look.dp(context, 4), Look.dp(context, 4)) }
        list = ListView(this).apply {
            divider = null
            adapter = rows
            setOnItemClickListener { _, _, at, _ -> shown.getOrNull(at)?.takeIf { it.header == null }?.let { toggle(it) } }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, pad / 2)
                addView(asked)
                addView(HorizontalScrollView(context).apply { addView(picked) ; isHorizontalScrollBarEnabled = false })
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(howSaid)
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(go, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(toEach, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(toGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    },
                )
            },
        )
        Edges.fit(this)
        drawPicked()
        // Handed whom to write to -- those a message went to --: chosen already, by their names.
        intent.getStringArrayExtra(EXTRA_NUMBERS)?.takeIf { it.isNotEmpty() }?.let { given ->
            thread {
                val found = given.map { whom(it, null) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    for (one in found) chosen[one.number] = one.name
                    drawPicked()
                    rows.notifyDataSetChanged()
                }
            }
        }
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
            // Then every contact one can write to, from A to Z, under a heading: by name and number at first, the
            // face and the certificate read as each comes into view.
            if (!contacts) return@thread
            val every = runCatching { Book.find(this, "", Int.MAX_VALUE) }.getOrDefault(emptyList()).map { Whom(it.number, it.name, null, light = true) }
            runOnUiThread {
                if (isFinishing || isDestroyed || every.isEmpty()) return@runOnUiThread
                recent = found + listOf(Whom("", "", null, header = getString(R.string.all_contacts, every.size))) + every
                if (asked.text.isEmpty()) show(recent)
            }
        }
    }

    private fun readFull(light: Whom) {
        if (!reading.add(light.number)) return
        thread {
            full[light.number] = whom(light.number, light.name)
            runOnUiThread { if (!isFinishing && !isDestroyed) rows.notifyDataSetChanged() }
        }
    }

    /** A number as the address book and foodjis know it: the contact's name and picture, else the certificate's. */
    private fun whom(number: String, name: String?): Whom {
        val person = if (contacts) Book.who(this, number) else null
        val face = person?.let { Book.face(this, it) } ?: person?.fingerprint?.let { foodjis.face(it) }
        val standing = person?.fingerprint?.let { foodjis.card(it)?.validity }
        // Kept, for the light lines of the A to Z.
        return Whom(number, name ?: person?.name ?: number, face, fingerprint = person?.fingerprint, standing = standing).also { full[number] = it }
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
            // A name, not a button: in a thin frame, the red cross the one thing to press.
            val pad = Look.dp(this, 8)
            picked.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Look.framed(context)
                    setPadding(pad, pad / 4, pad / 2, pad / 4)
                    addView(Look.words(context, name, size = 14f).apply { maxLines = 1 })
                    addView(
                        Look.words(context, "✕", size = 16f).apply {
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Look.colour(Verdict.RED))
                            setPadding(pad, 0, pad / 2, 0)
                        },
                    )
                    setOnClickListener { chosen.remove(number) ; drawPicked() ; rows.notifyDataSetChanged() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Look.dp(this@WriteActivity, 6) },
            )
        }
        (picked.parent as? View)?.visibility = if (chosen.isEmpty()) View.GONE else View.VISIBLE
        // A group MMS goes to as many as the limit set, this phone among them: beyond, to each. And this phone,
        // which gets it back: its number, from one's own contact.
        val known = Me.number(this) != null
        val most = Conf.groupMost(this) - 1
        val mms = Conf.mms(this)
        val groupable = chosen.size in 2..most && known && mms
        // A draft to these very people: the way it was to leave stands out, and is said.
        val these = chosen.keys.mapNotNull { Phone.plain(it) ?: it }.sorted().joinToString(",")
        if (these != draftOf) {
            draftOf = these
            drafted = if (chosen.size >= 2) Drafts.how(this, chosen.keys.toList()) else null
            if (drafted != null) android.widget.Toast.makeText(this, R.string.draft_found, android.widget.Toast.LENGTH_SHORT).show()
        }
        val several = chosen.size >= 2
        go.visibility = if (several) View.GONE else View.VISIBLE
        toEach.visibility = if (several) View.VISIBLE else View.GONE
        toGroup.visibility = toEach.visibility
        howSaid.visibility = toEach.visibility
        toGroup.isEnabled = groupable
        toGroup.alpha = if (groupable) 1f else 0.38f
        // The draft's way orange, the other as any button.
        for ((button, on) in listOf(toEach to (drafted == Drafts.How.EACH), toGroup to (drafted == Drafts.How.GROUP && groupable))) {
            // A new face brings its own padding, thinner: the button's own put back, so that it keeps its size.
            val (left, top, right, bottom) = listOf(button.paddingLeft, button.paddingTop, button.paddingRight, button.paddingBottom)
            button.background = Look.buttonFace(this, if (on) Look.ORANGE else Look.FACE)
            button.setPadding(left, top, right, bottom)
            // Bold either way, as "Write", which they take the place of.
            button.setTypeface(null, Typeface.BOLD)
        }
        // Each way and what it means, its name first; a group that cannot be, why.
        fun way(name: Int, said: String) = android.text.SpannableStringBuilder()
            .append(getString(name), android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(getString(R.string.colon)).append(said)
        howSaid.text = android.text.SpannableStringBuilder()
            .append(way(R.string.send_each, getString(R.string.send_each_note))).append("\n")
            .append(
                way(
                    R.string.send_group,
                    when {
                        groupable -> getString(R.string.send_group_note)
                        !mms -> getString(R.string.mms_off)
                        !known -> getString(R.string.group_needs_me)
                        else -> getString(R.string.send_group_most, most)
                    },
                ),
            )
        go.isEnabled = chosen.isNotEmpty() || Phone.plain(asked.text.toString()) != null
    }

    /** One chosen: their conversation; several: the same to each, separately, what was shared with this page included. */
    private fun write(group: Boolean) {
        Phone.plain(asked.text.toString())?.let { typed -> if (chosen.keys.none { Phone.same(it, typed) }) chosen[typed] = typed }
        if (chosen.isEmpty()) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val carried = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        fun go(next: Intent) {
            next.putExtra(Intent.EXTRA_TEXT, text)
            // What was shared goes on with the page, and the right to read it with it.
            carried?.let { next.putExtra(Intent.EXTRA_STREAM, it) ; next.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) ; next.clipData = android.content.ClipData.newRawUri(null, it) }
            startActivity(next)
            finish()
        }
        when {
            chosen.size == 1 -> go(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, chosen.keys.first()))
            // The group's conversation may have to be looked for among them all: not on the main thread.
            group -> {
                val numbers = chosen.keys.toList()
                // The button pressed is this one: the other is hidden while several are chosen.
                toGroup.isEnabled = false
                go.isEnabled = false
                thread {
                    val next = ThreadActivity.forGroup(this, numbers)
                    runOnUiThread { if (!isFinishing && !isDestroyed) go(next) }
                }
            }
            else -> go(Intent(this, ManyActivity::class.java).putExtra(ManyActivity.EXTRA_NUMBERS, chosen.keys.toTypedArray()))
        }
    }

    private val rows = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(at: Int) = shown[at]
        override fun getItemId(at: Int) = at.toLong()

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val context = this@WriteActivity
            shown[at].header?.let { heading ->
                return Look.words(context, heading, size = 14f, muted = true).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, Look.dp(context, 16), 0, Look.dp(context, 4))
                }
            }
            // One of the A to Z: read in full the first time it is shown, drawn again then.
            val one = shown[at].let { light -> if (!light.light) light else full[light.number] ?: light.also { readFull(it) } }
            val pad = Look.dp(context, 8)
            val isChosen = chosen.keys.any { Phone.same(it, one.number) }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
                // Framed by what their certificate is worth, as in the list of conversations.
                addView(Look.face(context, 44, null, one.face, one.standing, framable = true, seed = Look.seed(one.fingerprint, one.number)))
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

    companion object {
        /** Numbers chosen already when the page opens. */
        const val EXTRA_NUMBERS = "numbers"

        /** How many recent conversations open the list. */
        const val MOST = 60
    }
}
