package org.foopgp.foomms

/**
 * Whether bytes are a PGP message, told from the bytes: a network may rename
 * a part or relabel its type on the way, and what an MMS says of its parts is
 * no more than a label.
 *
 * A message locked for somebody opens on a session key packet (RFC 9580,
 * section 5.1 for a key, 5.3 for a password), in either of the two ways a
 * packet says what it is (section 4.2), and that packet on its version; in
 * armor it opens on its banner (section 6.2).
 */
object Pgp {

    const val TYPE = "application/pgp-encrypted"
    const val NAME = "message.pgp"

    fun isMessage(start: ByteArray): Boolean {
        if (start.isEmpty()) return false
        val first = start[0].toInt() and 0xFF
        if (first and 0x80 == 0) return isArmor(start)
        // The packet's tag, and where its body begins: behind one, two or
        // four bytes of length the old way, one, two or five the new way.
        val tag: Int
        val body: Int
        if (first and 0x40 != 0) {
            tag = first and 0x3F
            val length = start.getOrNull(1)?.toInt()?.and(0xFF) ?: return false
            body = when {
                length < 192 -> 2
                length < 224 -> 3
                length == 255 -> 6
                else -> return false
            }
        } else {
            tag = first and 0x3C shr 2
            body = when (first and 0x03) {
                0 -> 2
                1 -> 3
                2 -> 5
                else -> return false
            }
        }
        // A tag alone is one byte in sixteen, and a text that opens on "É" has
        // it: the body must open on a version these packets exist in.
        val version = start.getOrNull(body)?.toInt() ?: return false
        return when (tag) {
            PUBLIC_KEY_SESSION -> version == 3 || version == 6
            SYMMETRIC_KEY_SESSION -> version in 4..6
            else -> false
        }
    }

    /** The armored message a text carries, from its banner to its last line, or null. */
    fun armorIn(text: String): String? {
        val from = text.indexOf(BANNER)
        if (from < 0) return null
        val to = text.indexOf(END, from)
        if (to < 0) return null
        return text.substring(from, to + END.length)
    }

    /**
     * Whether an armored message is locked for somebody, rather than signed
     * and readable by anyone: told from its first packet, behind the armor's
     * own headers and before its checksum (RFC 9580, section 6.2).
     */
    fun isLocked(armor: String): Boolean {
        val lines = armor.lines().map { it.trim() }
        val blank = lines.indexOf("")
        if (blank < 0) return false
        val body = lines.drop(blank + 1).takeWhile { it.isNotEmpty() && !it.startsWith("=") && !it.startsWith("-----") }
        // Sixteen characters are twelve bytes: more than a packet's header and its version.
        val start = body.joinToString("").take(16)
        val bytes = runCatching { java.util.Base64.getDecoder().decode(start.dropLast(start.length % 4)) }
            .getOrNull() ?: return false
        return bytes.isNotEmpty() && bytes[0].toInt() and 0x80 != 0 && isMessage(bytes)
    }

    private fun isArmor(start: ByteArray): Boolean =
        String(start, 0, minOf(start.size, 64), Charsets.US_ASCII).trimStart().startsWith(BANNER)

    private const val PUBLIC_KEY_SESSION = 1
    private const val SYMMETRIC_KEY_SESSION = 3
    private const val BANNER = "-----BEGIN PGP MESSAGE-----"
    private const val END = "-----END PGP MESSAGE-----"
}
