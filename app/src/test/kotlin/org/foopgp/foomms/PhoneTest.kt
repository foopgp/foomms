package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTest {

    @Test fun `a number is read however it is written`() {
        assertEquals("+33600000000", Phone.plain("+33 6 00 00 00 00"))
        assertEquals("+33600000000", Phone.plain("tel:+33-6-00-00-00-00"))
        assertEquals("+33600000000", Phone.plain("0033 6 00 00 00 00"))
        assertEquals("0600000000", Phone.plain("06.00.00.00.00"))
        assertNull(Phone.plain("aline@example.org"))
        assertNull(Phone.plain("112"))
    }

    @Test fun `a national number is the full one it ends`() {
        assertTrue(Phone.same("06 00 00 00 00", "+33600000000"))
        assertTrue(Phone.same("+33600000000", "0600000000"))
        assertTrue(Phone.same("+33 6 00 00 00 00", "0033600000000"))
        assertFalse(Phone.same("+33600000000", "+32600000000"))
        assertFalse(Phone.same("0600000000", "0600000001"))
        // Two national numbers that differ are two numbers; too short a tail proves nothing.
        assertFalse(Phone.same("0600000000", "600000000"))
        assertFalse(Phone.same("+33600000000", "0000000"))
    }

    @Test fun `the numbers of a certificate are among its user ids`() {
        val userIds = listOf(
            "FN:Aline Example",
            "EMAIL:aline@example.org",
            "TEL:+33600000000",
            "TEL;TYPE=cell:tel:+33-7-00-00-00-00",
            "NOTE:TEL is not a number here",
        )
        assertEquals(listOf("+33600000000", "+33700000000"), Phone.carried(userIds))
    }

    @Test fun `a message to several is the group's, this phone among them`() {
        val me = "+33600000000"
        // To this phone alone: the sender's conversation.
        assertEquals(listOf("+33611111111"), Phone.received("+33611111111", listOf("0600000000")))
        // To a group: the sender and every number it went to, once, however each is written.
        assertEquals(
            listOf("+33611111111", "06 00 00 00 00", "+33622222222"),
            Phone.received("+33611111111", listOf("06 00 00 00 00", "+33622222222", "06.11.11.11.11")),
        )
        // Sent to one: that one's conversation; to several: the same numbers as a group's message received.
        assertEquals(listOf("+33611111111"), Phone.sent(listOf("+33611111111"), me))
        assertEquals(listOf("+33611111111", "+33622222222", me), Phone.sent(listOf("+33611111111", "+33622222222"), me))
    }

    @Test fun `a group is the same whether it names this phone or not, in any order and writing`() {
        val me = "+33600000000"
        val mine = listOf(me, "0699999999")
        val key = Phone.group(listOf("+33611111111", "+33622222222"), mine)
        assertEquals(key, Phone.group(listOf("06 22 22 22 22", "+33611111111"), mine))
        assertEquals(key, Phone.group(listOf("+33611111111", "0600000000", "+33622222222"), mine))
        assertEquals(key, Phone.group(listOf("+33622222222", "+33611111111", "+33600000000"), mine))
        // A number this phone had before is this phone's too.
        assertEquals(key, Phone.group(listOf("+33622222222", "+33611111111", "+33699999999"), mine))
    }
}
