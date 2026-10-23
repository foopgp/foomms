package org.foopgp.foomms

/**
 * A vCard as foodjis writes one for a certificate -- 4.0 (RFC 6350; the KEY
 * field as draft-foopgp-openpgp-vcard gives it) or, on an older phone, 3.0
 * (RFC 2426) --, read for what a contact can take: the
 * name, the addresses and numbers, the notes, the face, and where the
 * certificate is fetched.
 */
class Card(val lines: List<Line>) {

    class Line(val name: String, val parameters: String, val value: String) {
        /** 3.0's way of carrying bytes: `ENCODING=b` among the parameters, the value in base64. */
        val encoded: Boolean get() = parameters.split(';').any { it.equals("ENCODING=b", true) || it.equals("ENCODING=BASE64", true) }
    }

    val name: String? get() = first("FN")
    val emails: List<String> get() = all("EMAIL")
    val phones: List<String> get() = all("TEL").map { it.removePrefix("tel:") }
    val websites: List<String> get() = all("URL")
    val notes: List<String> get() = all("NOTE")

    /** A postal address is seven fields between semicolons: read as lines, the empty ones dropped. */
    val addresses: List<String>
        get() = lines.filter { it.name == "ADR" }
            .map { line -> fields(line.value).map { plain(it).trim() }.filter { it.isNotEmpty() }.joinToString("\n") }
            .filter { it.isNotEmpty() }

    /**
     * Where the certificate is fetched: the KEY field that is an address,
     * not the one that is the key itself -- a data address in 4.0, an
     * encoded value in 3.0.
     */
    val key: String?
        get() = lines.firstOrNull { it.name == "KEY" && !it.value.startsWith("data:") && !it.encoded }?.value

    val photo: ByteArray?
        get() = lines.firstOrNull { it.name == "PHOTO" && (it.value.startsWith("data:") || it.encoded) }
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it.value.substringAfter("base64,")) }.getOrNull() }

    /** What would be written, as a person reads it: a line each, the key and the face only named. */
    /** Its lines as said to a person; the notes left out when [withNotes] is false, they being asked about apart. */
    fun told(withNotes: Boolean = true): List<String> = buildList {
        name?.let { add(it) }
        addAll(emails) ; addAll(phones) ; addAll(addresses.map { it.replace('\n', ' ') }) ; addAll(websites)
        if (withNotes) addAll(notes)
    }

    private fun first(name: String) = all(name).firstOrNull()
    private fun all(name: String) = lines.filter { it.name == name }.map { plain(it.value) }.filter { it.isNotEmpty() }

    companion object {

        fun of(text: String): Card {
            // A long line is folded: what follows a line break and a space belongs to the line before (section 3.2).
            val whole = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "")
            return Card(
                whole.lines().mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@mapNotNull null
                    val head = line.substring(0, colon)
                    Line(head.substringBefore(';').uppercase(), head.substringAfter(';', ""), line.substring(colon + 1))
                },
            )
        }

        /** A value as it reads: `\,` is a comma, `\;` a semicolon, `\n` a new line (section 3.4). */
        fun plain(value: String): String =
            ESCAPE.replace(value) { found -> if (found.groupValues[1].equals("n", true)) "\n" else found.groupValues[1] }

        /** The fields of a structured value: cut at the semicolons that are not escaped. */
        private fun fields(value: String): List<String> = value.split(UNESCAPED_SEMICOLON)

        private val ESCAPE = Regex("""\\(.)""")
        private val UNESCAPED_SEMICOLON = Regex("""(?<!\\);""")
    }
}
