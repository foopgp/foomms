package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class AroundTest {

    @Test fun `as many shown before and after as set, whatever they are, within the conversation`() {
        // Set to 2: two before, two after, five in all with the one pressed.
        assertEquals(listOf(3, 4, 6, 7), Around.others(count = 20, chosen = 5, reach = 2))
        // The last message has nothing after it, the first nothing before.
        assertEquals(listOf(17, 18), Around.others(count = 20, chosen = 19, reach = 2))
        assertEquals(listOf(1, 2), Around.others(count = 20, chosen = 0, reach = 2))
        // None: the message pressed opens alone.
        assertEquals(emptyList<Int>(), Around.others(count = 20, chosen = 5, reach = 0))
        assertEquals(listOf(0, 2), Around.others(count = 3, chosen = 1, reach = 4))
        assertEquals(emptyList<Int>(), Around.others(count = 1, chosen = 0, reach = 4))
    }
}
