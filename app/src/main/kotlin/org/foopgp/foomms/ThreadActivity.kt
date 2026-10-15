package org.foopgp.foomms

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
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

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER) ?: intent.data?.schemeSpecificPart.orEmpty() }
    private val foodjis by lazy { Foodjis.of(this) }

    private var person: Book.Person? = null
    private var card: Foodjis.Cert? = null
    private var face: Bitmap? = null
    private var lines: List<Store.Line> = emptyList()
    /** How the next message leaves: what the two switches say, as far as each can be on. */
    private val mode: Mode get() = Mode.of(signs.isChecked && signs.isEnabled, locks.isChecked && locks.isEnabled)

    /** Who a key a message names belongs to, as far as foodjis knows. */
    private class For(val name: String, val face: Bitmap?)

    private var meant: Map<Long, For?> = emptyMap()

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
    private lateinit var chip: android.widget.TextView
    private lateinit var whoIs: android.widget.TextView
    private lateinit var under: android.widget.TextView
    private lateinit var portrait: android.widget.FrameLayout

    /** The conversation in the phone's store, once it is known to have one. */
    private var thread: Long? = null

    /** This conversation, as what is opened in it is counted down: none yet, the conversation of nobody. */
    private fun here(): Long = thread ?: Opened.NO_THREAD

    private val voice by lazy { Voice(this) }

    /** The field held down while empty: record, if the microphone may be used, until it is let go. */
    private fun speak(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), VOICE)
            return true
        }
        if (!voice.start(Outbox.heaviest(this) - 16 * 1024)) {
            say(getString(R.string.voice_failed))
            return true
        }
        field.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        chip.visibility = View.VISIBLE
        // The time it has been recording, said each second for as long as it records.
        val tick = object : Runnable {
            override fun run() {
                if (!voice.recording) return
                chip.text = getString(R.string.voice_recording, Media.clock(voice.millis))
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
        carry(said)
        if (said == null) Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
    }

    /** What the next message carries beside its words: a picture, a sound. */
    private var carried: Media.Attachment? = null

    /** The pictures of this conversation, small, by the part they are. */
    private var thumbs: Map<Long, Bitmap?> = emptyMap()

    /** How long each sound of this conversation lasts, by the part it is, as it came or as it was opened. */
    private val lasts = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** The message to show first, when the page was opened from a search: its date, taken once. */
    private var wanted = 0L

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        dressed = Look.dark
        super.onCreate(state)
        if (number.isBlank()) return finish()
        title = number
        wanted = intent.getLongExtra(EXTRA_AT, 0L)
        // In the bar: who the conversation is with -- a face, a name, and under it the number and what its
        // certificate is worth. Pressed, what can be done about them, as from the list of conversations.
        whoIs = Look.words(this, number, size = 17f).apply { setTextColor(Look.ORANGE) ; setTypeface(typeface, android.graphics.Typeface.BOLD) ; maxLines = 1 }
        under = Look.words(this, "", size = 13f).apply { setTextColor(android.graphics.Color.rgb(0xE0, 0xE7, 0xFF)) ; maxLines = 1 }
        portrait = android.widget.FrameLayout(this)
        actionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            setDisplayHomeAsUpEnabled(true)
            customView = LinearLayout(this@ThreadActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val side = Look.dp(context, 38)
                addView(portrait, LinearLayout.LayoutParams(side, side))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Look.dp(context, 10), 0, 0, 0)
                        // The dots that say the bar can be pressed, on its first line.
                        addView(Look.pressable(context, whoIs), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        addView(under)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                setOnClickListener {
                    TalkMenu.show(
                        this@ThreadActivity, person?.name ?: number, number, person, thread,
                        tie = { tie() }, refresh = { person?.fingerprint?.let { found(it) } },
                        changed = { person = null ; card = null ; load(first = true) }, gone = { finish() },
                        save = { Saving.save(this@ThreadActivity, thread, person?.name ?: number) },
                        // Only while foodjis keeps its door shut: once let in, there is nothing to ask.
                        letIn = if (foodjis.shut) ({ letIn() }) else null,
                    )
                }
                addView(Look.icon(context, R.drawable.search, getString(R.string.search_here)) { seeking(seeker.visibility != View.VISIBLE) })
            }
            customView.layoutParams = android.app.ActionBar.LayoutParams(android.app.ActionBar.LayoutParams.MATCH_PARENT, android.app.ActionBar.LayoutParams.WRAP_CONTENT)
        }
        list = ListView(this).apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = true
            adapter = bubbles
            setOnItemClickListener { _, _, at, _ -> pressed(at) }
            setOnItemLongClickListener { _, _, at, _ -> about(at) ; true }
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
        val add = Look.button(this, "+") { attach() }.apply { textSize = 22f }
        // What is attached, said above the field; pressed, it is taken off.
        chip = Look.words(this, "", size = 16f, muted = true).apply {
            setPadding(pad * 3, pad, pad * 3, 0)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
            setOnClickListener { carry(null) }
        }
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
                    addView(chip)
                    addView(options)
                    addView(bar)
                }
                addView(writing)
            },
        )
        Edges.fit(this)
        // Words handed over with the page -- shared from another application, or a link's "sms_body" -- are already written.
        if (state == null) (intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body"))?.let { field.setText(it) }
        // And what was shared with them: a picture made small enough for an MMS, any other file if it fits.
        @Suppress("DEPRECATION")
        if (state == null) intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { shared ->
            val type = intent.type ?: contentResolver.getType(shared).orEmpty()
            attached(if (type.startsWith("image/")) PICTURE else FILE, shared)
        }
    }

    /** Shown, and so read by the person: a conversation open behind another screen reads nothing. */
    private var shown = false

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(self: Boolean) { if (shown) load() }
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
            addView(Look.small(context, getString(R.string.close_now)) { Opened.close(here()) ; tick() })
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
        val closes = Opened.closesAt(here())
        if (closes != null) remaining.text = getString(R.string.closes_in, Media.clock(maxOf(0L, closes - System.currentTimeMillis() + 999)))
        closing.visibility = if (closes != null) View.VISIBLE else View.GONE
        // Closed now: the bubbles show it, and the pictures opened from locked parts go with the rest.
        if (closes == null && wasOpen) {
            openedThumbs.clear()
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

    /** What is looked for, while the search is shown. */
    private val looked: String get() = if (seeker.visibility == View.VISIBLE) sought.text.toString().trim() else ""

    /** The messages holding it, oldest first, and the one shown. */
    private var hits: List<Int>? = null
    private var hit = -1

    private fun seeking(on: Boolean) {
        seeker.visibility = if (on) View.VISIBLE else View.GONE
        val keys = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (on) { sought.requestFocus() ; keys?.showSoftInput(sought, 0) } else { keys?.hideSoftInputFromWindow(sought.windowToken, 0) ; field.requestFocus() }
        hits = null ; hit = -1
        bubbles.notifyDataSetChanged()
    }

    /** What a message shows that can be looked for: its words when they can be read, and what its parts are called. */
    private fun searchable(line: Store.Line): String {
        val armor = armorOf(line)
        val words = if (armor == null) line.body else Opened.words(armor)?.words.orEmpty()
        return words + " " + line.pieces.joinToString(" ") { Opened.piece(it.id)?.name ?: it.name.orEmpty() }
    }

    /** The next match up (-1) or down (1), round from one end to the other, or the latest one (0); the list scrolls to it. */
    private fun seek(way: Int) {
        val wanted = looked
        if (wanted.isEmpty()) { hitCount.text = "" ; bubbles.notifyDataSetChanged() ; return }
        val all = hits ?: lines.indices.filter { searchable(lines[it]).contains(wanted, ignoreCase = true) }.also { hits = it }
        if (all.isEmpty()) { hitCount.text = "0" ; bubbles.notifyDataSetChanged() ; return }
        hit = when {
            hit < 0 || way == 0 -> all.size - 1
            else -> Math.floorMod(hit + way, all.size)
        }
        hitCount.text = "${hit + 1}/${all.size}"
        bubbles.notifyDataSetChanged()
        list.setSelection(all[hit])
    }

    /** [text] with what is looked for marked wherever it is. */
    private fun marked(text: CharSequence): CharSequence {
        val wanted = looked
        if (wanted.isEmpty()) return text
        val out = android.text.SpannableString(text)
        var from = text.indexOf(wanted, ignoreCase = true)
        while (from >= 0) {
            out.setSpan(android.text.style.BackgroundColorSpan(Look.ORANGE), from, from + wanted.length, 0)
            from = text.indexOf(wanted, from + wanted.length, ignoreCase = true)
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        if (Look.wantsDark(this) != dressed) return recreate()
        Look.dress(this)
        shown = true
        whoAmI()
        contentResolver.registerContentObserver(MESSAGES, true, arrivals)
        load(first = card == null && person == null)
        closing.removeCallbacks(ticking)
        ticking.run()
    }

    override fun onPause() {
        shown = false
        if (voice.recording) voice.drop()
        player?.let { runCatching { it.stop() ; it.release() } }
        player = null
        contentResolver.unregisterContentObserver(arrivals)
        super.onPause()
    }

    /** Who this is, what foodjis says of their certificate, and the messages. */
    private fun load(first: Boolean = false) {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        thread {
            val who = if (contacts) Book.who(this, number) else null
            val whose = who?.fingerprint?.let { foodjis.card(it) }
            val theirs = who?.let { Book.face(this, it) } ?: who?.fingerprint?.let { foodjis.face(it) }
            // The conversation it was opened from, when it was opened from one: a sender that is a name and not a
            // number -- a bank, a shop -- has a conversation all the same.
            val thread: Long? = intent.getLongExtra(EXTRA_THREAD, -1L).takeIf { it >= 0 } ?: runCatching { Store.thread(this, number) }.getOrNull()
            val found = runCatching { thread?.let { Store.lines(this, it) } }.getOrNull().orEmpty()
            // Whom each locked message is for, by the keys it names: a face each, asked of foodjis once.
            // A locked part says it too, at the head of its bytes.
            // Whether it is locked at all, too: a part may be only signed, readable by anyone, and is not to be shown locked.
            for (piece in found.flatMap { it.pieces }.filter(::isSealed)) {
                if (!sealedFor.containsKey(piece.id)) {
                    val bytes = runCatching { contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull()
                    sealedFor[piece.id] = bytes?.let { Pgp.recipients(it) }.orEmpty()
                    bytes?.let { pieceLocked[piece.id] = Pgp.isLocked(it) }
                }
            }
            val named = (found.mapNotNull { armorOf(it) }.filter { Pgp.isLocked(it) }.flatMap { Pgp.recipients(it) } +
                found.flatMap { it.pieces }.flatMap { sealedFor[it.id].orEmpty() }).distinct()
            val small = found.takeLast(SHOWN_PICTURES).flatMap { it.pieces }.filter { it.type.startsWith("image/") }
                .associate { piece -> piece.id to (thumbs[piece.id] ?: Media.thumbnail(this, piece.uri, Look.dp(this, 200))) }
            for (piece in found.flatMap { it.pieces }) {
                if (piece.type.startsWith("audio/") && !lasts.containsKey(piece.id)) Media.lasting(this, piece.uri)?.let { lasts[piece.id] = it }
            }
            val theirFaces = named.associateWith { id -> foodjis.signer(id)?.let { whose -> For(whose.name, foodjis.face(whose.fingerprint)) } }
            if (shown) {
                thread?.let { Keep.read(this, it) }
                Notify.seen(this, number)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                person = who ; card = whose ; face = theirs ; lines = found ; meant = theirFaces ; thumbs = small
                hits = null
                this.thread = thread
                thread?.let { Opened.title(it, who?.name ?: number, number) }
                // A conversation is with a number: the name it has in the address book, and under it the number
                // and what its certificate is worth. With no name, the number is the title.
                title = who?.name ?: number
                whoIs.text = who?.name ?: number
                portrait.removeAllViews()
                portrait.addView(Look.face(this, 38, null, theirs))
                val standing: CharSequence = when {
                    who?.fingerprint == null -> getString(R.string.no_certificate)
                    foodjis.shut -> getString(R.string.foodjis_shut)
                    whose == null -> getString(R.string.certificate_not_held)
                    else -> android.text.SpannableStringBuilder("🔑 ").append(Look.standing(this, whose.validity))
                }
                under.text =
                    if (who?.name != null && who.name != number) android.text.SpannableStringBuilder("$number — ").append(standing) else standing
                // Asked from the list of conversations: whose certificate this number means.
                when (intent.getStringExtra(EXTRA_ASK).also { intent.removeExtra(EXTRA_ASK) }) {
                    ASK_TIE -> tie()
                    ASK_REFRESH -> who?.fingerprint?.let { found(it) }
                }
                // Nothing can be written to a sender that is not a number.
                val reachable = Phone.plain(number) != null
                field.isEnabled = reachable
                if (!reachable) field.setHint(R.string.no_answer)
                // With a certificate that can be locked for, that is how a message leaves unless told otherwise.
                if (first) { locks.isChecked = whose?.usable == true ; signs.isChecked = whose?.usable == true }
                paint()
                bubbles.notifyDataSetChanged()
                verifySigned()
                if (wanted > 0) {
                    found.indexOfFirst { it.at == wanted }.takeIf { it >= 0 }?.let { list.setSelection(it) }
                    wanted = 0
                }
            }
        }
    }

    /** The key of the identity foodjis holds, by the id the interface names it with; none until foodjis has said. */
    private var mine: Long? = null

    /** Asked once: whose identity this phone carries, to lock for it what is sent. */
    private fun whoAmI() {
        if (mine != null) return
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
        // Nothing to encrypt for without a certificate that stands; nothing to sign with until foodjis has said whose identity this is.
        locks.isEnabled = card?.usable == true
        signs.isEnabled = mine != null
        val worth = worth(mode)
        options.setBackgroundColor(Look.pale(worth))
        sender.background = Look.buttonFace(this, Look.colour(worth))
        sender.setTextColor(if (worth == Verdict.BLUE && !Look.dark) android.graphics.Color.WHITE else Look.INDIGO)
    }

    private fun send() {
        val written = field.text.toString()
        val with = carried
        if (written.isBlank() && with == null) return
        if (Phone.plain(number) == null) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        val mode = mode
        if (with != null) return sendWith(with, written, mode)
        if (mode == Mode.PLAIN) return leave(written, written, null)
        ask(request(mode, armored = true), written.toByteArray(), ::say) { answer ->
            val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                ?: return@ask say(getString(R.string.failed, "what came back is not a PGP message"))
            leave(armor, written, worth(mode))
        }
    }

    /** What foodjis is asked for a message leaving the way [mode] says. */
    private fun request(mode: Mode, armored: Boolean): Intent {
        val request = Intent(
            when (mode) {
                Mode.SIGN_AND_LOCK -> Provider.SIGN_AND_ENCRYPT
                Mode.LOCK -> Provider.ENCRYPT
                else -> Provider.SIGN
            },
        ).putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, armored)
        if (mode.locks) {
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
        fun post(words: String?, piece: Pdu.Part, verdict: Verdict?) = confirmMms {
            val transaction = "T" + java.lang.Long.toHexString(System.currentTimeMillis())
            val parts = listOfNotNull(words?.let { Pdu.Part.text(it) }, piece)
            runCatching { Outbox.send(this, number, Pdu.send(transaction, listOf(number), parts), transaction) }
                .onFailure { return@confirmMms say(getString(R.string.failed, it.message)) }
            if (verdict != null && words != null) Opened.keep(this, words, written, null, verdict, thread = here())
            field.setText("")
            carry(null)
            Toast.makeText(this, getString(R.string.handed_mms, with.name), Toast.LENGTH_SHORT).show()
            list.postDelayed({ if (shown) load() }, 1500)
        }
        if (mode == Mode.PLAIN) return post(written.takeIf { it.isNotBlank() }, Pdu.Part(with.type, with.name, with.bytes), null)
        fun piece(sealed: Provider.Answer.Done) = Pdu.Part(Pgp.TYPE, with.name + ".pgp", sealed.bytes)
        if (written.isBlank()) return ask(request(mode, armored = false), with.bytes, ::say) { post(null, piece(it), worth(mode)) }
        // Two things for foodjis to do, and one presentation of the key for
        // both: it is told of the file first, so that it knows what waits;
        // then asked for the words, which is when it asks for the key; and
        // what the key did for the file is then there to collect.
        provider.ask(request(mode, armored = false), with.bytes) { first ->
            if (isFinishing || isDestroyed) return@ask
            ask(request(mode, armored = true), written.toByteArray(), ::say) { answer ->
                val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                    ?: return@ask say(getString(R.string.failed, "what came back is not a PGP message"))
                if (first is Provider.Answer.Done) return@ask post(armor, piece(first), worth(mode))
                ask(request(mode, armored = false), with.bytes, ::say) { post(armor, piece(it), worth(mode)) }
            }
        }
    }

    /** An operator may charge for an MMS: the person who asked to be told is, before one leaves. */
    private fun confirmMms(go: () -> Unit) {
        if (Conf.preferMms(this) || !Conf.warnMms(this)) return go()
        AlertDialog.Builder(this)
            .setMessage(R.string.mms_ask)
            .setPositiveButton(R.string.mms_send) { _, _ -> go() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun carry(what: Media.Attachment?) {
        carried = what
        chip.visibility = if (what == null) View.GONE else View.VISIBLE
        chip.text = what?.let {
            val weight = it.bytes.size / 1024 + 1
            it.millis?.let { long -> getString(R.string.carrying_long, it.name, Media.clock(long), weight) } ?: getString(R.string.carrying, it.name, weight)
        }.orEmpty()
    }

    /**
     * What a message can carry: a photograph taken now, a picture, a
     * contact, where one is, any file. What the phone has no
     * application for is not offered.
     */
    private fun attach() {
        val choices = ArrayList<Pair<Int, () -> Unit>>()
        fun launch(asked: Intent, request: Int) {
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(asked, request) }.onFailure { say(getString(R.string.failed, it.message)) }
        }
        fun offered(asked: Intent) = asked.resolveActivity(packageManager) != null
        val camera = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (offered(camera)) {
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
        choices += R.string.attach_picture to { launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICTURE) }
        choices += R.string.attach_contact to { launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI), CONTACT) }
        choices += R.string.attach_place to { place() }
        choices += R.string.attach_file to { launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), FILE) }
        AlertDialog.Builder(this)
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
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
        val most = Outbox.heaviest(this) - 16 * 1024
        thread {
            val made = runCatching {
                when (request) {
                    PICTURE, SHOT -> Media.picture(this, from, most)
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
     * A text leaves as SMS while it takes few of them, as an MMS beyond --
     * beyond one when the person prefers MMS, beyond five otherwise: in both
     * it is text, which whoever receives it can read or copy. An operator may
     * charge for an MMS, and the person can ask to be told before one leaves.
     */
    private fun leave(text: String, written: String, verdict: Verdict?) {
        val mms = runCatching { Conf.asMms(Outbox.pieces(this, text), Conf.preferMms(this)) }.getOrDefault(false)
        fun go() {
            runCatching {
                if (!mms) Outbox.text(this, number, text)
                else {
                    val transaction = "T" + java.lang.Long.toHexString(System.currentTimeMillis())
                    Outbox.send(this, number, Pdu.send(transaction, listOf(number), listOf(Pdu.Part.text(text))), transaction)
                }
            }.onFailure { return say(getString(R.string.failed, it.message)) }
            if (verdict != null) Opened.keep(this, text, written, null, verdict, thread = here())
            field.setText("")
            // The phone writes what was sent a moment after taking it.
            list.postDelayed({ if (shown) load() }, 1500)
        }
        if (mms) confirmMms(::go) else go()
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
            return if (line.pieces.isNotEmpty()) pick(line) { handOver(it, Intent.ACTION_VIEW) }
            else textOf(line).takeIf { it.isNotEmpty() }?.let { select(it, armored = false) } ?: Unit
        }
        // The messages shown around it, as many on each side as set; of those, the ones encrypted and still closed.
        val near = Around.others(lines.size, at, Conf.around(this)).map { lines[it] }.filter(::lockedAndClosed)
        openAll(line, near)
    }

    /** Something a message holds that the key is to open: its words, or one of the parts it carries. */
    private sealed class Job {
        abstract val line: Store.Line
        class Words(override val line: Store.Line, val armor: String) : Job()
        class Part(override val line: Store.Line, val piece: Store.Piece, val bytes: ByteArray) : Job()
    }

    /**
     * A line's PGP message: the innermost opened so far ([Opened.inner]), or
     * none. One envelope opened onto another shows that one, closed, as a
     * message of its own -- grey, "encrypted for" --, and the person opens it
     * or not: nothing is opened further behind their back.
     */
    private fun armorOf(line: Store.Line): String? = Pgp.armorIn(line.body)?.let { Opened.inner(it) }

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
     * key: foodjis is told of everything first -- the words and every locked
     * part of each --, so that it knows what waits; then asked for the first
     * thing of the message pressed, which is when it asks for the key; and
     * what the key did for the rest is collected after. A signed message
     * needs no key and is read as soon as it is told of.
     */
    private fun openAll(chosen: Store.Line, near: List<Store.Line>) {
        thread {
            fun jobs(line: Store.Line): List<Job> {
                val found = ArrayList<Job>()
                armorOf(line)?.takeIf { Opened.words(it) == null }?.let { found += Job.Words(line, it) }
                for (piece in line.pieces.filter { isSealed(it) && Opened.piece(it.id) == null }) {
                    runCatching { contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull()
                        ?.let { found += Job.Part(line, piece, it) }
                }
                return found
            }
            val mine = jobs(chosen)
            val rest = mine.drop(1) + near.flatMap(::jobs)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val first = mine.firstOrNull() ?: return@runOnUiThread
                fun input(job: Job) = when (job) { is Job.Words -> job.armor.toByteArray() ; is Job.Part -> job.bytes }
                fun done(job: Job, answer: Provider.Answer.Done) = when (job) {
                    is Job.Words -> opened(job.armor, answer, offer = job.line === chosen && job.line.incoming)
                    is Job.Part -> got(job.piece, answer)
                }
                val waiting = rest.toMutableList()
                fun theFirst() {
                    val why: (String) -> Unit = { words -> if (first is Job.Words) closed(first.armor, words) else say(words) }
                    ask(Intent(Provider.DECRYPT_VERIFY), input(first), why) { answer ->
                        done(first, answer)
                        // What the key answered for the others is waiting: each is asked for again, and says nothing if it is not.
                        for (job in waiting) {
                            provider.ask(Intent(Provider.DECRYPT_VERIFY), input(job)) { again ->
                                if (again is Provider.Answer.Done && !isFinishing && !isDestroyed) done(job, again)
                            }
                        }
                    }
                }
                var left = rest.size
                if (left == 0) return@runOnUiThread theFirst()
                for (job in rest) {
                    provider.ask(Intent(Provider.DECRYPT_VERIFY), input(job)) { answer ->
                        if (answer is Provider.Answer.Done && !isFinishing && !isDestroyed) { done(job, answer) ; waiting.remove(job) }
                        if (--left == 0 && !isFinishing && !isDestroyed) theFirst()
                    }
                }
            }
        }
    }

    /** A part of an MMS that is itself a PGP message: by its label, or by the name given to what was locked. */
    private fun isSealed(piece: Store.Piece): Boolean {
        val name = piece.name.orEmpty().lowercase()
        return piece.type == Pgp.TYPE || name.endsWith(".pgp") || name.endsWith(".gpg") || name.endsWith(".asc")
    }

    /** A locked part, opened: kept with what it is, and a picture made small to sit in its bubble. */
    private fun got(piece: Store.Piece, answer: Provider.Answer.Done) {
        answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
        @Suppress("DEPRECATION")
        val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
        val certified = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED
        val told = Kind.of(answer.bytes.copyOf(minOf(answer.bytes.size, 32)), null)
        val name = piece.name.orEmpty().removeSuffix(".pgp").removeSuffix(".gpg").removeSuffix(".asc").ifEmpty { "file.${told.extension}" }
        val signer = signature?.takeIf { it.result != OpenPgpSignatureResult.RESULT_NO_SIGNATURE }
        Opened.keepPiece(
            this, piece.id, told.type, name, answer.bytes, Verdict.of(pieceLocked[piece.id] != false, certified),
            signed = Signer.of(signature), signer = signer?.keyId, eid = signer?.let { Signer.eid(it) }, thread = here(),
        )
        if (told.type.startsWith("audio/") || told.type == "video/3gpp") Media.lasting(answer.bytes)?.let { lasts[piece.id] = it }
        if (told.type.startsWith("image/")) openedThumbs[piece.id] = thumbnail(answer.bytes)
        bubbles.notifyDataSetChanged()
    }

    /** The pictures opened from locked parts, small, by the part they were. */
    private val openedThumbs = HashMap<Long, Bitmap?>()

    /** A picture opened from its lock, made small to sit in its bubble. */
    private fun thumbnail(bytes: ByteArray): Bitmap? = runCatching {
        val side = Look.dp(this, 200)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2 * side) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /**
     * A message that did not open -- locked for somebody else, most often --
     * is still a message: it can be handed on to whoever it is for, or kept.
     */
    private fun closed(armor: String, why: String) {
        AlertDialog.Builder(this)
            .setMessage(why)
            .setPositiveButton(R.string.forward) { _, _ ->
                startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, armor), null),
                )
            }
            .setNeutralButton(R.string.save) { _, _ ->
                saving = armor
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/pgp-encrypted").putExtra(Intent.EXTRA_TITLE, "message.asc"),
                    SAVE,
                )
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private var saving: String? = null

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        if (Saving.done(this, request, outcome, data, ::say)) return
        if (request == SHOT) {
            // The camera wrote where it was told: read it from there, without clearing it first.
            if (outcome == RESULT_OK) attached(SHOT, android.net.Uri.parse("content://$packageName.pdu/shot/photo.jpg"))
            return
        }
        if (request == PICTURE || request == CONTACT || request == FILE) {
            data?.data?.takeIf { outcome == RESULT_OK }?.let { attached(request, it) }
            return
        }
        if (request != SAVE) return
        val armor = saving.also { saving = null } ?: return
        val where = data?.data?.takeIf { outcome == RESULT_OK } ?: return
        runCatching { contentResolver.openOutputStream(where)?.use { it.write((armor + "\n").toByteArray()) } }
            .onSuccess { Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show() }
            .onFailure { say(getString(R.string.failed, it.message)) }
    }

    /** The words of a message as they can be read now: opened, or as they travelled. */
    private fun textOf(line: Store.Line): String {
        val armor = armorOf(line)
        return armor?.let { Opened.words(it)?.words } ?: line.body
    }

    /** Pressed long, a message says what can be done with it. */
    private fun about(at: Int) {
        val line = lines.getOrNull(at) ?: return
        val text = textOf(line)
        val choices = ArrayList<Pair<Int, () -> Unit>>()
        if (text.isNotEmpty()) {
            choices += R.string.copy_text to {
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.message), text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            choices += R.string.select_text to { select(text, armored = armorOf(line) != null && Opened.words(armorOf(line)!!) == null) }
        }
        if (line.pieces.isNotEmpty()) choices += R.string.open_file to { pick(line) { handOver(it, Intent.ACTION_VIEW) } }
        choices += R.string.forward to {
            if (text.isNotEmpty() || line.pieces.isEmpty()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            } else {
                pick(line) { handOver(it, Intent.ACTION_SEND) }
            }
        }
        choices += R.string.details to { details(line) }
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
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /** Which of the things a message carries: the only one, or the one the person names. */
    private fun pick(line: Store.Line, then: (Store.Piece) -> Unit) {
        if (line.pieces.size == 1) return then(line.pieces[0])
        AlertDialog.Builder(this)
            .setItems(line.pieces.map { it.name ?: it.type }.toTypedArray()) { _, which -> then(line.pieces[which]) }
            .show()
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

    /** Signed only, a message needs no key: it is verified as soon as it is shown, and never stays grey -- its words, and its parts. */
    private fun verifySigned() {
        for (piece in lines.flatMap { it.pieces }) {
            if (!isSealed(piece) || pieceLocked[piece.id] != false || Opened.piece(piece.id) != null || !verifyingParts.add(piece.id)) continue
            thread {
                val bytes = runCatching { contentResolver.openInputStream(piece.uri)?.use { it.readBytes() } }.getOrNull() ?: return@thread
                runOnUiThread {
                    provider.ask(Intent(Provider.DECRYPT_VERIFY), bytes) { answer ->
                        if (answer !is Provider.Answer.Done || isFinishing || isDestroyed) return@ask
                        got(piece, answer)
                        verifyingParts.remove(piece.id)
                    }
                }
            }
        }
        for (line in lines) {
            val armor = armorOf(line) ?: continue
            if (Pgp.isLocked(armor) || Opened.words(armor) != null || !verifying.add(armor)) continue
            provider.ask(Intent(Provider.DECRYPT_VERIFY), armor.toByteArray()) { answer ->
                if (answer !is Provider.Answer.Done || isFinishing || isDestroyed) return@ask
                opened(armor, answer, offer = line.incoming)
                verifying.remove(armor)
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
            runOnUiThread { if (!isFinishing && !isDestroyed) details(line, weights, signer, signerFace) }
        }
    }

    private fun details(line: Store.Line, weights: Map<Long, Long>, signer: Foodjis.Cert?, signerFace: Bitmap?) {
        val armor = armorOf(line)
        val precise = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
        val told = buildString {
            append(getString(if (line.incoming) R.string.detail_from else R.string.detail_to, line.address ?: number)).append('\n')
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
        fun person(face: Bitmap?, words: CharSequence) = column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Look.dp(context, 3), 0, Look.dp(context, 3))
                addView(Look.face(context, 32, null, face))
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
            for (id in keys) person(meant[id]?.face, (meant[id]?.name ?: getString(R.string.detail_unknown_key)) + "\n0x%016X".format(id))
        }
        sealOf(line)?.let { seal ->
            seal.signed?.let { Signer.words(this, it) }?.let { if (signer != null) person(signerFace, it) else text(it) }
            val more = buildString {
                seal.signer?.let { append(getString(R.string.detail_signer_key, "%016X".format(it))).append('\n') }
                signer?.let { append(getString(R.string.detail_signer_fpr, it.fingerprint.chunked(4).joinToString(" "))).append('\n') }
                (signer?.eid ?: seal.eid)?.let { append(getString(R.string.detail_signer_eid, it)).append('\n') }
            }
            if (more.isNotEmpty()) text(more.trimEnd())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.details)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /**
     * A message shown alone, its text to select from -- a word, a line, all
     * of it. In a window of its own: a list redraws its lines as it scrolls,
     * and a selection does not survive that.
     */
    private fun select(text: String, armored: Boolean) {
        val pad = Look.dp(this, 20)
        AlertDialog.Builder(this)
            .setView(
                android.widget.ScrollView(this).apply {
                    addView(
                        Look.words(context, text, size = if (armored) 12f else 16f).apply {
                            setTextIsSelectable(true)
                            setPadding(pad, pad, pad, 0)
                        },
                    )
                },
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** Whether the page has offered to tie the number to a signer's certificate already. */
    private var offered = false

    private fun opened(armor: String, answer: Provider.Answer.Done, offer: Boolean) {
        val signature = Signer.keep(this, armor, answer, here())
        bubbles.notifyDataSetChanged()
        // Opened onto another message: signed only, it is read at once, as any signed message is; locked, a press opens it.
        verifySigned()
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

    /** One thing a message carries: a picture shown small, a sound to play, or what it is called. */
    private fun shownPiece(piece: Store.Piece, at: Int): View {
        val opened = Opened.piece(piece.id)
        if (opened != null) {
            // Opened: shown as if it had travelled in clear, under its own name.
            // Made again from what is held open when this page did not open it: a conversation left and come back to
            // finds its pictures still open, and they are to look it.
            val small = if (opened.type.startsWith("image/")) openedThumbs.getOrPut(piece.id) { thumbnail(opened.bytes) } else null
            return when {
                small != null -> android.widget.ImageView(this).apply {
                    setImageBitmap(small)
                    adjustViewBounds = true
                    maxWidth = Look.dp(context, 220) ; maxHeight = Look.dp(context, 220)
                    setPadding(0, Look.dp(context, 4), 0, Look.dp(context, 4))
                }
                opened.type.startsWith("audio/") || opened.type == "video/3gpp" ->
                    Look.button(this, "▶ ${opened.name}" + lasted(piece)) { play(piece) }.apply { setOnLongClickListener { about(at) ; true } }
                else -> Look.words(this, "📎 ${opened.name}", size = 15f)
            }
        }
        val small = thumbs[piece.id]
        val name = piece.name ?: piece.type
        return when {
            small != null -> android.widget.ImageView(this).apply {
                setImageBitmap(small)
                adjustViewBounds = true
                maxWidth = Look.dp(context, 220) ; maxHeight = Look.dp(context, 220)
                setPadding(0, Look.dp(context, 4), 0, Look.dp(context, 4))
            }
            // The button takes the press, long ones too: a long one is handed to the message, whose menu it opens.
            piece.type.startsWith("audio/") -> Look.button(this, "▶ $name" + lasted(piece)) { play(piece) }.apply { setOnLongClickListener { about(at) ; true } }
            isSealed(piece) -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Look.words(context, (if (isLockedPiece(piece)) "⚿ " else "✍ ") + name, size = 15f, muted = true))
                // Whom it is for, unless the message's words already said it: one face each, not twice.
                val wordsFor = lines.getOrNull(at)?.let { armorOf(it) }?.takeIf { Pgp.isLocked(it) }?.let { Pgp.recipients(it) }.orEmpty()
                for (id in sealedFor[piece.id].orEmpty().filter { it !in wordsFor }) {
                    addView(View(context), LinearLayout.LayoutParams(Look.dp(context, 4), 1))
                    addView(Look.face(context, 22, null, meant[id]?.face))
                }
            }
            else -> Look.words(this, "📎 $name", size = 15f, muted = true)
        }
    }

    private fun lasted(piece: Store.Piece): String = lasts[piece.id]?.let { "  ·  " + Media.clock(it) }.orEmpty()

    private var player: android.media.MediaPlayer? = null

    /** Play a sound, or stop the one that plays: as it came, or as it was opened from its lock. */
    private fun play(piece: Store.Piece) {
        val playing = player
        player = null
        if (playing != null) { runCatching { playing.stop() ; playing.release() } ; return }
        runCatching {
            player = android.media.MediaPlayer().apply {
                val opened = Opened.piece(piece.id)
                if (opened != null) {
                    // What was locked is in memory only: a file of the cache, dropped as soon as it has been read.
                    val sound = java.io.File(cacheDir, "playing").apply { writeBytes(opened.bytes) }
                    sound.inputStream().use { setDataSource(it.fd) }
                    sound.delete()
                } else {
                    setDataSource(this@ThreadActivity, piece.uri)
                }
                setOnCompletionListener { release() ; if (player === it) player = null }
                prepare()
                start()
            }
        }.onFailure { player = null ; say(getString(R.string.failed, it.message)) }
    }

    private val bubbles = object : BaseAdapter() {
        override fun getCount() = lines.size
        override fun getItem(at: Int) = lines[at]
        override fun getItemId(at: Int) = at.toLong()

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val line = lines[at]
            val context = this@ThreadActivity
            val armor = armorOf(line)
            val read = armor?.let { Opened.words(it) }
            val sealed = line.pieces.filter(::isSealed)
            // Not opened yet: nothing tells what a PGP message is worth, signed or not, before the key does.
            val verdict = when {
                armor != null -> Opened.verdict(context, armor) ?: Verdict.GREY
                sealed.isNotEmpty() -> sealed.firstNotNullOfOrNull { Opened.pieceVerdict(context, it.id) } ?: Verdict.GREY
                else -> Verdict.RED
            }
            val closed = armor != null && read == null
            val locked = closed && Pgp.isLocked(armor!!)
            val words = when {
                armor == null -> line.body
                read != null -> read.words
                locked -> getString(R.string.locked_for)
                else -> getString(R.string.signed_press)
            }
            val under = buildString {
                append(DateUtils.getRelativeTimeSpanString(line.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL))
                if (verdict.encrypted) append("  ⚿")
                // Who signed: the words, or else what the first of its PGP parts said once opened -- a voice message has no words.
                (read?.signed ?: line.pieces.filter(::isSealed).firstNotNullOfOrNull { Opened.piece(it.id)?.signed })?.let { Signer.words(context, it) }
                    ?.let { append("  ✍ ").append(it) }
            }
            val pad = Look.dp(context, 10)
            val text = Look.words(context, if (closed) words else marked(words), size = 16f, muted = closed)
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.rounded(Look.pale(verdict), Look.dp(context, 14))
                setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                if (locked) {
                    // "Encrypted message for" and whom: a small face for each key the message names.
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(text)
                            for (id in Pgp.recipients(armor!!)) {
                                addView(View(context), LinearLayout.LayoutParams(Look.dp(context, 4), 1))
                                addView(Look.face(context, 22, null, meant[id]?.face))
                            }
                        },
                    )
                } else if (words.isNotEmpty() || line.pieces.isEmpty()) {
                    addView(text)
                }
                for (piece in line.pieces) addView(shownPiece(piece, at))
                addView(Look.words(context, under, size = 11f, muted = true))
            }
            // A mark of the full colour on the side the message came from, the bubble itself on that side.
            val mark = View(context).apply { background = Look.rounded(Look.colour(verdict), Look.dp(context, 2)) }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = (if (line.incoming) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
                setPadding(if (line.incoming) pad else pad * 5, pad / 3, if (line.incoming) pad * 5 else pad, pad / 3)
                val stripe = LinearLayout.LayoutParams(Look.dp(context, 4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = pad / 3 ; marginEnd = pad / 3
                }
                if (line.incoming) addView(mark, stripe)
                addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (!line.incoming) addView(mark, stripe)
                // The presses taken here, the whole row: a row holding a button -- a sound to play -- is no longer
                // one the list hands its presses to, and a long press beside the button did nothing.
                setOnClickListener { pressed(at) }
                setOnLongClickListener { about(at) ; true }
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
     * Nothing is written in the address book before the person has seen what
     * would be: the card foodjis makes of the certificate, a line each.
     */
    private fun offer(whose: Foodjis.Cert) {
        thread {
            val card = foodjis.vcard(whose.fingerprint)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (card == null) return@runOnUiThread say(getString(R.string.no_card, whose.name))
                val told = card.told().joinToString("\n") { "•  $it" } +
                    (if (card.photo != null) "\n•  " + getString(R.string.card_photo) else "") +
                    "\n•  🔑 " + (card.key ?: Fingerprint.address(whose.fingerprint))
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.tie_ask, number, whose.name))
                    .setMessage(
                        android.text.SpannableStringBuilder(getString(R.string.tie_ask_before)).append(" ")
                            .append(Look.standing(this, whose.validity)).append(getString(R.string.tie_ask_after)).append("\n\n").append(told),
                    )
                    .setPositiveButton(R.string.tie_write) { _, _ -> write(whose, card) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private var waiting: (() -> Unit)? = null

    private fun write(whose: Foodjis.Cert, vcard: Card) {
        if (NEEDED_TO_WRITE.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            waiting = { write(whose, vcard) }
            return requestPermissions(NEEDED_TO_WRITE, 2)
        }
        thread {
            val outcome = runCatching {
                Book.tie(this, number, vcard, vcard.key?.takeIf { Fingerprint.of(it) == whose.fingerprint } ?: Fingerprint.address(whose.fingerprint))
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

    private fun say(words: String) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        const val EXTRA_NUMBER = "number"

        /** The conversation, when the page is opened from the list of them. */
        const val EXTRA_THREAD = "thread"

        /** Opened for something to be done at once: say whose certificate the number means, or read it again from foodjis. */
        const val EXTRA_ASK = "ask"
        const val ASK_TIE = "tie"
        const val ASK_REFRESH = "refresh"

        /** The date of the message to show first. */
        const val EXTRA_AT = "at"
        private const val TIE = 1
        private const val SAVE = 4
        private const val PICTURE = 5
        private const val SHOT = 7
        private const val CONTACT = 8
        private const val FILE = 9
        private const val PLACE = 10
        private const val VOICE = 11

        /** How many of the latest messages have their pictures shown small. */
        private const val SHOWN_PICTURES = 40
        private val MESSAGES = android.net.Uri.parse("content://mms-sms/")
        private val NEEDED_TO_WRITE = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        private val VALID = setOf(
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED,
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
        )

    }
}
