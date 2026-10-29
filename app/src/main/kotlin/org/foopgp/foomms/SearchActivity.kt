package org.foopgp.foomms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import kotlin.concurrent.thread

/**
 * Looking for someone, or for a message by its words. From the list of
 * conversations it looks first in the contacts -- all of them, those one has
 * never written to as well -- each with its button to write; a selector beside
 * the field turns it to the conversations, and the page keeps the choice.
 * From a conversation it looks in that one only. A PGP message is found by
 * what it says only while it is open; closed, nobody here knows what it says.
 */
class SearchActivity : Activity() {

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER) }
    private var hits: List<Store.Hit> = emptyList()

    /** Of the results, those that stand for a message sent to each of several: whom it went to. */
    private var alongside: Map<Store.Hit, List<String>> = emptyMap()
    private val names = HashMap<String, String>()
    private var turn = 0

    /** Somebody one can write to, as the page of a new message shows them. */
    private class Whom(val number: String, val name: String, val face: android.graphics.Bitmap?, val typed: Boolean = false)

    private var people: List<Whom> = emptyList()
    private var inContacts = false
    private lateinit var asked: EditText

    private val foodjis by lazy { Foodjis.of(this) }

    /** Left by the top, as every page but the list. */
    private val swipes by lazy { Swipes.watch(this) }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean = swipes.feed(event) || super.dispatchTouchEvent(event)

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        // Where it was asked to look: the search button asks it ([Searching]).
        inContacts = number == null && intent.getStringExtra(EXTRA_IN) == IN_CONTACTS
        asked = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(text: Editable?) = look(text.toString())
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                },
            )
        }
        val list = ListView(this).apply {
            divider = null
            adapter = found
            setOnItemClickListener { _, _, at, _ ->
                if (inContacts) return@setOnItemClickListener people.getOrNull(at)?.let { write(it.number) } ?: Unit
                val hit = hits[at]
                // Sent to each of several: whom to, and pressed, the conversation with that one.
                val each = alongside[hit]
                if (each != null) return@setOnItemClickListener chooseAmong(each, hit.at)
                hit.number?.let { open(it, hit.at) }
            }
        }
        val pad = Look.dp(this, 12)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, 0)
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(asked, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    },
                )
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        Edges.fit(this)
        dressFor()
        asked.requestFocus()
    }

    private fun dressFor() {
        Look.barTitle(this, getString(if (number != null) R.string.search_here else if (inContacts) R.string.search_contacts else R.string.search_all))
        asked.setHint(if (inContacts) R.string.whom_hint else R.string.search_hint)
        hits = emptyList()
        people = emptyList()
        found.notifyDataSetChanged()
        look(asked.text.toString())
    }

    private fun open(number: String, at: Long) =
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number).putExtra(ThreadActivity.EXTRA_AT, at))

    /** Those a message went to, by name: the one pressed, its conversation at that message. */
    private fun chooseAmong(numbers: List<String>, at: Long) {
        val named = numbers.map { one -> synchronized(names) { names[one] }?.takeIf { it != one }?.let { "$it — $one" } ?: one }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.sent_to_each, numbers.size))
            .setItems(named.toTypedArray()) { _, which -> open(numbers[which], at) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun write(to: String) =
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, to))

    /** The contacts that hold what is typed -- all of them, nothing typed -- and a number typed in full, as it is. */
    private fun lookContacts(typed: String) {
        val mine = ++turn
        thread {
            val found = ArrayList<Whom>()
            Phone.plain(typed)?.let { found += Whom(it, it, null, typed = true) }
            if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                for (one in Book.find(this, typed, MOST_CONTACTS)) {
                    if (found.any { Phone.same(it.number, one.number) }) continue
                    val person = Book.who(this, one.number)
                    val face = person?.let { Book.face(this, it) } ?: person?.fingerprint?.let { foodjis.face(it) }
                    found += Whom(one.number, one.name, face)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || mine != turn || !inContacts) return@runOnUiThread
                people = found
                this.found.notifyDataSetChanged()
            }
        }
    }

    private fun look(wanted: String) {
        if (inContacts) return lookContacts(wanted)
        val mine = ++turn
        thread {
            val within = number?.let { runCatching { Store.thread(this, it) }.getOrNull() ?: -1L }
            val all = runCatching { Store.search(this, wanted, within) }.getOrDefault(emptyList())
            // The copies of one message sent to each of several, one result: the same words, sent at one moment -- or,
            // as another application filed a sending to each, in one conversation within moments.
            val each = HashMap<Store.Hit, List<String>>()
            val results = ArrayList<Store.Hit>()
            for (hit in all) {
                val first = if (hit.incoming) null else results.firstOrNull { !it.incoming && it.words == hit.words && (it.at == hit.at || (it.thread == hit.thread && kotlin.math.abs(it.at - hit.at) <= ALONGSIDE)) }
                if (first == null) { results += hit ; continue }
                each[first] = (each[first] ?: listOfNotNull(first.number)) + listOfNotNull(hit.number)
            }
            val contacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (contacts) {
                for (who in results.mapNotNull { it.number }.distinct()) {
                    if (synchronized(names) { who in names }) continue
                    val name = Book.who(this, who)?.name ?: who
                    synchronized(names) { names[who] = name }
                }
            }
            runOnUiThread {
                // An answer to what was typed three letters ago is no answer to what is typed now.
                if (isFinishing || isDestroyed || mine != turn || inContacts) return@runOnUiThread
                hits = results
                alongside = each
                found.notifyDataSetChanged()
            }
        }
    }

    private val found = object : BaseAdapter() {
        override fun getCount() = if (inContacts) people.size else hits.size
        override fun getItem(at: Int): Any = if (inContacts) people[at] else hits[at]
        override fun getItemId(at: Int) = at.toLong()

        override fun getView(at: Int, old: View?, parent: ViewGroup): View =
            if (inContacts) person(people[at]) else message(hits[at])

        /** A contact as the page of a new message shows them, with its own button to write. */
        private fun person(one: Whom): View {
            val context = this@SearchActivity
            val pad = Look.dp(context, 8)
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
                addView(Look.face(context, 44, null, one.face))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 12), 0, Look.dp(context, 8), 0)
                        val title = if (one.typed) getString(R.string.write_to, one.number) else one.name
                        addView(Look.words(context, title, size = 16f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) ; maxLines = 1 })
                        if (!one.typed && one.name != one.number) addView(Look.words(context, one.number, size = 14f, muted = true))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(Look.small(context, getString(R.string.whom_write)) { write(one.number) }.apply { isFocusable = false })
            }
        }

        private fun message(hit: Store.Hit): View {
            val context = this@SearchActivity
            val who = hit.number?.let { synchronized(names) { names[it] } ?: it } ?: getString(R.string.unknown_number)
            val many = alongside[hit]
            val about = (if (many != null) getString(R.string.sent_to_each, many.size) else getString(if (hit.incoming) R.string.hit_from else R.string.hit_to, who)) + "  ·  " +
                DateUtils.getRelativeTimeSpanString(hit.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = Look.dp(context, 10)
                setPadding(pad / 2, pad, pad / 2, pad)
                addView(Look.words(context, about, size = 13f, muted = true))
                addView(
                    Look.words(context, hit.words.replace('\n', ' '), size = 16f).apply {
                        maxLines = 3 ; ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
        }
    }

    companion object {
        /** The conversation to look in; none, and every conversation is looked in. */
        const val EXTRA_NUMBER = "number"

        /** Where to look: in the contacts, or in every conversation. */
        const val EXTRA_IN = "in"
        const val IN_CONTACTS = "contacts"
        const val IN_TALKS = "talks"

        /** How far apart the copies of one message sent to each may have been written. */
        private const val ALONGSIDE = 2 * 60 * 1000L

        /** How many contacts the list shows at most. */
        private const val MOST_CONTACTS = 200
    }
}
