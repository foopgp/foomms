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
class MainActivity : AskingActivity() {

    /** The list is the first page: nothing to leave it for by the top. */
    override val leftByTheTop = false

    /**
     * A line of the list. A group's names its members but this phone, by [group], its key; the conversations
     * Android keeps apart for it -- with this phone among the numbers, or without, as other applications
     * leave it out -- are one line, of all their [threads].
     */
    class Row(
        val talk: Store.Talk, val person: Book.Person?, val face: Bitmap?, val name: String, val verdict: Verdict, val last: String,
        val standing: String? = null, val group: String? = null, val threads: List<Long> = listOf(talk.thread), val unread: Boolean = talk.unread,
        /** Whom a conversation between two is with: the number besides this phone's, whatever Android files it under. */
        val with: String? = talk.number,
        /** A group's members as small faces, this phone last when there is room; a sending to each, those it went to. */
        val crowd: List<Look.Mini>? = null,
    ) {
        fun standing(now: String?) = Row(talk, person, face, name, verdict, last, now, group, threads, unread, with, crowd)

        /** What another application sent to each of several separately, filed as one conversation: among the conversations, marked. */
        val each: Boolean get() = talk.each

        /** Its face of pixels, with no picture: the certificate's, the number's, or the group's. */
        val seed: String? get() = group ?: Look.seed(person?.fingerprint, with)

        /** What lines are one by: a group's members, or whom a conversation between two is with. */
        val joint: String? get() = group ?: with?.let { "=" + Phone.key(it) }
    }

    /**
     * The lines of one group as one, and of one person: the latest's,
     * unread if any of them is. Android keeps a conversation with this phone
     * among its numbers apart from the one without.
     */
    private fun merged(found: List<Row>): List<Row> {
        val groups = LinkedHashMap<String, MutableList<Row>>()
        val out = ArrayList<Any>()
        for (row in found) {
            val key = row.joint
            if (key == null) { out += row ; continue }
            groups.getOrPut(key) { ArrayList<Row>().also { out += key } } += row
        }
        return out.map { one ->
            if (one is Row) one else groups.getValue(one as String).let { all ->
                val latest = all.first()
                if (all.size == 1) latest
                else Row(latest.talk, latest.person, latest.face, latest.name, latest.verdict, latest.last, latest.standing, latest.group, all.map { it.talk.thread }, all.any { it.unread }, latest.with, latest.crowd)
            }
        }
    }

    private lateinit var list: ListView
    private lateinit var asking: LinearLayout
    private lateinit var detail: android.widget.TextView

    /** The three pages of the list, in the order of the menu: the conversations between two, the unread, the groups. */
    private enum class Page { TALKS, UNREAD, GROUPS }

    /** Every conversation read so far, and the page shown. */
    private var every: List<Row> = emptyList()
    private var page = Page.TALKS
    private val onlyUnread get() = page == Page.UNREAD

    /** Whether [every] holds every conversation, rather than the first ones of a reading cut short. */
    private var everyWhole = false

    /** The unread page's or the groups' conversations, read for themselves; none yet, and they are picked out of [every]. */
    private var pageRows: List<Row>? = null
    private lateinit var heading: android.widget.TextView
    private lateinit var counted: android.widget.TextView

    /** Under the bar, the ways to the two other pages. */
    private lateinit var others: LinearLayout

    /** On the groups' page, under its buttons: oneself, a member of every group -- face, number, and the menu of its dots. */
    private lateinit var meBox: LinearLayout
    @Volatile private var me: MeMenu.State? = null

    /** The key of the identity foodjis holds, once it has said: the certificate offered first to tie one's contact to. */
    @Volatile private var mineKey: Long? = null

    /** Under the counts in detail: everything read at once. */
    private lateinit var tools: LinearLayout
    private lateinit var markAll: android.widget.Button
    private var rows: List<Row> = emptyList()

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        dressed = Look.dark
        super.onCreate(state)
        // foodjis started now, while the list is drawn, rather than when a conversation first needs it.
        Foodjis.of(this).wake()
        // This phone's number, from the MMS it has received, for the groups.
        if (state == null) thread { Me.look(applicationContext) }
        title = getString(R.string.app_title)
        // The counts of last time, until those of now are worked out.
        if (counts == null) {
            counts = Store.Counts.of(getSharedPreferences("counts", MODE_PRIVATE).getString("last", null))
        }
        // A new start reads the address book again: a name may have changed since the application last ran.
        if (state == null) { synchronized(people) { people.clear() } ; synchronized(lines) { lines.clear() } }
        page = Page.entries.getOrNull(state?.getInt(PAGE) ?: 0) ?: Page.TALKS
        // Nothing encrypted open: what was laid out for other applications from a lock has no business staying.
        // Not when the page is opened to receive one of them: forwarding a part hands it here, laid out for others.
        if (!Opened.anyOpen() && intent?.action != Intent.ACTION_SEND) Outbox.shut(this)
        // In the bar: the application's mark, then the page and how much it holds.
        heading = Look.words(this, "", size = 17f).apply { setTextColor(Look.ORANGE) ; setTypeface(typeface, Typeface.BOLD) ; maxLines = 1 }
        counted = Look.words(this, "", size = 13f).apply { setTextColor(android.graphics.Color.rgb(0xE0, 0xE7, 0xFF)) }
        actionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            customView = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Pressed: the pages, to go to another.
                setOnClickListener { pages() }
                val side = Look.dp(context, 36)
                // At the far left, the way to the configuration; at the far right, to the search.
                addView(Look.icon(context, R.drawable.settings, getString(R.string.configure)) { Menus.configure(this@MainActivity) })
                addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.launcher) }, LinearLayout.LayoutParams(side, side).apply { marginStart = Look.dp(context, 4) })
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 12), 0, 0, 0)
                        addView(Look.pressable(context, heading))
                        addView(counted)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(Look.icon(context, R.drawable.search, getString(R.string.search_all)) { Menus.search(this@MainActivity) })
            }
            // The whole width of the bar.
            customView.layoutParams = android.app.ActionBar.LayoutParams(android.app.ActionBar.LayoutParams.MATCH_PARENT, android.app.ActionBar.LayoutParams.WRAP_CONTENT)
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        asking = Look.column(this)
        list = ListView(this).apply {
            divider = null
            adapter = talks
            setOnItemLongClickListener { _, _, at, _ -> about(rows[at]) ; true }
            // Near the end of what is held: the next block.
            setOnScrollListener(
                object : android.widget.AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) = Unit
                    override fun onScroll(view: android.widget.AbsListView?, first: Int, seen: Int, all: Int) {
                        if (all > 0 && first + seen >= all - AHEAD) more()
                    }
                },
            )
            setOnItemClickListener { _, _, at, _ -> open(rows[at].talk, threads = rows[at].threads, with = rows[at].with ?: rows[at].talk.number, each = rows[at].each) }
        }
        others = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val side = Look.dp(context, 12)
            setPadding(side, side / 2, side, 0)
        }
        column.addView(others)
        meBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = Look.dp(context, 16)
            setPadding(side, side / 2, side / 2, 0)
            visibility = View.GONE
            setOnClickListener { me?.let { known -> MeMenu.show(this@MainActivity, known) { meChanged() } } }
        }
        column.addView(meBox)
        column.addView(closing)
        column.addView(asking)
        detail = Look.words(this, "", size = 14f, muted = true).apply {
            val pad = Look.dp(context, 16)
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
            // Pressed, the counts give the conversations back.
            setOnClickListener { go(Page.TALKS) }
        }
        column.addView(detail)
        markAll = Look.small(this, getString(R.string.mark_all_read)) { markAll() }
        tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val side = Look.dp(context, 16)
            setPadding(side, 0, side, side / 2)
            visibility = View.GONE
            addView(markAll)
        }
        column.addView(tools)
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pad = Look.dp(this, 8)
        column.addView(
            Look.button(this, getString(R.string.write)) { Menus.write(this) }.apply { textSize = 16f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(pad, 0, pad, pad) },
        )
        setContentView(column)
        Edges.fit(this)
        shared(intent)
    }

    private var dressed = false

    /** The signed messages being verified, or that could not be: not asked again while the page stands. */
    private val verifying = HashSet<String>()

    /**
     * Signed only, a last message needs no key: it is verified as soon as it
     * is shown, and never stays grey. The list is read again once foodjis has
     * answered for all of them.
     */
    private fun verifySigned(found: List<Row>) {
        val asked = found.mapNotNull { Pgp.armorIn(it.talk.last)?.let { a -> Opened.inner(a) } }
            .filter { !Pgp.isLocked(it) && Opened.words(it) == null && verifying.add(it) }
        var left = asked.size
        var any = false
        for (armor in asked) {
            provider.ask(android.content.Intent(Provider.DECRYPT_VERIFY), armor.toByteArray()) { answer ->
                if (isFinishing || isDestroyed) return@ask
                if (answer is Provider.Answer.Done) { Signer.keep(this, armor, answer) ; verifying.remove(armor) ; any = true }
                if (--left == 0 && any) fill()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    /**
     * A text, a picture or a file shared from another application: whom it is
     * for is asked, and their conversation opens with it written or carried.
     */
    private fun shared(asked: Intent?) {
        if (asked?.action != Intent.ACTION_SEND) return
        val text = asked.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = asked.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (text == null && stream == null) return
        // Once: the page made again keeps its intent, and would ask again.
        setIntent(Intent(this, MainActivity::class.java))
        Menus.write(this, text, stream)
    }

    /**
     * One's contact or number changed: the groups are told apart again --
     * this phone is now among the members of those it did not receive as
     * one --, their lines worked out anew, and the counts with them.
     */
    private fun meChanged() {
        synchronized(lines) { lines.clear() }
        readMe()
        fill()
    }

    /** Oneself read again, for the groups' page: what one's contact, number or certificate are now. */
    private fun readMe() {
        if (page != Page.GROUPS) return
        thread {
            val known = runCatching { MeMenu.read(this, mineKey) }.getOrNull() ?: return@thread
            me = known
            runOnUiThread { if (!isFinishing && !isDestroyed) drawMe(known) }
        }
    }

    /** "Me:", one's face framed by one's certificate's standing, this phone's number, the dots. */
    private fun drawMe(known: MeMenu.State) {
        meBox.removeAllViews()
        val pad = Look.dp(this, 10)
        meBox.addView(Look.words(this, getString(R.string.me_label), size = 16f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, 0, pad, 0) })
        val own = known.own
        meBox.addView(Look.face(this, 36, own?.photo, null, known.ownCert?.validity, framable = true, seed = Look.seed(own?.fingerprint, known.number)))
        meBox.addView(
            Look.words(this, known.number ?: getString(R.string.me_unknown), size = 16f, muted = known.number == null).apply { setPadding(pad, 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        meBox.addView(Look.words(this, "⋮", size = 22f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(pad, 0, pad, 0) })
    }

    /** Another page: the conversations between two, those with something unread and the counts in detail, or the groups. */
    private fun go(to: Page) {
        if (to == page) return
        page = to
        pageRows = null
        // Called by its own name: inside the bar's code, "show()" was the bar's, which shows the bar.
        redraw()
        readMe()
        // The unread are read for themselves. The others: what was read is shown at once,
        // and read again only if nothing was.
        if (page != Page.TALKS || every.isEmpty()) fill()
    }

    /** The pages, the one shown ticked, from the bar's dots. */
    private fun pages() {
        AlertDialog.Builder(this)
            .setSingleChoiceItems(Page.entries.map { named(it) }.toTypedArray(), page.ordinal) { asked, at -> asked.dismiss() ; go(Page.entries[at]) }
            .show()
    }

    /** A page's name, the unread's with how many there are. */
    private fun named(it: Page): String = when (it) {
        Page.TALKS -> getString(R.string.page_talks)
        Page.UNREAD -> counts?.let { known -> getString(R.string.page_unread_n, known.unread) } ?: getString(R.string.page_unread)
        Page.GROUPS -> getString(R.string.page_groups)
    }

    override fun onActivityResult(request: Int, outcome: Int, data: android.content.Intent?) {
        super.onActivityResult(request, outcome, data)
        if (MeMenu.result(this, request, outcome, data)) return meChanged()
        Saving.done(this, request, outcome, data) { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
        // Asked to be the messaging application, whatever was answered: what is still missing is asked now.
        if (request == ROLE) {
            val still = (NEEDED + listOfNotNull(NOTIFY)).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (still.isNotEmpty()) requestPermissions(still.toTypedArray(), 1)
        }
    }

    /**
     * Allowed: first the phone is asked to make foomms its messaging
     * application -- Play's rule for a default handler, whose prompt comes
     * before any permission's; the role brings the SMS with it --, then what
     * is still missing, the contacts and the notifications.
     */
    private fun allow(asked: List<String>) {
        if (!Keep.ours(this) && roleAskable()) becomeDefault() else requestPermissions(asked.toTypedArray(), 1)
    }

    private fun roleAskable(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getSystemService(android.app.role.RoleManager::class.java)?.let { it.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS) && !it.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) } == true
        } else true

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putInt(PAGE, page.ordinal)
    }

    override fun onResume() {
        super.onResume()
        // The theme was changed while this page was behind another: it is built again in the new one.
        if (Look.wantsDark(this) != dressed) return recreate()
        Look.dress(this)
        Foodjis.of(this).fresh()
        // Whose identity foodjis holds, for oneself on the groups' page; asked without showing any of its screens.
        provider.ask(Intent(Provider.GET_SIGN_KEY_ID), null) { answer ->
            mineKey = (answer as? Provider.Answer.Done)?.says?.getLongExtra(Provider.EXTRA_SIGN_KEY_ID, 0L)?.takeIf { it != 0L }
            readMe()
        }
        asking.removeAllViews()
        val missing = NEEDED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.READ_SMS in missing) {
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.about), muted = true))
            asking.addView(Look.words(this, getString(R.string.why_read)).apply { setPadding(0, Look.dp(context, 12), 0, Look.dp(context, 12)) })
            // The notifications are asked with the rest: the messaging application of the phone has them anyway,
            // and until foomms is that application it would show nothing of what arrives.
            val asked = missing + listOfNotNull(NOTIFY.takeIf { it != null && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            asking.addView(Look.button(this, getString(R.string.allow)) { allow(asked) })
            // Installed from a file, Android grants no SMS until the person allows the restricted settings in
            // foomms's page of the settings: asking here would end in silence.
            if (fromFile()) {
                asking.addView(Look.words(this, getString(R.string.restricted_hint), size = 14f, muted = true).apply { setPadding(0, Look.dp(context, 12), 0, Look.dp(context, 8)) })
                asking.addView(
                    Look.button(this, getString(R.string.restricted_open)) {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                    },
                )
            }
            return
        }
        asking.visibility = View.GONE
        // Nobody to ask for the keys: foodjis is offered to be installed, under the name this build speaks to.
        if (Provider.whom(this) == null) {
            val name = "foodjis"
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.no_foodjis, name), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })
            asking.addView(Look.button(this, getString(R.string.install_foodjis, name)) { Provider.install(this) })
        }
        // There, but older than this build needs: some of what it is asked would fail.
        Provider.outdated(this)?.let { held ->
            val name = "foodjis"
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.old_foodjis, name, held, Provider.NEEDED_NAME), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })
            asking.addView(Look.button(this, getString(R.string.update_foodjis, name)) { Provider.install(this) })
        }
        // Not the phone's messaging application yet: it can be asked for, and until then another receives.
        if (!Keep.ours(this)) {
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.not_default), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })
            asking.addView(Look.button(this, getString(R.string.become_default)) { becomeDefault() })
        }
        fill()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) = onResume()

    /** Installed from a file, downloaded or not, rather than by a store: Android then restricts the SMS. */
    private fun fromFile(): Boolean = android.os.Build.VERSION.SDK_INT >= 33 && runCatching {
        packageManager.getInstallSourceInfo(packageName).packageSource in setOf(
            android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE,
            android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE,
        )
    }.getOrDefault(false)

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(self: Boolean) = fill()
    }

    /**
     * Under the bar while encrypted messages are open somewhere: the
     * conversation whose countdown is the longest -- the last one opened in
     * --, when it closes, and a button to close everything now.
     */
    private val closing by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = Look.dp(context, 12)
            setPadding(pad, pad / 3, pad, pad / 3)
            setBackgroundColor(Look.pale(Verdict.YELLOW))
            visibility = View.GONE
            addView(remaining, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Look.small(context, getString(R.string.close_all)) {
                Opened.closeAll()
                Outbox.shut(this@MainActivity)
                tick()
                fill()
            })
        }
    }
    private val remaining by lazy {
        Look.words(this, "", size = 14f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            // Pressed, the conversation it names opens.
            setOnClickListener { goToLatest() }
        }
    }

    /** The conversation whose countdown the strip shows. */
    private fun goToLatest() {
        val (thread, _, _) = Opened.latest() ?: return
        val number = Opened.number(thread) ?: return
        startActivity(
            Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number).putExtra(ThreadActivity.EXTRA_THREAD, thread),
        )
    }

    private val ticking = object : Runnable {
        override fun run() {
            tick()
            closing.postDelayed(this, 1000)
        }
    }

    private var wasOpen = false

    private fun tick() {
        val latest = Opened.latest()
        if (latest != null) {
            val (_, title, closes) = latest
            val said = getString(R.string.closes_talk, title.orEmpty(), Media.clock(maxOf(0L, closes - System.currentTimeMillis() + 999)))
            // The name underlined: it is what a press opens.
            remaining.text = android.text.SpannableString(said).apply {
                val at = if (title.isNullOrEmpty()) -1 else said.indexOf(title)
                if (at >= 0) setSpan(android.text.style.UnderlineSpan(), at, at + title!!.length, 0)
            }
        }
        closing.visibility = if (latest != null) View.VISIBLE else View.GONE
        // Closed now: the last lines shown open are shown closed again.
        if (latest == null && wasOpen) { if (!Opened.anyOpen()) Outbox.shut(this) ; fill() }
        wasOpen = latest != null
    }

    override fun onStart() {
        super.onStart()
        closing.removeCallbacks(ticking)
        ticking.run()
        runCatching { contentResolver.registerContentObserver(android.net.Uri.parse("content://mms-sms/"), true, arrivals) }
    }

    override fun onStop() {
        closing.removeCallbacks(ticking)
        contentResolver.unregisterContentObserver(arrivals)
        super.onStop()
    }

    /**
     * The conversations, the latest first and shown as soon as they are
     * read, the rest behind them; or, when only the unread are wanted, those
     * alone, which are few. A phone holds years of messages: what was worked
     * out once -- who a number is, the line of a conversation that has not
     * changed -- is kept for as long as the application runs; the badge of
     * the certificate only is asked again.
     */
    private fun fill() {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val turn = ++turns
        val unreadOnly = onlyUnread
        // The unread and the groups are read for themselves: few among all the conversations.
        val apart = page != Page.TALKS
        val groupsOnly = page == Page.GROUPS
        if (written != Book.written) { synchronized(people) { people.clear() } ; synchronized(lines) { lines.clear() } ; written = Book.written }
        thread {
            val foodjis = Foodjis.of(this)
            // Oneself as a small face, last in each group's square: one's contact's picture, else pixels from one's number.
            val myself: Look.Mini by lazy {
                val own = Me.card(this)?.let { Book.own(this, it) }
                val cert = own?.fingerprint?.let { foodjis.card(it) }
                Look.Mini(
                    own?.photo?.let { Media.thumbnail(this, it, Media.FACE) } ?: own?.fingerprint?.let { foodjis.face(it) },
                    cert?.validity, Look.seed(own?.fingerprint, Me.number(this)),
                )
            }
            fun row(talk: Store.Talk): Row {
                // A line kept is kept but for its badge: what foodjis says of the certificate changes there, unseen here.
                synchronized(lines) { lines[talk.thread] }?.takeIf { it.talk.at == talk.at && it.talk.unread == talk.unread && it.talk.last == talk.last }?.let { kept ->
                    val now = kept.person?.fingerprint?.let { foodjis.card(it)?.validity }
                    return if (now == kept.standing) kept else kept.standing(now)
                        .also { synchronized(lines) { lines[talk.thread] = it } }
                }
                fun who(number: String): Book.Person? {
                    if (!contacts) return null
                    synchronized(people) { if (people.containsKey(number)) return people[number] }
                    return Book.who(this, number).also { synchronized(people) { people[number] = it } }
                }
                // A group is named by its members but this phone; its face comes later.
                val mine = Me.all(this)
                // Several besides this phone: a group. One: a conversation between two, whatever the numbers Android files it under.
                val others = talk.members.filterNot { member -> mine.any { Phone.same(member, it) } }
                val group = others.takeIf { it.size > 1 && !talk.each }
                // A sending to each is with nobody in particular: its own line, merged with none.
                val with = if (talk.each) null else others.firstOrNull() ?: talk.number
                val person = if (group != null) null else with?.let(::who)
                // The face read here, off the page: drawn where it is shown, a contact's picture held the list still.
                val face = person?.let { Book.face(this, it) } ?: person?.fingerprint?.let { foodjis.face(it) }
                val armor = Pgp.armorIn(talk.last)?.let { Opened.inner(it) }
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
                val name = (group ?: others.takeIf { talk.each })?.joinToString(", ") { who(it)?.name ?: it } ?: person?.name ?: with ?: getString(R.string.unknown_number)
                // A group's members as small faces, and this phone after them -- unless the nine places are taken.
                val crowd = (group ?: others.takeIf { talk.each })?.let { members ->
                    members.map { one ->
                        val them = who(one)
                        Look.Mini(
                            them?.let { Book.face(this, it) } ?: them?.fingerprint?.let { foodjis.face(it) },
                            them?.fingerprint?.let { foodjis.card(it)?.validity }, Look.seed(them?.fingerprint, one),
                        )
                    } + listOfNotNull(myself.takeIf { members.size < 9 && !talk.each })
                }
                return Row(talk, person, face, name, verdict, last, standing, group?.let { Phone.group(talk.members, mine) }, with = with, crowd = crowd)
                    // A closed PGP message may be opened at any moment: its line is worked out each time.
                    .also { if (armor == null) synchronized(lines) { lines[talk.thread] = it } }
            }
            fun show(found: List<Row>, whole: Boolean = false) = runOnUiThread {
                if (isFinishing || isDestroyed || turn != turns) return@runOnUiThread
                if (apart) pageRows = found else { every = found ; everyWhole = whole }
                redraw()
                verifySigned(found)
            }
            val stale = { turn != turns }
            // The counts as soon as there is something to look at: they are what a press on them shows, and
            // the ones of a moment ago stand in until then.
            fun count() = runCatching { Store.counts(this) }.getOrNull()?.let { fresh ->
                counts = fresh
                getSharedPreferences("counts", MODE_PRIVATE).edit()
                    .putString("last", fresh.kept())
                    .apply()
                runOnUiThread { if (!isFinishing && !isDestroyed) redraw() }
            }
            if (apart) {
                show(runCatching { Store.talks(this, Int.MAX_VALUE, unreadOnly = unreadOnly, given = stale, groupsOnly = groupsOnly) }.getOrDefault(emptyList()).map(::row))
                if (!stale()) count()
            } else {
                // The latest first, and no more than were shown already: the rest comes a block at a time, as the list is scrolled ([more]).
                rowOf = ::row
                val asked = maxOf(FIRST, every.size)
                val first = runCatching { Store.talks(this, asked, given = stale) }.getOrDefault(emptyList()).map(::row)
                show(first, whole = first.size < asked)
                if (!stale()) count()
            }
        }
    }

    /**
     * What the list shows of what was read: everything, or what holds
     * something unread -- at once from what is at hand, then from those
     * conversations read for themselves --, with the counts spelled out above.
     */
    private fun redraw() {
        rows = merged(
            when (page) {
                Page.TALKS -> every.filter { it.group == null }
                Page.UNREAD -> pageRows ?: every.filter { it.talk.unread }
                Page.GROUPS -> (pageRows ?: every).filter { it.group != null }
            },
        )
        talks.notifyDataSetChanged()
        heading.text = named(page).takeIf { page != Page.UNREAD } ?: getString(R.string.page_unread)
        others.removeAllViews()
        for (other in Page.entries.filter { it != page }) {
            others.addView(
                Look.small(this, named(other)) { go(other) }.apply { textSize = 15f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        meBox.visibility = if (page == Page.GROUPS) View.VISIBLE else View.GONE
        val known = counts
        if (known != null) {
            counted.text = when (page) {
                Page.TALKS -> resources.getQuantityString(R.plurals.talks_n, known.talks - known.groupTalks, known.talks - known.groupTalks)
                Page.UNREAD -> resources.getQuantityString(R.plurals.unread_n, known.unread, known.unread)
                Page.GROUPS -> resources.getQuantityString(R.plurals.groups_n, known.groups, known.groups)
            }
            val weight = java.text.NumberFormat.getIntegerInstance().format(known.bytes)
            detail.text = android.text.SpannableStringBuilder(
                getString(R.string.count_detail, known.all, known.sms, known.mms, known.pgp, known.pgpSms, known.pgpMms, known.talks, weight),
            ).append("\n\n").append(
                getString(R.string.count_detail_unread, known.unread),
                android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            ).append(getString(R.string.count_detail_unread_rest, known.unreadPgp, known.unreadTalks))
        }
        detail.visibility = if (onlyUnread && known != null) View.VISIBLE else View.GONE
        tools.visibility = detail.visibility
        // Nothing to do, greyed rather than gone: the line does not jump.
        usable(markAll, (known?.unread ?: 0) > 0)
    }

    private fun usable(button: android.widget.Button, on: Boolean) {
        button.isEnabled = on
        button.alpha = if (on) 1f else 0.38f
    }

    /** Every message read, locked or not, once the person has said so. */
    private fun markAll() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.mark_all_read_ask, counts?.unread ?: 0))
            .setPositiveButton(R.string.mark_all_read) { _, _ ->
                thread {
                    Keep.readAll(this)
                    Notify.seenAll(this)
                    runOnUiThread { if (!isFinishing && !isDestroyed) fill() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** How the latest filling makes a line of a conversation: the next blocks are made the same way. */
    @Volatile private var rowOf: ((Store.Talk) -> Row)? = null
    private var reading = false

    /**
     * The next block of conversations, read when the list comes near the end
     * of what it holds: the oldest are read only by whoever goes down to them.
     */
    private fun more() {
        if (page != Page.TALKS || everyWhole || reading) return
        val make = rowOf ?: return
        val turn = turns
        val from = every.size
        reading = true
        thread {
            val next = runCatching { Store.talks(this, BLOCK, from = from, given = { turn != turns }) }.getOrDefault(emptyList()).map(make)
            runOnUiThread {
                reading = false
                if (isFinishing || isDestroyed || turn != turns || every.size != from) return@runOnUiThread
                every = every + next
                everyWhole = next.size < BLOCK
                redraw()
                verifySigned(next)
            }
        }
    }

    /** Which filling is the latest asked for: an older one that finishes late shows nothing. */
    private var turns = 0

    /** A conversation's name, and after it what the person said of its sender: not to be announced. */
    private fun marked(row: Row): CharSequence = android.text.SpannableStringBuilder(row.name).apply {
        if (Marks.muted(this@MainActivity, row.talk.number)) append("  🔕")
    }

    /** Pressed long, a conversation says what can be done about it and about who it is with. */
    private fun about(row: Row) {
        // A group's own menu comes with its name.
        if (row.talk.group) return
        val sender = row.talk.number ?: return
        TalkMenu.show(
            this, row.name, sender, row.person, row.talk.thread,
            // Tying is done from the conversation, which is where foodjis shows its list over.
            tie = { open(row.talk, ask = ThreadActivity.ASK_TIE) },
            refresh = { open(row.talk, ask = ThreadActivity.ASK_REFRESH) },
            // What was said of the sender, or of whose certificate the number means, shows on its line.
            changed = { fill() },
            gone = { fill() },
            save = { Saving.save(this, row.talk.thread, row.name) },
        )
    }

    private fun open(talk: Store.Talk, ask: String? = null, threads: List<Long> = listOf(talk.thread), with: String? = talk.number, each: Boolean = false) = startActivity(
        Intent(this, ThreadActivity::class.java)
            .putExtra(ThreadActivity.EXTRA_EACH, each)
            .putExtra(ThreadActivity.EXTRA_NUMBER, with ?: getString(R.string.unknown_number))
            .putExtra(ThreadActivity.EXTRA_THREAD, talk.thread)
            .putExtra(ThreadActivity.EXTRA_THREADS, threads.toLongArray())
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
                    setTypeface(typeface, if (row.unread) Typeface.BOLD else Typeface.NORMAL)
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
                    Look.words(context, row.last.replace('\n', ' '), size = 14f, muted = !row.unread).apply {
                        maxLines = 1 ; ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The colour of what the last message travelled as, down the edge of the conversation.
                addView(View(context).apply { background = Look.rounded(Look.colour(row.talk.form), Look.dp(context, 3)) }, LinearLayout.LayoutParams(Look.dp(context, 6), Look.dp(context, if (row.crowd != null) CROWD else 44)))
                addView(View(context), LinearLayout.LayoutParams(pad, 1))
                addView(row.crowd?.let { Look.crowd(context, CROWD, it, each = row.each) } ?: Look.face(context, 44, null, row.face, row.standing, framable = true, seed = row.seed))
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // A group's square is tall enough: less room above and below it.
                val around = if (row.crowd != null) pad / 3 else pad
                setPadding(Look.dp(context, 4), around, pad, around)
            }
        }
    }

    /** Android asks the person; nothing changes unless they say so. */
    private fun becomeDefault() {
        val ask = if (android.os.Build.VERSION.SDK_INT >= 29) {
            getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
        } else {
            Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(ask, ROLE)
    }

    private fun ago(at: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)

    private companion object {
        const val PAGE = "page"
        /** Kept for as long as the application runs: who a number is, the line of each conversation, the last counts. */
        val people = HashMap<String, Book.Person?>()
        val lines = HashMap<Long, Row>()
        @Volatile var counts: Store.Counts? = null
        @Volatile var written = 0

        /** A group's square of small faces, larger than one face: each still a face at a glance. */
        const val CROWD = 66

        /** How many conversations are shown before the rest is read, and how many more each time the list nears its end. */
        const val FIRST = 20
        const val BLOCK = 30

        /** How near the end of what the list holds the next block is asked for: read before it is reached. */
        const val AHEAD = 8
        /** The answer to being asked to be the messaging application. */
        private const val ROLE = 3

        val NEEDED = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

        /** Asked from Android 13 on; before, notifications need no permission. */
        val NOTIFY: String? = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    }
}
