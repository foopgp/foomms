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
        named(userIds(signature) + listOfNotNull(signature.primaryUserId), signature.keyId)

    /**
     * What a signature said, kept without words -- its verdict and who -- so
     * that it is said in the language of the page that shows it, not of the
     * one that opened it.
     */
    class Said(val result: Int?, val who: String)

    fun of(signature: OpenPgpSignatureResult?): Said {
        // Who signed only when a valid signature names them: with none, the result's list of user ids is
        // null, and openpgp-api's own reader of it throws (seen on the phone, opening an unsigned message).
        val valid = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED ||
            signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED
        val who = if (valid) runCatching { named(signature!!) }.getOrNull() ?: "0x%016X".format(signature!!.keyId) else ""
        return Said(signature?.result, who)
    }

    /** What a signature says, in words: by whom, and whether they are certified. */
    fun words(context: android.content.Context, said: Said): CharSequence = when (said.result) {
        null, OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> context.getString(R.string.unsigned)
        OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> context.getString(R.string.signed_certified, said.who)
        // "Not certified" in bold: what the line is there to say.
        OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> Look.styled(context, R.string.signed_other, said.who)
        OpenPgpSignatureResult.RESULT_KEY_MISSING -> context.getString(R.string.signed_unknown)
        else -> context.getString(R.string.signed_broken)
    }

    fun said(context: android.content.Context, signature: OpenPgpSignatureResult?): String = words(context, of(signature)).toString()

    /** A message foodjis opened or verified, kept with what its signature says; the signature, for whoever asks more. */
    fun keep(context: android.content.Context, armor: String, answer: Provider.Answer.Done, thread: Long = Opened.NO_THREAD): OpenPgpSignatureResult? {
        answer.says.setExtrasClassLoader(OpenPgpSignatureResult::class.java.classLoader)
        @Suppress("DEPRECATION")
        val signature = answer.says.getParcelableExtra<OpenPgpSignatureResult>(Provider.RESULT_SIGNATURE)
        val certified = signature?.result == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED
        val signer = signature?.takeIf { it.result != OpenPgpSignatureResult.RESULT_NO_SIGNATURE }
        Opened.keep(
            context, armor, String(answer.bytes, Charsets.UTF_8), of(signature), Verdict.of(Provider.encrypted(answer, Pgp.isLocked(armor)), certified),
            signer = signer?.keyId, eid = signer?.let { eid(it) }, thread = thread,
        )
        return signature
    }

    /** The identifier a signature's certificate carries, in full. */
    fun eid(signature: OpenPgpSignatureResult): String? =
        (userIds(signature) + listOfNotNull(signature.primaryUserId)).firstOrNull { it.startsWith(EID) }?.substringAfter(EID)?.trim()

    /** A result's user ids: openpgp-api's reader throws when the provider sent none, rather than giving an empty list. */
    private fun userIds(signature: OpenPgpSignatureResult): List<String> = runCatching { signature.userIds }.getOrNull().orEmpty()

    private const val EID = "UID:urn:eid:"
    private val PROPERTY = Regex("^[A-Z]+[:;]")
}
