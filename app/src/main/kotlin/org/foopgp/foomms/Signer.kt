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

    /** What a signature says, in words: by whom, and whether they are certified. */
    fun said(context: android.content.Context, signature: OpenPgpSignatureResult?): String = when (signature?.result) {
        null, OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> context.getString(R.string.unsigned)
        OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> context.getString(R.string.signed_certified, named(signature))
        OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> context.getString(R.string.signed_other, named(signature))
        OpenPgpSignatureResult.RESULT_KEY_MISSING -> context.getString(R.string.signed_unknown)
        else -> context.getString(R.string.signed_broken)
    }

    /** A message foodjis opened or verified, kept with what its signature says; the signature, for whoever asks more. */
    fun keep(context: android.content.Context, armor: String, answer: Provider.Answer.Done, thread: Long = Opened.NO_THREAD): OpenPgpSignatureResult? {
        answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
        @Suppress("DEPRECATION")
        val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
        val certified = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED
        val signer = signature?.takeIf { it.result != OpenPgpSignatureResult.RESULT_NO_SIGNATURE }
        Opened.keep(
            context, armor, String(answer.bytes, Charsets.UTF_8), said(context, signature), Verdict.of(Pgp.isLocked(armor), certified),
            signer = signer?.keyId, eid = signer?.let { eid(it) }, thread = thread,
        )
        return signature
    }

    /** The identifier a signature's certificate carries, in full. */
    fun eid(signature: OpenPgpSignatureResult): String? =
        (signature.userIds.orEmpty() + listOfNotNull(signature.primaryUserId)).firstOrNull { it.startsWith(EID) }?.substringAfter(EID)?.trim()

    private const val EID = "UID:urn:eid:"
    private val PROPERTY = Regex("^[A-Z]+[:;]")
}
