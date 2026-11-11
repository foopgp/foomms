package org.foopgp.foomms

import android.content.Context
import java.security.MessageDigest

/**
 * What a message is worth, in four colours: whether it travelled locked, and
 * whether it is certified -- signed by somebody the person certified for one
 * received, locked or signed for somebody they certified for one sent. Not
 * signed is not certified.
 */
enum class Verdict(val encrypted: Boolean, val certified: Boolean) {
    RED(false, false),
    YELLOW(true, false),
    BLUE(false, true),
    GREEN(true, true),

    /** A PGP message not yet opened: what it is worth is in what the key will say. */
    GREY(true, false),

    /**
     * Received, and certified -- but signed by somebody else than whom the conversation is with: a message
     * forwarded, or passed off as theirs. In clear, violet; encrypted, yellow-green -- each its own shade, not
     * the marks' of the same names. Few read "signed by"; everybody sees a colour.
     */
    VIOLET(false, true),
    OLIVE(true, true);

    companion object {
        fun of(encrypted: Boolean, certified: Boolean): Verdict = when {
            encrypted && certified -> GREEN
            encrypted -> YELLOW
            certified -> BLUE
            else -> RED
        }

        /**
         * [verdict] of a message received in a conversation with the certificates whose primary keys are
         * [whose], signed by [signer]'s: blue or green only when the signer is one of them -- with none tied to the
         * number, nobody says the signer is whom one writes with.
         */
        fun received(verdict: Verdict, signer: Long?, whose: Collection<Long>): Verdict = when {
            !verdict.certified || (signer != null && signer in whose) -> verdict
            verdict == GREEN -> OLIVE
            verdict == BLUE -> VIOLET
            else -> verdict
        }
    }
}

/**
 * What a message is, known as soon as it arrives, without the key: in
 * clear, signed in clear, or encrypted -- of its parts, the most guarded.
 * Where the verdict says what a message is worth, once read, the form says
 * what it travelled as: the colour of the marks beside the bubbles, and of
 * the notifications, before anything is opened.
 */
enum class Form {
    PLAIN, SIGNED, LOCKED;

    companion object {
        fun of(locked: Boolean, signed: Boolean): Form = when {
            locked -> LOCKED
            signed -> SIGNED
            else -> PLAIN
        }

        /** The form of words as they travelled, an armored message in them or not. */
        fun of(words: String): Form = Pgp.armorIn(words)?.let { of(Pgp.isLocked(it), true) } ?: PLAIN
    }
}

/**
 * What was learnt of the PGP messages opened: their words, what carried
 * them, and what they are worth -- known from the key and held with the
 * words, in memory. What was encrypted is held for the time the person set,
 * a countdown for each conversation, from the last opening in it: each one
 * winds it up again for the whole conversation. Past it, they close
 * together, and are grey again: what they are worth is in what the key
 * says, and the key is asked again. What was only signed needs no key, and
 * stays.
 */
object Opened {

    /** [signer]: the key that signed, and [eid] the identifier its certificate carries, when it said so. */
    class Read(
        val words: String, val signed: Signer.Said?, val at: Long, val verdict: Verdict,
        val signer: Long? = null, val eid: String? = null, val thread: Long = NO_THREAD, val message: String? = null,
    )

    /**
     * Which message of the phone's store something opened belongs to, to count messages rather than what they
     * carry: a text in armor and the parts of the same MMS make one. Unknown, each counts alone.
     */
    fun message(line: Store.Line): String = (if (line.mms) "m" else "s") + line.id

    /** Opened outside any conversation known: what a message to several held, as it left. */
    const val NO_THREAD = -1L

    private val read = HashMap<String, Read>()

    fun key(armor: String): String =
        MessageDigest.getInstance("SHA-256").digest(armor.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    /** How long what was encrypted stays open after the last opening, in milliseconds. */
    @Volatile var holdFor: Long = Conf.USUAL_HOLD.seconds * 1000L

    /** When something encrypted was last opened, conversation by conversation. */
    private val lastOpened = HashMap<Long, Long>()

    /** What each conversation is called, for whoever shows its countdown elsewhere. */
    private val titles = HashMap<Long, String>()

    fun title(thread: Long, title: String, number: String) = synchronized(titles) { titles[thread] = title ; numbers[thread] = number }

    /** The number each conversation is with, to open it again from elsewhere. */
    private val numbers = HashMap<Long, String>()

    fun number(thread: Long): String? = synchronized(titles) { numbers[thread] }

    /** The conversations holding something encrypted open. */
    private fun open(): Set<Long> =
        synchronized(read) { read.values.filter { it.verdict.encrypted }.map { it.thread } }.toSet() +
            synchronized(pieces) { pieces.values.filter { it.verdict.encrypted }.map { it.thread } }

    /** When what is open in [thread] closes; nothing encrypted open there, null. */
    fun closesAt(thread: Long, now: Long = System.currentTimeMillis()): Long? {
        expire(now)
        return if (thread in open()) synchronized(lastOpened) { lastOpened[thread] }?.plus(holdFor) else null
    }

    /**
     * How many messages are open in [thread]: those whose store number is known once each, the others one by one,
     * an armor opened onto another counted with it.
     */
    fun count(thread: Long, now: Long = System.currentTimeMillis()): Int {
        expire(now)
        val words = synchronized(read) {
            read.values.filter { it.verdict.encrypted && it.thread == thread }
                .filterNot { it.message == null && Pgp.armorIn(it.words)?.let { inner -> read.containsKey(key(inner)) } == true }
        }
        val parts = synchronized(pieces) { pieces.values.filter { it.verdict.encrypted && it.thread == thread } }
        val known = (words.mapNotNull { it.message } + parts.mapNotNull { it.message }).toSet()
        return known.size + words.count { it.message == null } + parts.count { it.message == null }
    }

    /** Every conversation holding something encrypted open, its title and when it closes, the longest first. */
    fun all(now: Long = System.currentTimeMillis()): List<Triple<Long, String?, Long>> {
        expire(now)
        return open().filter { it != NO_THREAD }.mapNotNull { thread ->
            val at = synchronized(lastOpened) { lastOpened[thread] } ?: return@mapNotNull null
            Triple(thread, synchronized(titles) { titles[thread] }, at + holdFor)
        }.sortedByDescending { it.third }
    }

    /** Whether anything encrypted is open anywhere. */
    fun anyOpen(now: Long = System.currentTimeMillis()): Boolean {
        expire(now)
        return open().isNotEmpty()
    }

    /** What is encrypted in [thread], closed now. */
    fun close(thread: Long) {
        synchronized(read) { read.values.removeAll { it.verdict.encrypted && it.thread == thread } }
        synchronized(pieces) { pieces.values.removeAll { it.verdict.encrypted && it.thread == thread } }
    }

    /** Everything encrypted, closed now. */
    fun closeAll() {
        synchronized(read) { read.values.removeAll { it.verdict.encrypted } }
        synchronized(pieces) { pieces.values.removeAll { it.verdict.encrypted } }
    }

    /**
     * What was opened while its signer's key was missing, forgotten: the
     * certificate has come since. Signed only, it is verified again as soon
     * as it is shown; encrypted, it is closed, its signature judged when the
     * key opens it again. Whether anything encrypted was closed so.
     */
    fun forgetMissing(): Boolean {
        val missing = { said: Signer.Said? -> said?.result == org.openintents.openpgp.OpenPgpSignatureResult.RESULT_KEY_MISSING }
        val words = synchronized(read) { read.values.filter { missing(it.signed) }.also { gone -> read.values.removeAll(gone.toSet()) } }
        val parts = synchronized(pieces) { pieces.values.filter { missing(it.signed) }.also { gone -> pieces.values.removeAll(gone.toSet()) } }
        return words.any { it.verdict.encrypted } || parts.any { it.verdict.encrypted }
    }

    /** The conversations past their time, closed. */
    private fun expire(now: Long) {
        val past = synchronized(lastOpened) { lastOpened.filterValues { now - it > holdFor }.keys }
        for (thread in past) close(thread)
    }

    /** The words of a message, while it is open. */
    fun words(armor: String, now: Long = System.currentTimeMillis()): Read? {
        expire(now)
        return synchronized(read) { read[key(armor)] }
    }

    fun keep(
        context: Context, armor: String, words: String, signed: Signer.Said?, verdict: Verdict,
        signer: Long? = null, eid: String? = null, thread: Long = NO_THREAD, message: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        expire(now)
        synchronized(read) { read[key(armor)] = Read(words, signed, now, verdict, signer, eid, thread, message) }
        if (verdict.encrypted) synchronized(lastOpened) { lastOpened[thread] = now }
        forgetOld(context)
    }

    /**
     * The message a PGP message holds, as far as it has been opened: a message
     * locked or signed again as it stood -- shared from foodjis, then locked
     * on the way out -- opens onto another armor, and that one is the
     * message. The innermost reached, open or not.
     */
    fun inner(armor: String): String {
        var at = armor
        repeat(8) {
            val opened = words(at) ?: return at
            val next = Pgp.armorIn(opened.words)?.takeIf { it != at } ?: return at
            at = next
        }
        return at
    }

    /** What a message is worth while it is open; closed, nothing -- and it is shown grey. */
    @Suppress("UNUSED_PARAMETER")
    fun verdict(context: Context, armor: String): Verdict? = words(armor)?.verdict

    /** What a part of an MMS held once it was opened: the file, what it is, what it is worth. */
    class Piece(
        val type: String, val name: String, val bytes: ByteArray, val at: Long, val verdict: Verdict,
        val signed: Signer.Said? = null, val signer: Long? = null, val eid: String? = null, val thread: Long = NO_THREAD,
        val message: String? = null,
    )

    private val pieces = HashMap<Long, Piece>()

    /** A part while it is open, by its number in the phone's store. */
    fun piece(part: Long, now: Long = System.currentTimeMillis()): Piece? {
        expire(now)
        return synchronized(pieces) { pieces[part] }
    }

    fun keepPiece(
        context: Context, part: Long, type: String, name: String, bytes: ByteArray, verdict: Verdict,
        signed: Signer.Said? = null, signer: Long? = null, eid: String? = null, thread: Long = NO_THREAD,
        message: String? = null, now: Long = System.currentTimeMillis(),
    ) {
        expire(now)
        synchronized(pieces) { pieces[part] = Piece(type, name, bytes, now, verdict, signed, signer, eid, thread, message) }
        if (verdict.encrypted) synchronized(lastOpened) { lastOpened[thread] = now }
        forgetOld(context)
    }

    /**
     * What was just sent, kept open as it left: the phone's store numbers its
     * part only once it holds it, so it waits under a number of its own,
     * found again by what was sealed ([claim]). Closed with its conversation
     * in the meantime, it is not found again.
     */
    fun keepSent(
        context: Context, sealed: ByteArray, type: String, name: String, bytes: ByteArray, verdict: Verdict,
        thread: Long = NO_THREAD, message: String? = null, now: Long = System.currentTimeMillis(),
    ) {
        val waiting = synchronized(sent) { sent.getOrPut(digest(sealed)) { --unnumbered } }
        keepPiece(context, waiting, type, name, bytes, verdict, thread = thread, message = message, now = now)
    }

    /** The part [part] of the store, if it is what was just sent ([sealed]): open under its number from now. */
    fun claim(part: Long, sealed: ByteArray): Piece? {
        val waiting = synchronized(sent) { sent.remove(digest(sealed)) } ?: return null
        return synchronized(pieces) { pieces.remove(waiting)?.also { pieces[part] = it } }
    }

    /** What was sent and not yet found in the store, by what it was sealed as. */
    private val sent = HashMap<String, Long>()

    /** Numbers below any the store gives, for what waits to be found there. */
    private var unnumbered = NO_THREAD

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("UNUSED_PARAMETER")
    fun pieceVerdict(context: Context, part: Long): Verdict? = piece(part)?.verdict

    /** Verdicts once written down by an earlier version, which outlived the words: gone. */
    private fun forgetOld(context: Context) {
        context.getSharedPreferences("verdicts", Context.MODE_PRIVATE).takeIf { it.all.isNotEmpty() }?.edit()?.clear()?.apply()
    }
}
