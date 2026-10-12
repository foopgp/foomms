package org.foopgp.foomms

/**
 * A certificate named by its fingerprint: forty hexadecimal digits (version 4,
 * RFC 9580 section 5.5.4), written alone, in groups, behind `0x`, or inside
 * the address of a key server -- the form a vCard's KEY field gives it.
 */
object Fingerprint {

    fun of(written: String): String? {
        // In an address, what follows "search=" is the fingerprint and the rest is not.
        val where = written.substringAfter("search=", written).substringBefore('&')
        val digits = where.removePrefix("0x").removePrefix("0X").filterNot { it.isWhitespace() || it == ':' }
        return digits.uppercase().takeIf { it.length == 40 && it.all { c -> c in "0123456789ABCDEF" } }
    }

    /** The key id the service interface names a certificate by: the fingerprint's last sixty-four bits. */
    fun keyId(fingerprint: String): Long = java.lang.Long.parseUnsignedLong(fingerprint.takeLast(16), 16)

    /** The address a vCard's KEY field carries for this certificate. */
    fun address(fingerprint: String, server: String = SERVER): String = "$server/pks/lookup?op=get&search=0x$fingerprint"

    const val SERVER = "https://keys.foopgp.org"
}
