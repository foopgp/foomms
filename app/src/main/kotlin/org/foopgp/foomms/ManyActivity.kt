package org.foopgp.foomms

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * One message for several people: written once, and sent to each of them, in
 * the conversation each already has.
 *
 * Encrypted, it is one PGP message locked for all of them together -- one
 * operation, and one use of the security key to sign it -- as a letter with
 * several addressees is: each can see, by the keys it names, who else it was
 * for.
 */
class ManyActivity : AskingActivity() {

    private class Whom(val number: String, val name: String, val cert: Foodjis.Cert?)

    private val numbers by lazy { intent.getStringArrayExtra(EXTRA_NUMBERS).orEmpty().toList() }
    private val foodjis by lazy { Foodjis.of(this) }
    private var whom: List<Whom> = emptyList()
    private var mine: Long? = null

    private lateinit var people: LinearLayout
    private lateinit var options: LinearLayout
    private lateinit var signs: Flip
    private lateinit var locks: Flip
    private lateinit var field: EditText
    private lateinit var sender: android.widget.Button

    /** What goes with the words to each, when something was shared: made ready once, sent to each in an MMS of their own. */
    private var carried: Media.Attachment? = null
    private lateinit var chip: android.widget.TextView
    private lateinit var chipRow: LinearLayout

    override fun onPause() {
        // Closed before anything was laid out -- fewer than two numbers --: nothing written to keep.
        if (::field.isInitialized) Drafts.keep(this, numbers, field.text.toString())
        super.onPause()
    }

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        if (numbers.size < 2) return finish()
        title = getString(R.string.many_title, numbers.size)
        people = Look.column(this)
        signs = Flip(this, "✍", R.string.switch_sign) { paint() }
        locks = Flip(this, "⚿", R.string.switch_lock) { paint() }
        field = EditText(this).apply {
            setHint(R.string.words_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        }
        sender = Look.button(this, "➤") { send() }.apply { textSize = 20f }
        val pad = Look.dp(this, 6)
        options = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 3, pad / 2, pad * 3, pad / 2)
            addView(signs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad * 3 })
            addView(locks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ScrollView(context).apply { addView(people) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                // What is attached, said above the field; only its cross takes it off.
                val shown = Look.carried(context) { carried = null ; chipRow.visibility = View.GONE }
                chip = shown.words
                chipRow = shown.row
                writing = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(chipRow)
                    addView(options)
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.BOTTOM
                            isBaselineAligned = false
                            setPadding(pad, pad, pad, pad)
                            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad })
                            addView(sender, LinearLayout.LayoutParams(Look.dp(context, 56), ViewGroup.LayoutParams.WRAP_CONTENT))
                        },
                    )
                }
                addView(writing)
            },
        )
        Edges.fit(this)
        // Words handed over with the page -- shared from another application, or a link's "sms_body" -- are already written.
        // Words handed over with the page, or else what was being written to these same people and not sent.
        val handed = if (state == null) intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") else null
        (handed ?: Drafts.of(this, numbers).takeIf { it.isNotEmpty() })?.let { field.setText(it) ; field.setSelection(it.length) }
        // A picture made small enough for an MMS, any other file if it fits, as in a conversation.
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { shared ->
            val most = Outbox.heaviest(this) - Outbox.MARGIN
            val picture = (intent.type ?: contentResolver.getType(shared).orEmpty()).startsWith("image/")
            thread {
                val made = runCatching { if (picture) Media.picture(this, shared, most) else Media.file(this, shared, most) }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (made == null) return@runOnUiThread say(getString(R.string.too_heavy, most / 1024))
                    carried = made
                    chip.text = getString(R.string.carrying, made.name, made.bytes.size / 1024 + 1)
                    chipRow.visibility = View.VISIBLE
                }
            }
        }
        provider.ask(Intent(Provider.GET_SIGN_KEY_ID), null) { answer ->
            if (answer is Provider.Answer.Done) mine = answer.says.getLongExtra(Provider.EXTRA_SIGN_KEY_ID, 0L).takeIf { it != 0L }
            if (!isFinishing && !isDestroyed) paint()
        }
        thread {
            val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            val found = numbers.map { number ->
                val who = if (contacts) Book.who(this, number) else null
                Whom(number, who?.name ?: number, who?.fingerprint?.let { foodjis.card(it) })
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                whom = found
                // Encrypted and signed to begin with, when it can be for all of them.
                locks.isChecked = found.all { it.cert?.usable == true }
                signs.isChecked = locks.isChecked
                people.removeAllViews()
                for (one in found) {
                    val standing: CharSequence = one.cert?.let { android.text.SpannableStringBuilder("🔑 ").append(Look.standing(this, it.validity)) }
                        ?: getString(R.string.no_certificate)
                    people.addView(Look.words(this, one.name, size = 17f).apply { setPadding(0, Look.dp(context, 8), 0, 0) })
                    people.addView(
                        Look.words(this, if (one.name != one.number) android.text.SpannableStringBuilder("${one.number} — ").append(standing) else standing, size = 13f, muted = true),
                    )
                }
                paint()
            }
        }
    }

    private fun worth(): Verdict {
        val locked = locks.isChecked && locks.isEnabled
        val signed = signs.isChecked && signs.isEnabled
        // Signed here is signed by the identity this phone carries: certified, whoever it goes to.
        return Verdict.of(locked, signed)
    }

    private fun paint() {
        // Encrypted for all of them or for none: a message some could not read is not sent as if all could.
        locks.isEnabled = whom.isNotEmpty() && whom.all { it.cert?.usable == true }
        signs.isEnabled = mine != null
        val worth = worth()
        options.setBackgroundColor(Look.pale(worth))
        sender.background = Look.buttonFace(this, Look.colour(worth))
        sender.setTextColor(if (worth == Verdict.BLUE && !Look.dark) android.graphics.Color.WHITE else Look.INDIGO)
    }

    /** [plainSaid]: the person was asked, and said it may leave unencrypted. */
    private fun send(plainSaid: Boolean = false) {
        val written = field.text.toString()
        val with = carried
        if ((written.isBlank() && with == null) || whom.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        if (!plainSaid && plainOrAsk(locks, ::paint) { send(plainSaid = true) }) return
        val locked = locks.isChecked && locks.isEnabled
        val signed = signs.isChecked && signs.isEnabled
        if (!locked && !signed) return leave(written.takeIf { it.isNotBlank() }, written, null, with?.let { Pdu.Part(it.type, it.name, it.bytes) })
        fun request(armored: Boolean) = Intent(if (locked && signed) Provider.SIGN_AND_ENCRYPT else if (locked) Provider.ENCRYPT else Provider.SIGN)
            .putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, armored).apply {
                // A file under its name, kept inside what is locked.
                if (!armored) with?.name?.let { putExtra(Provider.EXTRA_ORIGINAL_FILENAME, it) }
                if (locked) {
                    val keys = (whom.mapNotNull { it.cert?.fingerprint?.let(Fingerprint::keyId) } + listOfNotNull(mine.takeIf { Conf.toMyself(this@ManyActivity) })).distinct()
                    putExtra(Provider.EXTRA_KEY_IDS, keys.toLongArray())
                }
            }
        fun piece(sealed: Provider.Answer.Done) = Pdu.Part(Pgp.TYPE, Pgp.outerName(sealed.bytes), sealed.bytes)
        if (with == null) {
            return ask(request(true), written.toByteArray(), ::say) { answer ->
                val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                    ?: return@ask say(getString(R.string.failed, "what came back is not a PGP message"))
                leave(armor, written, worth(), null)
            }
        }
        if (written.isBlank()) return ask(request(false), with.bytes, ::say) { leave(null, written, worth(), piece(it)) }
        // The file told of first, so that one presentation of the key does both, as in a conversation.
        provider.ask(request(false), with.bytes) { first ->
            if (isFinishing || isDestroyed) return@ask
            ask(request(true), written.toByteArray(), ::say) { answer ->
                val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                    ?: return@ask say(getString(R.string.failed, "what came back is not a PGP message"))
                if (first is Provider.Answer.Done) return@ask leave(armor, written, worth(), piece(first))
                ask(request(false), with.bytes, ::say) { leave(armor, written, worth(), piece(it)) }
            }
        }
    }

    /**
     * The same to each, separately: a text as SMS or as an MMS by the same
     * rule as in a conversation; with a [piece], always an MMS of its own.
     */
    private fun leave(text: String?, written: String, verdict: Verdict?, piece: Pdu.Part?) {
        val mms = piece != null || runCatching { Conf.asMms(Outbox.pieces(this, text.orEmpty()), Conf.preferMms(this)) }.getOrDefault(false)
        fun go() {
            val failed = ArrayList<String>()
            for (one in whom) {
                runCatching {
                    if (!mms) Outbox.text(this, one.number, text.orEmpty())
                    else {
                        val transaction = "T" + java.lang.Long.toHexString(System.nanoTime())
                        val parts = listOfNotNull(text?.let { Pdu.Part.text(it) }, piece)
                        Outbox.send(this, listOf(one.number), Pdu.send(transaction, listOf(one.number), parts), transaction)
                    }
                }.onFailure { failed += one.name }
            }
            if (verdict != null && text != null) Opened.keep(this, text, written, null, verdict)
            if (failed.isNotEmpty()) return say(getString(R.string.many_failed, failed.joinToString(", ")))
            Toast.makeText(this, getString(R.string.many_sent, whom.size), Toast.LENGTH_LONG).show()
            field.setText("")
            finish()
        }
        if (mms && !Conf.preferMms(this) && Conf.warnMms(this)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.mms_ask)
                .setPositiveButton(R.string.mms_send) { _, _ -> go() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            go()
        }
    }

    private fun say(words: String) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        const val EXTRA_NUMBERS = "numbers"
    }
}
