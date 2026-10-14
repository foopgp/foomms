package org.foopgp.foomms

import org.openintents.openpgp.OpenPgpSignatureResult

/**
 * Who signed, as a person reads it: the name, and the PGP ID identifier
 * shortened -- two certificates may carry the same name, never the same
 * identifier.
 *
 * The provider hands over the certificate's user ids as they are; a PGP ID
 * certificate says its name in one of them (`FN:…`) and its identifier in
 * another (`UID:urn:eid:…`).
 */
object Signer {

    fun named(userIds: List<String>, keyId: Long): String {
        val name = userIds.firstOrNull { it.startsWith("FN:") }?.substringAfter(':')?.trim()
            ?: userIds.firstOrNull { !PROPERTY.containsMatchIn(it) }
                ?.substringBefore('<')?.substringBefore('(')?.trim()
        val eid = userIds.firstOrNull { it.startsWith(EID) }?.substringAfter(EID)?.trim()
        val short = eid?.takeIf { it.length > 6 }?.let { "${it.take(2)}=${it.substring(2, 6)}…" }
        return when {
            !name.isNullOrEmpty() && short != null -> "$name ($short)"
            !name.isNullOrEmpty() -> name
            short != null -> short
            else -> "0x%016X".format(keyId)
        }
    }

    fun named(signature: OpenPgpSignatureResult): String =
        named(signature.userIds.orEmpty() + listOfNotNull(signature.primaryUserId), signature.keyId)

    /** The identifier a signature's certificate carries, in full. */
    fun eid(signature: OpenPgpSignatureResult): String? =
        (signature.userIds.orEmpty() + listOfNotNull(signature.primaryUserId)).firstOrNull { it.startsWith(EID) }?.substringAfter(EID)?.trim()

    private const val EID = "UID:urn:eid:"
    private val PROPERTY = Regex("^[A-Z]+[:;]")
}
