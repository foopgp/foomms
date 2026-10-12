package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class BookTest {

    @Test fun `a user id gives the properties a contact can take`() {
        assertEquals(listOf("EMAIL:aline@example.org"), Book.properties("EMAIL:aline@example.org"))
        assertEquals(listOf("TEL;TYPE=cell:+33600000000"), Book.properties("TEL;TYPE=cell:+33600000000"))
        assertEquals(listOf("EMAIL:aline@example.org"), Book.properties("Aline Example (work) <aline@example.org>"))
        assertEquals(emptyList<String>(), Book.properties("Aline Example"))
    }

    @Test fun `a vCard value is read without its escapes`() {
        assertEquals("Born in Marseille, at 20:43; then\nmore \\ less", Book.plain("Born in Marseille\\, at 20:43\\; then\\nmore \\\\ less"))
    }
}
