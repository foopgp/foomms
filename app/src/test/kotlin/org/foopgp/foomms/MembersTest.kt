package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class MembersTest {

    @Test fun `the members of a group, written and read back`() {
        val fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567"
        val written = listOf(
            Members.Member("Anne, la cousine", "+33611111111", fingerprint),
            Members.Member("Bruno", "06 22 22 22 22", null),
        )
        val read = Members.read(Members.write(written))
        assertEquals(2, read.size)
        assertEquals("Anne, la cousine", read[0].name)
        assertEquals("+33611111111", read[0].number)
        assertEquals(fingerprint, read[0].fingerprint)
        assertEquals("0622222222", read[1].number)
        assertEquals(null, read[1].fingerprint)
    }
}
