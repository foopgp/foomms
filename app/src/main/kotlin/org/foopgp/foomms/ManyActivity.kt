package org.foopgp.foomms

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        Look.homeMark(this)
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
            },
        )
        Edges.fit(this)
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

    private fun send() {
        val written = field.text.toString()
        if (written.isBlank() || whom.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        val locked = locks.isChecked && locks.isEnabled
        val signed = signs.isChecked && signs.isEnabled
        if (!locked && !signed) return leave(written, written, null)
        val request = Intent(if (locked && signed) Provider.SIGN_AND_ENCRYPT else if (locked) Provider.ENCRYPT else Provider.SIGN)
            .putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, true)
        if (locked) {
            val keys = (whom.mapNotNull { it.cert?.fingerprint?.let(Fingerprint::keyId) } + listOfNotNull(mine.takeIf { Conf.toMyself(this) })).distinct()
            request.putExtra(Provider.EXTRA_KEY_IDS, keys.toLongArray())
        }
        ask(request, written.toByteArray(), ::say) { answer ->
            val armor = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
                ?: return@ask say(getString(R.string.failed, "what came back is not a PGP message"))
            leave(armor, written, worth())
        }
    }

    /** The same text to each, as SMS or as an MMS by the same rule as in a conversation. */
    private fun leave(text: String, written: String, verdict: Verdict?) {
        val mms = runCatching { Conf.asMms(Outbox.pieces(this, text), Conf.preferMms(this)) }.getOrDefault(false)
        fun go() {
            val failed = ArrayList<String>()
            for (one in whom) {
                runCatching {
                    if (!mms) Outbox.text(this, one.number, text)
                    else {
                        val transaction = "T" + java.lang.Long.toHexString(System.nanoTime())
                        Outbox.send(this, one.number, Pdu.send(transaction, listOf(one.number), listOf(Pdu.Part.text(text))), transaction)
                    }
                }.onFailure { failed += one.name }
            }
            if (verdict != null) Opened.keep(this, text, written, null, verdict)
            if (failed.isNotEmpty()) return say(getString(R.string.many_failed, failed.joinToString(", ")))
            Toast.makeText(this, getString(R.string.many_sent, whom.size), Toast.LENGTH_LONG).show()
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
