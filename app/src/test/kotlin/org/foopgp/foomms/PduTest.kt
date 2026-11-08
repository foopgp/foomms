package org.foopgp.foomms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PduTest {

    private fun bytes(vararg values: Any): ByteArray = values.flatMap { value ->
        when (value) {
            is Int -> listOf(value.toByte())
            is String -> value.toByteArray(Charsets.UTF_8).toList()
            else -> error("neither a byte nor a text")
        }
    }.toByteArray()

    @Test fun `a text for one person, byte for byte`() {
        val expected = bytes(
            0x8C, 0x80,                         // X-Mms-Message-Type: m-send-req
            0x98, "T1", 0,                      // X-Mms-Transaction-ID
            0x8D, 0x93,                         // X-Mms-MMS-Version: 1.3
            0x89, 0x01, 0x81,                   // From: insert-address-token
            0x97, "+33600000000/TYPE=PLMN", 0,  // To
            0x8A, 0x80,                         // X-Mms-Message-Class: personal
            0x8F, 0x81,                         // X-Mms-Priority: normal
            0x86, 0x81,                         // X-Mms-Delivery-Report: no
            0x90, 0x81,                         // X-Mms-Read-Report: no
            0xBC, 0x81,                         // X-Mms-Adaptation-Allowed: no
            0x84, 0xA3,                         // Content-Type: multipart/mixed
            0x01,                               // one part
            0x12, 0x02,                         // its headers: 18 bytes; its data: 2
            0x0A, 0x83, 0x81, 0xEA, 0x85, "a.txt", 0,   // text/plain; charset=utf-8; name=a.txt
            0x8E, "a.txt", 0,                   // Content-Location
            "hi",
        )
        val made = Pdu.send("T1", listOf("+33 6 00 00 00 00"), listOf(Pdu.Part.text("hi", "a.txt")))
        assertArrayEquals(expected, made)
    }

    @Test fun `a part of a type the tables do not know, longer than a length byte says`() {
        val data = ByteArray(200) { it.toByte() }
        val made = Pdu.send("T2", listOf("0600000000"), listOf(Pdu.Part(Pgp.TYPE, Pgp.NAME, data)))
        val type = bytes(Pgp.TYPE, 0, 0x85, Pgp.NAME, 0)
        val headers = bytes(0x1F, type.size) + type + bytes(0x8E, Pgp.NAME, 0)
        // 200 = 1 * 128 + 72: two bytes, the first flagged as not the last.
        val part = bytes(headers.size, 0x81, 0x48) + headers + data
        assertArrayEquals(part, made.copyOfRange(made.size - part.size, made.size))
        assertEquals(0x01, made[made.size - part.size - 1].toInt())
    }

    @Test fun `a subject says its alphabet, and each person has a line`() {
        val made = Pdu.send("T3", listOf("+33600000000", "+33700000000"), listOf(Pdu.Part.text("x")), "Été")
        // The alphabet, a quote because "É" opens on a byte above 127, five bytes of text and a zero: eight.
        val subject = bytes(0x96, 0x08, 0xEA, 0x7F, "Été", 0)
        val text = String(made, Charsets.ISO_8859_1)
        assertEquals(1, text.split(String(subject, Charsets.ISO_8859_1)).size - 1)
        assertEquals(2, text.split("/TYPE=PLMN").size - 1)
    }

    @Test fun `nobody to send to is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Pdu.send("T4", emptyList(), emptyList()) }
    }

    @Test fun `the answer that an MMS was fetched, byte for byte`() {
        val expected = bytes(
            0x8C, 0x83,                         // X-Mms-Message-Type: m-notifyresp-ind
            0x98, "abc", 0,                     // X-Mms-Transaction-ID
            0x8D, 0x92,                         // X-Mms-MMS-Version: 1.2
            0x95, 0x81,                         // X-Mms-Status: retrieved
            0x91, 0x81,                         // X-Mms-Report-Allowed: no
        )
        assertArrayEquals(expected, Pdu.fetched("abc"))
    }
}
