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
    private lateinit var carriedShown: Look.Carried

    /** The draft as it was read when the page opened: kept again only once it has changed. */
    private var draftRead = ""

    override fun onPause() {
        Preview.stop()
        // Closed before anything was laid out -- fewer than two numbers --: nothing written to keep.
        if (::field.isInitialized) {
            val words = field.text.toString()
            if (words != draftRead) {
                draftRead = words
                val context = applicationContext
                val those = numbers
                // In the background: a mark is written in the conversation of each.
                thread { Drafts.keepEach(context, those, words) }
            }
        }
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
                val shown = Look.carried(context) { carried = null ; chipRow.visibility = View.GONE ; Preview.show(this@ManyActivity, carriedShown, null, title.toString()) }
                carriedShown = shown
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
        draftRead = Drafts.of(this, numbers)
        (handed ?: draftRead.takeIf { it.isNotEmpty() })?.let { field.setText(it) ; field.setSelection(it.length) }
        // Some of them have a draft of their own, which the same to each will take the place of: said once, first.
        Drafts.ownOf(this, numbers).takeIf { it.isNotEmpty() && state == null }?.let { own ->
            AlertDialog.Builder(this)
                .setMessage(resources.getQuantityString(R.plurals.each_over_drafts, own.size, own.size))
                .setPositiveButton(R.string.draft_replace, null)
                // Left as they were: the words handed over are not kept in their place on the way out ([onPause]).
                .setNegativeButton(android.R.string.cancel) { _, _ -> draftRead = field.text.toString() ; finish() }
                .setCancelable(false)
                .show()
        }
        // A picture made small enough for an MMS, any other file if it fits, as in a conversation.
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { shared ->
            val most = Outbox.heaviest(this) - Outbox.MARGIN
            val picture = (intent.type ?: contentResolver.getType(shared).orEmpty()).startsWith("image/")
            thread {
                val made = runCatching { if (picture) Media.picture(this, shared, most, Media.pictureName(this, shared)) else Media.file(this, shared, most) }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (made == null) return@runOnUiThread say(getString(R.string.too_heavy, most / 1024))
                    carried = made
                    chip.text = getString(R.string.carrying, made.name, made.bytes.size / 1024 + 1)
                    chipRow.visibility = View.VISIBLE
                    Preview.show(this, carriedShown, made, title.toString())
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
        if ((written.isBlank() && with == null) || whom.isEmpty() || isFinishing) return
        // Pressed twice: once. Asked again after a question, it is the same sending.
        if (!plainSaid && Twice.again(written, with, whom.toList())) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        // MMS off: no part -- said before anything else.
        if (with != null && !Conf.mms(this)) return say(getString(R.string.mms_off))
        if (!plainSaid && plainOrAsk(locks, ::paint) { send(plainSaid = true) }) return
        val locked = locks.isChecked && locks.isEnabled
        val signed = signs.isChecked && signs.isEnabled
        val file = with?.let { Pdu.Part(it.type, it.name, it.bytes) }
        fun same(text: String?, piece: Pdu.Part?) = whom.map { ToEach.Copy(it.number, it.name, text, piece) }
        if (!locked && !signed) return leave(same(written.takeIf { it.isNotBlank() }, file), written, null)
        // Encrypted: a copy for each alone -- and for oneself, unless the person said not --, none of them naming the
        // others' keys. Signed only: one, the same for all.
        fun request(armored: Boolean, one: Whom?) = Intent(if (locked && signed) Provider.SIGN_AND_ENCRYPT else if (locked) Provider.ENCRYPT else Provider.SIGN)
            .putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, armored).apply {
                // A file under its name, kept inside what is locked.
                if (!armored) with?.name?.let { putExtra(Provider.EXTRA_ORIGINAL_FILENAME, it) }
                if (locked && one != null) {
                    val keys = (listOfNotNull(one.cert?.fingerprint?.let(Fingerprint::keyId)) + listOfNotNull(mine.takeIf { Conf.toMyself(this@ManyActivity) })).distinct()
                    putExtra(Provider.EXTRA_KEY_IDS, keys.toLongArray())
                }
            }
        fun piece(sealed: Provider.Answer.Done) = Pdu.Part(Pgp.TYPE, Pgp.outerName(sealed.bytes), sealed.bytes)
        fun armor(answer: Provider.Answer.Done) = Pgp.armorIn(String(answer.bytes, Charsets.US_ASCII))
        // Encrypted and signed: signed once, then each copy locked with that signature inside -- when foodjis takes one.
        if (locked && signed) return signsOnce { once -> if (once) signedOnce(written, with) else eachSigned(written, with, ::request, ::piece, ::armor) }
        eachSigned(written, with, ::request, ::piece, ::armor)
    }

    /** What foodjis is asked for every copy, signing each, or one for all when only signed. */
    private fun eachSigned(
        written: String, with: Media.Attachment?, request: (Boolean, Whom?) -> Intent,
        piece: (Provider.Answer.Done) -> Pdu.Part, armor: (Provider.Answer.Done) -> String?,
    ) {
        val locked = locks.isChecked && locks.isEnabled
        fun same(text: String?, part: Pdu.Part?) = whom.map { ToEach.Copy(it.number, it.name, text, part) }
        val each = if (locked) whom else listOf(null)
        // What foodjis is asked: for each copy, its words and its file.
        val jobs = ArrayList<Pair<Intent, ByteArray>>()
        for (one in each) {
            if (written.isNotBlank()) jobs += request(true, one) to written.toByteArray()
            if (with != null) jobs += request(false, one) to with.bytes
        }
        all(jobs) { done ->
            var at = 0
            val made = each.map { one ->
                val words = if (written.isNotBlank()) armor(done[at++]) ?: return@all say(Foodjis.says(this, "what came back is not a PGP message")) else null
                val sealed = if (with != null) piece(done[at++]) else null
                one to (words to sealed)
            }
            val copies = if (locked) made.map { (one, it) -> ToEach.Copy(one!!.number, one.name, it.first, it.second) } else same(made[0].second.first, made[0].second.second)
            leave(copies, written, worth())
        }
    }

    /**
     * Encrypted and signed, signed once: the words and the file each signed
     * by the key -- one presentation of it --, then a copy for each alone,
     * locked with those signatures inside, which needs no key.
     */
    private fun signedOnce(written: String, with: Media.Attachment?) {
        val inputs = listOfNotNull(written.toByteArray().takeIf { written.isNotBlank() }, with?.bytes)
        val signing = inputs.map { Intent(Provider.DETACHED_SIGN).putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, false) to it }
        all(signing) { signed ->
            val signatures = signed.map { it.says.getByteArrayExtra(Provider.RESULT_DETACHED_SIGNATURE) ?: return@all say(Foodjis.says(this, "no signature came back")) }
            val jobs = ArrayList<Pair<Intent, ByteArray>>()
            for (one in whom) {
                val keys = (listOfNotNull(one.cert?.fingerprint?.let(Fingerprint::keyId)) + listOfNotNull(mine.takeIf { Conf.toMyself(this) })).distinct().toLongArray()
                var at = 0
                if (written.isNotBlank()) jobs += Intent(Provider.ENCRYPT).putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, true)
                    .putExtra(Provider.EXTRA_KEY_IDS, keys).putExtra(Provider.EXTRA_SIGNATURE, signatures[at++]) to written.toByteArray()
                if (with != null) jobs += Intent(Provider.ENCRYPT).putExtra(Provider.EXTRA_REQUEST_ASCII_ARMOR, false).putExtra(Provider.EXTRA_ORIGINAL_FILENAME, with.name)
                    .putExtra(Provider.EXTRA_KEY_IDS, keys).putExtra(Provider.EXTRA_SIGNATURE, signatures[at]) to with.bytes
            }
            all(jobs) { done ->
                var at = 0
                val copies = whom.map { one ->
                    val words = if (written.isNotBlank()) Pgp.armorIn(String(done[at++].bytes, Charsets.US_ASCII)) ?: return@all say(Foodjis.says(this, "what came back is not a PGP message")) else null
                    val sealed = if (with != null) done[at++].let { Pdu.Part(Pgp.TYPE, Pgp.outerName(it.bytes), it.bytes) } else null
                    ToEach.Copy(one.number, one.name, words, sealed)
                }
                leave(copies, written, worth())
            }
        }
    }

    /**
     * Whether foodjis lays a signature made already inside a lock: asked
     * once, with one that is not a signature -- refused, it reads the extra;
     * taken, it does not, and every copy is signed on its own.
     */
    private fun signsOnce(then: (Boolean) -> Unit) {
        knowsSignature?.let { return then(it) }
        val key = mine ?: return then(false)
        provider.ask(
            Intent(Provider.ENCRYPT).putExtra(Provider.EXTRA_KEY_IDS, longArrayOf(key)).putExtra(Provider.EXTRA_SIGNATURE, byteArrayOf(0)),
            "?".toByteArray(),
        ) { answer ->
            val knows = answer is Provider.Answer.Failed
            knowsSignature = knows
            if (!isFinishing && !isDestroyed) then(knows)
        }
    }

    private var knowsSignature: Boolean? = null

    /**
     * Every one of [jobs] asked of foodjis, and one presentation of the key
     * for all: the others told of first, so that foodjis knows what waits;
     * then the first, which is when the key is asked for; then the others
     * collected. [then] hears all the answers, in order.
     */
    private fun all(jobs: List<Pair<Intent, ByteArray>>, then: (List<Provider.Answer.Done>) -> Unit) {
        if (jobs.isEmpty()) return
        val done = arrayOfNulls<Provider.Answer.Done>(jobs.size)
        fun collect() {
            val missing = jobs.indices.filter { done[it] == null }
            if (missing.isEmpty()) return then(done.map { it!! })
            var left = missing.size
            for (at in missing) {
                provider.ask(Intent(jobs[at].first), jobs[at].second) { answer ->
                    if (isFinishing || isDestroyed) return@ask
                    if (answer is Provider.Answer.Done) done[at] = answer
                    if (--left == 0) {
                        if (done.all { it != null }) then(done.map { it!! }) else say(Foodjis.says(this, "it did not answer for every copy"))
                    }
                }
            }
        }
        fun first() = ask(Intent(jobs[0].first), jobs[0].second, ::say) { done[0] = it ; collect() }
        var told = jobs.size - 1
        if (told == 0) return first()
        for (at in 1 until jobs.size) {
            provider.ask(Intent(jobs[at].first), jobs[at].second) { answer ->
                if (isFinishing || isDestroyed) return@ask
                if (answer is Provider.Answer.Done) done[at] = answer
                if (--told == 0) first()
            }
        }
    }


    /**
     * The same to each, separately: a text as SMS or as an MMS by the same
     * rule as in a conversation; with a [piece], always an MMS of its own.
     */
    private fun leave(copies: List<ToEach.Copy>, written: String, verdict: Verdict?) {
        val pieces = copies.maxOf { runCatching { Outbox.pieces(this, it.text.orEmpty()) }.getOrDefault(1) }
        val mms = copies.any { it.piece != null } || Conf.asMms(this, pieces)
        fun go() {
            // Each copy's words, kept as written, for the conversation it is filed in -- its recipient's, as Android
            // files a message to one number --: every conversation shows what was sent, opened, and counts down.
            if (verdict != null) for (one in copies) one.text?.let { armor ->
                val thread = runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(this, one.number) }.getOrDefault(Opened.NO_THREAD)
                Opened.keep(this, armor, written, null, verdict, thread = thread)
            }
            ToEach.send(applicationContext, copies, mms)
            Toast.makeText(this, getString(R.string.many_sent, whom.size), Toast.LENGTH_LONG).show()
            field.setText("")
            finish()
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

    private fun say(words: CharSequence) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    companion object {
        const val EXTRA_NUMBERS = "numbers"
    }
}
