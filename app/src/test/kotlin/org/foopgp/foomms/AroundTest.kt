package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class AroundTest {

    @Test fun `the nearest closed messages, before and after in turn, and no more than asked`() {
        val all: (Int) -> Boolean = { true }
        assertEquals(listOf(4, 6), Around.others(count = 20, chosen = 5, most = 2, closed = all))
        assertEquals(listOf(4, 6, 3), Around.others(count = 20, chosen = 5, most = 3, closed = all))
        // The last message has nothing after it, the first nothing before.
        assertEquals(listOf(18, 17), Around.others(count = 20, chosen = 19, most = 2, closed = all))
        assertEquals(listOf(1, 2), Around.others(count = 20, chosen = 0, most = 2, closed = all))
        // None asked for: the message pressed opens alone.
        assertEquals(emptyList<Int>(), Around.others(count = 20, chosen = 5, most = 0, closed = all))
        assertEquals(listOf(0, 2), Around.others(count = 3, chosen = 1, most = 5, closed = all))
        assertEquals(emptyList<Int>(), Around.others(count = 1, chosen = 0, most = 5, closed = all))
    }

    @Test fun `what is open already, or is no PGP message, is stepped over`() {
        val closed = setOf(0, 2, 9)
        assertEquals(listOf(2, 9), Around.others(count = 10, chosen = 5, most = 2) { it in closed })
        assertEquals(listOf(2, 9, 0), Around.others(count = 10, chosen = 5, most = 5) { it in closed })
    }
}
