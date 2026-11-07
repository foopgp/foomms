package org.foopgp.foomms

import java.io.ByteArrayOutputStream

/**
 * An MMS as it leaves a phone: the M-Send.req of OMA-MMS-ENC (v1.2, section
 * 6.1), in the binary encoding WSP gives headers (WAP-230-WSP, section 8.4).
 *
 * Android sends what it is handed and offers nothing to write it with, so it
 * is written here: a dozen headers and a multipart body, each part behind its
 * content type. Only what a message of ours needs -- one or several people it
 * goes to, parts of any type under a file name.
 */
object Pdu {

    class Part(val type: String, val name: String, val bytes: ByteArray) {
        companion object {
            fun text(words: String, name: String = "text.txt") =
                Part("text/plain", name, words.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * [transaction] names this sending, for the answer that will speak of it.
     * [to] as people write numbers: the network is told they are phone
     * numbers, and reads national ones as its own.
     */
    fun send(transaction: String, to: List<String>, parts: List<Part>, subject: String? = null): ByteArray {
        require(to.isNotEmpty()) { "an MMS goes to somebody" }
        val out = ByteArrayOutputStream()
        out.write(MESSAGE_TYPE) ; out.write(M_SEND_REQ)
        out.write(TRANSACTION_ID) ; out.text(transaction)
        out.write(MMS_VERSION) ; out.write(VERSION_1_2)
        // "Put my number here yourself": the network knows it better than a phone does.
        out.write(FROM) ; out.write(1) ; out.write(INSERT_ADDRESS)
        for (number in to) {
            out.write(TO) ; out.text(number.filterNot { it.isWhitespace() } + "/TYPE=PLMN")
        }
        if (!subject.isNullOrEmpty()) {
            val words = ByteArrayOutputStream().apply { write(UTF_8) ; text(subject) }.toByteArray()
            out.write(SUBJECT) ; out.valueLength(words.size) ; out.write(words)
        }
        out.write(MESSAGE_CLASS) ; out.write(PERSONAL)
        out.write(PRIORITY) ; out.write(NORMAL)
        out.write(DELIVERY_REPORT) ; out.write(NO)
        out.write(READ_REPORT) ; out.write(NO)
        // Last of the headers, as the encoding wants it: what follows is the body.
        out.write(CONTENT_TYPE) ; out.write(MULTIPART_MIXED)
        out.uintvar(parts.size)
        for (part in parts) {
            val headers = ByteArrayOutputStream().apply {
                write(contentType(part))
                write(CONTENT_LOCATION) ; text(part.name)
            }.toByteArray()
            out.uintvar(headers.size)
            out.uintvar(part.bytes.size)
            out.write(headers)
            out.write(part.bytes)
        }
        return out.toByteArray()
    }

    /**
     * The M-NotifyResp.ind (OMA-MMS-ENC 1.2, section 6.2) that tells the operator's server the MMS it announced
     * under [transaction] was fetched: told nothing, some servers announce it again, and it is fetched twice.
     */
    fun fetched(transaction: String): ByteArray = ByteArrayOutputStream().apply {
        write(MESSAGE_TYPE) ; write(M_NOTIFYRESP_IND)
        write(TRANSACTION_ID) ; text(transaction)
        write(MMS_VERSION) ; write(VERSION_1_2)
        write(STATUS) ; write(RETRIEVED)
        write(REPORT_ALLOWED) ; write(NO)
    }.toByteArray()

    /** A part's type, the name it is to be saved under, and for a text the alphabet it is written in. */
    private fun contentType(part: Part): ByteArray {
        val inside = ByteArrayOutputStream().apply {
            if (part.type == "text/plain") {
                write(TEXT_PLAIN) ; write(CHARSET) ; write(UTF_8)
            } else {
                text(part.type)
                // Any other text -- a contact card -- says its alphabet too: names are not all ASCII.
                if (part.type.startsWith("text/")) { write(CHARSET) ; write(UTF_8) }
            }
            write(NAME) ; text(part.name)
        }.toByteArray()
        return ByteArrayOutputStream().apply { valueLength(inside.size) ; write(inside) }.toByteArray()
    }

    /** Text-string: the bytes and a zero, behind a quote when the first could be taken for a code. */
    private fun ByteArrayOutputStream.text(words: String) {
        val bytes = words.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && bytes[0].toInt() and 0x80 != 0) write(QUOTE)
        write(bytes)
        write(0)
    }

    /** Value-length: one byte up to 30, else a marker and a uintvar. */
    private fun ByteArrayOutputStream.valueLength(length: Int) {
        if (length <= 30) write(length) else { write(LENGTH_QUOTE) ; uintvar(length) }
    }

    /** Uintvar: seven bits a byte, most significant first, the high bit set on all but the last. */
    private fun ByteArrayOutputStream.uintvar(value: Int) {
        require(value >= 0)
        var shift = 28
        while (shift > 0 && value ushr shift == 0) shift -= 7
        while (shift > 0) {
            write((value ushr shift and 0x7F) or 0x80)
            shift -= 7
        }
        write(value and 0x7F)
    }

    // Header names, OMA-MMS-ENC table 12, each with its high bit set.
    private const val CONTENT_TYPE = 0x84
    private const val DELIVERY_REPORT = 0x86
    private const val FROM = 0x89
    private const val MESSAGE_CLASS = 0x8A
    private const val MESSAGE_TYPE = 0x8C
    private const val MMS_VERSION = 0x8D
    private const val PRIORITY = 0x8F
    private const val READ_REPORT = 0x90
    private const val REPORT_ALLOWED = 0x91
    private const val STATUS = 0x95
    private const val SUBJECT = 0x96
    private const val TO = 0x97
    private const val TRANSACTION_ID = 0x98

    // Their values.
    private const val M_SEND_REQ = 0x80
    private const val M_NOTIFYRESP_IND = 0x83
    private const val RETRIEVED = 0x81
    private const val VERSION_1_2 = 0x92
    private const val INSERT_ADDRESS = 0x81
    private const val PERSONAL = 0x80
    private const val NORMAL = 0x81
    private const val NO = 0x81

    // WSP: content types (table 40), parameters (table 38), character sets (IANA MIBenum 106).
    private const val MULTIPART_MIXED = 0xA3
    private const val TEXT_PLAIN = 0x83
    private const val CHARSET = 0x81
    private const val NAME = 0x85
    private const val UTF_8 = 0xEA
    private const val CONTENT_LOCATION = 0x8E
    private const val QUOTE = 0x7F
    private const val LENGTH_QUOTE = 0x1F
}
