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

    /**
     * The name a PGP part travels under, in clear in the MMS: its size and
     * nothing else -- what the network sees anyway, and enough to tell two
     * apart in a list. What it is and when it was made stay inside.
     */
    fun outerName(sealed: ByteArray): String = "${sealed.size}.pgp"

    /**
     * The name of what a PGP part held, once opened. Under its size alone, the
     * name is found inside: a sound or a video says when it was recorded, and
     * whether it has a picture -- `v20261019T210406Z.3gp`, `a….3gp`; anything
     * else is named by its kind. Under an older name, that name less ".pgp".
     */
    fun innerName(outer: String?, opened: ByteArray, extension: String): String {
        val given = outer.orEmpty()
        if (!Regex("^\\d+\\.(pgp|gpg|asc)$").matches(given)) {
            given.removeSuffix(".pgp").removeSuffix(".gpg").removeSuffix(".asc").takeIf { it.isNotEmpty() }?.let { return it }
        }
        return Media.recorded(opened)?.let { (at, picture) -> Media.named(if (picture) "v" else "a", at, extension) } ?: "file.$extension"
    }

    /**
     * The type a file travels under: a PGP message by its name or its bytes
     * goes as [TYPE], whatever [given]. Android's own table gives ".pgp" as
     * application/pgp-signature, which is a detached signature (RFC 3156,
     * section 5), and ".gpg" as application/octet-stream.
     */
    fun typeOf(name: String, given: String, start: ByteArray): String =
        if (name.lowercase().let { it.endsWith(".pgp") || it.endsWith(".gpg") } || isMessage(start)) TYPE else given

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

    /** Whether a message as it travels in a part of an MMS, bare or armored, is locked for somebody rather than only signed. */
    fun isLocked(bytes: ByteArray): Boolean =
        if (isArmor(bytes)) isLocked(String(bytes, Charsets.US_ASCII)) else isMessage(bytes)

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

    /** The bytes an armor wraps: what stands between its headers and its checksum. */
    private fun unarmored(armor: String): ByteArray? {
        val lines = armor.lines().map { it.trim() }
        val blank = lines.indexOf("")
        if (blank < 0) return null
        val body = lines.drop(blank + 1).takeWhile { it.isNotEmpty() && !it.startsWith("=") && !it.startsWith("-----") }
        return runCatching { java.util.Base64.getDecoder().decode(body.joinToString("")) }.getOrNull()
    }

    /**
     * The keys a locked message says it is for, by key id: one session key
     * packet each, at the head of the message (RFC 9580, section 5.1.1 for
     * the version these carry an id in). A key that asked not to be named is
     * a zero, and is left out, as is a packet of a version that names a key
     * otherwise.
     */
    fun recipients(armor: String): List<Long> = unarmored(armor)?.let { recipients(it) }.orEmpty()

    /** The same, of a message as it travels in a part of an MMS: bare, or armored. */
    fun recipients(bytes: ByteArray): List<Long> {
        if (isArmor(bytes)) return recipients(String(bytes, Charsets.US_ASCII))
        val found = ArrayList<Long>()
        var at = 0
        while (at < bytes.size) {
            val first = bytes[at].toInt() and 0xFF
            if (first and 0x80 == 0) break
            val tag: Int
            val length: Int
            var body = at + 1
            fun byte(): Int = bytes.getOrNull(body++)?.toInt()?.and(0xFF) ?: -1
            if (first and 0x40 != 0) {
                tag = first and 0x3F
                val one = byte()
                length = when {
                    one < 0 -> return found
                    one < 192 -> one
                    one < 224 -> ((one - 192) shl 8) + byte() + 192
                    one == 255 -> (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
                    else -> return found
                }
            } else {
                tag = first and 0x3C shr 2
                length = when (first and 0x03) {
                    0 -> byte()
                    1 -> (byte() shl 8) or byte()
                    2 -> (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
                    else -> return found
                }
            }
            if (length < 0 || (tag != PUBLIC_KEY_SESSION && tag != SYMMETRIC_KEY_SESSION)) break
            if (tag == PUBLIC_KEY_SESSION && bytes.getOrNull(body)?.toInt() == 3 && body + 9 <= bytes.size) {
                var id = 0L
                for (i in 1..8) id = (id shl 8) or (bytes[body + i].toLong() and 0xFF)
                if (id != 0L) found += id
            }
            at = body + length
        }
        return found.distinct()
    }

    private fun isArmor(start: ByteArray): Boolean =
        String(start, 0, minOf(start.size, 64), Charsets.US_ASCII).trimStart().startsWith(BANNER)

    private const val PUBLIC_KEY_SESSION = 1
    private const val SYMMETRIC_KEY_SESSION = 3
    private const val BANNER = "-----BEGIN PGP MESSAGE-----"
    private const val END = "-----END PGP MESSAGE-----"
}
