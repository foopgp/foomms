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

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER) ?: intent.data?.schemeSpecificPart.orEmpty() }
    private val foodjis by lazy { Foodjis.of(this) }

    private var person: Book.Person? = null
    private var card: Foodjis.Cert? = null
    private var face: Bitmap? = null
    private var lines: List<Store.Line> = emptyList()
    private var mode = Mode.PLAIN

    private lateinit var list: ListView
    private lateinit var field: EditText
    private lateinit var how: android.widget.Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Phone.plain(number) == null) return finish()
        title = number
        list = ListView(this).apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = true
            adapter = bubbles
            setOnItemClickListener { _, _, at, _ -> pressed(lines[at]) }
        }
        field = EditText(this).apply {
            setHint(R.string.words_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        }
        how = Look.button(this, mode.glyph) { next() }.apply { textSize = 20f }
        val send = Look.button(this, "➤") { send() }.apply { setTextColor(Look.ORANGE) ; textSize = 20f }
        val pad = Look.dp(this, 6)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(pad, pad, pad, pad)
            addView(how, LinearLayout.LayoutParams(Look.dp(context, 64), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad ; marginEnd = pad })
            addView(send, LinearLayout.LayoutParams(Look.dp(context, 56), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bar)
            },
        )
    }

    /** Shown, and so read by the person: a conversation open behind another screen reads nothing. */
    private var shown = false

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(self: Boolean) { if (shown) load() }
    }

    override fun onResume() {
        super.onResume()
        shown = true
        contentResolver.registerContentObserver(MESSAGES, true, arrivals)
        load(first = card == null && person == null)
    }

    override fun onPause() {
        shown = false
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
            val thread = runCatching { Store.thread(this, number) }.getOrNull()
            val found = runCatching { thread?.let { Store.lines(this, it) } }.getOrNull().orEmpty()
            if (shown) {
                thread?.let { Keep.read(this, it) }
                Notify.seen(this, number)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                person = who ; card = whose ; face = theirs ; lines = found
                title = who?.name ?: number
                actionBar?.subtitle = when {
                    who?.fingerprint == null -> getString(R.string.no_certificate)
                    foodjis.shut -> getString(R.string.foodjis_shut)
                    whose == null -> getString(R.string.certificate_not_held)
                    whose.certified -> getString(R.string.certificate_certified, whose.name)
                    else -> getString(R.string.certificate_held, whose.name, whose.validity)
                }
                // With a certificate that can be locked for, that is how a message leaves unless told otherwise.
                if (first) mode = if (whose?.usable == true) Mode.SIGN_AND_LOCK else Mode.PLAIN
                paint()
                bubbles.notifyDataSetChanged()
            }
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
        if (written.isBlank()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        val mode = mode
        if (mode == Mode.PLAIN) return leave(written, written, null)
        val request = Intent(
            when (mode) {
                Mode.SIGN_AND_LOCK -> Provider.SIGN_AND_ENCRYPT
                Mode.LOCK -> Provider.ENCRYPT
                else -> Provider.SIGN
            },
        ).putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, true)
        if (mode.locks) {
            // The certificate the address book ties to this number; with none, whoever foodjis knows by the number.
            val whose = person?.fingerprint
            if (whose != null) request.putExtra(Provider.EXTRA_KEY_IDS, longArrayOf(Fingerprint.keyId(whose)))
            else request.putExtra(Provider.EXTRA_USER_IDS, arrayOf("tel:$number"))
        }
        ask(request, written.toByteArray(), ::say) { answer ->
            val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                ?: return@ask say(getString(R.string.failed, "what came back is not a PGP message"))
            leave(armor, written, worth(mode))
        }
    }

    /**
     * A text leaves as SMS while it takes few of them, as an MMS beyond: in
     * both it is text, which whoever receives it can read or copy.
     */
    private fun leave(text: String, written: String, verdict: Verdict?) {
        runCatching {
            if (Outbox.pieces(this, text) <= MOST_SMS) Outbox.text(this, number, text)
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

    private fun pressed(line: Store.Line) {
        val armor = Pgp.armorIn(line.body) ?: return
        if (Opened.words(armor) != null) return
        ask(Intent(Provider.DECRYPT_VERIFY), armor.toByteArray(), ::say) { answer ->
            answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
            @Suppress("DEPRECATION")
            val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
            val certified = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED
            Opened.keep(this, armor, String(answer.bytes, Charsets.UTF_8), signed(signature), Verdict.of(Pgp.isLocked(armor), certified))
            bubbles.notifyDataSetChanged()
            // Signed, from a number that means no certificate yet: it may well mean the signer's.
            if (line.incoming && person?.fingerprint == null && signature?.result in VALID) {
                val keyId = signature!!.keyId
                thread {
                    val whose = foodjis.signer(keyId) ?: return@thread
                    runOnUiThread { if (!isFinishing && !isDestroyed) offer(whose) }
                }
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
            val words = when {
                armor == null -> line.body
                read != null -> read.words
                Pgp.isLocked(armor) -> getString(if (line.incoming) R.string.locked_press else R.string.locked_message)
                else -> getString(R.string.signed_press)
            }
            val under = buildString {
                append(DateUtils.getRelativeTimeSpanString(line.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL))
                if (verdict.encrypted) append("  ⚿")
                read?.signed?.let { append("  ✍ ").append(it) }
                if (line.attachments > 0) append("  ").append("📎".repeat(minOf(line.attachments, 3)))
            }
            val pad = Look.dp(context, 10)
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.rounded(Look.pale(verdict), Look.dp(context, 14))
                setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                addView(Look.words(context, words, size = 16f, muted = armor != null && read == null))
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
        menu.add(0, LET_IN, 1, R.string.let_in)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        TIE -> { tie() ; true }
        LET_IN -> { letIn() ; true }
        else -> super.onOptionsItemSelected(item)
    }

    /** foodjis asks the person whether this application may use the identity; its door opens with the answer. */
    private fun letIn() {
        ask(Intent(Provider.GET_SIGN_KEY_ID), null, ::say) {
            foodjis.forget()
            load()
        }
    }

    /** Say whose certificate this number means: chosen in foodjis's list, or named by its fingerprint. */
    private fun tie() {
        val asked = EditText(this).apply {
            setHint(R.string.fingerprint_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tie)
            .setMessage(R.string.tie_about)
            .setView(asked)
            .setPositiveButton(R.string.tie_choose) { _, _ -> choose() }
            .setNeutralButton(R.string.tie_named) { _, _ ->
                val fingerprint = Fingerprint.of(asked.text.toString()) ?: return@setNeutralButton say(getString(R.string.not_a_fingerprint))
                found(fingerprint)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** foodjis shows its own list of contacts; the first ticked is whose certificate the number means. */
    private fun choose() {
        ask(Intent(Provider.GET_KEY_IDS), null, ::say) { answer ->
            val keyId = answer.says.getLongArrayExtra(Provider.EXTRA_KEY_IDS)?.firstOrNull() ?: return@ask
            found("%016X".format(keyId))
        }
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
                    .setMessage(getString(R.string.tie_ask_about, whose.validity) + "\n\n" + told)
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

    private fun say(words: String) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        private const val TIE = 1
        private const val LET_IN = 2
        private val MESSAGES = android.net.Uri.parse("content://mms-sms/")
        private val NEEDED_TO_WRITE = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        private val VALID = setOf(
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED,
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
        )

        /** Beyond this many SMS, a text leaves as one MMS. */
        private const val MOST_SMS = 5
    }
}
