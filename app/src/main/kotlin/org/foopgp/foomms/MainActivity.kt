package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import kotlin.concurrent.thread

/**
 * The conversations the phone holds, newest first: who, the last words, when,
 * and down the edge the colour of the last message.
 */
class MainActivity : Activity() {

    class Row(val talk: Store.Talk, val person: Book.Person?, val face: Bitmap?, val name: String, val verdict: Verdict, val last: String, val standing: String? = null)

    private lateinit var list: ListView
    private lateinit var asking: LinearLayout
    private lateinit var detail: android.widget.TextView

    /** Every conversation read so far, and whether only those with something unread are shown. */
    private var every: List<Row> = emptyList()
    private var onlyUnread = false

    /** Whether [every] holds every conversation, rather than the first ones of a reading cut short. */
    private var everyWhole = false

    /** The conversations with something unread, read for themselves; none yet, and they are picked out of [every]. */
    private var unreadRows: List<Row>? = null
    private lateinit var all: android.widget.TextView
    private lateinit var unread: android.widget.TextView
    private var rows: List<Row> = emptyList()

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        dressed = Look.dark
        super.onCreate(state)
        title = getString(R.string.app_title)
        // The counts of last time, until those of now are worked out.
        if (counts == null) {
            val last = getSharedPreferences("counts", MODE_PRIVATE).getString("last", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            if (last?.size == 8) counts = Store.Counts(last[0], last[1], last[2], last[3], last[4], last[5], last[6], last[7])
        }
        // A new start reads the address book again: a name may have changed since the application last ran.
        if (state == null) { synchronized(people) { people.clear() } ; synchronized(lines) { lines.clear() } }
        // In the bar: the application's mark, then how much of what the phone holds is PGP.
        all = Look.words(this, "", size = 15f).apply { setTextColor(Look.ORANGE) ; setTypeface(typeface, Typeface.BOLD) }
        unread = Look.words(this, "", size = 13f).apply { setTextColor(android.graphics.Color.rgb(0xE0, 0xE7, 0xFF)) }
        actionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            customView = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Pressed: the counts in more detail, and only the conversations that hold something unread; pressed again, all of them.
                setOnClickListener {
                    onlyUnread = !onlyUnread
                    unreadRows = null
                    // Called by its own name: inside the bar's code, "show()" was the bar's, which shows the bar.
                    redraw()
                    // In: the unread are read for themselves. Out: what was read of the rest is shown at once,
                    // and read to its end if being in had cut that short.
                    if (onlyUnread || !everyWhole) fill()
                }
                val side = Look.dp(context, 36)
                addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.launcher) }, LinearLayout.LayoutParams(side, side))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 12), 0, 0, 0)
                        addView(all)
                        // The dots that say the bar can be pressed, on its second line.
                        addView(Look.pressable(context, unread), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            // The whole width, up to the menu's own button: the dots stand at the right of it.
            customView.layoutParams = android.app.ActionBar.LayoutParams(android.app.ActionBar.LayoutParams.MATCH_PARENT, android.app.ActionBar.LayoutParams.WRAP_CONTENT)
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        asking = Look.column(this)
        list = ListView(this).apply {
            divider = null
            adapter = talks
            setOnItemLongClickListener { _, _, at, _ -> about(rows[at]) ; true }
            setOnItemClickListener { _, _, at, _ -> open(rows[at].talk) }
        }
        column.addView(asking)
        detail = Look.words(this, "", size = 14f, muted = true).apply {
            val pad = Look.dp(context, 16)
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
        }
        column.addView(detail)
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        Edges.fit(this)
    }

    private var dressed = false

    override fun onResume() {
        super.onResume()
        // The theme was changed while this page was behind another: it is built again in the new one.
        if (Look.wantsDark(this) != dressed) return recreate()
        Look.dress(this)
        asking.removeAllViews()
        val missing = NEEDED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.READ_SMS in missing) {
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.about), muted = true))
            asking.addView(Look.words(this, getString(R.string.why_read)).apply { setPadding(0, Look.dp(context, 12), 0, Look.dp(context, 12)) })
            asking.addView(Look.button(this, getString(R.string.allow)) { requestPermissions(missing.toTypedArray(), 1) })
            return
        }
        // Not the phone's messaging application yet: it can be asked for, and until then another receives.
        if (Keep.ours(this)) asking.visibility = View.GONE
        else {
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.not_default), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })
            asking.addView(Look.button(this, getString(R.string.become_default)) { becomeDefault() })
        }
        fill()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) = onResume()

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(self: Boolean) = fill()
    }

    override fun onStart() {
        super.onStart()
        runCatching { contentResolver.registerContentObserver(android.net.Uri.parse("content://mms-sms/"), true, arrivals) }
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(arrivals)
        super.onStop()
    }

    /**
     * The conversations, the latest first and shown as soon as they are
     * read, the rest behind them; or, when only the unread are wanted, those
     * alone, which are few. A phone holds years of messages: what was worked
     * out once -- who a number is, the line of a conversation that has not
     * changed -- is kept for as long as the application runs.
     */
    private fun fill() {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val turn = ++turns
        val unreadOnly = onlyUnread
        if (written != Book.written) { synchronized(people) { people.clear() } ; synchronized(lines) { lines.clear() } ; written = Book.written }
        thread {
            val foodjis = Foodjis.of(this)
            fun row(talk: Store.Talk): Row {
                synchronized(lines) { lines[talk.thread] }?.takeIf { it.talk.at == talk.at && it.talk.unread == talk.unread && it.talk.last == talk.last }?.let { return it }
                val person = talk.number?.takeIf { contacts }?.let { number ->
                    synchronized(people) { if (people.containsKey(number)) return@let people[number] }
                    Book.who(this, number).also { synchronized(people) { people[number] = it } }
                }
                // The face read here, off the page: drawn where it is shown, a contact's picture held the list still.
                val face = person?.let { Book.face(this, it) } ?: person?.fingerprint?.let { foodjis.face(it) }
                val armor = Pgp.armorIn(talk.last)
                val verdict = when {
                    armor == null -> Verdict.RED
                    // Not opened yet: nothing tells what it is worth, signed or not, before the key does.
                    else -> Opened.verdict(this, armor) ?: Verdict.GREY
                }
                val last = when {
                    armor == null -> talk.last
                    else -> Opened.words(armor)?.words ?: getString(if (Pgp.isLocked(armor)) R.string.locked_message else R.string.signed_message)
                }
                val standing = person?.fingerprint?.let { foodjis.card(it)?.validity }
                return Row(talk, person, face, person?.name ?: talk.number ?: getString(R.string.unknown_number), verdict, last, standing)
                    // A closed PGP message may be opened at any moment: its line is worked out each time.
                    .also { if (armor == null) synchronized(lines) { lines[talk.thread] = it } }
            }
            fun show(found: List<Row>, whole: Boolean = false) = runOnUiThread {
                if (isFinishing || isDestroyed || turn != turns) return@runOnUiThread
                if (unreadOnly) unreadRows = found else { every = found ; everyWhole = whole }
                redraw()
            }
            val stale = { turn != turns }
            // The counts as soon as there is something to look at: they are what a press on them shows, and
            // the ones of a moment ago stand in until then.
            fun count() = runCatching { Store.counts(this) }.getOrNull()?.let { fresh ->
                counts = fresh
                getSharedPreferences("counts", MODE_PRIVATE).edit()
                    .putString("last", listOf(fresh.pgp, fresh.all, fresh.unreadPgp, fresh.unread, fresh.sms, fresh.mms, fresh.talks, fresh.unreadTalks).joinToString(","))
                    .apply()
                runOnUiThread { if (!isFinishing && !isDestroyed) redraw() }
            }
            if (unreadOnly) {
                show(runCatching { Store.talks(this, Int.MAX_VALUE, unreadOnly = true, given = stale) }.getOrDefault(emptyList()).map(::row))
                if (!stale()) count()
            } else {
                val first = runCatching { Store.talks(this, FIRST) }.getOrDefault(emptyList()).map(::row)
                show(first, whole = first.size < FIRST)
                if (!stale()) count()
                if (first.size >= FIRST && !stale()) {
                    val all = runCatching { Store.talks(this, Int.MAX_VALUE, given = stale) }.getOrDefault(emptyList())
                    if (!stale()) show(all.map(::row), whole = true)
                }
            }
        }
    }

    /**
     * What the list shows of what was read: everything, or what holds
     * something unread -- at once from what is at hand, then from those
     * conversations read for themselves --, with the counts spelled out above.
     */
    private fun redraw() {
        rows = if (!onlyUnread) every else unreadRows ?: every.filter { it.talk.unread }
        talks.notifyDataSetChanged()
        val known = counts
        if (known != null) {
            all.text = getString(R.string.count_all, known.pgp, known.all)
            unread.text = getString(R.string.count_unread, known.unreadPgp, known.unread)
            detail.text = getString(
                R.string.count_detail,
                known.all, known.sms, known.mms, known.pgp, known.talks, known.unread, known.unreadPgp, known.unreadTalks,
            )
        }
        detail.visibility = if (onlyUnread && known != null) View.VISIBLE else View.GONE
    }

    /** Which filling is the latest asked for: an older one that finishes late shows nothing. */
    private var turns = 0

    /** A conversation's name, and after it what the person said of its sender: not to be announced. */
    private fun marked(row: Row): CharSequence = android.text.SpannableStringBuilder(row.name).apply {
        // The certificate the number is tied to, by the letter and colour foodjis's contacts page gives it.
        row.standing?.let { append("  ").append(Look.badge(it)) }
        if (Marks.muted(this@MainActivity, row.talk.number)) append("  🔕")
    }

    /** Pressed long, a conversation says what can be done about it and about who it is with. */
    private fun about(row: Row) {
        val sender = row.talk.number ?: return
        TalkMenu.show(
            this, row.name, sender, row.person, row.talk.thread,
            // Tying is done from the conversation, which is where foodjis shows its list over.
            tie = { open(row.talk, ask = ThreadActivity.ASK_TIE) },
            refresh = { open(row.talk, ask = ThreadActivity.ASK_REFRESH) },
            // What was said of the sender, or of whose certificate the number means, shows on its line.
            changed = { fill() },
            gone = { fill() },
        )
    }

    private fun open(talk: Store.Talk, ask: String? = null) = startActivity(
        Intent(this, ThreadActivity::class.java)
            .putExtra(ThreadActivity.EXTRA_NUMBER, talk.number ?: getString(R.string.unknown_number))
            .putExtra(ThreadActivity.EXTRA_THREAD, talk.thread)
            .putExtra(ThreadActivity.EXTRA_ASK, ask),
    )

    private val talks = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(at: Int) = rows[at]
        override fun getItemId(at: Int) = rows[at].talk.thread

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val row = rows[at]
            val context = this@MainActivity
            val pad = Look.dp(context, 12)
            val names = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            names.addView(
                Look.words(context, marked(row), size = 17f).apply {
                    setTypeface(typeface, if (row.talk.unread) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1 ; ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            names.addView(Look.words(context, ago(row.talk.at), size = 13f, muted = true))
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, 0, 0, 0)
                addView(names)
                addView(
                    Look.words(context, row.last.replace('\n', ' '), size = 14f, muted = !row.talk.unread).apply {
                        maxLines = 1 ; ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The colour of the last message, down the edge of the conversation.
                addView(View(context).apply { background = Look.rounded(Look.colour(row.verdict), Look.dp(context, 3)) }, LinearLayout.LayoutParams(Look.dp(context, 6), Look.dp(context, 44)))
                addView(View(context), LinearLayout.LayoutParams(pad, 1))
                addView(Look.face(context, 44, null, row.face))
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setPadding(Look.dp(context, 4), pad, pad, pad)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Menus.build(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = Menus.select(this, item) || super.onOptionsItemSelected(item)

    /** Android asks the person; nothing changes unless they say so. */
    private fun becomeDefault() {
        val ask = if (android.os.Build.VERSION.SDK_INT >= 29) {
            getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
        } else {
            Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(ask, 3)
    }

    private fun ago(at: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)

    private companion object {
        /** Kept for as long as the application runs: who a number is, the line of each conversation, the last counts. */
        val people = HashMap<String, Book.Person?>()
        val lines = HashMap<Long, Row>()
        @Volatile var counts: Store.Counts? = null
        @Volatile var written = 0

        /** How many conversations are shown before the rest is read. */
        const val FIRST = 20
        val NEEDED = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
    }
}
