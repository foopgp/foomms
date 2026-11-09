package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictTest {

    private val alice = 0x1111L
    private val mallory = 0x2222L

    @Test fun `certified by whom the conversation is with stays blue or green`() {
        assertEquals(Verdict.BLUE, Verdict.received(Verdict.BLUE, alice, setOf(alice)))
        assertEquals(Verdict.GREEN, Verdict.received(Verdict.GREEN, alice, setOf(alice)))
    }

    @Test fun `certified by somebody else turns violet or yellow-green`() {
        assertEquals(Verdict.VIOLET, Verdict.received(Verdict.BLUE, mallory, setOf(alice)))
        assertEquals(Verdict.OLIVE, Verdict.received(Verdict.GREEN, mallory, setOf(alice)))
    }

    @Test fun `with no certificate tied to the number, certified is somebody else`() {
        assertEquals(Verdict.VIOLET, Verdict.received(Verdict.BLUE, alice, emptySet()))
    }

    @Test fun `what is not certified keeps its colour`() {
        assertEquals(Verdict.RED, Verdict.received(Verdict.RED, mallory, setOf(alice)))
        assertEquals(Verdict.YELLOW, Verdict.received(Verdict.YELLOW, mallory, setOf(alice)))
        assertEquals(Verdict.GREY, Verdict.received(Verdict.GREY, null, setOf(alice)))
    }
}
