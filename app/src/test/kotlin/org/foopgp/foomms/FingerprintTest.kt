package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {

    private val fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567"

    @Test fun `a fingerprint is read however it is written`() {
        assertEquals(fingerprint, Fingerprint.of(fingerprint.lowercase()))
        assertEquals(fingerprint, Fingerprint.of("0x$fingerprint"))
        assertEquals(fingerprint, Fingerprint.of("0123 4567 89AB CDEF 0123  4567 89AB CDEF 0123 4567"))
        assertEquals(fingerprint, Fingerprint.of("http://keys.example.org:11371/pks/lookup?op=get&search=0x$fingerprint"))
        assertEquals(fingerprint, Fingerprint.of("https://keys.example.org/pks/lookup?search=0x$fingerprint&op=get"))
        assertNull(Fingerprint.of("0x89ABCDEF01234567"))
        assertNull(Fingerprint.of("https://example.org/"))
        assertNull(Fingerprint.of(fingerprint.dropLast(1) + "G"))
    }

    @Test fun `the key id is its end, the address its vCard form`() {
        assertEquals(0x89ABCDEF01234567uL.toLong(), Fingerprint.keyId(fingerprint))
        assertEquals(fingerprint, Fingerprint.of(Fingerprint.address(fingerprint)))
    }
}
