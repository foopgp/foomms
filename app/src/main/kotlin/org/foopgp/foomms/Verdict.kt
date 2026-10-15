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
    GREY(true, false);

    companion object {
        fun of(encrypted: Boolean, certified: Boolean): Verdict = when {
            encrypted && certified -> GREEN
            encrypted -> YELLOW
            certified -> BLUE
            else -> RED
        }
    }
}

/**
 * What was learnt of the PGP messages opened: their words, what carried
 * them, and what they are worth -- known from the key and held with the
 * words, in memory. What was encrypted is held for the time the person set,
 * counted from the last opening: each one winds it up again for all. Past
 * it, they close together, and are grey again: what they are worth is in
 * what the key says, and the key is asked again. What was only signed needs
 * no key, and stays.
 */
object Opened {

    /** [signer]: the key that signed, and [eid] the identifier its certificate carries, when it said so. */
    class Read(val words: String, val signed: String?, val at: Long, val verdict: Verdict, val signer: Long? = null, val eid: String? = null)

    private val read = HashMap<String, Read>()

    fun key(armor: String): String =
        MessageDigest.getInstance("SHA-256").digest(armor.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    /** How long what was encrypted stays open after the last opening, in milliseconds. */
    @Volatile var holdFor: Long = 5 * 60_000L

    /** When something encrypted was last opened; nothing open, none. */
    @Volatile private var lastOpened = 0L

    /** When what is open closes; nothing encrypted open, null. */
    fun closesAt(now: Long = System.currentTimeMillis()): Long? {
        close(now)
        val open = synchronized(read) { read.values.any { it.verdict.encrypted } } || synchronized(pieces) { pieces.values.any { it.verdict.encrypted } }
        return if (open) lastOpened + holdFor else null
    }

    /** Everything encrypted, closed: past its time, or [now] when asked to. */
    fun close(now: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!force && now - lastOpened <= holdFor) return
        synchronized(read) { read.values.removeAll { it.verdict.encrypted } }
        synchronized(pieces) { pieces.values.removeAll { it.verdict.encrypted } }
    }

    /** The words of a message, while it is open. */
    fun words(armor: String, now: Long = System.currentTimeMillis()): Read? {
        close(now)
        return synchronized(read) { read[key(armor)] }
    }

    fun keep(
        context: Context, armor: String, words: String, signed: String?, verdict: Verdict,
        signer: Long? = null, eid: String? = null, now: Long = System.currentTimeMillis(),
    ) {
        close(now)
        synchronized(read) { read[key(armor)] = Read(words, signed, now, verdict, signer, eid) }
        if (verdict.encrypted) lastOpened = now
        forgetOld(context)
    }

    /** What a message is worth while it is open; closed, nothing -- and it is shown grey. */
    @Suppress("UNUSED_PARAMETER")
    fun verdict(context: Context, armor: String): Verdict? = words(armor)?.verdict

    /** What a part of an MMS held once it was opened: the file, what it is, what it is worth. */
    class Piece(
        val type: String, val name: String, val bytes: ByteArray, val at: Long, val verdict: Verdict,
        val signed: String? = null, val signer: Long? = null, val eid: String? = null,
    )

    private val pieces = HashMap<Long, Piece>()

    /** A part while it is open, by its number in the phone's store. */
    fun piece(part: Long, now: Long = System.currentTimeMillis()): Piece? {
        close(now)
        return synchronized(pieces) { pieces[part] }
    }

    fun keepPiece(
        context: Context, part: Long, type: String, name: String, bytes: ByteArray, verdict: Verdict,
        signed: String? = null, signer: Long? = null, eid: String? = null, now: Long = System.currentTimeMillis(),
    ) {
        close(now)
        synchronized(pieces) { pieces[part] = Piece(type, name, bytes, now, verdict, signed, signer, eid) }
        if (verdict.encrypted) lastOpened = now
        forgetOld(context)
    }

    @Suppress("UNUSED_PARAMETER")
    fun pieceVerdict(context: Context, part: Long): Verdict? = piece(part)?.verdict

    /** Verdicts once written down by an earlier version, which outlived the words: gone. */
    private fun forgetOld(context: Context) {
        context.getSharedPreferences("verdicts", Context.MODE_PRIVATE).takeIf { it.all.isNotEmpty() }?.edit()?.clear()?.apply()
    }
}
