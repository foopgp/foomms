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
import android.view.Menu
import android.view.MenuItem
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
 * one received -- and until then says what it is. Beside the field, a button
 * says how the next message will leave: signed and locked, locked, signed,
 * or as it is.
 */
class ThreadActivity : AskingActivity() {

    private enum class Mode(val glyph: String, val locks: Boolean, val signs: Boolean) {
        SIGN_AND_LOCK("✍⚿", true, true),
        LOCK("⚿", true, false),
        SIGN("✍", false, true),
        PLAIN("✉", false, false),
    }

    /** Whom this conversation is with, for a page opened from this one. */
    val talkingTo: String get() = number

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER) ?: intent.data?.schemeSpecificPart.orEmpty() }
    private val foodjis by lazy { Foodjis.of(this) }

    private var person: Book.Person? = null
    private var card: Foodjis.Cert? = null
    private var face: Bitmap? = null
    private var lines: List<Store.Line> = emptyList()
    private var mode = Mode.PLAIN

    /** Who a key a message names belongs to, as far as foodjis knows. */
    private class For(val name: String, val face: Bitmap?)

    private var meant: Map<Long, For?> = emptyMap()

    /** The message whose text can be selected, and so copied: the one pressed long, or none. */

    private lateinit var list: ListView
    private lateinit var field: EditText
    private lateinit var how: android.widget.Button
    private lateinit var chip: android.widget.TextView

    /** What the next message carries beside its words: a picture, a sound. */
    private var carried: Media.Attachment? = null

    /** The pictures of this conversation, small, by the part they are. */
    private var thumbs: Map<Long, Bitmap?> = emptyMap()

    /** The message to show first, when the page was opened from a search: its date, taken once. */
    private var wanted = 0L

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        dressed = Look.dark
        super.onCreate(state)
        if (number.isBlank()) return finish()
        title = number
        wanted = intent.getLongExtra(EXTRA_AT, 0L)
        list = ListView(this).apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = true
            adapter = bubbles
            setOnItemClickListener { _, _, at, _ -> pressed(at) }
            setOnItemLongClickListener { _, _, at, _ -> select(at) ; true }
        }
        field = EditText(this).apply {
            setHint(R.string.words_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        }
        how = Look.button(this, mode.glyph) { next() }.apply { textSize = 20f }
        val send = Look.button(this, "➤") { send() }.apply { setTextColor(Look.ORANGE) ; textSize = 20f }
        val pad = Look.dp(this, 6)
        val add = Look.button(this, "+") { attach() }.apply { textSize = 22f }
        // What is attached, said above the field; pressed, it is taken off.
        chip = Look.words(this, "", size = 14f, muted = true).apply {
            setPadding(pad * 3, pad, pad * 3, 0)
            visibility = View.GONE
            setOnClickListener { carry(null) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(pad, pad, pad, pad)
            addView(how, LinearLayout.LayoutParams(Look.dp(context, 64), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(add, LinearLayout.LayoutParams(Look.dp(context, 44), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = pad })
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad ; marginEnd = pad })
            addView(send, LinearLayout.LayoutParams(Look.dp(context, 56), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(chip)
                addView(bar)
            },
        )
    }

    /** Shown, and so read by the person: a conversation open behind another screen reads nothing. */
    private var shown = false

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(self: Boolean) { if (shown) load() }
    }

    private var dressed = false

    override fun onResume() {
        super.onResume()
        if (Look.wantsDark(this) != dressed) return recreate()
        Look.dress(this)
        shown = true
        whoAmI()
        contentResolver.registerContentObserver(MESSAGES, true, arrivals)
        load(first = card == null && person == null)
    }

    override fun onPause() {
        shown = false
        player?.let { runCatching { it.stop() ; it.release() } }
        player = null
        // Set never to be remembered, what was opened here is gone with the page.
        if (Opened.holdFor == 0L) Opened.forget()
        contentResolver.unregisterContentObserver(arrivals)
        super.onPause()
    }

    /** Who this is, what foodjis says of their certificate, and the messages. */
    private fun load(first: Boolean = false) {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        thread {
            val who = if (contacts) Book.who(this, number) else null
            val whose = who?.fingerprint?.let { foodjis.card(it) }
            val theirs = who?.fingerprint?.takeIf { who.photo == null }?.let { foodjis.face(it) }
            // The conversation it was opened from, when it was opened from one: a sender that is a name and not a
            // number -- a bank, a shop -- has a conversation all the same.
            val thread = intent.getLongExtra(EXTRA_THREAD, -1L).takeIf { it >= 0 } ?: runCatching { Store.thread(this, number) }.getOrNull()
            val found = runCatching { thread?.let { Store.lines(this, it) } }.getOrNull().orEmpty()
            // Whom each locked message is for, by the keys it names: a face each, asked of foodjis once.
            val named = found.mapNotNull { Pgp.armorIn(it.body) }.filter { Pgp.isLocked(it) }.flatMap { Pgp.recipients(it) }.distinct()
            val small = found.takeLast(SHOWN_PICTURES).flatMap { it.pieces }.filter { it.type.startsWith("image/") }
                .associate { piece -> piece.id to (thumbs[piece.id] ?: Media.thumbnail(this, piece.uri, Look.dp(this, 200))) }
            val theirFaces = named.associateWith { id -> foodjis.signer(id)?.let { whose -> For(whose.name, foodjis.face(whose.fingerprint)) } }
            if (shown) {
                thread?.let { Keep.read(this, it) }
                Notify.seen(this, number)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                person = who ; card = whose ; face = theirs ; lines = found ; meant = theirFaces ; thumbs = small
                // A conversation is with a number: the name it has in the address book, and under it the number
                // and what its certificate is worth. With no name, the number is the title.
                title = who?.name ?: number
                val standing = when {
                    who?.fingerprint == null -> getString(R.string.no_certificate)
                    foodjis.shut -> getString(R.string.foodjis_shut)
                    whose == null -> getString(R.string.certificate_not_held)
                    else -> "🔑 " + standing(whose.validity)
                }
                actionBar?.subtitle = if (who?.name != null && who.name != number) "$number — $standing" else standing
                // Nothing can be written to a sender that is not a number.
                val reachable = Phone.plain(number) != null
                field.isEnabled = reachable
                if (!reachable) field.setHint(R.string.no_answer)
                // With a certificate that can be locked for, that is how a message leaves unless told otherwise.
                if (first) mode = if (whose?.usable == true) Mode.SIGN_AND_LOCK else Mode.PLAIN
                paint()
                bubbles.notifyDataSetChanged()
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
        }
    }

    /** What the next message will be worth to who receives it. */
    private fun worth(mode: Mode): Verdict = Verdict.of(mode.locks, (mode.locks || mode.signs) && card?.certified == true)

    private fun paint() {
        how.text = mode.glyph
        how.background = Look.rounded(Look.pale(worth(mode)), Look.dp(this, 10))
    }

    private fun next() {
        mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
        paint()
        val says = when (mode) {
            Mode.SIGN_AND_LOCK -> R.string.mode_sign_lock
            Mode.LOCK -> R.string.mode_lock
            Mode.SIGN -> R.string.mode_sign
            Mode.PLAIN -> R.string.mode_plain
        }
        Toast.makeText(this, says, Toast.LENGTH_SHORT).show()
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
            if (verdict != null && words != null) Opened.keep(this, words, written, null, verdict)
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
        chip.text = what?.let { getString(R.string.carrying, it.name, it.bytes.size / 1024 + 1) }.orEmpty()
    }

    /** A picture from the phone, or a sound recorded now. */
    private fun attach() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.attach_picture), getString(R.string.attach_sound))) { _, which ->
                val picture = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                val record = Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                // A phone with no recorder of its own still has sounds to choose among.
                val sound = if (record.resolveActivity(packageManager) != null) record
                else Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                @Suppress("DEPRECATION")
                runCatching { startActivityForResult(if (which == 0) picture else sound, if (which == 0) PICTURE else SOUND) }
                    .onFailure { say(getString(R.string.failed, it.message)) }
            }
            .show()
    }

    private fun attached(request: Int, from: android.net.Uri) {
        // Room for the message around it, and for what locking adds.
        val most = Outbox.heaviest(this) - 16 * 1024
        thread {
            val made = runCatching {
                if (request == PICTURE) Media.picture(this, from, most) else Media.file(this, from, most)
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
            if (verdict != null) Opened.keep(this, text, written, null, verdict)
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
        val chosen = lines.getOrNull(at)?.let { Pgp.armorIn(it.body) } ?: return
        if (Opened.words(chosen) != null) return
        val others = Around.others(lines.size, at, Conf.around(this)) { other ->
            Pgp.armorIn(lines[other].body)?.let { it != chosen && Opened.words(it) == null } == true
        }.mapNotNull { Pgp.armorIn(lines[it].body) }.distinct()
        var left = others.size
        fun theChosen() {
            ask(Intent(Provider.DECRYPT_VERIFY), chosen.toByteArray(), { closed(chosen, it) }) { answer ->
                opened(chosen, answer, offer = lines.getOrNull(at)?.incoming == true)
                // What the key answered for the others is waiting: each is asked for again, and says nothing if it is not.
                others.filter { Opened.words(it) == null }.forEach { quietly(it) }
            }
        }
        if (left == 0) return theChosen()
        for (other in others) {
            provider.ask(Intent(Provider.DECRYPT_VERIFY), other.toByteArray()) { answer ->
                // A signed message needs no key and is read at once.
                if (answer is Provider.Answer.Done && !isFinishing && !isDestroyed) opened(other, answer, offer = false)
                if (--left == 0 && !isFinishing && !isDestroyed) theChosen()
            }
        }
    }

    private fun quietly(armor: String) {
        provider.ask(Intent(Provider.DECRYPT_VERIFY), armor.toByteArray()) { answer ->
            if (answer is Provider.Answer.Done && !isFinishing && !isDestroyed) opened(armor, answer, offer = false)
        }
    }

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
        if (request == PICTURE || request == SOUND) {
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

    /**
     * Pressed long, a message is shown alone, its text to select from -- a
     * word, a line, all of it -- and to copy. A message still closed shows
     * what travelled: its armor, to be opened elsewhere.
     *
     * In a window of its own: a list redraws its lines as it scrolls, and a
     * selection does not survive that.
     */
    private fun select(at: Int) {
        val line = lines.getOrNull(at) ?: return
        val armor = Pgp.armorIn(line.body)
        val text = armor?.let { Opened.words(it)?.words } ?: armor ?: line.body
        val pad = Look.dp(this, 20)
        AlertDialog.Builder(this)
            .setView(
                android.widget.ScrollView(this).apply {
                    addView(
                        Look.words(context, text, size = if (text === armor) 12f else 16f).apply {
                            setTextIsSelectable(true)
                            setPadding(pad, pad, pad, 0)
                        },
                    )
                },
            )
            .setPositiveButton(R.string.copy_all) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.message), text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.forward) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun opened(armor: String, answer: Provider.Answer.Done, offer: Boolean) {
        answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
        @Suppress("DEPRECATION")
        val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
        val certified = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED
        Opened.keep(this, armor, String(answer.bytes, Charsets.UTF_8), signed(signature), Verdict.of(Pgp.isLocked(armor), certified))
        bubbles.notifyDataSetChanged()
        // Signed, from a number that means no certificate yet: it may well mean the signer's.
        if (offer && person?.fingerprint == null && signature?.result in VALID) {
            val keyId = signature!!.keyId
            thread {
                val whose = foodjis.signer(keyId) ?: return@thread
                runOnUiThread { if (!isFinishing && !isDestroyed) offer(whose) }
            }
        }
    }

    private fun signed(signature: OpenPgpSignatureResult?): String = when (signature?.result) {
        null, OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> getString(R.string.unsigned)
        OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> getString(R.string.signed_certified, Signer.named(signature))
        OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> getString(R.string.signed_other, Signer.named(signature))
        OpenPgpSignatureResult.RESULT_KEY_MISSING -> getString(R.string.signed_unknown)
        else -> getString(R.string.signed_broken)
    }

    /** One thing a message carries: a picture shown small, a sound to play, or what it is called. */
    private fun shownPiece(piece: Store.Piece): View {
        val small = thumbs[piece.id]
        val name = piece.name ?: piece.type
        return when {
            small != null -> android.widget.ImageView(this).apply {
                setImageBitmap(small)
                adjustViewBounds = true
                maxWidth = Look.dp(context, 220) ; maxHeight = Look.dp(context, 220)
                setPadding(0, Look.dp(context, 4), 0, Look.dp(context, 4))
            }
            piece.type.startsWith("audio/") -> Look.button(this, "▶ $name") { play(piece) }
            piece.type == Pgp.TYPE || name.endsWith(".pgp") || name.endsWith(".gpg") -> Look.words(this, "⚿ $name", size = 15f, muted = true)
            else -> Look.words(this, "📎 $name", size = 15f, muted = true)
        }
    }

    private var player: android.media.MediaPlayer? = null

    /** Play a sound, or stop the one that plays. */
    private fun play(piece: Store.Piece) {
        val playing = player
        player = null
        if (playing != null) { runCatching { playing.stop() ; playing.release() } ; return }
        runCatching {
            player = android.media.MediaPlayer().apply {
                setDataSource(this@ThreadActivity, piece.uri)
                setOnCompletionListener { release() ; if (player === it) player = null }
                prepare()
                start()
            }
        }.onFailure { player = null ; say(getString(R.string.failed, it.message)) }
    }

    /** The last message still locked, which is the one that says how they open. */
    private fun lastLocked(): Int =
        lines.indexOfLast { line -> Pgp.armorIn(line.body)?.let { Pgp.isLocked(it) && Opened.words(it) == null } == true }

    private val bubbles = object : BaseAdapter() {
        override fun getCount() = lines.size
        override fun getItem(at: Int) = lines[at]
        override fun getItemId(at: Int) = at.toLong()

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val line = lines[at]
            val context = this@ThreadActivity
            val armor = Pgp.armorIn(line.body)
            val read = armor?.let { Opened.words(it) }
            val verdict = when {
                armor == null -> Verdict.RED
                else -> Opened.verdict(context, armor) ?: Verdict.of(Pgp.isLocked(armor), false)
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
                read?.signed?.let { append("  ✍ ").append(it) }
            }
            val pad = Look.dp(context, 10)
            val text = Look.words(context, words, size = 16f, muted = closed)
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
                    // Said once, on the last of them: every closed message opens the same way.
                    if (at == lastLocked()) addView(Look.words(context, getString(R.string.locked_help), size = 13f, muted = true))
                } else if (words.isNotEmpty() || line.pieces.isEmpty()) {
                    addView(text)
                }
                for (piece in line.pieces) addView(shownPiece(piece))
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
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, TIE, 0, R.string.tie)
        // Only while foodjis keeps its door shut: once let in, there is nothing to ask.
        if (foodjis.shut) menu.add(0, LET_IN, 1, R.string.let_in)
        Menus.build(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        TIE -> { tie() ; true }
        LET_IN -> { letIn() ; true }
        else -> Menus.select(this, item) || super.onOptionsItemSelected(item)
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
                    .setMessage(getString(R.string.tie_ask_about, standing(whose.validity)) + "\n\n" + told)
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
        val then = waiting.also { waiting = null }
        if (request == 2 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) then?.invoke()
    }

    /** A certificate's standing, as foodjis words it, in the person's language. */
    private fun standing(validity: String): String = getString(
        when (validity) {
            "certified" -> R.string.standing_certified
            "uncertified" -> R.string.standing_uncertified
            "expired" -> R.string.standing_expired
            "revoked" -> R.string.standing_revoked
            else -> R.string.standing_broken
        },
    )

    private fun say(words: String) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        const val EXTRA_NUMBER = "number"

        /** The conversation, when the page is opened from the list of them. */
        const val EXTRA_THREAD = "thread"

        /** The date of the message to show first. */
        const val EXTRA_AT = "at"
        private const val TIE = 1
        private const val LET_IN = 2
        private const val SAVE = 4
        private const val PICTURE = 5
        private const val SOUND = 6

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
