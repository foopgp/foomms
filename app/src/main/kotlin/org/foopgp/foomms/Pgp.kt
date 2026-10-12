package org.foopgp.foomms

/**
 * Whether bytes are a PGP message, told from the bytes: a network may rename
 * a part or relabel its type on the way, and what an MMS says of its parts is
 * no more than a label.
 *
 * A message locked for somebody opens on a session key packet (RFC 9580,
 * section 5.1 for a key, 5.3 for a password), in either of the two ways a
 * packet says what it is (section 4.2); in armor it opens on its banner
 * (section 6.2).
 */
object Pgp {

    const val TYPE = "application/pgp-encrypted"
    const val NAME = "message.pgp"

    fun isMessage(start: ByteArray): Boolean {
        if (start.isEmpty()) return false
        val first = start[0].toInt() and 0xFF
        if (first and 0x80 == 0) return isArmor(start)
        val tag = if (first and 0x40 != 0) first and 0x3F else first and 0x3C shr 2
        return tag == PUBLIC_KEY_SESSION || tag == SYMMETRIC_KEY_SESSION
    }

    private fun isArmor(start: ByteArray): Boolean =
        String(start, 0, minOf(start.size, 64), Charsets.US_ASCII).trimStart().startsWith(BANNER)

    private const val PUBLIC_KEY_SESSION = 1
    private const val SYMMETRIC_KEY_SESSION = 3
    private const val BANNER = "-----BEGIN PGP MESSAGE-----"
}
