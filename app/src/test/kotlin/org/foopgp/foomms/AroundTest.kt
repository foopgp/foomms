package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class AroundTest {

    @Test fun `as many shown before as set, whatever they are, within the conversation`() {
        // Set to 3: the three shown before the one pressed, and none after.
        assertEquals(listOf(2, 3, 4), Around.others(count = 20, chosen = 5, reach = 3))
        // The first message has nothing before it.
        assertEquals(emptyList<Int>(), Around.others(count = 20, chosen = 0, reach = 3))
        assertEquals(listOf(0, 1), Around.others(count = 20, chosen = 2, reach = 10))
        // None: the message pressed opens alone.
        assertEquals(emptyList<Int>(), Around.others(count = 20, chosen = 5, reach = 0))
    }
}
