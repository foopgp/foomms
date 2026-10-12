package org.foopgp.foomms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun `a locked message is known by its first packet`() {
        // Session key for a public key: tag 1, old way of writing a packet (0x84, 0x85) and new (0xC1).
        assertTrue(Pgp.isMessage(bytes(0x84, 0x5E, 0x03)))
        assertTrue(Pgp.isMessage(bytes(0x85, 0x01, 0x0C)))
        assertTrue(Pgp.isMessage(bytes(0xC1, 0x5E, 0x03)))
        // For a password: tag 3.
        assertTrue(Pgp.isMessage(bytes(0x8C, 0x0D, 0x04)))
        assertTrue(Pgp.isMessage(bytes(0xC3, 0x0D, 0x04)))
        assertTrue(Pgp.isMessage("\n-----BEGIN PGP MESSAGE-----\n\nhF4D".toByteArray()))
    }

    @Test fun `what is not one is left alone`() {
        assertFalse(Pgp.isMessage(ByteArray(0)))
        // A photo, a certificate (tag 6), a signature (tag 2), a text.
        assertFalse(Pgp.isMessage(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertFalse(Pgp.isMessage(bytes(0x99, 0x01, 0x0D)))
        assertFalse(Pgp.isMessage(bytes(0xC2, 0x5E)))
        assertFalse(Pgp.isMessage("Bonjour".toByteArray()))
        assertFalse(Pgp.isMessage("-----BEGIN PGP PUBLIC KEY BLOCK-----".toByteArray()))
    }
}
