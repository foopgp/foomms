package org.foopgp.foomms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PduReadTest {

    private fun bytes(vararg values: Any): ByteArray = values.flatMap { value ->
        when (value) {
            is Int -> listOf(value.toByte())
            is String -> value.toByteArray(Charsets.UTF_8).toList()
            is ByteArray -> value.toList()
            else -> error("neither a byte nor a text")
        }
    }.toByteArray()

    @Test fun `what this application writes, it reads back`() {
        val attachment = ByteArray(300) { (it * 7).toByte() }
        val read = PduRead.read(
            Pdu.send(
                "T1", listOf("+33600000000", "+33700000000"),
                listOf(Pdu.Part.text("Été comme hiver", "a.txt"), Pdu.Part(Pgp.TYPE, Pgp.NAME, attachment)), "Sujet à lire",
            ),
        )
        assertEquals(PduRead.SEND, read.kind)
        assertEquals("T1", read.transaction)
        assertNull(read.from)
        assertEquals(listOf("+33600000000", "+33700000000"), read.to)
        assertEquals("Sujet à lire", read.subject)
        assertEquals("application/vnd.wap.multipart.mixed", read.type)
        assertEquals(2, read.parts.size)
        assertEquals("text/plain", read.parts[0].type)
        assertEquals("Été comme hiver", read.parts[0].text())
        assertEquals("a.txt", read.parts[0].name)
        assertEquals(Pgp.TYPE, read.parts[1].type)
        assertEquals(Pgp.NAME, read.parts[1].name)
        assertArrayEquals(attachment, read.parts[1].bytes)
    }

    @Test fun `an announcement says where the message waits`() {
        val read = PduRead.read(
            bytes(
                0x8C, 0x82,                               // X-Mms-Message-Type: m-notification-ind
                0x98, "abc123", 0,                        // X-Mms-Transaction-ID
                0x8D, 0x90,                               // X-Mms-MMS-Version: 1.0
                0x89, 0x18, 0x80, "+33600000000/TYPE=PLMN", 0,   // From: address-present
                0x8A, 0x80,                               // X-Mms-Message-Class: personal
                0x8E, 0x02, 0x12, 0x34,                   // X-Mms-Message-Size: two bytes
                0x88, 0x05, 0x81, 0x03, 0x09, 0x3A, 0x80, // X-Mms-Expiry: relative, seven days
                0x83, "http://mmsc.example.org/get?id=42", 0,    // X-Mms-Content-Location
            ),
        )
        assertEquals(PduRead.NOTIFICATION, read.kind)
        assertEquals("abc123", read.transaction)
        assertEquals("+33600000000", read.from)
        assertEquals("http://mmsc.example.org/get?id=42", read.location)
        assertEquals(0, read.parts.size)
    }

    @Test fun `a message as a network hands it over`() {
        val smil = "<smil></smil>"
        val words = "Bonjour à toi"
        val smilType = bytes("application/smil", 0, 0x81, 0xEA)
        val textType = bytes(0x83, 0x81, 0xEA, 0x85, "text_0.txt", 0)
        val relatedType = bytes(0xB3, 0x8A, "<smil>", 0, 0x89, "application/smil", 0)
        val smilHeaders = bytes(smilType.size, smilType, 0xC0, 0x22, "<smil>", 0, 0x8E, "smil.xml", 0)
        val textHeaders = bytes(textType.size, textType, 0xC0, 0x22, "<text_0>", 0, 0x8E, "text_0.txt", 0)
        val read = PduRead.read(
            bytes(
                0x8C, 0x84,                               // m-retrieve-conf
                0x98, "T9", 0,
                0x8D, 0x92,
                0x8B, "msg-id@mmsc.example.org", 0,       // Message-ID
                0x85, 0x04, 0x65, 0x00, 0x00, 0x00,       // Date: four bytes
                0x89, 0x18, 0x80, "+33600000000/TYPE=PLMN", 0,
                0x97, "+33700000000/TYPE=PLMN", 0,
                0x96, 0x07, 0xEA, "Salut", 0,             // Subject, saying its alphabet
                "X-Operator-Header", 0, "anything", 0,    // a header written out by name
                0xBA, 0x80,                               // X-Mms-Content-Class, which this does not use
                0x84, relatedType.size, relatedType,      // Content-Type: multipart/related; start; type
                0x02,
                smilHeaders.size, smil.length, smilHeaders, smil,
                textHeaders.size, words.toByteArray().size, textHeaders, words,
            ),
        )
        assertEquals(PduRead.RETRIEVED, read.kind)
        assertEquals("+33600000000", read.from)
        assertEquals(listOf("+33700000000"), read.to)
        assertEquals("Salut", read.subject)
        assertEquals(0x65000000L, read.date)
        assertEquals("msg-id@mmsc.example.org", read.id)
        assertEquals("application/vnd.wap.multipart.related", read.type)
        assertEquals(listOf("application/smil", "text/plain"), read.parts.map { it.type })
        assertEquals("<smil>", read.parts[0].id)
        assertEquals("smil.xml", read.parts[0].location)
        assertEquals(words, read.parts[1].text())
        assertEquals("text_0.txt", read.parts[1].name)
        assertEquals("<text_0>", read.parts[1].id)
    }

    @Test fun `a group's members part goes and comes back with its alphabet`() {
        val card = Members.write(listOf(Members.Member("Hélène", "+33611111111", null)))
        val pdu = Pdu.send("T1", listOf("+33611111111", "+33622222222"), listOf(Pdu.Part.text("Bonjour"), Pdu.Part(Members.TYPE, Members.PART, card)))
        val part = PduRead.read(pdu).parts.first { it.name == Members.PART || it.location == Members.PART }
        org.junit.Assert.assertEquals("Hélène", Members.read(part.bytes).single().name)
        org.junit.Assert.assertEquals(106, part.charset)
    }
}
