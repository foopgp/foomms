package org.foopgp.foomms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwiceTest {

    @Test fun `the same sent again at once is a second press`() {
        assertFalse(Twice.at(10_000, "bonjour", "+33600000001"))
        assertTrue(Twice.at(10_500, "bonjour", "+33600000001"))
    }

    @Test fun `the same sent again later is a second message`() {
        assertFalse(Twice.at(20_000, "encore", "+33600000001"))
        assertFalse(Twice.at(23_000, "encore", "+33600000001"))
    }

    @Test fun `other words or another person at once are another message`() {
        assertFalse(Twice.at(30_000, "un", "+33600000001"))
        assertFalse(Twice.at(30_100, "deux", "+33600000001"))
        assertFalse(Twice.at(30_200, "deux", "+33600000002"))
    }
}
