package org.foopgp.foomms

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import kotlin.concurrent.thread
import org.openintents.openpgp.OpenPgpSignatureResult

/**
 * One conversation: the messages in bubbles, each in the colour of what it
 * is worth, and at the bottom a field to write in.
 *
 * A PGP message shows its words once foodjis has opened it -- pressed, for
 * one received -- and until then says what it is. Above the field, two
 * switches say how the next message will leave: signed, encrypted, both,
 * or as it is.
 */
class ThreadActivity : AskingActivity() {

    private enum class Mode(val glyph: String, val locks: Boolean, val signs: Boolean) {
        SIGN_AND_LOCK("✍⚿", true, true),
        LOCK("⚿", true, false),
        SIGN("✍", false, true),
        PLAIN("✉", false, false) ;

        companion object {
            fun of(signs: Boolean, locks: Boolean): Mode = entries.first { it.signs == signs && it.locks == locks }
        }
    }

    /**
     * What this page was told by foomms itself: its own intent; asked from outside -- "write to this number" from
     * any application --, none. The page shows a conversation's history and sends to a number: another
     * application naming one person's conversation and another's number was showing the first and sending to the
     * second.
     */
    private val inner: Intent by lazy {
        if (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW) Intent() else intent
    }

    private val number by lazy { inner.getStringExtra(EXTRA_NUMBER) ?: intent.data?.schemeSpecificPart.orEmpty() }
    private val foodjis by lazy { Foodjis.of(this) }

    private var person: Book.Person? = null
    private var card: Foodjis.Cert? = null
    private var face: Bitmap? = null
    private var lines: List<Store.Line> = emptyList()
    /** How the next message leaves: what the two switches say, as far as each can be on. */
    private val mode: Mode get() = Mode.of(signs.isChecked && signs.isEnabled, locks.isChecked && locks.isEnabled)

    /** Who a key a message names belongs to, as far as foodjis knows. */
    private class For(val name: String, val face: Bitmap?, val validity: String? = null, val fingerprint: String? = null)

    private val meant: MutableMap<Long, For?> = java.util.Collections.synchronizedMap(HashMap())

    /** Whom each locked part of an MMS is for, by the keys it names, by the part it is. */
    private val sealedFor = java.util.concurrent.ConcurrentHashMap<Long, List<Long>>()

    /** Whether each PGP part is locked, or only signed; not read yet, it is taken to be locked. */
    private val pieceLocked = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private fun isLockedPiece(piece: Store.Piece): Boolean = isSealed(piece) && pieceLocked[piece.id] != false

    /** The message whose text can be selected, and so copied: the one pressed long, or none. */

    private lateinit var list: ListView
    private lateinit var field: EditText
    private lateinit var signs: Flip
    private lateinit var locks: Flip
    private lateinit var options: LinearLayout
    private lateinit var sender: android.widget.Button
    /** The button that attaches: greyed with the field when the sender cannot be answered. */
    private lateinit var adding: android.widget.Button
    private lateinit var chip: android.widget.TextView

    /** [chip] between its paper clip and the cross that alone takes the attachment off. */
    private lateinit var carriedRow: Look.Carried

    /** The message being answered, cited above the field until it leaves or is let go. */
    private var replying: Store.Line? = null
    private lateinit var replyRow: LinearLayout
    private lateinit var replyCited: FrameLayout
    private val chipRow get() = carriedRow.row
    private lateinit var whoIs: android.widget.TextView
    private lateinit var under: android.widget.TextView
    private lateinit var portrait: android.widget.FrameLayout

    /** The conversation in the phone's store, once it is known to have one. */
    private var thread: Long? = null

    /** This conversation, as what is opened in it is counted down: none yet, the conversation of nobody. */
    private fun here(): Long = thread ?: Opened.NO_THREAD

    /** Every conversation the page shows lines of -- a group's several, a sending to each filed elsewhere --: closed together. */
    private fun shownHere(): Set<Long> = lines.map { it.thread }.toSet() + here()

    /**
     * Everybody the conversation is with, this phone included when it is a
     * group's: the numbers Android names its conversation by. Read at once,
     * the drafts being kept under them.
     */
    private val everyone: List<String> by lazy {
        val all = ArrayList<String>()
        // A group heard only from the headers of MMS filed elsewhere: its members, as the list read them.
        val heard = if (elsewhere.isEmpty()) emptyList() else inner.getStringArrayExtra(EXTRA_MEMBERS)?.toList().orEmpty()
        for (one in given.flatMap { runCatching { Store.members(this, it) }.getOrDefault(emptyList()) } + heard) {
            if (all.none { Phone.same(it, one) || it == one }) all += one
        }
        all.ifEmpty { listOf(number) }
    }

    /** A group's MMS filed in their senders' conversations by another application, by number: shown with its own. */
    private val elsewhere: List<Long> by lazy { inner.getLongArrayExtra(EXTRA_MESSAGES)?.toList().orEmpty() }

    /**
     * The conversations the page was opened on: one, or those Android keeps
     * apart for the same group -- with this phone among the numbers, or
     * without --, shown as one.
     */
    private val given: List<Long> by lazy {
        inner.getLongArrayExtra(EXTRA_THREADS)?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(inner.getLongExtra(EXTRA_THREAD, -1L).takeIf { it >= 0 })
    }

    /** A conversation with several numbers: a group MMS's. */
    private val group get() = everyone.count { !Me.mine(this, it) } > 1

    /** One of a group but this phone, and who they are. */
    class Member(val number: String, val person: Book.Person?, val card: Foodjis.Cert?, val face: Bitmap?) {
        val name: String get() = person?.name ?: number
        val seed: String? get() = Look.seed(card?.fingerprint ?: person?.fingerprint, number)
    }

    /** The bar's first line: a name and its dots, or a group's faces and its dots. */
    private lateinit var named: View
    private lateinit var crowd: LinearLayout

    /** A group's members but this phone, once known; none in a conversation between two. */
    @Volatile private var members: List<Member> = emptyList()

    /** The primary keys of those this conversation is with: the certificate tied to its number, or its members'. */
    private fun whose(): Set<Long> =
        (listOfNotNull(person?.fingerprint) + members.mapNotNull { it.card?.fingerprint ?: it.person?.fingerprint })
            .mapNotNull { runCatching { Fingerprint.keyId(it) }.getOrNull() }.toSet()

    /** A group's drafts, under its members but this phone: the same, from whichever of its conversations. */
    private val drafted: List<String> by lazy {
        if (!group) listOf(number) else everyone.filterNot { Me.mine(this, it) }
    }

    /**
     * Whom what is written goes to: one number, or every member of the group
     * and this phone, which gets it back -- what a group is, is then always
     * the list of whom its messages go to.
     */
    private fun targets(): List<String> = if (group) members.map { it.number } + listOfNotNull(Me.number(this)) else listOf(number)

    /** The member a message came from, as the network wrote their number. */
    private fun memberOf(address: String?): Member? = address?.let { from -> members.firstOrNull { Phone.same(it.number, from) || it.number == from } }

    /** In a group, the members already asked whether a signer's certificate is theirs: once each, a page. */
    private val offeredTo = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Who sent each MMS received in a group: read after the page is shown, an asking each ([Store.addressOf]). */
    private val senders = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private val voice by lazy { Voice(this) }

    /** The field held down while empty: record, if the microphone may be used, until it is let go. */
    private fun speak(): Boolean {
        // A voice leaves as an MMS: not with MMS off.
        if (!Conf.mms(this)) { say(getString(R.string.mms_off)) ; return true }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), VOICE)
            return true
        }
        // A call holds the microphone: what would be recorded is silence.
        if (Voice.inCall(this)) { say(getString(R.string.voice_in_call)) ; return true }
        if (!voice.start(Outbox.heaviest(this) - Outbox.MARGIN)) {
            say(getString(R.string.voice_failed))
            return true
        }
        field.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        chipRow.visibility = View.VISIBLE
        carriedRow.attached(false)
        // The time it has been recording, said each second for as long as it records; and whether the microphone hears.
        val tick = object : Runnable {
            override fun run() {
                if (!voice.recording) return
                val mute = voice.listen()
                chip.text = getString(if (mute) R.string.voice_recording_mute else R.string.voice_recording, Media.clock(voice.millis))
                chip.postDelayed(this, 250)
            }
        }
        tick.run()
        return true
    }

    /** Let go: what was said is attached to the next message, to send or to take off as any attachment. */
    private fun spoken() {
        val said = voice.stop()
        field.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        // Silence from start to end: the microphone was elsewhere; nothing to send.
        if (said != null && voice.silent) { carry(null) ; return say(getString(R.string.voice_silent)) }
        carry(said)
        if (said == null) Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
        else if (voice.partlySilent) say(getString(R.string.voice_partly_silent))
    }

    /** What the next message carries beside its words: a picture, a sound. */
    private var carried: Media.Attachment? = null

    /** The pictures of this conversation, small, by the part they are. */
    private val thumbs = java.util.concurrent.ConcurrentHashMap<Long, Bitmap>()

    /** How long each sound of this conversation lasts, by the part it is, as it came or as it was opened. */
    private val lasts = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** the first picture of each part that is a video, by part; a sound alone has none. */
    private val frames = java.util.concurrent.ConcurrentHashMap<Long, Bitmap>()

    /** The parts already looked at for a picture, with one or not: a map of pictures holds no "none". */
    private val framed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** The message to show first, when the page was opened from a search: its date, taken once. */
    private var wanted = 0L

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        dressed = Look.dark
        super.onCreate(state)
        if (number.isBlank()) return finish()
        title = number
        wanted = inner.getLongExtra(EXTRA_AT, 0L)
        // In the bar: who the conversation is with -- a face, a name, and under it the number and what its
        // certificate is worth. Pressed, what can be done about them, as from the list of conversations.
        whoIs = Look.words(this, number, size = 17f).apply { setTextColor(Look.ORANGE) ; setTypeface(typeface, android.graphics.Typeface.BOLD) ; maxLines = 1 }
        // One line, smaller when the language's word for the certificate's standing is long: the badge fell off in Greek.
        under = Look.words(this, "", size = 13f).apply {
            setTextColor(android.graphics.Color.rgb(0xE0, 0xE7, 0xFF))
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        portrait = android.widget.FrameLayout(this)
        actionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            setDisplayHomeAsUpEnabled(true)
            customView = LinearLayout(this@ThreadActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // As large as the face it holds, its frame included: never cut.
                addView(portrait, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 10), 0, 0, 0)
                        // The dots that say the bar can be pressed, on its first line. A group's: its members' faces.
                        named = Look.pressable(context, whoIs)
                        addView(named, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        crowd = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL ; gravity = Gravity.CENTER_VERTICAL ; visibility = View.GONE }
                        addView(crowd, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        addView(under, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                setOnClickListener {
                    // A group's: its members.
                    if (group) return@setOnClickListener groupMenu()
                    // Taken now: what changed forgets who the number is with before the refresh is done.
                    val tied = person?.fingerprint
                    TalkMenu.show(
                        this@ThreadActivity, person?.name ?: number, number, person, thread,
                        tie = { tie() }, refresh = { tied?.let { refreshed(it) } },
                        changed = { person = null ; card = null ; load(first = true) }, gone = { finish() },
                        save = { Saving.save(this@ThreadActivity, thread, person?.name ?: number) },
                        // Only while foodjis keeps its door shut: once let in, there is nothing to ask.
                        letIn = if (foodjis.shut) ({ letIn() }) else null,
                    )
                }
                addView(Look.icon(context, R.drawable.search, getString(R.string.search_here)) { Searching.ask(this@ThreadActivity) { seeking(true) } })
            }
            customView.layoutParams = android.app.ActionBar.LayoutParams(android.app.ActionBar.LayoutParams.MATCH_PARENT, android.app.ActionBar.LayoutParams.WRAP_CONTENT)
        }
        list = Swiped(this) { at -> lines.getOrNull(at)?.let(::reply) }.apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = true
            adapter = bubbles
        }
        field = EditText(this).apply {
            // Empty, the field also listens: held down, it records what is said, for as long as it is held.
            setHint(R.string.words_or_voice)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            setOnLongClickListener { if (text.isEmpty()) speak() else false }
            setOnTouchListener { _, touch ->
                if (voice.recording && (touch.action == android.view.MotionEvent.ACTION_UP || touch.action == android.view.MotionEvent.ACTION_CANCEL)) {
                    spoken()
                    true
                } else {
                    false
                }
            }
        }
        // Two switches above the field: signed, encrypted. Each is grey when it cannot be; together they
        // give the line, and the button that sends, the colour the message will have.
        signs = Flip(this, "✍", R.string.switch_sign) { paint() }
        locks = Flip(this, "⚿", R.string.switch_lock) { paint() }
        sender = Look.button(this, "➤") { send() }.apply { textSize = 20f }
        val send = sender
        val pad = Look.dp(this, 6)
        adding = Look.button(this, "+") { attach() }.apply { textSize = 22f }
        val add = adding
        // What is attached, said above the field; only its cross takes it off.
        carriedRow = Look.carried(this) { carry(null) }
        // The message answered, under the switches: its sign, it cited as in the bubble, and the cross that lets it go.
        replyCited = FrameLayout(this)
        replyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(pad * 3, pad / 2, 0, pad / 2)
            addView(Look.words(context, "↩", size = 22f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            addView(replyCited, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad * 2 })
            addView(
                Look.words(context, "✕", size = 22f).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.rgb(0xE5, 0x39, 0x35))
                    setPadding(pad * 4, 0, pad * 4, 0)
                    contentDescription = getString(R.string.reply_drop)
                    setOnClickListener { reply(null) }
                },
            )
        }
        chip = carriedRow.words.apply { typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            // Aligned on their baselines, the two buttons would follow the field's first line up as it grows.
            isBaselineAligned = false
            setPadding(pad, pad, pad, pad)
            // Attaching at the bottom left, sending at the bottom right.
            addView(add, LinearLayout.LayoutParams(Look.dp(context, 48), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad ; marginEnd = pad })
            addView(send, LinearLayout.LayoutParams(Look.dp(context, 56), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(closing)
                addView(seeker)
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                options = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad * 3, pad / 2, pad * 3, pad / 2)
                    addView(signs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad * 3 })
                    addView(locks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                writing = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(eachBanner)
                    addView(options)
                    addView(chipRow)
                    addView(replyRow)
                    addView(bar)
                }
                addView(writing)
            },
        )
        Edges.fit(this)
        // Words handed over with the page -- shared from another application, or a link's "sms_body" -- are already written;
        // otherwise what was being written here and not sent, the page having been closed meanwhile.
        val handed = if (state == null) intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") else null
        draftRead = Drafts.of(this, drafted)
        (handed ?: draftRead.takeIf { it.isNotEmpty() })?.let { field.setText(it) ; field.setSelection(it.length) }
        given.firstOrNull()?.let(::offerEach)
        // And what was shared with them: a picture made small enough for an MMS, any other file if it fits.
        @Suppress("DEPRECATION")
        if (state == null) intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.takeUnless(Outbox::path)?.let { shared ->
            val type = intent.type ?: contentResolver.getType(shared).orEmpty()
            attached(if (type.startsWith("image/")) PICTURE else FILE, shared)
        }
    }

    /** Shown, and so read by the person: a conversation open behind another screen reads nothing. */
    private var shown = false

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        // Once the messages stop changing: opening several marks each read, and each change read the page again.
        private val again = Runnable { if (shown && !isFinishing && !isDestroyed) load() }
        override fun onChange(self: Boolean) {
            closing.removeCallbacks(again)
            closing.postDelayed(again, SETTLE_MS)
        }
    }

    private var dressed = false

    /** Under the bar while encrypted messages are open: when they close, and a button to close them now. */
    private val closing by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = Look.dp(context, 12)
            setPadding(pad, pad / 3, pad, pad / 3)
            setBackgroundColor(Look.pale(Verdict.YELLOW))
            visibility = View.GONE
            addView(remaining, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Look.small(context, getString(R.string.close_now)) { shownHere().forEach(Opened::close) ; tick() })
        }
    }
    private val remaining by lazy { Look.words(this, "", size = 14f) }

    /** Each second while the page is shown: the time left, and what was open shown closed once it is up. */
    private val ticking = object : Runnable {
        override fun run() {
            tick()
            if (shown) closing.postDelayed(this, 1000)
        }
    }

    private var wasOpen = false

    private fun tick() {
        val closes = shownHere().mapNotNull { Opened.closesAt(it) }.maxOrNull()
        if (closes != null) remaining.text = getString(R.string.closes_in, Media.clock(maxOf(0L, closes - System.currentTimeMillis() + 999)))
        closing.visibility = if (closes != null) View.VISIBLE else View.GONE
        // Closed now: the bubbles show it, and the pictures opened from locked parts go with the rest.
        if (closes == null && wasOpen) {
            openedThumbs.clear()
            // A voice message opened from its lock and still playing beyond the page stops with the rest.
            val locked = lines.flatMap { it.pieces }.filter(::isSealed).map { it.id }.toSet()
            if (Listening.playing in locked) Listening.stop(this)
            // a video opened from its lock leaves no picture of itself, nor its length.
            for (id in locked) { frames.remove(id) ; framed.remove(id) ; lasts.remove(id) }
            if (!Opened.anyOpen()) Outbox.shut(this)
            bubbles.notifyDataSetChanged()
        }
        wasOpen = closes != null
    }

    /** Under the bar when asked: words looked for in this conversation, found one after the other. */
    private val seeker by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = Look.dp(context, 8)
            setPadding(pad, 0, pad, 0)
            visibility = View.GONE
            addView(sought, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(hitCount)
            addView(Look.small(context, "▲") { seek(-1) })
            addView(Look.small(context, "▼") { seek(1) })
            addView(Look.small(context, "✕") { seeking(false) })
        }
    }
    private val sought by lazy {
        EditText(this).apply {
            setHint(R.string.search_hint)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> seek(-1) ; true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { hits = null ; hit = -1 ; seek(0) }
            })
        }
    }
    private val hitCount by lazy { Look.words(this, "", size = 13f, muted = true).apply { setPadding(Look.dp(context, 6), 0, Look.dp(context, 6), 0) } }

    /** What is looked for, while the search is shown: as typed, spaces and punctuation included -- "je " is not "je". */
    private val looked: String get() = if (seeker.visibility == View.VISIBLE) sought.text.toString().takeIf { it.isNotBlank() }.orEmpty() else ""

    /**
     * Each place it is found, oldest first: a message and where in its words,
     * or -1 for a part's name; and the one shown. Counted as marked, one by
     * one, not message by message.
     */
    private class Hit(val line: Int, val at: Int)
    private var hits: List<Hit>? = null
    private var hit = -1

    private fun seeking(on: Boolean) {
        seeker.visibility = if (on) View.VISIBLE else View.GONE
        // Kept at the bottom, the list went back there each time the marks were drawn again, undoing the step to a match.
        list.transcriptMode = if (on) ListView.TRANSCRIPT_MODE_DISABLED else ListView.TRANSCRIPT_MODE_NORMAL
        val keys = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (on) { sought.requestFocus() ; keys?.showSoftInput(sought, 0) } else { keys?.hideSoftInputFromWindow(sought.windowToken, 0) ; field.requestFocus() }
        hits = null ; hit = -1
        bubbles.notifyDataSetChanged()
    }

    /** Where [wanted] is in [text], whatever its case. */
    private fun places(text: CharSequence, wanted: String): List<Int> {
        val found = ArrayList<Int>()
        var from = text.indexOf(wanted, ignoreCase = true)
        while (from >= 0) {
            found += from
            from = text.indexOf(wanted, from + wanted.length, ignoreCase = true)
        }
        return found
    }

    /** What a message shows that can be looked for: each place in its words when they can be read, and each part whose name holds it. */
    private fun hitsIn(index: Int, wanted: String): List<Hit> {
        val line = lines[index]
        val armor = armorOf(line)
        val words = if (armor == null) line.body else Opened.words(armor)?.words.orEmpty()
        val named = line.pieces.count { (Opened.piece(it.id)?.name ?: it.name.orEmpty()).contains(wanted, ignoreCase = true) }
        return places(words, wanted).map { Hit(index, it) } + List(named) { Hit(index, -1) }
    }

    /** The next match up (-1) or down (1), round from one end to the other, or the latest one (0); the list scrolls to it. */
    private fun seek(way: Int) {
        val wanted = looked
        if (wanted.isEmpty()) { hitCount.text = "" ; bubbles.notifyDataSetChanged() ; return }
        val all = hits ?: lines.indices.flatMap { hitsIn(it, wanted) }.also { hits = it }
        if (all.isEmpty()) { hitCount.text = "0" ; bubbles.notifyDataSetChanged() ; return }
        hit = when {
            hit < 0 || way == 0 -> all.size - 1
            else -> Math.floorMod(hit + way, all.size)
        }
        hitCount.text = "${hit + 1}/${all.size}"
        bubbles.notifyDataSetChanged()
        list.setSelectionFromTop(all[hit].line, list.height / 3)
    }

    /** [text], the words of message [index], with what is looked for marked wherever it is: the place shown fully, the others faintly. */
    private fun marked(text: CharSequence, index: Int): CharSequence {
        val wanted = looked
        if (wanted.isEmpty()) return text
        val out = android.text.SpannableString(text)
        val shown = hits?.getOrNull(hit)
        val faint = (Look.ORANGE and 0x00FFFFFF) or 0x66000000
        for (from in places(text, wanted)) {
            val colour = if (shown != null && shown.line == index && shown.at == from) Look.ORANGE else faint
            out.setSpan(android.text.style.BackgroundColorSpan(colour), from, from + wanted.length, 0)
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        if (Look.wantsDark(this) != dressed) return recreate()
        Look.dress(this)
        shown = true
        // A long voice message starting or ending beyond the page: its button says so.
        Listening.onChange = { runOnUiThread { if (!isFinishing && !isDestroyed) bubbles.notifyDataSetChanged() } }
        foodjis.fresh()
        whoAmI()
        contentResolver.registerContentObserver(MESSAGES, true, arrivals)
        load(first = card == null && person == null)
        closing.removeCallbacks(ticking)
        ticking.run()
    }

    /**
     * [line] answered, or none: cited above the field -- its time and some of its words -- until the
     * answer leaves or the cross lets it go; kept with the draft. The field is offered at once.
     */
    private fun reply(line: Store.Line?) {
        replying = line
        replyRow.visibility = if (line == null) View.GONE else View.VISIBLE
        showReply()
        Replies.keep(this, drafted, line?.key)
        if (line == null) return
        field.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(field, 0)
    }

    /** The answer's citation as it would leave now: what was locked hides its words as soon as Encrypt is off. */
    private fun showReply() {
        replyCited.removeAllViews()
        val cited = replying?.let { Quote.split(quoteOf(it, mode.locks))?.first } ?: return
        replyCited.addView(Look.cited(this, cited), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * The first line of an answer to [line] ([Quote]): when it was sent -- by its sender's clock, when the network
     * says it --, and its words; a part's type, name and size when it has none. What is still closed is cited by
     * its time alone; so is what travelled locked when the answer leaves in clear ([locks] false): it would leak.
     */
    private fun quoteOf(line: Store.Line, locks: Boolean): String {
        val at = if (line.incoming && line.sentAt > 0) line.sentAt else line.at
        val armor = armorOf(line)
        val read = armor?.let { Opened.words(it) }
        val shown = when {
            armor == null -> line.body
            read == null || (read.verdict.encrypted && !locks) -> Quote.LOCKED
            else -> read.words
        }
        if (Quote.excerpt(shown).isNotEmpty() || armor != null) return Quote.line(at, shown)
        val pieces = line.pieces.filterNot { Members.isPart(it.name) }
        val first = pieces.firstOrNull() ?: return Quote.line(at, "")
        val opened = Opened.piece(first.id)
        val told = when {
            opened != null -> if (opened.verdict.encrypted && !locks) Quote.LOCKED else "${opened.type} ${opened.name} ${opened.bytes.size}"
            isSealed(first) -> Quote.LOCKED
            else -> "${first.type} ${first.name.orEmpty()} ${runCatching { contentResolver.openFileDescriptor(first.uri, "r")?.use { it.statSize } }.getOrNull() ?: ""}"
        }
        return Quote.line(at, told?.let { if (pieces.size > 1 && it != Quote.LOCKED) "$it +${pieces.size - 1}" else it }.orEmpty())
    }

    /** A cited message pressed: the conversation goes to it, if it is here. */
    private fun goToCited(cited: Quote.Cited, replyAt: Long) {
        val ats = lines.map { if (it.incoming && it.sentAt > 0) it.sentAt else it.at }
        val found = Quote.find(cited, replyAt, ats) { at -> lines.getOrNull(at)?.let(::textOf) }
            ?: return Toast.makeText(this, R.string.quote_gone, Toast.LENGTH_SHORT).show()
        list.setSelection(found)
    }

    /** The draft as it was read when the page opened: kept again only once it has changed. */
    private var draftRead = ""

    /** How what is written here is to leave: to the group, or to the one person. */
    private val draftHow get() = if (group) Drafts.How.GROUP else Drafts.How.ONE

    /** What is written and not sent, kept -- in the background: the phone's store is written --, when it changed. */
    private fun keepDraft() {
        val words = field.text.toString()
        if (words == draftRead) return
        draftRead = words
        val at = given.firstOrNull() ?: thread
        val numbers = drafted
        val how = draftHow
        val context = applicationContext
        kotlin.concurrent.thread { Drafts.keep(context, numbers, words, how, at) }
    }

    /** Sent: the draft is no more. */
    private fun forgetDraft() {
        draftRead = ""
        val at = given.firstOrNull() ?: thread
        val numbers = drafted
        val how = draftHow
        val context = applicationContext
        kotlin.concurrent.thread { Drafts.keep(context, numbers, "", how, at) }
        eachBanner.visibility = View.GONE
    }

    /** Above the field, when the draft here is a sending to each's: the way back to it, to all of them. */
    private val eachBanner by lazy {
        Look.small(this, "") {}.apply { visibility = View.GONE }
    }

    private fun offerEach(thread: Long) {
        if (group) return
        val those = Drafts.eachOf(this, thread) ?: return
        eachBanner.text = getString(R.string.draft_each_resume, those.size)
        eachBanner.setOnClickListener {
            startActivity(Intent(this, ManyActivity::class.java).putExtra(ManyActivity.EXTRA_NUMBERS, those.toTypedArray()))
            finish()
        }
        eachBanner.visibility = View.VISIBLE
    }

    override fun onPause() {
        shown = false
        keepDraft()
        Listening.onChange = null
        playingPiece = null
        if (voice.recording) voice.drop()
        player?.let { runCatching { it.stop() ; it.release() } }
        player = null
        contentResolver.unregisterContentObserver(arrivals)
        super.onPause()
    }

    /** The readings of the page begun: only the latest's results are shown, an older one finishing late put back what was. */
    @Volatile private var loads = 0

    /**
     * Who this is, what foodjis says of their certificate, and the messages.
     *
     * The conversation read and shown as it comes: its messages at once,
     * then -- each found side by side -- who it is with, the pictures, the
     * lengths, whom locked parts are for, and the faces of those named, the
     * page drawn again as each arrives. Something moves at once; the page
     * fills before the eyes.
     */
    private fun load(first: Boolean = false) {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val turn = ++loads
        val latest = { turn == loads }
        thread {
            // Who the number is, beside the messages: its head drawn as soon as known. A group's, each member.
            if (group) thread {
                val found = GroupMenu.read(this, everyone)
                // And oneself, a member too: one's face, and whether one's own contact is tied to a certificate that can be locked for.
                val mineCert = Me.card(this)?.let { Book.own(this, it) }?.fingerprint?.let { foodjis.card(it) }
                val myself = Me.mini(this, foodjis)
                runOnUiThread { if (!isFinishing && !isDestroyed && latest()) groupHead(found, first, mineCert?.usable == true, myself) }
            } else thread {
                val who = if (contacts) Book.who(this, number) else null
                val whose = who?.fingerprint?.let { foodjis.card(it) }
                val theirs = who?.let { Book.face(this, it) } ?: who?.fingerprint?.let { foodjis.face(it) }
                runOnUiThread { if (!isFinishing && !isDestroyed && latest()) head(who, whose, theirs, first) }
            }
            // The conversation it was opened from, when it was opened from one: a sender that is a name and not a
            // number -- a bank, a shop -- has a conversation all the same.
            // A group heard only from MMS filed in their senders' conversations has none of its own yet: not its sender's.
            val thread: Long? = given.firstOrNull() ?: if (elsewhere.isNotEmpty()) null else runCatching { Store.thread(this, number) }.getOrNull()
            // A group's conversations, one after the other in time.
            // With one person: what another application sent them as one of several, filed elsewhere, shown here too.
            // A group's: its MMS filed in their senders' conversations.
            val elsewhere = if (group) Store.linesOf(this, this.elsewhere) else Store.sentToEach(this, number)
            // A group's: its MMS. An SMS there can only be a copy of a sending to each, shown in the conversation of whom it went to.
            val found = (given.ifEmpty { listOfNotNull(thread) }).flatMap { runCatching { Store.lines(this, it) }.getOrDefault(emptyList()) }
                .let { lines -> if (group) lines.filter { it.mms } else lines }
                .let { if (given.size > 1 || elsewhere.isNotEmpty()) (it + elsewhere).sortedBy { line -> line.at } else it }
            // The messages shown at once; signed ones verified from now.
            runOnUiThread {
                if (isFinishing || isDestroyed || !latest()) return@runOnUiThread
                lines = found
                // An answer begun here before, still to send: cited again.
                if (replying == null) Replies.of(this, drafted)?.let { key -> found.firstOrNull { it.key == key }?.let(::reply) }
                hits = null
                this.thread = thread
                if (given.isEmpty()) thread?.let(::offerEach)
                bubbles.notifyDataSetChanged()
                verifySigned()
                if (wanted > 0) {
                    found.indexOfFirst { it.at == wanted }.takeIf { it >= 0 }?.let { list.setSelection(it) }
                    wanted = 0
                }
            }
            if (shown && latest()) {
                // Read, but for what is still locked, which waits to be opened, and what the person made unread again --
                // and no later than what was read here: one arrived meanwhile has not been seen.
                for (one in given.ifEmpty { listOfNotNull(thread) }) {
                    val upTo = found.filter { it.thread == one }.maxOfOrNull { it.at } ?: continue
                    Keep.read(this, one, found.filter { line -> line.thread == one && (lockedAndClosed(line) || line.key in keptUnread) }, upTo)
                }
                Notify.seen(this, number)
            }
            // A group's: who sent each MMS, for its face beside it. One sent from this phone and come back -- kept before
            // copies delivered one recipient at a time were known for such --: gone, being there already as sent.
            if (group) thread {
                var gone = false
                for (line in found.filter { it.incoming && it.mms && it.address == null && !senders.containsKey(it.id) }) {
                    val from = Store.addressOf(this, line) ?: continue
                    if (Me.mine(this, from) && line.pieces.any { Members.isPart(it.name) }) { gone = Keep.forget(this, line) || gone ; continue }
                    senders[line.id] = from
                    drip()
                }
                if (gone) runOnUiThread { if (!isFinishing && !isDestroyed) load() }
            }
            val pieces = found.flatMap { it.pieces }
            // The pictures, the lengths, and whom each locked part is for -- at the head of its bytes, and whether it
            // is locked at all: a part may be only signed --, side by side.
            val pictures = thread {
                for (piece in found.takeLast(SHOWN_PICTURES).flatMap { it.pieces }.filter { it.type.startsWith("image/") }) {
                    if (thumbs.containsKey(piece.id)) continue
                    Media.thumbnail(this, piece.uri, Look.dp(this, 200))?.let { thumbs[piece.id] = it ; drip() }
                }
            }
            val lengths = thread {
                // As many as the pictures: a picture of each video of a long conversation was held, a megabyte each.
                for (piece in found.takeLast(SHOWN_PICTURES).flatMap { it.pieces }) {
                    if (piece.type.startsWith("audio/") && !lasts.containsKey(piece.id)) Media.lasting(this, piece.uri)?.let { lasts[piece.id] = it ; drip() }
                    // What was opened on an earlier visit is still open: its length, and a video's picture, made again.
                    Opened.piece(piece.id)?.let { opened ->
                        if ((opened.type.startsWith("audio/") || opened.type.startsWith("video/")) && !lasts.containsKey(piece.id)) Media.lasting(opened.bytes)?.let { lasts[piece.id] = it ; drip() }
                        if (opened.type.startsWith("video/") && framed.add(piece.id)) Media.frame(opened.bytes, Look.dp(this, 200))?.let { frames[piece.id] = it ; drip() }
                    }
                    // a video shows its first picture, and plays on its own page.
                    if ((piece.type.startsWith("video/") || piece.type.startsWith("audio/3gp")) && framed.add(piece.id)) {
                        Media.frame(this, piece.uri, Look.dp(this, 200))?.let { first ->
                            frames[piece.id] = first
                            if (!lasts.containsKey(piece.id)) Media.lasting(this, piece.uri)?.let { lasts[piece.id] = it }
                            drip()
                        }
                    }
                }
            }
            for (piece in pieces.filter(::isSealed)) {
                if (sealedFor.containsKey(piece.id)) continue
                val bytes = runCatching { contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull()
                sealedFor[piece.id] = bytes?.let { Pgp.recipients(it) }.orEmpty()
                bytes?.let { pieceLocked[piece.id] = Pgp.isLocked(it) }
                // Just sent from here: open as it left.
                bytes?.let { Opened.claim(piece.id, it) }?.let { opened ->
                    if (opened.type.startsWith("audio/") || opened.type.startsWith("video/")) Media.lasting(opened.bytes)?.let { lasts[piece.id] = it }
                    if (opened.type.startsWith("video/") && framed.add(piece.id)) Media.frame(opened.bytes, Look.dp(this, 200))?.let { frames[piece.id] = it }
                }
                drip()
            }
            // Those named, each asked of foodjis at once, their faces drawn as they come.
            val named = (found.mapNotNull { armorOf(it) }.filter { Pgp.isLocked(it) }.flatMap { Pgp.recipients(it) } +
                pieces.flatMap { sealedFor[it.id].orEmpty() }).distinct()
            val faces = named.filter { !meant.containsKey(it) }.map { id ->
                thread { meant[id] = foodjis.signer(id)?.let { whose -> For(whose.name, foodjis.face(whose.fingerprint), whose.validity, whose.fingerprint) } ; drip() }
            }
            (faces + pictures + lengths).forEach { it.join() }
            // What the locked parts said known: those signed only are verified now.
            runOnUiThread { if (!isFinishing && !isDestroyed && latest()) { bubbles.notifyDataSetChanged() ; verifySigned() } }
        }
    }

    /** Whom each message sent went to, when it went to each of several: asked once a message, off the page. */
    private val sentTo = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val askingSentTo = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun sentWith(line: Store.Line): List<String>? {
        sentTo[line.key]?.let { return it }
        if (askingSentTo.add(line.key)) thread { sentTo[line.key] = Store.sentWith(this, line) ; drip() }
        return null
    }

    /** Whom a message went to, each by name; and a new message to all of them, from the page that starts one. */
    private fun sentToEach(numbers: List<String>) {
        thread {
            val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            val named = numbers.map { one -> (if (contacts) Book.who(this, one)?.name else null)?.let { "$it — $one" } ?: one }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // The new message heads the list rather than sitting among the buttons: its words, on two lines
                // there, pushed "Close" out of the dialog.
                val write = android.text.SpannableString(getString(R.string.write_to_each, numbers.size)).apply {
                    setSpan(android.text.style.ForegroundColorSpan(Look.ORANGE), 0, length, 0)
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sent_to_each, numbers.size))
                    .setItems((listOf<CharSequence>(write) + named).toTypedArray()) { _, which ->
                        if (which != 0) return@setItems
                        // To all of them but oneself.
                        val them = numbers.filterNot { one -> Me.mine(this, one) || Me.numbers(this).any { Phone.same(it, one) } }
                        startActivity(Intent(this, WriteActivity::class.java).putExtra(WriteActivity.EXTRA_NUMBERS, them.toTypedArray()))
                    }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
        }
    }

    /** The page drawn again soon, once for all that arrived meanwhile: not once per picture. */
    private val dripping = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun drip() {
        if (!dripping.compareAndSet(false, true)) return
        list.postDelayed({ dripping.set(false) ; if (!isFinishing && !isDestroyed) bubbles.notifyDataSetChanged() }, DRIP_MS)
    }

    /** Who the conversation is with, in its head: name, face, what their certificate is worth, and what follows from it. */
    private fun head(who: Book.Person?, whose: Foodjis.Cert?, theirs: Bitmap?, first: Boolean) {
        person = who ; card = whose ; face = theirs
        thread?.let { Opened.title(it, who?.name ?: number, number) }
        // A conversation is with a number: the name it has in the address book, and under it the number
        // and what its certificate is worth. With no name, the number is the title.
        title = who?.name ?: number
        whoIs.text = who?.name ?: number
        portrait.removeAllViews()
        // Framed as in the list: the colour of what its certificate is worth, the face of pixels when there is no picture.
        // The frame comes on top of the picture's 38 dp, never out of them.
        portrait.addView(Look.face(this, 38, null, theirs, whose?.validity, framable = true, seed = Look.seed(who?.fingerprint, number)))
        val standing: CharSequence = when {
            who?.fingerprint == null -> getString(R.string.no_certificate)
            foodjis.shut -> getString(R.string.foodjis_shut)
            whose == null -> getString(R.string.certificate_not_held)
            else -> android.text.SpannableStringBuilder("🔑 ").append(Look.standing(this, whose.validity))
        }
        under.text =
            if (who?.name != null && who.name != number) android.text.SpannableStringBuilder("$number — ").append(standing) else standing
        // Asked from the list of conversations: whose certificate this number means.
        when (inner.getStringExtra(EXTRA_ASK).also { inner.removeExtra(EXTRA_ASK) }) {
            ASK_TIE -> tie()
            ASK_REFRESH -> who?.fingerprint?.let { refreshed(it) }
        }
        // Nothing can be written to a sender that is not a number.
        val reachable = Phone.plain(number) != null
        field.isEnabled = reachable
        // Nothing to attach to, either: the "+" is greyed as the field is.
        adding.isEnabled = reachable
        adding.alpha = if (reachable) 1f else 0.38f
        if (!reachable) field.setHint(R.string.no_answer)
        // With a certificate that can be locked for, that is how a message leaves unless told otherwise.
        // Not over what the person set: the page is read again on every return to it, from the camera as well.
        if (first) { locks.suggest(whose?.usable == true) ; signs.suggest(whose?.usable == true) }
        paint()
        bubbles.notifyDataSetChanged()
    }

    /** A group's head: its members by name, how many of them have a certificate, and the switches as they can be for all. */
    private fun groupHead(found: List<Member>, first: Boolean, mineUsable: Boolean = false, myself: Look.Mini? = null) {
        members = found
        // The name the person gave the group, if any: it stands for the group where its members' faces did.
        val called = GroupNames.of(this, groupSet())
        val name = called ?: found.joinToString(", ") { it.name }
        thread?.let { Opened.title(it, name, number) }
        title = name
        whoIs.text = name
        // Faces rather than names, which never fit: ten on a line.
        portrait.visibility = View.GONE
        named.visibility = if (called != null) View.VISIBLE else View.GONE
        crowd.visibility = if (called != null) View.GONE else View.VISIBLE
        crowd.removeAllViews()
        // Oneself last, as in the list's square; beyond nine, eight faces and how many more.
        val faces = found.map { Look.Mini(it.face, it.card?.validity, it.seed) } + listOfNotNull(myself)
        for (one in if (faces.size > 9) faces.take(8) else faces) crowd.addView(Look.face(this, 22, null, one.face, one.standing, framable = true, seed = one.seed, lineDp = 2))
        if (faces.size > 9) crowd.addView(Look.words(this, "+${faces.size - 8}", size = 15f).apply { setTextColor(android.graphics.Color.WHITE) ; setPadding(Look.dp(context, 6), 0, 0, 0) })
        crowd.addView(android.widget.TextView(this).apply {
            text = "⋮" ; setTextColor(android.graphics.Color.WHITE) ; paint.isFakeBoldText = true
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f) ; setPadding(Look.dp(context, 6), 0, Look.dp(context, 4), 0)
        })
        // Counted with oneself, a member as the others are.
        val all = found.size + 1
        val usable = found.count { it.card?.usable == true } + (if (mineUsable) 1 else 0)
        under.text = android.text.SpannableStringBuilder(resources.getQuantityString(R.plurals.group_members, all, all))
            .append("  ·  🔑 $usable/$all")
        if (first) { locks.suggest(lockable()) ; signs.suggest(lockable()) }
        paint()
        bubbles.notifyDataSetChanged()
    }

    /** A group's menu, over its conversation ([GroupMenu]). */
    private fun groupMenu() = GroupMenu.show(
        this, members, groupSet(), given.ifEmpty { listOfNotNull(thread) }, title?.toString(),
        changed = { load(first = false) }, gone = { finish() }, tie = { tieMember(it) },
    )

    /**
     * A member of the group tied to a certificate, their contact made if
     * they have none: the one the group's members part named, fetched by
     * foodjis if it does not hold it; else one from foodjis's list. Through
     * the same window as any tying ([Tying]).
     */
    private fun tieMember(to: String) {
        // What the group's members part said, kept when it came.
        val named = Members.known(this, to)?.fingerprint
        if (named == null) {
            fun chosen(says: Intent?): Boolean {
                val keyId = says?.getLongArrayExtra(Provider.EXTRA_KEY_IDS)?.firstOrNull() ?: return false
                offerFor(to, "%016X".format(keyId))
                return true
            }
            return ask(Intent(Provider.GET_KEY_IDS).putExtra(Provider.EXTRA_SINGLE, true), null, ::say, ::chosen) { chosen(it.says) }
        }
        // Held, or asked of the key servers first.
        thread {
            val held = foodjis.card(named) != null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (held) return@runOnUiThread offerFor(to, named)
                ask(Intent(Provider.GET_KEY).putExtra(Provider.EXTRA_KEY_ID, Fingerprint.keyId(named)), null, ::say) { foodjis.fresh() ; offerFor(to, named) }
            }
        }
    }

    private fun offerFor(to: String, named: String) {
        thread {
            val whose = foodjis.card(named)
            val stale = whose?.let { foodjis.vcard(it.fingerprint) }?.let { Book.stale(this, to, it) }.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (whose == null) return@runOnUiThread say(getString(R.string.certificate_not_held))
                Tying.offer(this, to, whose, stale) { card, face, note, name -> write(whose, card, stale, face, note, name, to) }
            }
        }
    }


    /** The group as the set of its numbers but this phone's: what its name and its silence are kept by. */
    private fun groupSet(): String = Phone.group(everyone, Me.all(this))

    /** Whether what is written can be encrypted: for the certificate of the number, or of every member of the group. */
    private fun lockable(): Boolean = if (group) members.isNotEmpty() && members.all { it.card?.usable == true } else card?.usable == true

    /** The key of the identity foodjis holds, by the id the interface names it with; none until foodjis has said. */
    private var mine: Long? = null

    /** Asked each time the page comes back: whose identity this phone carries, to lock for it what is sent -- foodjis may hold another, or none, since. */
    private fun whoAmI() {
        provider.ask(Intent(Provider.GET_SIGN_KEY_ID), null) { answer ->
            if (answer is Provider.Answer.Done) mine = answer.says.getLongExtra(Provider.EXTRA_SIGN_KEY_ID, 0L).takeIf { it != 0L }
            if (!isFinishing && !isDestroyed) paint()
        }
    }

    /** What the next message will be worth to who receives it. */
    // Certified when signed: signed here, it is signed by the identity this phone carries, which certifies itself --
    // whoever it goes to, and whether or not their number means a certificate.
    private fun worth(mode: Mode): Verdict = Verdict.of(mode.locks, mode.signs)

    /** The switches as they can be, and the colour of what they add up to. */
    private fun paint() {
        // What the answer cites follows the switches: locked words hide once it would leave in clear.
        if (::replyCited.isInitialized) showReply()
        // Nothing to encrypt for without a certificate that stands; nothing to sign with until foodjis has said whose identity this is.
        locks.isEnabled = lockable()
        signs.isEnabled = mine != null
        val worth = worth(mode)
        options.setBackgroundColor(Look.pale(worth))
        sender.background = Look.buttonFace(this, Look.colour(worth))
        sender.setTextColor(if (worth == Verdict.BLUE && !Look.dark) android.graphics.Color.WHITE else Look.INDIGO)
    }

    /** [plainSaid]: the person was asked, and said it may leave unencrypted; [manySaid], to more than the group limit. */
    private fun send(plainSaid: Boolean = false, manySaid: Boolean = false) {
        val written = field.text.toString()
        val with = carried
        if (written.isBlank() && with == null) return
        // Pressed twice: once. Asked again after a question, it is the same sending.
        if (!plainSaid && !manySaid && Twice.again(written, with, number, members)) return
        // A group's: to all its members, once they are known.
        if (if (group) members.isEmpty() else Phone.plain(number) == null) return
        // MMS off: no part, no group -- said before anything else.
        if ((with != null || group) && !Conf.mms(this)) return say(getString(R.string.mms_off))
        // To a group, this phone gets it back: not without its number, which one's own contact gives.
        if (group && Me.number(this) == null) return say(getString(R.string.group_needs_me))
        // More than the limit set, oneself included: networks may refuse it, or deliver it to each alone. Asked.
        if (group && !manySaid && members.size + 1 > Conf.groupMost(this)) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.group_over, members.size + 1, Conf.groupMost(this)))
                .setPositiveButton(R.string.group_over_send) { _, _ -> send(plainSaid, manySaid = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        if (!plainSaid && plainOrAsk(locks, ::paint) { send(plainSaid = true, manySaid = manySaid) }) return
        val mode = mode
        // An answer: the message it cites, on its first line -- what was locked cited only by its time, sent in clear.
        val said = replying?.let { quoteOf(it, mode.locks) + "\n" + written } ?: written
        if (with != null) return sendWith(with, said, mode)
        if (mode == Mode.PLAIN) return leave(said, said, null)
        ask(request(mode, armored = true), said.toByteArray(), ::say) { answer ->
            val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                ?: return@ask say(Foodjis.says(this, "what came back is not a PGP message"))
            leave(armor, said, worth(mode))
        }
    }

    /**
     * [parts] and, to a group, the part that tells it ([Members]), as it is:
     * everyone in it, oneself included, by the name their contact has here.
     */
    private fun withMembers(parts: List<Pdu.Part>, then: (List<Pdu.Part>) -> Unit) {
        if (!group) return then(parts)
        thread {
            val own = Me.card(this)?.let { Book.own(this, it) }
            val mineCert = mine?.let { foodjis.signer(it) }
            val number = Me.number(this).orEmpty()
            val all = listOf(Members.Member(own?.name ?: mineCert?.name ?: number, number, mineCert?.fingerprint, mineCert?.eid)) +
                members.map { Members.Member(it.name, it.number, it.card?.fingerprint, it.card?.eid) }
            runOnUiThread { if (!isFinishing && !isDestroyed) then(parts + Pdu.Part(Members.TYPE, Members.PART, Members.write(all))) }
        }
    }

    /** What foodjis is asked for a message leaving the way [mode] says; a file under its [name], kept inside. */
    private fun request(mode: Mode, armored: Boolean, name: String? = null): Intent {
        val request = Intent(
            when (mode) {
                Mode.SIGN_AND_LOCK -> Provider.SIGN_AND_ENCRYPT
                Mode.LOCK -> Provider.ENCRYPT
                else -> Provider.SIGN
            },
        ).putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, armored)
        name?.let { request.putExtra(Provider.EXTRA_ORIGINAL_FILENAME, it) }
        if (mode.locks) {
            // A group's: every member's certificate, and always one's own -- what one sent, one reads again.
            if (group) {
                val keys = (members.mapNotNull { it.card?.fingerprint?.let(Fingerprint::keyId) } + listOfNotNull(mine)).distinct()
                return request.putExtra(Provider.EXTRA_KEY_IDS, keys.toLongArray())
            }
            // The certificate the address book ties to this number; with none, whoever foodjis knows by the number.
            val whose = person?.fingerprint
            // And for oneself, unless the person said not: what is locked for another alone cannot be read again.
            val keys = listOfNotNull(whose?.let { Fingerprint.keyId(it) }, mine.takeIf { Conf.toMyself(this) }).distinct()
            if (keys.isNotEmpty()) request.putExtra(Provider.EXTRA_KEY_IDS, keys.toLongArray())
            if (whose == null) request.putExtra(Provider.EXTRA_USER_IDS, arrayOf("tel:$number"))
        }
        return request
    }

    /**
     * A message that carries a picture or a sound is an MMS. Locked or
     * signed, what it carries becomes a PGP message of its own -- the file
     * itself, which foodjis opens as it opens any -- under its name and
     * `.pgp`; the words, if there are any, stay a text in armor beside it.
     * Signing both asks for the security key once.
     */
    private fun sendWith(with: Media.Attachment, written: String, mode: Mode) {
        fun post(words: String?, piece: Pdu.Part, verdict: Verdict?) = withMembers(listOfNotNull(words?.let { Pdu.Part.text(it) }, piece)) { parts ->
            val transaction = Outbox.transaction()
            runCatching { Outbox.send(this, targets(), Pdu.send(transaction, targets(), parts), transaction) }
                .onFailure { return@withMembers say(getString(R.string.failed, it.message)) }
            if (verdict != null && words != null) Opened.keep(this, words, written, null, verdict, thread = here())
            // What it carries, open as its words are, for as long.
            if (verdict != null) Opened.keepSent(this, piece.bytes, with.type, with.name, with.bytes, verdict, here())
            field.setText("")
            reply(null)
            forgetDraft()
            carry(null)
            Toast.makeText(this, getString(R.string.handed_mms, with.name), Toast.LENGTH_SHORT).show()
            list.postDelayed({ if (shown) load() }, 1500)
        }
        if (mode == Mode.PLAIN) return post(written.takeIf { it.isNotBlank() }, Pdu.Part(with.type, with.name, with.bytes), null)
        fun piece(sealed: Provider.Answer.Done) = Pdu.Part(Pgp.TYPE, Pgp.outerName(sealed.bytes), sealed.bytes)
        if (written.isBlank()) return ask(request(mode, armored = false, name = with.name), with.bytes, ::say) { post(null, piece(it), worth(mode)) }
        // Two things for foodjis to do, and one presentation of the key for
        // both: it is told of the file first, so that it knows what waits;
        // then asked for the words, which is when it asks for the key; and
        // what the key did for the file is then there to collect.
        provider.ask(request(mode, armored = false, name = with.name), with.bytes) { first ->
            if (isFinishing || isDestroyed) return@ask
            ask(request(mode, armored = true), written.toByteArray(), ::say) { answer ->
                val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                    ?: return@ask say(Foodjis.says(this, "what came back is not a PGP message"))
                if (first is Provider.Answer.Done) return@ask post(armor, piece(first), worth(mode))
                ask(request(mode, armored = false, name = with.name), with.bytes, ::say) { post(armor, piece(it), worth(mode)) }
            }
        }
    }

    private fun carry(what: Media.Attachment?) {
        carried = what
        chipRow.visibility = if (what == null) View.GONE else View.VISIBLE
        carriedRow.attached(true)
        chip.text = what?.let {
            val weight = it.bytes.size / 1024 + 1
            it.millis?.let { long -> getString(R.string.carrying_long, it.name, Media.clock(long), weight) } ?: getString(R.string.carrying, it.name, weight)
        }.orEmpty()
    }

    /**
     * What a message can carry: a photograph taken now, a picture, a
     * contact, where one is, any file, or what was copied. What the phone
     * has no application for is not offered.
     */
    private fun attach() {
        val choices = ArrayList<Pair<Int, () -> Unit>>()
        fun launch(asked: Intent, request: Int) {
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(asked, request) }.onFailure { say(getString(R.string.failed, it.message)) }
        }
        fun offered(asked: Intent) = asked.resolveActivity(packageManager) != null
        // What leaves as an MMS, MMS being on; a place and pasted words are text.
        val mms = Conf.mms(this)
        val camera = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (mms && offered(camera)) {
            choices += R.string.attach_photo to {
                val where = Outbox.shot(this)
                launch(
                    camera.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, where)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .also { it.clipData = android.content.ClipData.newRawUri(null, where) },
                    SHOT,
                )
            }
        }
        if (mms) {
            // A short video, filmed here, under the photograph.
            choices += R.string.attach_video to {
                launch(Intent(this, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_MOST, (Outbox.heaviest(this) - Outbox.MARGIN).toLong()), VIDEO)
            }
            choices += R.string.attach_picture to { launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICTURE) }
            choices += R.string.attach_contact to { launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI), CONTACT) }
        }
        choices += R.string.attach_place to { place() }
        if (mms) choices += R.string.attach_file to { launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), FILE) }
        choices += R.string.attach_text to { paste() }
        AlertDialog.Builder(this)
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /**
     * What was copied, written into the message where the cursor is: the
     * field, held, records the voice, and offers no pasting of its own.
     */
    private fun paste() {
        val copied = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (copied.isEmpty()) return say(getString(R.string.clipboard_empty))
        val at = field.selectionStart.coerceAtLeast(0)
        field.text.insert(at, copied)
    }

    /**
     * Where one is, written into the message: a `geo:` address, which a map
     * application opens, and the same place on OpenStreetMap for whoever has
     * none. Asked of the phone once, when it is asked for.
     */
    private fun place() {
        val wanted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (wanted.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return requestPermissions(wanted, PLACE)
        val locations = getSystemService(android.location.LocationManager::class.java) ?: return
        val from = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locations.isProviderEnabled(it) }.getOrDefault(false) }
        if (from.isEmpty()) return say(getString(R.string.place_off))
        Toast.makeText(this, R.string.place_seeking, Toast.LENGTH_SHORT).show()
        fun found(where: android.location.Location?) {
            if (isFinishing || isDestroyed) return
            if (where == null) return say(getString(R.string.place_none))
            val lat = "%.5f".format(java.util.Locale.ROOT, where.latitude)
            val lon = "%.5f".format(java.util.Locale.ROOT, where.longitude)
            val words = "📍 geo:$lat,$lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
            val at = field.selectionStart.coerceAtLeast(0)
            field.text.insert(at, (if (at > 0) "\n" else "") + words)
        }
        @Suppress("MissingPermission")
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            locations.getCurrentLocation(from.last(), null, mainExecutor) { found(it ?: from.firstNotNullOfOrNull { p -> runCatching { locations.getLastKnownLocation(p) }.getOrNull() }) }
        } else {
            found(from.firstNotNullOfOrNull { runCatching { locations.getLastKnownLocation(it) }.getOrNull() })
        }
    }

    private fun attached(request: Int, from: android.net.Uri) {
        // Room for the message around it, and for what locking adds.
        val most = Outbox.heaviest(this) - Outbox.MARGIN
        thread {
            val made = runCatching {
                when (request) {
                    // A photograph just taken, named when it was; a picture chosen keeps its own name, as a JPEG.
                    SHOT -> Media.picture(this, from, most, Media.named("photo", System.currentTimeMillis(), "jpg"))
                    PICTURE -> Media.picture(this, from, most, Media.pictureName(this, from))
                    CONTACT -> Media.contact(this, from, most)
                    else -> Media.file(this, from, most)
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (made == null) say(getString(R.string.too_heavy, most / 1024)) else carry(made)
            }
        }
    }

    /**
     * A text leaves as SMS while it takes no more of them than the person
     * set, as an MMS beyond when MMS may be sent, and otherwise once the
     * person agreed: in both it is text, which whoever receives it can read
     * or copy.
     */
    private fun leave(text: String, written: String, verdict: Verdict?) {
        // To a group, always one MMS to all; else an MMS once longer than one SMS, MMS being on.
        val pieces = runCatching { Outbox.pieces(this, text) }.getOrDefault(1)
        val mms = group || Conf.asMms(this, pieces)
        fun go() = withMembers(listOf(Pdu.Part.text(text))) { parts ->
            runCatching {
                if (!mms) Outbox.text(this, number, text)
                else {
                    val transaction = Outbox.transaction()
                    Outbox.send(this, targets(), Pdu.send(transaction, targets(), parts), transaction)
                }
            }.onFailure { return@withMembers say(getString(R.string.failed, it.message)) }
            if (verdict != null) Opened.keep(this, text, written, null, verdict, thread = here())
            field.setText("")
            reply(null)
            forgetDraft()
            // The phone writes what was sent a moment after taking it.
            list.postDelayed({ if (shown) load() }, 1500)
        }
        // Many SMS: asked first.
        if (!mms && pieces > Conf.smsMost(this)) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.many_sms, pieces))
                .setPositiveButton(R.string.group_over_send) { _, _ -> go() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            go()
        }
    }

    /**
     * A PGP message pressed: it is opened, and with it those around it that
     * are not yet -- as many before and after as the person set. foodjis is
     * asked for the others first, so that it knows what waits; then for the
     * one pressed, which is when it asks for the security key; and one
     * presentation of the key has answered for all of them.
     */
    private fun pressed(at: Int) {
        val line = lines.getOrNull(at) ?: return
        // Nothing to unlock: what a message carries is opened, and failing that its text is shown to select from.
        if (!closed(line)) {
            return if (line.pieces.isNotEmpty()) pick(line.pieces) { handOver(it, Intent.ACTION_VIEW) }
            else textOf(line).takeIf { it.isNotEmpty() }?.let { select(it, armored = false) } ?: Unit
        }
        // The messages shown around it, as many on each side as set; of those, the ones encrypted and still closed.
        val near = Around.others(lines.size, at, Conf.around(this)).map { lines[it] }.filter(::lockedAndClosed)
        openAll(line, near)
    }

    /** A line's PGP message, as far as it has been opened ([Unlocking.armorOf]). */
    private fun armorOf(line: Store.Line): String? = Unlocking.armorOf(line)

    /** Whether a message holds something encrypted still closed: its words, or a part. */
    private fun lockedAndClosed(line: Store.Line): Boolean =
        armorOf(line)?.let { Pgp.isLocked(it) && Opened.words(it) == null } == true ||
            line.pieces.any { isLockedPiece(it) && Opened.piece(it.id) == null }

    /** Whether a message holds something still closed: its words, or a locked part. */
    private fun closed(line: Store.Line): Boolean =
        armorOf(line)?.let { Opened.words(it) == null } == true ||
            line.pieces.any { isSealed(it) && Opened.piece(it.id) == null }

    /**
     * A message pressed, and the closed ones near it, opened by one use of the
     * key ([Unlocking.open]); with them, as many not read yet in the other
     * conversations as the person set, kept for when theirs is opened. What
     * opens here is read; what foodjis says is locked for somebody else, before
     * the person's eyes, is read too: nothing will ever open it here.
     */
    private fun openAll(chosen: Store.Line, near: List<Store.Line>) {
        val elsewhere = Conf.unreadAll(this)
        val here = here()
        thread {
            val mine = Unlocking.jobs(this, chosen)
            val rest = mine.drop(1) + near.flatMap { Unlocking.jobs(this, it) } +
                Unlocking.waiting(this, elsewhere, besides = here).flatMap { Unlocking.jobs(this, it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val first = mine.firstOrNull() ?: return@runOnUiThread
                // What went wrong, said: sharing or saving the message is the long press's to offer.
                val why: (CharSequence) -> Unit = { words -> say(words) }
                Unlocking.open(
                    provider, first, rest,
                    ask = { input, then -> ask(Intent(Provider.DECRYPT_VERIFY), input, why, notForUs = { read(chosen) }, done = then) },
                    alive = { !isFinishing && !isDestroyed },
                ) { job, answer ->
                    // Another conversation's, not on this page: kept for it. One on this page, of whatever conversation
                    // it is filed in, is opened as this page's: shown, read, counted down and closed with the rest.
                    if (lines.none { it.key == job.line.key }) return@open Unlocking.keep(this, job, answer)
                    when (job) {
                        is Unlocking.Job.Words -> opened(job.armor, answer, offer = job.line === chosen && job.line.incoming)
                        is Unlocking.Job.Part -> got(job.piece, answer)
                    }
                    read(job.line)
                }
            }
        }
    }

    /** The messages the person made unread again: left so while the page stands, however often it is read again. */
    private val keptUnread = HashSet<String>()

    private val Store.Line.key: String get() = (if (mms) "m" else "s") + id

    /** A message of this conversation read, while it is on screen. */
    private fun read(line: Store.Line) {
        if (!line.unread || !shown) return
        thread { Keep.read(this, line) }
    }

    /** A part of an MMS that is itself a PGP message: by its label, or by the name given to what was locked. */
    private fun isSealed(piece: Store.Piece): Boolean = piece.sealed

    /** A locked part, opened: kept with what it is, and a picture made small to sit in its bubble. */
    private fun got(piece: Store.Piece, answer: Provider.Answer.Done) {
        val told = Unlocking.keepPiece(this, piece, answer, pieceLocked[piece.id] != false, here())
        if (told.type.startsWith("audio/") || told.type == "video/3gpp") Media.lasting(answer.bytes)?.let { lasts[piece.id] = it }
        if (told.type == "video/3gpp" || told.type.startsWith("video/")) Media.frame(answer.bytes, Look.dp(this, 200))?.let { frames[piece.id] = it }
        if (told.type.startsWith("image/")) openedThumbs[piece.id] = thumbnail(answer.bytes)
        bubbles.notifyDataSetChanged()
    }

    /** The pictures opened from locked parts, small, by the part they were. */
    private val openedThumbs = HashMap<Long, Bitmap?>()

    /** A picture opened from its lock, made small to sit in its bubble, or to the [side] asked. */
    private fun thumbnail(bytes: ByteArray): Bitmap? = Media.thumbnail(bytes, Look.dp(this, 200))

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        if (Saving.done(this, request, outcome, data, ::say)) return
        if (request == SHOT) {
            // The camera wrote where it was told: read it from there, without clearing it first.
            if (outcome == RESULT_OK) attached(SHOT, android.net.Uri.parse("content://$packageName.pdu/shot/photo.jpg"))
            return
        }
        if (request == VIDEO) {
            if (outcome == RESULT_OK) {
                val filmed = VideoActivity.file(this)
                val name = data?.getStringExtra(VideoActivity.EXTRA_NAME) ?: Media.named("video", System.currentTimeMillis(), "3gp")
                runCatching { filmed.readBytes() }.getOrNull()?.let { carry(Media.Attachment(data?.getStringExtra(VideoActivity.EXTRA_TYPE) ?: "video/3gpp", name, it, Media.lasting(it))) }
                filmed.delete()
            }
            return
        }
        if (request == PICTURE || request == CONTACT || request == FILE) {
            data?.data?.takeIf { outcome == RESULT_OK }?.let { attached(request, it) }
            return
        }
        if (request == SAVE_PIECE) {
            val piece = savingPiece.also { savingPiece = null } ?: return
            val where = data?.data?.takeIf { outcome == RESULT_OK } ?: return
            thread {
                val done = runCatching {
                    val bytes = Opened.piece(piece.id)?.bytes ?: contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } ?: error("nothing to read")
                    contentResolver.openOutputStream(where)?.use { it.write(bytes) } ?: error("nowhere to write")
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    done.onSuccess { Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show() }
                        .onFailure { say(getString(R.string.failed, it.message)) }
                }
            }
            return
        }
    }

    /** The words of a message as they can be read now: opened, or as they travelled. */
    private fun textOf(line: Store.Line): String {
        val armor = armorOf(line)
        return armor?.let { Opened.words(it)?.words } ?: line.body
    }

    /**
     * Pressed long, a message says what can be done with it: with the part
     * pressed -- its [words], or a [piece] it carries --, and with the whole.
     */
    private fun about(at: Int, piece: Store.Piece? = null, words: Boolean = false) {
        val line = lines.getOrNull(at) ?: return
        val text = textOf(line)
        val pieces = if (piece != null) listOf(piece) else if (words) emptyList() else line.pieces
        val choices = ArrayList<Pair<Int, () -> Unit>>()
        // Still locked: opening it comes first, as a press does.
        if (lockedAndClosed(line)) choices += R.string.decrypt_this to { pressed(at) }
        choices += R.string.reply to { reply(line) }
        if (piece == null && text.isNotEmpty()) {
            choices += R.string.copy_text to {
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.message), text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            choices += R.string.select_text to { select(text, armored = armorOf(line) != null && Opened.words(armorOf(line)!!) == null) }
        }
        if (pieces.isNotEmpty()) {
            choices += R.string.open_file to { pick(pieces) { handOver(it, Intent.ACTION_VIEW) } }
            choices += R.string.save to { pick(pieces) { savePiece(it) } }
        }
        choices += R.string.share to {
            if (piece == null && (text.isNotEmpty() || pieces.isEmpty())) {
                HandOut.shareText(this, text, armorOf(line)?.let { Opened.words(it) }?.verdict?.encrypted == true)
            } else {
                pick(pieces) {
                    val opened = Opened.piece(it.id)
                    HandOut.share(
                        this, opened?.bytes, if (opened == null) it.uri else null,
                        opened?.name ?: it.name ?: "file", opened?.type ?: it.type, opened?.verdict?.encrypted == true,
                    )
                }
            }
        }
        choices += R.string.details to { details(line) }
        // Read or not is a received message's state alone: what was sent is read from the start.
        if (line.incoming) {
            if (line.unread) {
                choices += R.string.mark_read to {
                    keptUnread -= line.key
                    thread { Keep.read(this, line) ; runOnUiThread { if (!isFinishing && !isDestroyed) load() } }
                }
            } else {
                choices += R.string.mark_unread to {
                    keptUnread += line.key
                    thread { Keep.unread(this, line) ; runOnUiThread { if (!isFinishing && !isDestroyed) load() } }
                }
            }
        }
        choices += R.string.forget_message to {
            AlertDialog.Builder(this)
                .setMessage(R.string.forget_message_ask)
                .setPositiveButton(R.string.forget_yes) { _, _ ->
                    thread {
                        val gone = Keep.forget(this, line)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (gone) load() else say(getString(if (Keep.ours(this)) R.string.forget_failed else R.string.forget_not_ours))
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        AlertDialog.Builder(this)
            .setItems(choices.map { if (it.first == R.string.share) HandOut.label(this, getString(it.first)) else getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /** Which of the things a message carries: the only one, or the one the person names. */
    private fun pick(pieces: List<Store.Piece>, then: (Store.Piece) -> Unit) {
        if (pieces.size == 1) return then(pieces[0])
        AlertDialog.Builder(this)
            .setItems(pieces.map { Opened.piece(it.id)?.name ?: it.name ?: it.type }.toTypedArray()) { _, which -> then(pieces[which]) }
            .show()
    }

    /** A part pressed: still locked, the key opens it; a picture grows in the conversation or goes back; anything else opens. */
    private fun pressedPiece(piece: Store.Piece, at: Int) {
        val opened = Opened.piece(piece.id)
        if (isSealed(piece) && opened == null) return pressed(at)
        // a video plays on its own page.
        if (frames.containsKey(piece.id)) return watch(piece, at)
        // A picture looked at on its own page, on the whole screen.
        if ((opened?.type ?: piece.type).startsWith("image/") && (openedThumbs[piece.id] ?: thumbs[piece.id]) != null) {
            val (title, said) = seenAs(piece, at)
            return PictureActivity.look(
                this, opened?.bytes, if (opened == null) piece.uri else null, title, said,
                opened?.name ?: piece.name ?: "picture", opened?.type ?: piece.type, opened?.verdict?.encrypted == true,
            )
        }
        handOver(piece, Intent.ACTION_VIEW)
    }

    private var savingPiece: Store.Piece? = null

    /** A part kept in a file the person chooses: as it was opened, or as it came. */
    private fun savePiece(piece: Store.Piece) {
        savingPiece = piece
        val opened = Opened.piece(piece.id)
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(opened?.type ?: piece.type).putExtra(Intent.EXTRA_TITLE, opened?.name ?: piece.name ?: "file"),
            SAVE_PIECE,
        )
    }

    /**
     * Hand what a message carries to another application, to open or to
     * send on: under the type its bytes say, which is not always the one it
     * was labelled with.
     */
    private fun handOver(piece: Store.Piece, how: String) {
        thread {
            val handed = runCatching {
                val opened = Opened.piece(piece.id)
                if (opened != null) Outbox.toOpen(this, opened.name, opened.bytes, opened.type)
                else {
                    val bytes = contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } ?: error("nothing to read")
                    Outbox.toOpen(this, piece.name ?: "file", bytes, piece.type)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val (where, type) = handed.getOrElse { return@runOnUiThread say(getString(R.string.failed, it.message)) }
                val asked = if (how == Intent.ACTION_VIEW) Intent(how).setDataAndType(where, type) else Intent(how).setType(type).putExtra(Intent.EXTRA_STREAM, where)
                asked.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                asked.clipData = android.content.ClipData.newRawUri(null, where)
                runCatching { startActivity(Intent.createChooser(asked, null)) }
                    .onFailure { say(getString(R.string.failed, it.message)) }
            }
        }
    }

    /** The signed messages being verified, or that could not be: not asked again while the page stands. */
    private val verifying = HashSet<String>()

    /** The parts only signed being verified, or that could not be. */
    private val verifyingParts = HashSet<Long>()

    /** The signed messages whose key was missing, asked about again on this page. */
    private val rechecked = HashSet<String>()

    /** The same, of the parts. */
    private val recheckedParts = HashSet<Long>()

    /** Signed only, a message needs no key: it is verified as soon as it is shown, and never stays grey -- its words, and its parts. */
    /**
     * Those foodjis has answered for, their signer's key found: not looked at again. Each answer read the whole
     * conversation again, hashing every signed message's armor to look it up -- the work growing as the square of
     * their number, on the main thread.
     */
    private val answered = HashSet<String>()
    private val answeredParts = HashSet<Long>()

    /** One reading of what is still to verify for the answers that come together: the first one asks, the others join it. */
    private val verifyAgain = Runnable { if (!isFinishing && !isDestroyed) verifySigned() }

    private fun verifySoon() {
        list.removeCallbacks(verifyAgain)
        list.postDelayed(verifyAgain, VERIFY_SETTLE_MS)
    }

    private fun verifySigned() {
        for (piece in lines.flatMap { it.pieces }) {
            if (piece.id in answeredParts) continue
            // A part whose signer's key was missing, too, once a page.
            val missing = Opened.piece(piece.id)?.signed?.result == OpenPgpSignatureResult.RESULT_KEY_MISSING
            if (!isSealed(piece) || pieceLocked[piece.id] != false || (Opened.piece(piece.id) != null && !(missing && recheckedParts.add(piece.id))) || !verifyingParts.add(piece.id)) continue
            thread {
                val bytes = runCatching { contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull() ?: return@thread
                runOnUiThread {
                    provider.ask(Intent(Provider.DECRYPT_VERIFY), bytes) { answer ->
                        if (answer !is Provider.Answer.Done || isFinishing || isDestroyed) return@ask
                        got(piece, answer)
                        verifyingParts.remove(piece.id)
                        if (Opened.piece(piece.id)?.signed?.result != OpenPgpSignatureResult.RESULT_KEY_MISSING) answeredParts += piece.id
                    }
                }
            }
        }
        for (line in lines) {
            val armor = armorOf(line) ?: continue
            if (armor in answered) continue
            // A key that was missing is asked about again, once a page: foodjis may hold it since. Once only --
            // each answer verifies the rest again, and two still missing would be asked about in turn for ever.
            val missing = Opened.words(armor)?.signed?.result == OpenPgpSignatureResult.RESULT_KEY_MISSING
            if (Pgp.isLocked(armor) || (Opened.words(armor) != null && !(missing && rechecked.add(armor))) || !verifying.add(armor)) continue
            provider.ask(Intent(Provider.DECRYPT_VERIFY), armor.toByteArray()) { answer ->
                if (answer !is Provider.Answer.Done || isFinishing || isDestroyed) return@ask
                opened(armor, answer, offer = line.incoming)
                verifying.remove(armor)
                if (Opened.words(armor)?.signed?.result != OpenPgpSignatureResult.RESULT_KEY_MISSING) answered += armor
            }
        }
    }

    /** What a signature said of an opened message: of its words, or else of the first of its locked parts opened. */
    private class Seal(val signed: Signer.Said?, val signer: Long?, val eid: String?)

    private fun sealOf(line: Store.Line): Seal? =
        armorOf(line)?.let { Opened.words(it) }?.let { Seal(it.signed, it.signer, it.eid) }
            ?: line.pieces.filter(::isSealed).firstNotNullOfOrNull { Opened.piece(it.id) }?.let { Seal(it.signed, it.signer, it.eid) }

    /** What is known of a message beyond its words: where from, when, how heavy, what it is made of. */
    private fun details(line: Store.Line) {
        // The parts are weighed off the page: a message of the phone's store says nothing of their size.
        thread {
            val weights = line.pieces.associate { it.id to Media.weight(this, it.uri) }
            // Who signed, as foodjis knows them: the key named by the signature is all the message says.
            val signer = sealOf(line)?.signer?.let { foodjis.signer(it) }
            val signerFace = signer?.let { foodjis.face(it.fingerprint) }
            val address = Store.addressOf(this, line)
            runOnUiThread { if (!isFinishing && !isDestroyed) details(line, weights, signer, signerFace, address) }
        }
    }

    private fun details(line: Store.Line, weights: Map<Long, Long>, signer: Foodjis.Cert?, signerFace: Bitmap?, address: String?) {
        val armor = armorOf(line)
        val precise = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
        val told = buildString {
            append(getString(if (line.incoming) R.string.detail_from else R.string.detail_to, address ?: number)).append('\n')
            append(getString(if (line.incoming) R.string.detail_received else R.string.detail_handed, precise.format(java.util.Date(line.at)))).append('\n')
            if (line.sentAt > 0 && line.incoming) append(getString(R.string.detail_sent, precise.format(java.util.Date(line.sentAt)))).append('\n')
            append(getString(R.string.detail_kind, if (line.mms) "MMS" else "SMS"))
            if (!line.mms) append(' ').append(getString(R.string.detail_pieces, runCatching { Outbox.pieces(this@ThreadActivity, line.body) }.getOrDefault(1)))
            append('\n')
            append(getString(R.string.detail_size, line.body.toByteArray().size + weights.values.sum())).append('\n')
            for (piece in line.pieces) {
                append("•  ").append(piece.name ?: "?").append(" — ").append(piece.type)
                append(" — ").append(android.text.format.Formatter.formatShortFileSize(this@ThreadActivity, weights[piece.id] ?: 0L))
                lasts[piece.id]?.let { append(" — ").append(Media.clock(it)) }
                append('\n')
            }
        }
        // Then what PGP says of it, the people it names each behind their picture.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Look.dp(context, 20)
            setPadding(pad, pad / 2, pad, 0)
        }
        fun text(words: CharSequence) = column.addView(Look.words(this, words, size = 15f).apply { setTextIsSelectable(true) })
        // Each framed in its certificate's colour, as everywhere a person is named.
        fun person(face: Bitmap?, words: CharSequence, standing: String? = null, seed: String? = null) = column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Look.dp(context, 3), 0, Look.dp(context, 3))
                addView(Look.face(context, 32, null, face, standing, framable = true, seed = seed))
                addView(Look.words(context, words, size = 15f).apply { setTextIsSelectable(true) ; setPadding(Look.dp(context, 10), 0, 0, 0) })
            },
        )
        text(told.trimEnd())
        val lockedWords = armor != null && Pgp.isLocked(armor)
        val lockedParts = line.pieces.filter(::isLockedPiece)
        if (armor != null && !lockedWords) text(getString(R.string.detail_signed))
        if (lockedWords || lockedParts.isNotEmpty()) {
            text(getString(R.string.detail_locked))
            val keys = (armor?.takeIf { lockedWords }?.let { Pgp.recipients(it) }.orEmpty() + lockedParts.flatMap { sealedFor[it.id].orEmpty() }).distinct()
            for (id in keys) person(meant[id]?.face, (meant[id]?.name ?: getString(R.string.detail_unknown_key)) + "\n0x%016X".format(id), meant[id]?.validity, meant[id]?.fingerprint)
        }
        sealOf(line)?.let { seal ->
            seal.signed?.let { Signer.words(this, it) }?.let { if (signer != null) person(signerFace, it, signer.validity, signer.fingerprint) else text(it) }
            val more = buildString {
                seal.signer?.let { append(getString(R.string.detail_signer_key, "%016X".format(it))).append('\n') }
                signer?.let { append(getString(R.string.detail_signer_fpr, it.fingerprint.chunked(4).joinToString(" "))).append('\n') }
                (signer?.eid ?: seal.eid)?.let { append(getString(R.string.detail_signer_eid, it)).append('\n') }
            }
            if (more.isNotEmpty()) text(more.trimEnd())
        }
        // Signed by a key foodjis does not hold: it can look for the certificate on the keyservers.
        val missing = sealOf(line)?.takeIf { it.signed?.result == OpenPgpSignatureResult.RESULT_KEY_MISSING }?.signer
        AlertDialog.Builder(this)
            .setTitle(R.string.details)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setPositiveButton(R.string.close, null)
            .apply { if (missing != null) setNeutralButton(R.string.fetch_cert) { _, _ -> fetchCert(missing) } }
            .show()
    }

    /** foodjis asked for the certificate of [keyId], from its keyservers; found, the signed messages are verified again. */
    private fun fetchCert(keyId: Long) {
        // Shown while foodjis looks, and gone when it answers, whatever it says.
        val looking = AlertDialog.Builder(this).setMessage(R.string.cert_fetching).setPositiveButton(R.string.close, null).show()
        fetching = looking
        ask(
            Intent(Provider.GET_KEY).putExtra(Provider.EXTRA_KEY_ID, keyId), null,
            failed = { looking.dismiss() ; say(getString(R.string.cert_not_found)) },
            // A screen of foodjis instead -- this application not let in: what it showed was the answer.
            screened = { looking.dismiss() ; true },
            gaveUp = { looking.dismiss() },
        ) {
            looking.dismiss()
            // What was judged without it is judged again: signed only, at once; encrypted, when opened again.
            val closed = Opened.forgetMissing()
            say(getString(if (closed) R.string.cert_fetched_reopen else R.string.cert_fetched))
            rechecked.clear()
            recheckedParts.clear()
            verifySigned()
            bubbles.notifyDataSetChanged()
        }
    }

    /** The window shown while foodjis looks for a certificate: closed with the page, should it leave first. */
    private var fetching: AlertDialog? = null

    override fun onDestroy() {
        runCatching { fetching?.dismiss() }
        super.onDestroy()
    }

    /**
     * A message shown alone, its text to select from -- a word, a line, all
     * of it. In a window of its own: a list redraws its lines as it scrolls,
     * and a selection does not survive that.
     *
     * The text to select from, its web addresses pressable as in a page; Copy takes what is selected, or all
     * of it when nothing is.
     */
    private fun select(text: String, armored: Boolean) {
        val pad = Look.dp(this, 20)
        val shown = Look.words(this, text, size = if (armored) 12f else 16f).apply {
            // A selectable text still follows its links when it was linked by itself: the mask, then the text again.
            if (!armored) autoLinkMask = android.text.util.Linkify.WEB_URLS
            setText(text)
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, 0)
        }
        AlertDialog.Builder(this)
            .setView(android.widget.ScrollView(this).apply { addView(shown) })
            .setNeutralButton(R.string.copy) { _, _ ->
                val from = minOf(shown.selectionStart, shown.selectionEnd)
                val to = maxOf(shown.selectionStart, shown.selectionEnd)
                val taken = if (from in 0 until to) shown.text.subSequence(from, to).toString() else text
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.message), taken))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** Whether the page has offered to tie the number to a signer's certificate already. */
    private var offered = false

    private fun opened(armor: String, answer: Provider.Answer.Done, offer: Boolean) {
        val signature = Signer.keep(this, armor, answer, here())
        bubbles.notifyDataSetChanged()
        // Opened onto another message: signed only, it is read at once, as any signed message is; locked, a press opens it.
        verifySoon()
        // A group's: whoever sent that message, not the conversation's first number -- once a member, a page.
        if (group) {
            if (!offer || signature?.result !in VALID) return
            val keyId = signature!!.keyId
            val line = lines.firstOrNull { armorOf(it) == armor } ?: return
            thread {
                val from = line.address ?: senders[line.id] ?: Store.addressOf(this, line) ?: return@thread
                val one = memberOf(from) ?: return@thread
                if (one.person?.fingerprint != null || !offeredTo.add(Phone.key(from))) return@thread
                val whose = foodjis.signer(keyId) ?: return@thread
                runOnUiThread { if (!isFinishing && !isDestroyed) offerFor(from, whose.fingerprint) }
            }
            return
        }
        // Signed, from a number that means no certificate yet: it may well mean the signer's.
        // Once a page: several signed messages from the same unknown number ask the same question.
        if (offer && !offered && person?.fingerprint == null && signature?.result in VALID) {
            val keyId = signature!!.keyId
            thread {
                val whose = foodjis.signer(keyId) ?: return@thread
                runOnUiThread {
                    if (isFinishing || isDestroyed || offered) return@runOnUiThread
                    offered = true
                    offer(whose)
                }
            }
        }
    }

    /**
     * One thing a message carries, taking its own presses: a short one does
     * what the part is for, a long one asks what to do with it.
     */
    private fun shownPiece(piece: Store.Piece, at: Int): View = pieceView(piece, at).apply {
        // A sound's button already plays it.
        if (this !is android.widget.Button) setOnClickListener { pressedPiece(piece, at) }
        setOnLongClickListener { about(at, piece) ; true }
    }

    /** a video watched on its own page, said there by whose conversation it is, its name and when it came. */
    private fun watch(piece: Store.Piece, at: Int) {
        val opened = Opened.piece(piece.id)
        val (title, said) = seenAs(piece, at)
        WatchActivity.watch(
            this, opened?.bytes, if (opened == null) piece.uri else null, title, said,
            opened?.name ?: piece.name ?: "video", opened?.type ?: piece.type, opened?.verdict?.encrypted == true,
        )
    }

    /** What a page of its own says over a part: whose conversation, then the part's name and when it came. */
    private fun seenAs(piece: Store.Piece, at: Int): Pair<String, String> {
        val name = Opened.piece(piece.id)?.name ?: piece.name ?: piece.type
        val then = lines.getOrNull(at)?.let { Look.ago(it.at) }
        return (person?.name ?: number) to listOfNotNull(name, then).joinToString("  ·  ")
    }

    /** a video, by its first picture with ▶ and its length over it; pressed, it plays on its own page. */
    private fun movie(piece: Store.Piece, first: Bitmap, watch: () -> Unit): View = android.widget.FrameLayout(this).apply {
        addView(
            android.widget.ImageView(context).apply {
                setImageBitmap(first)
                adjustViewBounds = true
                maxWidth = Look.dp(context, 220) ; maxHeight = Look.dp(context, 220)
            },
        )
        addView(
            Look.words(context, "▶" + lasted(piece), size = 18f).apply {
                setTextColor(android.graphics.Color.WHITE)
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            },
            android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
        )
        setPadding(0, Look.dp(context, 4), 0, Look.dp(context, 4))
        setOnClickListener { watch() }
    }

    /** A picture, small in its bubble; pressed, on the whole screen. */
    private fun picture(piece: Store.Piece, small: Bitmap): View = android.widget.ImageView(this).apply {
        setImageBitmap(small)
        adjustViewBounds = true
        maxWidth = Look.dp(context, 220) ; maxHeight = Look.dp(context, 220)
        setPadding(0, Look.dp(context, 4), 0, Look.dp(context, 4))
    }

    /** One thing a message carries: a picture shown small, a sound to play, or what it is called. */
    private fun pieceView(piece: Store.Piece, at: Int): View {
        val opened = Opened.piece(piece.id)
        if (opened != null) {
            // Opened: shown as if it had travelled in clear, under its own name.
            // Made again from what is held open when this page did not open it: a conversation left and come back to
            // finds its pictures still open, and they are to look it.
            val small = if (opened.type.startsWith("image/")) openedThumbs.getOrPut(piece.id) { thumbnail(opened.bytes) } else null
            return when {
                small != null -> picture(piece, small)
                frames[piece.id] != null -> movie(piece, frames[piece.id]!!) { watch(piece, at) }
                opened.type.startsWith("audio/") || opened.type == "video/3gpp" ->
                    Look.button(this, "${sign(piece)} ${opened.name}" + lasted(piece)) { play(piece) }
                else -> Look.words(this, "📎 ${opened.name}", size = 15f)
            }
        }
        val small = thumbs[piece.id]
        val name = piece.name ?: piece.type
        return when {
            small != null -> picture(piece, small)
            frames[piece.id] != null -> movie(piece, frames[piece.id]!!) { watch(piece, at) }
            piece.type.startsWith("audio/") -> Look.button(this, "${sign(piece)} $name" + lasted(piece)) { play(piece) }
            isSealed(piece) -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // As the words of a locked message: the key in its square while unread, the key alone once read.
                val unread = lines.getOrNull(at)?.unread ?: true
                addView(Look.words(context, (if (!isLockedPiece(piece)) "✍ " else if (unread) "$LOCKED " else "$OPENED_ONCE ") + name, size = 15f, muted = true))
                // Whom it is for, unless the message's words already said it: one face each, not twice.
                val wordsFor = lines.getOrNull(at)?.let { armorOf(it) }?.takeIf { Pgp.isLocked(it) }?.let { Pgp.recipients(it) }.orEmpty()
                val whom = sealedFor[piece.id].orEmpty().filter { it !in wordsFor }
                // "for" before them, as a locked message's words say it.
                if (whom.isNotEmpty()) addView(Look.words(context, " " + getString(R.string.piece_for), size = 15f, muted = true))
                for (id in whom) {
                    addView(View(context), LinearLayout.LayoutParams(Look.dp(context, 4), 1))
                    addView(Look.face(context, 22, null, meant[id]?.face))
                }
            }
            else -> Look.words(this, "📎 $name", size = 15f, muted = true)
        }
    }

    private fun lasted(piece: Store.Piece): String = lasts[piece.id]?.let { "  ·  " + Media.clock(it) }.orEmpty()

    private var player: android.media.MediaPlayer? = null

    /** The part the page plays itself, while it does. */
    private var playingPiece: Long? = null

    /** What a sound's button says: ▶ to play it, ■ while it plays -- pressed again, it stops, in its bubble or beyond the page. ■ has no side bearing of its own: a thin space keeps it off the edge, as ▶ is. */
    private fun sign(piece: Store.Piece) = if (playingPiece == piece.id || Listening.playing == piece.id) "\u2009■" else "▶"

    /** Play a sound, or stop the one that plays: as it came, or as it was opened from its lock. */
    private fun play(piece: Store.Piece) {
        val playing = player
        val wasThis = playingPiece == piece.id
        player = null
        playingPiece = null
        if (playing != null) {
            runCatching { playing.stop() ; playing.release() }
            bubbles.notifyDataSetChanged()
            // Pressed again, it stops; another pressed, that one plays instead.
            if (wasThis) return
        }
        // A long one is listened to beyond the page, with its controls in the notifications: pressed again, it stops.
        if ((lasts[piece.id] ?: 0L) > Listening.LONG) {
            if (Listening.playing == piece.id) return Listening.stop(this)
            val title = getString(R.string.listening_title, person?.name ?: number)
            thread {
                val bytes = Opened.piece(piece.id)?.bytes ?: runCatching { contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull()
                runOnUiThread {
                    if (bytes == null) say(getString(R.string.failed, "nothing to read"))
                    else runCatching { Listening.play(this, piece.id, title, bytes) }.onFailure { say(getString(R.string.failed, it.message)) }
                }
            }
            return
        }
        runCatching {
            player = android.media.MediaPlayer().apply {
                val opened = Opened.piece(piece.id)
                if (opened != null) {
                    // What was locked is in memory only, and played from there.
                    setDataSource(Media.source(opened.bytes))
                } else {
                    setDataSource(this@ThreadActivity, piece.uri)
                }
                setOnCompletionListener {
                    release()
                    if (player === it) { player = null ; playingPiece = null ; bubbles.notifyDataSetChanged() }
                }
                prepare()
                start()
            }
            playingPiece = piece.id
            bubbles.notifyDataSetChanged()
        }.onFailure { player = null ; playingPiece = null ; say(getString(R.string.failed, it.message)) }
    }

    private val bubbles = object : BaseAdapter() {
        override fun getCount() = lines.size
        override fun getItem(at: Int) = lines[at]
        override fun getItemId(at: Int) = at.toLong()

        // Only the parts of a message take presses, not the white around them.
        override fun areAllItemsEnabled() = false

        override fun isEnabled(at: Int) = false

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val line = lines[at]
            val context = this@ThreadActivity
            val armor = armorOf(line)
            val read = armor?.let { Opened.words(it) }
            val sealed = line.pieces.filter(::isSealed)
            // Not opened yet: nothing tells what a PGP message is worth, signed or not, before the key does.
            val opened = when {
                armor != null -> read?.let { it.verdict to it.signer }
                sealed.isNotEmpty() -> sealed.firstNotNullOfOrNull { Opened.piece(it.id) }?.let { it.verdict to it.signer }
                else -> Verdict.RED to null
            }
            // Received and certified by somebody else than whom this conversation is with: its own colours.
            val verdict = opened?.let { (worth, signer) -> if (line.incoming) Verdict.received(worth, signer, whose()) else worth } ?: Verdict.GREY
            val closed = armor != null && read == null
            val locked = closed && Pgp.isLocked(armor!!)
            val words = when {
                armor == null -> line.body
                read != null -> read.words
                // Its first sign the key in its square while unread, the key alone once read: at a glance, which are still to open.
                locked -> getString(R.string.locked_for).let { if (line.unread) it else it.replaceFirst(LOCKED, OPENED_ONCE) }
                else -> getString(R.string.signed_press)
            }
            val under = android.text.SpannableStringBuilder().apply {
                append(Look.ago(line.at))
                if (verdict.encrypted) append("  ⚿")
                // Who signed: the words, or else what the first of its PGP parts said once opened -- a voice message has no words.
                (read?.signed ?: line.pieces.filter(::isSealed).firstNotNullOfOrNull { Opened.piece(it.id)?.signed })?.let { Signer.words(context, it) }
                    ?.let { append("  ✍ ").append(it) }
                // Refused by the network: certainly not sent -- the one thing known for sure of a sending.
                if (line.failed) {
                    append("\n").append(
                        getString(R.string.refused), android.text.style.ForegroundColorSpan(Look.standingColour("revoked")),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                // Come without what it carried: the network took it out, and says so in its own words.
                line.stripped?.let { notice ->
                    append("\n").append(
                        getString(R.string.stripped, notice), android.text.style.ForegroundColorSpan(Look.standingColour("revoked")),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            val pad = Look.dp(context, 10)
            // An answer: what it cites, apart above its own words.
            val citing = if (closed) null else Quote.split(words)
            val own = citing?.second ?: words
            val text = Look.words(context, if (closed) own else marked(own, at), size = 16f, muted = closed)
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.rounded(Look.pale(verdict), Look.dp(context, 14))
                setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                if (locked) {
                    // "Encrypted message for" and whom: a small face for each key the message names.
                    addView(
                        LinearLayout(context).apply {
                            setOnClickListener { pressed(at) }
                            setOnLongClickListener { about(at, words = true) ; true }
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(text)
                            // The faces after the words, going on to the next line on the right when the bubble is full.
                            val faces = Look.flow(context, Look.dp(context, 4))
                            for (id in Pgp.recipients(armor!!)) faces.addView(Look.face(context, 22, null, meant[id]?.face))
                            removeView(text)
                            faces.addView(text, 0)
                            addView(faces)
                        },
                    )
                } else if (words.isNotEmpty() || line.pieces.isEmpty()) {
                    citing?.first?.let { cited ->
                        addView(
                            Look.cited(context, cited).apply {
                                setOnClickListener { goToCited(cited, if (line.incoming && line.sentAt > 0) line.sentAt else line.at) }
                            },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad / 2 },
                        )
                    }
                    // Open, the words are selected, links included: nothing is followed on a press alone.
                    text.setOnClickListener { if (closed || words.isEmpty()) pressed(at) else select(words, armored = false) }
                    text.setOnLongClickListener { about(at, words = true) ; true }
                    // An answer made of its citation and a part alone has no words of its own to show.
                    if (own.isNotEmpty() || citing == null) addView(text)
                }
                // An encrypted group's members part is for foomms, not for reading.
                for (piece in line.pieces.filterNot { Members.isPart(it.name) }) addView(shownPiece(piece, at))
                // Sent to each of several at once: said, and pressed, whom to. Received as one of several: who else.
                if (!line.incoming || line.mms) {
                    val with = sentWith(line)
                    if (with != null && with.size > 1) {
                        val said = getString(R.string.sent_to_each, with.size)
                        addView(
                            Look.words(context, said, size = 12f, muted = true).apply {
                                setPadding(0, pad / 3, 0, 0)
                                setOnClickListener { sentToEach(with) }
                            },
                        )
                    }
                }
                // The whole line, its text as the rest, gives the menu the long press gives; the details are in it.
                addView(
                    Look.words(context, under, size = 11f, muted = true).apply {
                        setOnClickListener { about(at) }
                        setOnLongClickListener { about(at) ; true }
                    },
                )
            }
            // A mark on the side the message came from, the bubble itself on that side: of the colour of what it travelled as.
            val outer = Pgp.armorIn(line.body)
            val form = Form.of(
                locked = outer?.let(Pgp::isLocked) == true || line.pieces.any(::isLockedPiece),
                signed = outer != null || line.pieces.any(::isSealed),
            )
            val mark = View(context).apply { background = Look.rounded(Look.colour(form), Look.dp(context, 2)) }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // The mark first, then the bubble, from the side the message came from: a row laid out the other way
                // for one sent. The bubble measured last gets what the mark left it -- measured first, a wide one
                // took the whole row, and the mark pushed its edge out of sight. Its own words keep the page's direction.
                layoutDirection = if (line.incoming) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
                bubble.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPaddingRelative(pad, pad / 3, pad * 5, pad / 3)
                val stripe = LinearLayout.LayoutParams(Look.dp(context, 4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = pad / 3 ; marginEnd = pad / 3
                }
                addView(mark, stripe)
                // In a group, who wrote it: their face beside what they wrote, framed by what their certificate is worth.
                if (group && line.incoming) {
                    val who = memberOf(line.address ?: senders[line.id])
                    addView(
                        Look.face(context, 32, null, who?.face, who?.card?.validity, framable = true, seed = who?.seed ?: (line.address ?: senders[line.id])?.let(Phone::key)).apply {
                            // Pressed, who it is.
                            setOnClickListener { Toast.makeText(context, who?.let { if (it.name != it.number) "${it.name} — ${it.number}" else it.number } ?: (line.address ?: senders[line.id]).orEmpty(), Toast.LENGTH_SHORT).show() }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.TOP ; marginEnd = pad / 2
                        },
                    )
                }
                addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    /** foodjis asks the person whether this application may use the identity; its door opens with the answer. */
    private fun letIn() {
        ask(Intent(Provider.GET_SIGN_KEY_ID), null, ::say) {
            foodjis.forget()
            load()
        }
    }

    /**
     * Say whose certificate this number means: foodjis shows its own list of
     * contacts, over this page, and the one pressed is the one meant.
     */
    private fun tie() {
        fun chosen(says: Intent?): Boolean {
            val keyId = says?.getLongArrayExtra(Provider.EXTRA_KEY_IDS)?.firstOrNull() ?: return false
            found("%016X".format(keyId))
            return true
        }
        // The list answers by itself: the contact pressed comes back with the screen.
        ask(Intent(Provider.GET_KEY_IDS).putExtra(Provider.EXTRA_SINGLE, true), null, ::say, ::chosen) { chosen(it.says) }
    }

    /** What foodjis says of the certificate named, by fingerprint or by key id, then the question. */
    private fun found(named: String) {
        thread {
            val whose = foodjis.card(named)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    foodjis.shut -> say(getString(R.string.foodjis_shut))
                    whose == null -> say(getString(R.string.certificate_not_held))
                    else -> offer(whose)
                }
            }
        }
    }

    /**
     * The certificate refreshed by foodjis, the page drawn again: the contact
     * is offered what the certificate says only when it says something the
     * contact does not.
     */
    private fun refreshed(fingerprint: String) {
        foodjis.fresh()
        // What was judged without the certificate is judged again with it: signed only, as the page loads.
        Opened.forgetMissing()
        rechecked.clear()
        recheckedParts.clear()
        load()
        thread {
            val whose = foodjis.card(fingerprint)
            val card = whose?.let { foodjis.vcard(it.fingerprint) }
            val lacks = card != null &&
                Book.lacks(this, number, card, card.key?.takeIf { Fingerprint.of(it) == fingerprint } ?: Fingerprint.address(fingerprint))
            val stale = card?.let { Book.stale(this, number, it) }.orEmpty()
            val differs = card?.let { Book.differs(this, number, it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    foodjis.shut -> say(getString(R.string.foodjis_shut))
                    whose == null -> say(getString(R.string.certificate_not_held))
                    lacks || stale.isNotEmpty() || differs?.face != null || differs?.notes?.isNotEmpty() == true || differs?.name != null -> offer(whose, stale)
                    else -> Toast.makeText(this, R.string.cert_refreshed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** What would be written in the contact of this number, shown before it is ([Tying]). */
    private fun offer(whose: Foodjis.Cert, stale: List<Book.Stale> = emptyList()) =
        Tying.offer(this, number, whose, stale) { card, face, note, name -> write(whose, card, stale, face, note, name) }

    private var waiting: (() -> Unit)? = null

    private fun write(
        whose: Foodjis.Cert, vcard: Card, stale: List<Book.Stale> = emptyList(),
        face: Book.Pick = Book.Pick.CONTACT, note: Book.Pick = Book.Pick.CONTACT, name: Book.Pick = Book.Pick.CONTACT,
        to: String = number,
    ) {
        if (NEEDED_TO_WRITE.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            waiting = { write(whose, vcard, stale, face, note, name, to) }
            return requestPermissions(NEEDED_TO_WRITE, 2)
        }
        thread {
            val outcome = runCatching {
                Book.tie(this, to, vcard, vcard.key?.takeIf { Fingerprint.of(it) == whose.fingerprint } ?: Fingerprint.address(whose.fingerprint), stale, face, note, name = name)
                getString(R.string.tied, whose.name)
            }.getOrElse { getString(R.string.failed, it.message) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, outcome, Toast.LENGTH_LONG).show()
                person = null ; card = null
                load(first = true)
            }
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        if (request == PLACE) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) place()
            return
        }
        // Let in: the next press records. The one that asked was already let go.
        if (request == VOICE) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) Toast.makeText(this, R.string.voice_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val then = waiting.also { waiting = null }
        if (request == 2 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) then?.invoke()
    }

    private fun say(words: CharSequence) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        /** A locked message not read yet, and one read already -- closed again, or never opened here. */
        private const val LOCKED = "⚿"
        private const val OPENED_ONCE = "⚷"

        /**
         * The page of the group of [numbers]: the conversations it already has, with this phone among the
         * numbers or without; else a new one. From a new message, and from a draft's line.
         */
        fun forGroup(context: android.content.Context, numbers: List<String>): Intent {
            val me = Me.number(context)
            // Where its draft was marked, at once; else looked for among every conversation of several -- not on the main thread.
            val threads = listOfNotNull(Drafts.groupThread(context, numbers)).ifEmpty { Store.groupThreads(context, numbers, Me.all(context)) }.ifEmpty {
                listOfNotNull(runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(context, (numbers + listOfNotNull(me)).toSet()) }.getOrNull())
            }
            return Intent(context, ThreadActivity::class.java).putExtra(EXTRA_NUMBER, numbers.first())
                .putExtra(EXTRA_THREAD, threads.firstOrNull() ?: -1L)
                .putExtra(EXTRA_THREADS, threads.toLongArray())
        }

        const val EXTRA_NUMBER = "number"

        /** The conversation, when the page is opened from the list of them. */
        const val EXTRA_THREAD = "thread"
        /** Several conversations shown as one: a group's, with this phone among its numbers and without. */
        const val EXTRA_THREADS = "threads"
        /** A group's MMS filed elsewhere, and its members: one heard only from the headers of received MMS. */
        const val EXTRA_MESSAGES = "messages"
        const val EXTRA_MEMBERS = "members"

        /** Opened for something to be done at once: say whose certificate the number means, or read it again from foodjis. */
        const val EXTRA_ASK = "ask"
        const val ASK_TIE = "tie"
        const val ASK_REFRESH = "refresh"

        /** The date of the message to show first. */
        const val EXTRA_AT = "at"
        private const val PICTURE = 5
        private const val SHOT = 7
        private const val CONTACT = 8
        private const val FILE = 9
        private const val PLACE = 10
        private const val VOICE = 11
        private const val SAVE_PIECE = 12
        private const val VIDEO = 13


        /** How many of the latest messages have their pictures shown small. */
        private const val SHOWN_PICTURES = 40

        /** How long the messages are to stay still before the page is read again. */
        private const val SETTLE_MS = 400L

        /** How long answers from foodjis are let gather before what is still to verify is read again. */
        private const val VERIFY_SETTLE_MS = 150L

        /** How long the page waits to be drawn again, gathering what arrives meanwhile. */
        private const val DRIP_MS = 120L
        private val MESSAGES = android.net.Uri.parse("content://mms-sms/")
        private val NEEDED_TO_WRITE = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        private val VALID = setOf(
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED,
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
        )

    }
}
