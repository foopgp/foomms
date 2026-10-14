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
 * words, in memory, for the time the person set. Past it, a message is
 * closed again, and grey again: what it is worth is in what the key says,
 * and the key is asked again.
 */
object Opened {

    /** [signer]: the key that signed, and [eid] the identifier its certificate carries, when it said so. */
    class Read(val words: String, val signed: String?, val at: Long, val verdict: Verdict, val signer: Long? = null, val eid: String? = null)

    private val read = HashMap<String, Read>()

    fun key(armor: String): String =
        MessageDigest.getInstance("SHA-256").digest(armor.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    /** How long words are held, in milliseconds; none, and they last as long as the page that opened them. */
    @Volatile var holdFor: Long = 15 * 60_000L

    /** The words of a message, if it was opened not too long ago; past that, they are dropped. */
    fun words(armor: String, now: Long = System.currentTimeMillis()): Read? = synchronized(read) {
        val key = key(armor)
        val held = read[key] ?: return null
        if (holdFor > 0 && now - held.at > holdFor) { read.remove(key) ; return null }
        held
    }

    fun keep(
        context: Context, armor: String, words: String, signed: String?, verdict: Verdict,
        signer: Long? = null, eid: String? = null, now: Long = System.currentTimeMillis(),
    ) {
        synchronized(read) { read[key(armor)] = Read(words, signed, now, verdict, signer, eid) }
        forgetOld(context)
    }

    /** What a message is worth while it is open; closed, nothing -- and it is shown grey. */
    @Suppress("UNUSED_PARAMETER")
    fun verdict(context: Context, armor: String): Verdict? = words(armor)?.verdict

    /** What a part of an MMS held once it was opened: the file, what it is, what it is worth. */
    class Piece(val type: String, val name: String, val bytes: ByteArray, val at: Long, val verdict: Verdict)

    private val pieces = HashMap<Long, Piece>()

    /** A part opened not too long ago, by its number in the phone's store. */
    fun piece(part: Long, now: Long = System.currentTimeMillis()): Piece? = synchronized(pieces) {
        val held = pieces[part] ?: return null
        if (holdFor > 0 && now - held.at > holdFor) { pieces.remove(part) ; return null }
        held
    }

    fun keepPiece(context: Context, part: Long, type: String, name: String, bytes: ByteArray, verdict: Verdict, now: Long = System.currentTimeMillis()) {
        synchronized(pieces) { pieces[part] = Piece(type, name, bytes, now, verdict) }
        forgetOld(context)
    }

    @Suppress("UNUSED_PARAMETER")
    fun pieceVerdict(context: Context, part: Long): Verdict? = piece(part)?.verdict

    fun holdsAnything(): Boolean = synchronized(read) { read.isNotEmpty() } || synchronized(pieces) { pieces.isNotEmpty() }

    /** The words of every message and what they carried, dropped, and what they were worth with them. */
    fun forget() {
        synchronized(read) { read.clear() }
        synchronized(pieces) { pieces.clear() }
    }

    /** Verdicts once written down by an earlier version, which outlived the words: gone. */
    private fun forgetOld(context: Context) {
        context.getSharedPreferences("verdicts", Context.MODE_PRIVATE).takeIf { it.all.isNotEmpty() }?.edit()?.clear()?.apply()
    }
}
