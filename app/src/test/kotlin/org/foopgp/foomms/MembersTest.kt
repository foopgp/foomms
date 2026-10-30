package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class MembersTest {

    @Test fun `the members of a group, written and read back`() {
        val fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567"
        val written = listOf(
            Members.Member("Anne, la cousine", "+33611111111", fingerprint, "sRyUhEbNU5OwyLEjfSwaXAe_42.17-002.76"),
            Members.Member("Bruno", "06 22 22 22 22", null),
        )
        val read = Members.read(Members.write(written))
        assertEquals(2, read.size)
        assertEquals("Anne, la cousine", read[0].name)
        assertEquals("+33611111111", read[0].number)
        assertEquals(fingerprint, read[0].fingerprint)
        assertEquals("sRyUhEbNU5OwyLEjfSwaXAe_42.17-002.76", read[0].eid)
        assertEquals(null, read[1].eid)
        assertEquals("0622222222", read[1].number)
        assertEquals(null, read[1].fingerprint)
    }

    @Test fun `a message's parts without words show the group first, then a clip each`() {
        assertEquals("👥 📎", Members.glyphs(listOf("photo.jpg", Members.PART)))
        assertEquals("📎 📎", Members.glyphs(listOf("a.jpg", "b.jpg")))
        assertEquals("👥 📎 📎 📎", Members.glyphs(listOf(Members.PART, "1", "2", "3", "4")))
    }
}
