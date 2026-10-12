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

    /** The numbers a certificate carries, from its user ids (`TEL:+33…`, `TEL;TYPE=cell:tel:+33…`). */
    fun carried(userIds: List<String>): List<String> = userIds
        .filter { it.startsWith("TEL:") || it.startsWith("TEL;") }
        .mapNotNull { plain(it.substringAfter(':')) }
}
