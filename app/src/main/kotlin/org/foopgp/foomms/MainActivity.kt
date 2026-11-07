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
        /** A group's: who wrote its last message, beside their name. */
        val speaker: Look.Mini? = null,
        /** A group's MMS filed elsewhere -- in their sender's conversation, by another application --, by number. */
        val messages: List<Long> = emptyList(),
        /** What is being written to them and not sent: shown in place of the last message. */
        val draft: String? = null,
    ) {
        fun standing(now: String?) = Row(talk, person, face, name, verdict, last, now, group, threads, unread, with, crowd, speaker, messages, draft)

        /** The line of one of those a sending to each went to: theirs, the sending shown in their conversation, not a conversation of its own. */
        fun apart() = Row(talk, person, face, name, verdict, last, standing, group, emptyList(), unread, with, crowd, speaker)

        /** A group heard from the headers of [messages], filed in their senders' conversations: none of those is its. */
        fun heard(messages: List<Long>) = Row(talk, person, face, name, verdict, last, standing, group, emptyList(), unread, with, crowd, speaker, messages)

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
                else Row(
                    latest.talk, latest.person, latest.face, latest.name, latest.verdict, latest.last, latest.standing, latest.group,
                    all.flatMap { it.threads }.distinct(), all.any { it.unread }, latest.with, latest.crowd, latest.speaker, all.flatMap { it.messages }.distinct(),
                    all.firstNotNullOfOrNull { it.draft },
                )
            }
        }
    }

    private lateinit var list: ListView
    private lateinit var asking: LinearLayout
    private lateinit var detail: android.widget.TextView

    /** The conversations, every one; oneself, the counts, the drafts and the unread. */
    private enum class Page { TALKS, PROFILE }

    /** Every conversation read so far, and the page shown. */
    private var every: List<Row> = emptyList()

    private var page = Page.TALKS

    /** Whether [every] holds every conversation, rather than the first ones of a reading cut short. */
    private var everyWhole = false

    private lateinit var heading: android.widget.TextView
    private lateinit var counted: android.widget.TextView

    /** Under the bar, the way to the profile's page; from it, the way back is the bar's arrow. */
    private lateinit var others: LinearLayout

    /** In the bar, the way to the configuration, on the conversations' page. */
    private lateinit var gear: View

    /** On the profile's page: oneself, a member of every group -- face and number --, and under it what can be done about it. */
    private lateinit var meBox: LinearLayout
    private lateinit var meActions: LinearLayout
    private lateinit var meRule: View

    /** In the bar, the page's mark: the application's, or a glyph. */
    private lateinit var mark: android.widget.ImageView
    private lateinit var markGlyph: android.widget.TextView

    /** The groups only the headers of received MMS tell, filed in their senders' conversations: among all the conversations. */
    private var heardRows: List<Row> = emptyList()
    @Volatile private var me: MeMenu.State? = null

    /** Whether this phone's numbers were read from the MMS it received, since the application started. */
    private var looked = false

    /** The key of the identity foodjis holds, once it has said: the certificate offered first to tie one's contact to. */
    @Volatile private var mineKey: Long? = null

    /** On the profile's page, under the drafts: how many are unread, and a way to see them. */
    private lateinit var unreadLine: LinearLayout
    private lateinit var seeUnread: android.widget.Button
    private lateinit var draftsLine: LinearLayout

    /** On the profile's page: what the messages weigh, saving them all, restoring a saving. */
    private lateinit var savingLines: LinearLayout
    private lateinit var bytesSaid: android.widget.TextView
    private lateinit var draftsSaid: android.widget.TextView
    private lateinit var clearDrafts: android.widget.Button
    private lateinit var unreadSaid: android.widget.TextView

    /** The conversations holding something unread, read with the profile's page. */
    private var unreadRows: List<Row> = emptyList()
    private var rows: List<Row> = emptyList()

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        dressed = Look.dark
        super.onCreate(state)
        // foodjis started now, while the list is drawn, rather than when a conversation first needs it.
        Foodjis.of(this).wake()
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
                // At the far left, the way to the configuration -- on the profile's page, the arrow back to the
                // conversations in its place --; at the far right, to the search.
                gear = Look.icon(context, R.drawable.settings, getString(R.string.configure)) { Menus.configure(this@MainActivity) }
                addView(gear)
                // The page's mark: the application's on the conversations, the unread's and the profile's on theirs.
                mark = android.widget.ImageView(context).apply { setImageResource(R.drawable.launcher) }
                addView(mark, LinearLayout.LayoutParams(side, side).apply { marginStart = Look.dp(context, 4) })
                markGlyph = Look.words(context, "", size = 24f).apply { gravity = Gravity.CENTER ; visibility = View.GONE }
                addView(markGlyph, LinearLayout.LayoutParams(side, side).apply { marginStart = Look.dp(context, 4) })
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 12), 0, 0, 0)
                        addView(Look.pressable(context, heading))
                        addView(counted)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(Look.icon(context, R.drawable.search, getString(R.string.search_all)) { Searching.ask(this@MainActivity) })
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
            setOnItemClickListener { _, _, at, _ -> open(rows[at]) }
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
        }
        // What the menu of oneself held, laid out: the page has the room, and nothing needs pressing to be found.
        meActions = Look.column(this).apply { visibility = View.GONE }
        column.addView(closing)
        column.addView(asking)
        detail = Look.words(this, "", size = 14f, muted = true).apply {
            val pad = Look.dp(context, 16)
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
        }
        column.addView(detail)
        // On the profile's page, under the counts: what it all weighs and saving it all, then restoring a saving --
        // what the configuration's page offers, where the counts are.
        bytesSaid = Look.words(this, "", size = 14f, muted = true)
        savingLines = Look.column(this).apply {
            val side = Look.dp(context, 16)
            setPadding(side, 0, side, Look.dp(context, 14))
            visibility = View.GONE
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bytesSaid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Look.small(context, getString(R.string.backup_save)) { Saving.save(this@MainActivity) })
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(
                        Look.small(context, getString(R.string.backup_restore)) {
                            if (!Saving.restore(this@MainActivity)) android.widget.Toast.makeText(this@MainActivity, R.string.forget_not_ours, android.widget.Toast.LENGTH_LONG).show()
                        },
                    )
                },
            )
        }
        column.addView(savingLines)
        // The drafts, and a way to be rid of them all, on a line before the unread.
        draftsSaid = Look.words(this, "", size = 14f, muted = true)
        clearDrafts = Look.small(this, getString(R.string.clear_drafts)) { clearDrafts() }
        draftsLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = Look.dp(context, 16)
            setPadding(side, 0, side, side / 2)
            visibility = View.GONE
            addView(draftsSaid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearDrafts)
        }
        column.addView(draftsLine)
        // The unread, the same way, under the drafts.
        unreadSaid = Look.words(this, "", size = 14f, muted = true)
        seeUnread = Look.small(this, getString(R.string.see_unread)) { seeUnread() }
        unreadLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = Look.dp(context, 16)
            setPadding(side, 0, side, side / 2)
            visibility = View.GONE
            addView(unreadSaid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(seeUnread)
        }
        column.addView(unreadLine)
        // On the profile's page, after the counts, the drafts and the unread: a rule, then oneself.
        meRule = View(this).apply { setBackgroundColor(Look.MUTED) ; visibility = View.GONE }
        column.addView(meRule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { val side = Look.dp(this@MainActivity, 16) ; setMargins(side, 0, side, Look.dp(this@MainActivity, 4)) })
        column.addView(meBox)
        column.addView(meActions)
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pad = Look.dp(this, 8)
        column.addView(
            Look.button(this, getString(R.string.write)) { Menus.write(this) }.apply { textSize = 16f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(pad, 0, pad, pad) },
        )
        setContentView(column)
        Edges.fit(this)
        shared(intent)
        if (state == null) offerMe()
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
        if (page != Page.PROFILE) return
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
        meActions.removeAllViews()
        val side = Look.dp(this, 16)
        for ((words, then) in MeMenu.actions(this, known) { meChanged() }) {
            meActions.addView(
                Look.words(this, words, size = 16f).apply {
                    setPadding(side, pad, side, pad)
                    setOnClickListener { then() }
                },
            )
        }
    }

    /** Another page: the conversations, or oneself and the counts. */
    private fun go(to: Page) {
        if (to == page) return
        page = to
        // Called by its own name: inside the bar's code, "show()" was the bar's, which shows the bar.
        redraw()
        readMe()
        // What was read is shown at once, and read again only if nothing was; the profile reads its drafts and unread.
        if (page != Page.TALKS || every.isEmpty()) fill()
    }

    /**
     * The pages, from the bar's dots: each after its mark -- the application's own for the conversations, said
     * more strongly as the main page --, the one shown in orange.
     */
    private fun pages() {
        var shown: AlertDialog? = null
        val column = Look.column(this)
        val pad = Look.dp(this, 12)
        val side = Look.dp(this, 28)
        for (one in Page.entries) {
            column.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad * 2, pad, pad * 2, pad)
                    addView(
                        if (one == Page.TALKS) android.widget.ImageView(context).apply { setImageResource(R.drawable.launcher) }
                        else Look.words(context, glyph(one), size = 20f).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(side, side),
                    )
                    addView(
                        Look.words(context, named(one, counted = true), size = 17f).apply {
                            setPadding(pad, 0, 0, 0)
                            if (one == Page.TALKS) setTypeface(typeface, Typeface.BOLD)
                            if (one == page) setTextColor(Look.ORANGE)
                        },
                    )
                    setOnClickListener { shown?.dismiss() ; go(one) }
                },
            )
        }
        shown = AlertDialog.Builder(this).setView(column).show()
    }

    /**
     * Oneself not said yet: asked once a start, with a way to put it off. Only once foomms is the messaging
     * application and may read the contacts -- what is asked first, and what the asking needs.
     */
    private fun offerMe() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        if (android.provider.Telephony.Sms.getDefaultSmsPackage(this) != packageName) return
        if (Me.card(this) != null) return
        thread {
            val known = runCatching { MeMenu.read(this, mineKey) }.getOrNull() ?: return@thread
            if (known.own != null) return@thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(R.string.me_wizard_title)
                    .setMessage(R.string.me_wizard_text)
                    .setPositiveButton(R.string.me_wizard_choose) { _, _ -> MeMenu.choose(this, known) { meChanged() } }
                    .setNegativeButton(R.string.me_wizard_later, null)
                    .show()
            }
        }
    }

    /** A page's name after its mark, the application's own drawn in the text for the conversations, [side] high. */
    private fun marked(of: Page, side: Int): CharSequence {
        if (of != Page.TALKS) return "${glyph(of)} ${named(of, counted = true)}"
        val mark = getDrawable(R.drawable.launcher)?.apply { setBounds(0, 0, side, side) } ?: return named(of)
        return android.text.SpannableStringBuilder("  ${named(of)}").apply {
            setSpan(android.text.style.ImageSpan(mark, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** The mark of a page but the conversations', whose mark is the application's. */
    private fun glyph(of: Page): String = "👤"

    /** A page's name; the profile's, on the way to it, with how many messages are unread, when some are. */
    private fun named(it: Page, counted: Boolean = false): String = when (it) {
        Page.TALKS -> getString(R.string.page_talks)
        Page.PROFILE -> counts?.unread?.takeIf { counted && it > 0 }?.let { getString(R.string.page_profile_n, it) } ?: getString(R.string.page_profile)
    }

    /** The bar's arrow, and the phone's back, from the profile's page: the conversations. */
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home && page == Page.PROFILE) {
            go(Page.TALKS)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * The phone's back, taken while on the profile's page: from Android 13, through the callback -- with this
     * target, Android 16 no longer calls [onBackPressed] --, before that through it.
     */
    private var backing: Any? = null

    private fun backToTalks(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 33 || on == (backing != null)) return
        val dispatcher = onBackInvokedDispatcher
        if (on) {
            val callback = android.window.OnBackInvokedCallback { go(Page.TALKS) }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backing = callback
        } else {
            dispatcher.unregisterOnBackInvokedCallback(backing as android.window.OnBackInvokedCallback)
            backing = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page == Page.PROFILE) return go(Page.TALKS)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onActivityResult(request: Int, outcome: Int, data: android.content.Intent?) {
        super.onActivityResult(request, outcome, data)
        if (MeMenu.result(this, request, outcome, data)) return meChanged()
        // A saving made or restored, said as it goes; once said done, the conversations and the counts are read again.
        Saving.done(this, request, outcome, data) { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() ; fill() }
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
        // The MMS that could not be fetched, asked for again: opened, the phone is likelier to have the network.
        thread { runCatching { Unfetched.againAll(applicationContext) } }
        // This phone's numbers, from the MMS it has received: once the messages may be read -- on a first start, not yet.
        if (!looked && checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            looked = true
            thread { Me.look(applicationContext) ; runOnUiThread { if (!isFinishing && !isDestroyed) readMe() } }
        }
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

    /**
     * The list read again once the messages stop changing: a conversation
     * read marks its messages one by one, and each change began a reading
     * that the next one dropped, so that nothing was shown for seconds.
     */
    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        private val again = Runnable { if (!isFinishing && !isDestroyed) fill() }
        override fun onChange(self: Boolean) {
            list.removeCallbacks(again)
            list.postDelayed(again, SETTLE_MS)
        }
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
        // The profile shows no conversation: only those its drafts and unread are in, in their windows.
        val profile = page == Page.PROFILE
        if (written != Book.written) { synchronized(people) { people.clear() } ; synchronized(lines) { lines.clear() } ; written = Book.written }
        thread {
            val foodjis = Foodjis.of(this)
            // Oneself by name, when one wrote a group's last message.
            val myName: String by lazy { Me.card(this)?.let { Book.own(this, it) }?.name ?: getString(R.string.me_tag) }
            // Oneself as a small face, last in each group's square: one's contact's picture, else pixels from one's number.
            val myself: Look.Mini by lazy {
                val own = Me.card(this)?.let { Book.own(this, it) }
                val cert = own?.fingerprint?.let { foodjis.card(it) }
                Look.Mini(
                    own?.photo?.let { Media.thumbnail(this, it, Media.FACE) } ?: own?.fingerprint?.let { foodjis.face(it) },
                    cert?.validity, Look.seed(own?.fingerprint, Me.number(this)),
                )
            }
            fun row(talk: Store.Talk, kept: Boolean = true): Row {
                // A line kept is kept but for its badge: what foodjis says of the certificate changes there, unseen here.
                synchronized(lines) { lines[talk.thread] }?.takeIf { kept }?.takeIf { it.talk.at == talk.at && it.talk.unread == talk.unread && it.talk.last == talk.last }?.let { kept ->
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
                val group = others.takeIf { it.size > 1 }
                val with = others.firstOrNull() ?: talk.number
                val person = if (group != null) null else with?.let(::who)
                // The face read here, off the page: drawn where it is shown, a contact's picture held the list still.
                val face = person?.let { Book.face(this, it) } ?: person?.fingerprint?.let { foodjis.face(it) }
                val armor = Pgp.armorIn(talk.last)?.let { Opened.inner(it) }
                val verdict = when {
                    armor == null -> Verdict.RED
                    // Not opened yet: nothing tells what it is worth, signed or not, before the key does.
                    // Received and certified by somebody else than whom the line is with: its own colours.
                    else -> Opened.words(armor)?.let { read ->
                        if (!talk.incoming) read.verdict
                        else Verdict.received(read.verdict, read.signer, listOfNotNull((person ?: talk.from?.takeIf { group != null }?.let(::who))?.fingerprint).mapNotNull { runCatching { Fingerprint.keyId(it) }.getOrNull() })
                    } ?: Verdict.GREY
                }
                val last = when {
                    armor == null -> talk.last
                    else -> Opened.words(armor)?.words ?: getString(if (Pgp.isLocked(armor)) R.string.locked_message else R.string.signed_message)
                }
                val standing = person?.fingerprint?.let { foodjis.card(it)?.validity }
                // A group's line is named by who wrote its last message, beside their face: its members are in its square.
                val speaker = group?.let { talk.from }
                val them = speaker?.let(::who)
                val name = if (group != null) (if (talk.incoming) them?.name ?: speaker ?: getString(R.string.unknown_number) else myName)
                    else person?.name ?: with ?: getString(R.string.unknown_number)
                // A group's members as small faces, and this phone after them -- unless the nine places are taken.
                val crowd = group?.let { members ->
                    members.map { one ->
                        val them = who(one)
                        Look.Mini(
                            them?.let { Book.face(this, it) } ?: them?.fingerprint?.let { foodjis.face(it) },
                            them?.fingerprint?.let { foodjis.card(it)?.validity }, Look.seed(them?.fingerprint, one),
                        )
                    } + myself
                }
                val said = group?.let {
                    if (!talk.incoming) myself
                    else Look.Mini(
                        them?.let { Book.face(this, it) } ?: them?.fingerprint?.let { foodjis.face(it) },
                        them?.fingerprint?.let { foodjis.card(it)?.validity }, Look.seed(them?.fingerprint, speaker),
                    )
                }
                // What is being written to them, held by foomms: in place of the last message.
                val draft = Drafts.inThread(this, talk.thread)
                return Row(talk, person, face, name, verdict, last, standing, group?.let { Phone.group(talk.members, mine) }, with = with, crowd = crowd, speaker = said, draft = draft)
                    // A closed PGP message may be opened at any moment: its line is worked out each time.
                    .also { if (armor == null && kept) synchronized(lines) { lines[talk.thread] = it } }
            }
            // A sending to each, filed as one conversation: a line for each of those it went to, as if in their own
            // conversation, which shows it -- merged with theirs ([merged]), or theirs alone when they have no other.
            fun rowsOf(talk: Store.Talk): List<Row> =
                if (!talk.each) listOf(row(talk))
                else talk.members.filterNot { Me.mine(this, it) }.map { one ->
                    row(Store.Talk(talk.thread, one, talk.at, talk.last, talk.incoming, talk.unread, talk.form), kept = false).apart()
                }
            fun show(found: List<Row>, whole: Boolean = false) = runOnUiThread {
                if (isFinishing || isDestroyed || turn != turns) return@runOnUiThread
                every = found
                everyWhole = whole
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
            if (profile) {
                // The conversations holding a draft, for the window that offers to forget them all ([clearDrafts]).
                val drafted = runCatching { Store.talks(this, Int.MAX_VALUE, given = stale, only = Drafts.threads(this)) }.getOrDefault(emptyList())
                    .flatMap(::rowsOf).sortedByDescending { it.talk.at }
                // And those holding something unread, read for themselves: few among all the conversations.
                val unread = runCatching { Store.talks(this, Int.MAX_VALUE, unreadOnly = true, given = stale) }.getOrDefault(emptyList())
                    .flatMap(::rowsOf).sortedByDescending { it.talk.at }
                runOnUiThread { if (!isFinishing && !isDestroyed && turn == turns) { draftRows = drafted ; unreadRows = unread } }
                if (!stale()) count()
            } else {
                // The latest first, and no more than were shown already: the rest comes a block at a time, as the list is scrolled ([more]).
                rowOf = ::rowsOf
                val asked = maxOf(FIRST, read)
                // The newest few first, over what was shown: a conversation just read from its notification is at the
                // top at once, not after all the others the list had read are read again.
                val quick = runCatching { Store.talks(this, QUICK, given = stale) }.getOrDefault(emptyList())
                if (quick.isNotEmpty() && !stale()) {
                    val fresh = quick.map { it.thread }.toSet()
                    val oldest = quick.last().at
                    val shown = every
                    show(quick.flatMap(::rowsOf) + shown.filter { it.talk.thread !in fresh && it.talk.at < oldest }, whole = everyWhole)
                }
                val talks = runCatching { Store.talks(this, asked, given = stale) }.getOrDefault(emptyList())
                val first = talks.flatMap(::rowsOf)
                read = talks.size
                show(first, whole = talks.size < asked)
                // The groups only the headers of received MMS tell, merged with the rest by time.
                val heard = runCatching { Store.heard(this) }.getOrDefault(emptyList()).map { row(it.talk, kept = false).heard(it.messages) }
                runOnUiThread { if (!isFinishing && !isDestroyed && turn == turns) { heardRows = heard ; redraw() } }
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
                // Those heard of only as far back as what was read: the older come with the next block.
                Page.TALKS -> {
                    val oldest = if (everyWhole) Long.MIN_VALUE else every.lastOrNull()?.talk?.at ?: Long.MAX_VALUE
                    (every + heardRows.filter { it.talk.at >= oldest }).sortedByDescending { it.talk.at }
                }
                Page.PROFILE -> emptyList()
            },
        )
        talks.notifyDataSetChanged()
        // Its own count in the profile's title as well, as on the way to it.
        heading.text = named(page, counted = true)
        others.removeAllViews()
        for (other in Page.entries.filter { it != page }) {
            others.addView(
                // Each after its mark, as in the menu of the pages; the conversations, the main page, said a little
                // more strongly than the two others.
                Look.small(this, marked(other, Look.dp(this, 20))) { go(other) }.apply {
                    textSize = 15f
                    if (other == Page.TALKS) setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        // On the profile's page, the arrow every other page has, back to the conversations, where the gear was.
        actionBar?.setDisplayHomeAsUpEnabled(page == Page.PROFILE)
        backToTalks(page == Page.PROFILE)
        gear.visibility = if (page == Page.PROFILE) View.GONE else View.VISIBLE
        others.visibility = if (page == Page.PROFILE) View.GONE else View.VISIBLE
        meBox.visibility = if (page == Page.PROFILE) View.VISIBLE else View.GONE
        meActions.visibility = meBox.visibility
        meRule.visibility = meBox.visibility
        mark.visibility = if (page == Page.TALKS) View.VISIBLE else View.GONE
        markGlyph.visibility = if (page == Page.TALKS) View.GONE else View.VISIBLE
        markGlyph.text = glyph(page)
        val known = counts
        if (known != null) {
            counted.text = when (page) {
                Page.TALKS -> resources.getQuantityString(R.plurals.talks_n, known.persons, known.persons)
                Page.PROFILE -> me?.number ?: ""
            }
            val weight = java.text.NumberFormat.getIntegerInstance().format(known.bytes)
            detail.text = getString(
                R.string.count_detail, known.all, known.sms, known.mms, known.pgp, known.pgpSms, known.pgpMms, known.talks, weight,
                known.received, known.sent, known.persons, known.groups,
                known.receivedSms, known.receivedMms, known.sentSms, known.sentMms,
            )
            draftsSaid.text = "✎ " + resources.getQuantityString(R.plurals.drafts_n, known.drafts, known.drafts)
            // "Delete" alone for one, which the line beside it names; "Delete drafts" otherwise, greyed for none.
            clearDrafts.text = getString(if (known.drafts == 1) R.string.clear_draft else R.string.clear_drafts)
            seeUnread.text = getString(if (known.unread == 1) R.string.see_unread_one else R.string.see_unread)
            bytesSaid.text = getString(R.string.count_bytes, weight)
            unreadSaid.text = android.text.SpannableStringBuilder("📬 ").append(
                resources.getQuantityString(R.plurals.unread_short, known.unread, known.unread),
                android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            usable(clearDrafts, known.drafts > 0)
            usable(seeUnread, known.unread > 0)
        }
        // The counts, the drafts and the unread on the profile's page.
        detail.visibility = if (page == Page.PROFILE && known != null) View.VISIBLE else View.GONE
        draftsLine.visibility = detail.visibility
        unreadLine.visibility = detail.visibility
        savingLines.visibility = detail.visibility
    }

    private fun usable(button: android.widget.Button, on: Boolean) {
        button.isEnabled = on
        button.alpha = if (on) 1f else 0.38f
    }

    /** The conversations holding a draft, read with the profile's page. */
    private var draftRows: List<Row> = emptyList()

    /**
     * Every draft forgotten, its mark and its words, once the person has said
     * so -- under the conversations that hold one.
     */
    private fun clearDrafts() {
        val many = counts?.drafts ?: 0
        conversations(resources.getQuantityString(R.plurals.clear_drafts_ask, many, many), draftRows, getString(R.string.clear_drafts)) {
            thread {
                Drafts.forgetAll(this)
                runOnUiThread { if (!isFinishing && !isDestroyed) fill() }
            }
        }
    }

    /** The conversations holding something unread, and a way to mark it all read. */
    private fun seeUnread() {
        val many = counts?.unread ?: 0
        val known = counts
        // How many, how many of them PGP, and in how many conversations: the window lists the conversations.
        val said = android.text.SpannableStringBuilder().append(
            resources.getQuantityString(R.plurals.unread_n, many, many),
            android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        ).append(resources.getQuantityString(R.plurals.count_detail_unread_rest, known?.unreadTalks ?: 0, known?.unreadPgp ?: 0, known?.unreadTalks ?: 0))
        conversations(said, unreadRows, getString(R.string.mark_all_read)) {
            thread {
                Keep.readAll(this)
                Notify.seenAll(this)
                runOnUiThread { if (!isFinishing && !isDestroyed) fill() }
            }
        }
    }

    /**
     * A window of conversations, drawn as in the list -- a press goes into
     * one, a long press shows its menu --, under [said], with one act on them
     * all, [named], done by [act].
     */
    private fun conversations(said: CharSequence, shown: List<Row>, named: String, act: () -> Unit) {
        var window: AlertDialog? = null
        val list = ListView(this).apply {
            adapter = lines { shown }
            setOnItemClickListener { _, _, at, _ ->
                window?.dismiss()
                open(shown[at])
            }
            setOnItemLongClickListener { _, _, at, _ ->
                window?.dismiss()
                about(shown[at])
                true
            }
        }
        // Half the screen at most: a long list scrolls inside it, and the buttons stay in sight under it.
        val most = resources.displayMetrics.heightPixels / 2
        val held = android.widget.FrameLayout(this).apply {
            addView(list, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (shown.size > SHORT_LIST) most else ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        window = AlertDialog.Builder(this)
            .setMessage(said)
            .apply { if (shown.isNotEmpty()) setView(held) }
            .setPositiveButton(named) { _, _ -> act() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** How the latest filling makes a line of a conversation: the next blocks are made the same way. */
    @Volatile private var rowOf: ((Store.Talk) -> List<Row>)? = null

    /** How many conversations the list has read: a sending to each makes several lines of one. */
    @Volatile private var read = 0
    private var reading = false

    /**
     * The next block of conversations, read when the list comes near the end
     * of what it holds: the oldest are read only by whoever goes down to them.
     */
    private fun more() {
        if (page != Page.TALKS || everyWhole || reading) return
        val make = rowOf ?: return
        val turn = turns
        val from = read
        reading = true
        thread {
            val talks = runCatching { Store.talks(this, BLOCK, from = from, given = { turn != turns }) }.getOrDefault(emptyList())
            val next = talks.flatMap(make)
            runOnUiThread {
                reading = false
                if (isFinishing || isDestroyed || turn != turns || read != from) return@runOnUiThread
                every = every + next
                read = from + talks.size
                everyWhole = talks.size < BLOCK
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
        // A group's menu, over the list: its members read first, away from the screen.
        if (row.talk.group || row.group != null) {
            val set = row.group ?: Phone.group(row.talk.members, Me.all(this))
            thread {
                val members = runCatching { GroupMenu.read(this, row.talk.members) }.getOrDefault(emptyList())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    GroupMenu.show(
                        this, members, set, row.threads, GroupNames.of(this, set) ?: members.joinToString(", ") { it.name },
                        changed = { fill() }, gone = { fill() },
                        // Tying is done from the member's conversation, where foodjis shows its list over.
                        tie = { number -> startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number).putExtra(ThreadActivity.EXTRA_ASK, ThreadActivity.ASK_TIE)) },
                    )
                }
            }
            return
        }
        // Whom it is with, as the line opens it: Android files some conversations between two under no number.
        val sender = row.with ?: row.talk.number ?: return
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

    /** Into a line's conversation, as the list goes into it. */
    private fun open(row: Row) = open(row.talk, threads = row.threads, with = row.with ?: row.talk.number, messages = row.messages)

    private fun open(talk: Store.Talk, ask: String? = null, threads: List<Long> = listOf(talk.thread), with: String? = talk.number, messages: List<Long> = emptyList()) = startActivity(
        Intent(this, ThreadActivity::class.java)
            .putExtra(ThreadActivity.EXTRA_MESSAGES, messages.toLongArray())
            .putExtra(ThreadActivity.EXTRA_MEMBERS, talk.members.toTypedArray())
            .putExtra(ThreadActivity.EXTRA_NUMBER, with ?: getString(R.string.unknown_number))
            // None for one who has only received a sending to each: their own conversation, by their number.
            .putExtra(ThreadActivity.EXTRA_THREAD, threads.firstOrNull() ?: -1L)
            .putExtra(ThreadActivity.EXTRA_THREADS, threads.toLongArray())
            .putExtra(ThreadActivity.EXTRA_ASK, ask),
    )

    private val talks = lines { rows }

    /** Conversations drawn as the list draws them, from [of]: the list's own, or the drafts' in their window. */
    private fun lines(of: () -> List<Row>) = object : BaseAdapter() {
        override fun getCount() = of().size
        override fun getItem(at: Int) = of()[at]
        override fun getItemId(at: Int) = of()[at].talk.thread

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val row = of()[at]
            val context = this@MainActivity
            val pad = Look.dp(context, 12)
            val names = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            // A group's: who wrote last, their face before their name.
            row.speaker?.let { one ->
                names.addView(
                    Look.face(context, 18, null, one.face, one.standing, framable = true, seed = one.seed, lineDp = 1),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Look.dp(context, 6) },
                )
            }
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
                // On the groups' page, a group the person named: its name first, a line of its own above who spoke.
                val called = row.group?.let { GroupNames.of(context, it) }
                if (called != null) {
                    addView(
                        Look.words(context, called, size = 17f).apply {
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 1 ; ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                }
                addView(names)
                addView(
                    Look.words(context, (row.draft?.let { getString(R.string.draft_said, it) } ?: row.last).replace('\n', ' '), size = 14f, muted = !row.unread).apply {
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
                addView(row.crowd?.let { Look.crowd(context, CROWD, it) } ?: Look.face(context, 44, null, row.face, row.standing, framable = true, seed = row.seed))
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

        /** The newest read first, alone, before the rest is read again ([fill]). */
        const val QUICK = 6

        /** As many conversations as a window shows whole; more, and it scrolls within half the screen. */
        const val SHORT_LIST = 4

        /** How long the messages are to stay still before the list is read again. */
        const val SETTLE_MS = 400L
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
