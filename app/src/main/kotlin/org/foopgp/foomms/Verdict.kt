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
 * What has been learnt of the PGP messages seen so far, by what they are --
 * the digest of their armor -- and not by where the phone keeps them.
 *
 * The verdict outlasts the application; the words do not: what a message said
 * is held in memory for the time the person set, never written down, and is
 * asked of the security key again after that.
 */
object Opened {

    class Read(val words: String, val signed: String?, val at: Long)

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

    fun keep(context: Context, armor: String, words: String, signed: String?, verdict: Verdict, now: Long = System.currentTimeMillis()) {
        synchronized(read) { read[key(armor)] = Read(words, signed, now) }
        kept(context).edit().putString(key(armor), verdict.name).apply()
    }

    /** What a part of an MMS held once it was opened: the file, and what it is. */
    class Piece(val type: String, val name: String, val bytes: ByteArray, val at: Long)

    private val pieces = HashMap<Long, Piece>()

    /** A part opened not too long ago, by its number in the phone's store. */
    fun piece(part: Long, now: Long = System.currentTimeMillis()): Piece? = synchronized(pieces) {
        val held = pieces[part] ?: return null
        if (holdFor > 0 && now - held.at > holdFor) { pieces.remove(part) ; return null }
        held
    }

    fun keepPiece(context: Context, part: Long, type: String, name: String, bytes: ByteArray, verdict: Verdict, now: Long = System.currentTimeMillis()) {
        synchronized(pieces) { pieces[part] = Piece(type, name, bytes, now) }
        kept(context).edit().putString("part:$part", verdict.name).apply()
    }

    fun pieceVerdict(context: Context, part: Long): Verdict? =
        kept(context).getString("part:$part", null)?.let { name -> Verdict.entries.firstOrNull { it.name == name } }

    fun holdsAnything(): Boolean = synchronized(read) { read.isNotEmpty() } || synchronized(pieces) { pieces.isNotEmpty() }

    /** The words of every message and what they carried, dropped; what each was worth stays known. */
    fun forget() {
        synchronized(read) { read.clear() }
        synchronized(pieces) { pieces.clear() }
    }

    fun verdict(context: Context, armor: String): Verdict? =
        kept(context).getString(key(armor), null)?.let { name -> Verdict.entries.firstOrNull { it.name == name } }

    private fun kept(context: Context) = context.getSharedPreferences("verdicts", Context.MODE_PRIVATE)
}
