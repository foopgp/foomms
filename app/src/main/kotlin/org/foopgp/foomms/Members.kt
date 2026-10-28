package org.foopgp.foomms

/**
 * What tells an encrypted group, carried in each of its messages: who is
 * in it, one vCard each -- the name, the number, and the address of the
 * certificate, which holds its fingerprint --, encrypted for all of them as
 * the message is. Networks are free to rewrite to whom an MMS went, and
 * some do; this is not rewritten. The keys it is encrypted for, which every
 * message names unencrypted, tell the group before anything is opened.
 */
object Members {

    /** The name the part travels under: what tells a message of an encrypted group, before it is opened. */
    const val PART = "foomms-group.vcf.pgp"

    /** The name of the file inside, once opened. */
    const val INSIDE = "foomms-group.vcf"

    class Member(val name: String, val number: String, val fingerprint: String?)

    fun isPart(name: String?): Boolean = name == PART

    /** One vCard 4.0 after another (RFC 6350), lines ending in CRLF. */
    fun write(members: List<Member>): ByteArray = buildString {
        for (one in members) {
            append("BEGIN:VCARD\r\nVERSION:4.0\r\n")
            append("FN:").append(escape(one.name)).append("\r\n")
            append("TEL;VALUE=uri:tel:").append(one.number.filterNot { it.isWhitespace() }).append("\r\n")
            one.fingerprint?.let { append("KEY:").append(Fingerprint.address(it)).append("\r\n") }
            append("END:VCARD\r\n")
        }
    }.toByteArray(Charsets.UTF_8)

    /** The members a part says, once opened: those with a number. */
    fun read(bytes: ByteArray): List<Member> =
        String(bytes, Charsets.UTF_8).split("END:VCARD").mapNotNull { block ->
            if (!block.contains("BEGIN:VCARD")) return@mapNotNull null
            val card = Card.of(block.substringAfter("BEGIN:VCARD").let { "BEGIN:VCARD$it\r\nEND:VCARD" })
            val number = card.phones.firstOrNull()?.let { Phone.plain(it) } ?: return@mapNotNull null
            Member(card.name ?: number, number, card.key?.let { Fingerprint.of(it) })
        }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
}
