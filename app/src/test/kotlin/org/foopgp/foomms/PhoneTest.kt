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
}
