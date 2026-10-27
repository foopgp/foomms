package org.foopgp.foomms

/**
 * Phone numbers, as far as telling whether two of them are one.
 *
 * A certificate carries a number in full (`TEL:+33…`); a person types it, and
 * a network reports it, either in full or as the country dials it (`06…`).
 * With no table of countries here, a national number is held to be the full
 * one it ends: the same eight digits or more behind the zero. Good enough to
 * find whose key a number means among the contacts of one person; not a way
 * to prove anything, which is the certificate's work.
 */
object Phone {

    /** Digits, behind a `+` when the number says its country; null when it is not a number. */
    fun plain(written: String): String? {
        val value = written.trim().removePrefix("tel:").removePrefix("TEL:")
        if (value.any { !it.isDigit() && it !in " .-()/+" }) return null
        val digits = value.filter { it.isDigit() }
        if (digits.length < 4) return null
        return when {
            value.trimStart().startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            else -> digits
        }
    }

    fun same(one: String, other: String): Boolean {
        val a = plain(one) ?: return false
        val b = plain(other) ?: return false
        if (a == b) return true
        val full = listOf(a, b).filter { it.startsWith("+") }
        val national = listOf(a, b).filterNot { it.startsWith("+") }
        if (full.size != 1 || national.size != 1) return false
        val tail = national[0].trimStart('0')
        return tail.length >= 8 && full[0].endsWith(tail)
    }

    /**
     * The conversation a message received comes to: its sender's, when it
     * went to this phone alone; else the group's -- the sender and everybody
     * it went to, this phone among them, each once.
     */
    fun received(from: String, to: List<String>): List<String> =
        if (to.size <= 1) listOf(from) else once(listOf(from) + to)

    /**
     * The conversation a message sent comes to: the one it went to, alone;
     * else the group's -- everybody it went to and [me], as a message
     * received from one of them names it ([received]). Without [me], those
     * it went to.
     */
    fun sent(to: List<String>, me: String?): List<String> =
        if (to.size <= 1 || me == null) once(to) else once(to + me)

    private fun once(numbers: List<String>): List<String> {
        val all = ArrayList<String>()
        for (one in numbers) if (all.none { same(it, one) || it == one }) all += one
        return all
    }

    /** A number as a key: its last eight digits, the part [same] compares; what is not a number, as it is written. */
    fun key(written: String): String = plain(written)?.filter { it.isDigit() }?.takeLast(8) ?: written

    /**
     * A group as a key: its members but [me], each once, in any order. The
     * same group then, whether a conversation names this phone or not, as
     * other applications leave it out.
     */
    fun group(members: List<String>, me: String?): String =
        members.filterNot { me != null && same(it, me) }.map(::key).distinct().sorted().joinToString(",")

    /** The numbers a certificate carries, from its user ids (`TEL:+33…`, `TEL;TYPE=cell:tel:+33…`). */
    fun carried(userIds: List<String>): List<String> = userIds
        .filter { it.startsWith("TEL:") || it.startsWith("TEL;") }
        .mapNotNull { plain(it.substringAfter(':')) }
}
