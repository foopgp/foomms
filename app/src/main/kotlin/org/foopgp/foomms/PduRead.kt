package org.foopgp.foomms

import java.nio.charset.Charset

/**
 * An MMS as it reaches a phone, read: the announcement that one is waiting
 * (M-Notification.ind) and the message itself once fetched (M-Retrieve.conf),
 * both in the binary encoding of OMA-MMS-ENC 1.2 over WSP (WAP-230-WSP,
 * section 8.4) -- the reverse of [Pdu].
 *
 * Every header's value takes one of three shapes, told by its first byte
 * (WSP 8.4.1.2): one byte when it is 128 or more, a text up to a zero when it
 * is a character, a length and that many bytes otherwise. So a header this
 * does not know is stepped over rather than misread.
 */
object PduRead {

    const val NOTIFICATION = 0x82
    const val RETRIEVED = 0x84
    const val SEND = 0x80

    class Part(val type: String, val charset: Int?, val name: String?, val location: String?, val id: String?, val bytes: ByteArray) {
        val isText: Boolean get() = type == "text/plain"

        fun text(): String = String(bytes, alphabet(charset))
    }

    class Message(
        val kind: Int,
        val from: String?,
        val to: List<String>,
        val subject: String?,
        /** Seconds since 1970, as the header says them. */
        val date: Long?,
        /** For an announcement: where the message is fetched. */
        val location: String?,
        val transaction: String?,
        val id: String?,
        val type: String?,
        val parts: List<Part>,
    )

    fun read(pdu: ByteArray): Message = Reader(pdu).message()

    private enum class Shape { OCTET, TEXT, LENGTH }

    private class Value(val shape: Shape, val bytes: ByteArray)

    private class Reader(private val pdu: ByteArray, private var at: Int = 0, private val end: Int = pdu.size) {

        fun message(): Message {
            var kind = 0
            var from: String? = null
            val to = ArrayList<String>()
            var subject: String? = null
            var date: Long? = null
            var location: String? = null
            var transaction: String? = null
            var id: String? = null
            var type: String? = null
            var parts: List<Part> = emptyList()
            while (at < end) {
                val header = byte()
                if (header < 0x80) {
                    // A header written out by name: its name, then its value, both texts.
                    at-- ; value() ; value()
                    continue
                }
                if (header == CONTENT_TYPE) {
                    // The last header: what follows it is the body.
                    type = contentType().type
                    if (type.startsWith("application/vnd.wap.multipart")) parts = parts()
                    break
                }
                val value = value()
                when (header) {
                    MESSAGE_TYPE -> kind = value.bytes[0].toInt() and 0xFF
                    FROM -> from = from(value)
                    TO -> encoded(value)?.let { to += address(it) }
                    SUBJECT -> subject = encoded(value)
                    DATE -> date = integer(value)
                    CONTENT_LOCATION -> location = text(value.bytes)
                    TRANSACTION_ID -> transaction = text(value.bytes)
                    MESSAGE_ID -> id = text(value.bytes)
                }
            }
            return Message(kind, from, to, subject, date, location, transaction, id, type, parts)
        }

        private fun byte(): Int = pdu[at++].toInt() and 0xFF

        private fun uintvar(): Int {
            var value = 0
            while (true) {
                val b = byte()
                value = (value shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return value
            }
        }

        /** A value in whichever of the three shapes its first byte announces. */
        private fun value(): Value {
            val first = byte()
            return when {
                first >= 0x80 -> Value(Shape.OCTET, byteArrayOf(first.toByte()))
                first <= 30 -> Value(Shape.LENGTH, take(first))
                first == 31 -> Value(Shape.LENGTH, take(uintvar()))
                else -> {
                    val from = at - 1
                    while (at < end && pdu[at].toInt() != 0) at++
                    Value(Shape.TEXT, pdu.copyOfRange(from, at)).also { at++ }
                }
            }
        }

        private fun take(length: Int): ByteArray {
            require(length >= 0 && at + length <= end) { "a length runs past the end" }
            return pdu.copyOfRange(at, at + length).also { at += length }
        }

        /** A text with its quote taken off: the one that guards a first byte above 127, or the one that opens a quoted string. */
        private fun text(bytes: ByteArray, charset: Int? = null): String {
            val from = if (bytes.isNotEmpty() && (bytes[0].toInt() == 0x7F || bytes[0].toInt() == 0x22)) 1 else 0
            val to = if (bytes.isNotEmpty() && bytes.last().toInt() == 0) bytes.size - 1 else bytes.size
            return String(bytes, from, maxOf(0, to - from), alphabet(charset))
        }

        /** A text that may say its alphabet first: then it comes as a length, the alphabet, the text. */
        private fun encoded(value: Value): String? = when (value.shape) {
            Shape.TEXT -> text(value.bytes)
            Shape.OCTET -> null
            Shape.LENGTH -> {
                val inside = Reader(value.bytes)
                val charset = inside.integer(inside.value()).toInt()
                text(value.bytes.copyOfRange(inside.at, value.bytes.size), charset)
            }
        }

        /** An integer: seven bits in one byte, or as many bytes as its length says. */
        fun integer(value: Value): Long = when (value.shape) {
            Shape.OCTET -> (value.bytes[0].toLong() and 0x7F)
            else -> value.bytes.fold(0L) { sum, b -> (sum shl 8) or (b.toLong() and 0xFF) }
        }

        /** From: an address, or the token by which a phone leaves it to the network to fill in. */
        private fun from(value: Value): String? {
            if (value.shape != Shape.LENGTH || value.bytes.isEmpty() || value.bytes[0].toInt() and 0xFF != ADDRESS_PRESENT) return null
            val inside = Reader(value.bytes, 1)
            return inside.encoded(inside.value())?.let { address(it) }
        }

        /** A number as the network writes it carries its kind behind a slash. */
        private fun address(written: String): String = written.substringBefore("/TYPE=")

        class Type(val type: String, val charset: Int?, val name: String?)

        /** A content type: a known one in a byte, a named one as text, or either with parameters behind a length. */
        fun contentType(): Type {
            val first = pdu[at].toInt() and 0xFF
            if (first >= 0x80) { at++ ; return Type(known(first and 0x7F), null, null) }
            if (first > 31) return Type(text(value().bytes), null, null)
            val inside = Reader(value().bytes)
            val media = inside.value()
            val type = if (media.shape == Shape.OCTET) known(media.bytes[0].toInt() and 0x7F) else text(media.bytes)
            var charset: Int? = null
            var name: String? = null
            while (inside.at < inside.end) {
                val parameter = inside.value()
                val its = inside.value()
                if (parameter.shape != Shape.OCTET) continue
                when (parameter.bytes[0].toInt() and 0x7F) {
                    PARAMETER_CHARSET -> charset = integer(its).toInt()
                    PARAMETER_NAME, PARAMETER_NAME_1_4, PARAMETER_FILENAME, PARAMETER_FILENAME_1_4 ->
                        if (its.shape == Shape.TEXT) name = name ?: text(its.bytes)
                }
            }
            return Type(type, charset, name)
        }

        private fun parts(): List<Part> {
            val parts = ArrayList<Part>()
            repeat(uintvar()) {
                val headers = uintvar()
                val data = uintvar()
                val inside = Reader(pdu, at, at + headers)
                val type = inside.contentType()
                var location: String? = null
                var id: String? = null
                while (inside.at < inside.end) {
                    val header = inside.byte()
                    if (header < 0x80) { inside.at-- ; inside.value() ; inside.value() ; continue }
                    val value = inside.value()
                    when (header and 0x7F) {
                        PART_CONTENT_LOCATION -> location = text(value.bytes)
                        PART_CONTENT_ID -> id = text(value.bytes)
                    }
                }
                at += headers
                parts += Part(type.type, type.charset, type.name ?: location, location, id, take(data))
            }
            return parts
        }
    }

    private fun alphabet(charset: Int?): Charset = when (charset) {
        null, 106, 3 -> Charsets.UTF_8
        4 -> Charsets.ISO_8859_1
        1000, 1015 -> Charsets.UTF_16
        else -> runCatching { Charset.forName(NAMES[charset] ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
    }

    private val NAMES = mapOf(5 to "ISO-8859-2", 12 to "ISO-8859-9", 17 to "Shift_JIS", 2026 to "Big5", 109 to "ISO-8859-13", 111 to "ISO-8859-15", 2252 to "windows-1252")

    /** WSP's numbered content types (table 40): those an MMS is seen to carry. */
    private fun known(code: Int): String = when (code) {
        0x00 -> "*/*"
        0x01 -> "text/*"
        0x02 -> "text/html"
        0x03 -> "text/plain"
        0x06 -> "text/x-vcalendar"
        0x07 -> "text/x-vcard"
        0x1C -> "image/*"
        0x1D -> "image/gif"
        0x1E -> "image/jpeg"
        0x1F -> "image/tiff"
        0x20 -> "image/png"
        0x21 -> "image/vnd.wap.wbmp"
        0x22 -> "application/vnd.wap.multipart.*"
        0x23 -> "application/vnd.wap.multipart.mixed"
        0x26 -> "application/vnd.wap.multipart.alternative"
        0x33 -> "application/vnd.wap.multipart.related"
        else -> "application/octet-stream"
    }

    private const val CONTENT_LOCATION = 0x83
    private const val CONTENT_TYPE = 0x84
    private const val DATE = 0x85
    private const val FROM = 0x89
    private const val MESSAGE_ID = 0x8B
    private const val MESSAGE_TYPE = 0x8C
    private const val SUBJECT = 0x96
    private const val TO = 0x97
    private const val TRANSACTION_ID = 0x98
    private const val ADDRESS_PRESENT = 0x80
    private const val PARAMETER_CHARSET = 0x01
    private const val PARAMETER_NAME = 0x05
    private const val PARAMETER_FILENAME = 0x06
    private const val PARAMETER_NAME_1_4 = 0x17
    private const val PARAMETER_FILENAME_1_4 = 0x18
    private const val PART_CONTENT_LOCATION = 0x0E
    private const val PART_CONTENT_ID = 0x40
}
