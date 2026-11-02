package org.foopgp.foomms

/**
 * What tells an encrypted group, carried in each of its messages: who is
 * in it, one vCard each -- the name, the number, the entity identifier when
 * the certificate gives one, and the address of the certificate, which
 * holds its fingerprint --, encrypted for all of them as
 * the message is. Networks are free to rewrite to whom an MMS went, and
 * some do; this is not rewritten. The keys it is encrypted for, which every
 * message names unencrypted, tell the group before anything is opened.
 */
object Members {

    /** The name the part travels under: what tells a message of a group. */
    const val PART = "foomms-group.vcf"

    /** Its type, as contact cards are sent by MMS. */
    const val TYPE = "text/x-vcard"

    class Member(val name: String, val number: String, val fingerprint: String?, val eid: String? = null)

    fun isPart(name: String?): Boolean = name == PART

    /** What a part is shown as where its name is not: the members part as a group, any other as a paper clip. */
    fun glyph(name: String?): String = if (isPart(name)) "👥" else "📎"

    /** The parts of a message with no words, by [names]: the group first, then a paper clip for each other, three at most. */
    fun glyphs(names: List<String?>): String =
        (names.filter(::isPart).take(1) + names.filterNot(::isPart).take(3)).joinToString(" ") { glyph(it) }

    /** One vCard 4.0 after another (RFC 6350), lines ending in CRLF. */
    fun write(members: List<Member>): ByteArray = buildString {
        for (one in members) {
            append("BEGIN:VCARD\r\nVERSION:4.0\r\n")
            append("FN:").append(escape(one.name)).append("\r\n")
            one.eid?.let { append("UID:urn:eid:").append(it).append("\r\n") }
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
            val eid = card.lines.firstOrNull { it.name == "UID" }?.value?.takeIf { it.startsWith(EID) }?.removePrefix(EID)
            Member(card.name ?: number, number, card.key?.let { Fingerprint.of(it) }, eid)
        }

    /** How an entity identifier is written as a vCard's UID. */
    private const val EID = "urn:eid:"

    /** What a members part said of each, kept by number: the certificate a member is tied to from a group's menu. */
    fun remember(context: android.content.Context, members: List<Member>) {
        val edit = kept(context).edit()
        for (one in members) edit.putString(Phone.key(one.number), listOf(one.name, one.number, one.fingerprint.orEmpty(), one.eid.orEmpty()).joinToString("\t"))
        edit.apply()
    }

    /** The number a members part last gave the certificate [fingerprint]. */
    fun numberOf(context: android.content.Context, fingerprint: String): String? =
        kept(context).all.values.mapNotNull { (it as? String)?.split('\t') }.firstOrNull { it.size >= 4 && it[2].equals(fingerprint, ignoreCase = true) }?.get(1)

    /** What a members part last said of [number]. */
    fun known(context: android.content.Context, number: String): Member? =
        kept(context).getString(Phone.key(number), null)?.split('\t')?.takeIf { it.size >= 4 }?.let {
            Member(it[0], it[1], it[2].ifEmpty { null }, it[3].ifEmpty { null })
        }

    private fun kept(context: android.content.Context) = context.getSharedPreferences("members", android.content.Context.MODE_PRIVATE)

    private fun escape(text: String) = text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
}
