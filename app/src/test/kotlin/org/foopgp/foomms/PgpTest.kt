package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun `a locked message is known by its first packet`() {
        // Session key for a public key: tag 1, old way of writing a packet (0x84, 0x85) and new (0xC1), version 3.
        assertTrue(Pgp.isMessage(bytes(0x84, 0x5E, 0x03)))
        assertTrue(Pgp.isMessage(bytes(0x85, 0x01, 0x0C, 0x03)))
        assertTrue(Pgp.isMessage(bytes(0xC1, 0x5E, 0x03)))
        assertTrue(Pgp.isMessage(bytes(0xC1, 0xC0, 0x10, 0x06)))
        // For a password: tag 3, version 4.
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
        // A text in UTF-8 that opens on an accented capital wears the tag, and not the version.
        assertFalse(Pgp.isMessage("États".toByteArray()))
        assertFalse(Pgp.isMessage("À demain".toByteArray()))
        assertFalse(Pgp.isMessage(bytes(0xC1)))
        assertFalse(Pgp.isMessage("-----BEGIN PGP PUBLIC KEY BLOCK-----".toByteArray()))
    }

    @Test fun `an armored message is found in a text, and told locked from signed`() {
        // A session key packet for a public key (0x84, version 3) and a one-pass signature packet (0x90, version 3).
        val locked = "-----BEGIN PGP MESSAGE-----\n\nhF4DAAAAAAAAAAAS\n=AAAA\n-----END PGP MESSAGE-----"
        val signed = "-----BEGIN PGP MESSAGE-----\nComment: x\n\nkA0DAAoWAAAAAAAAAAAB\n-----END PGP MESSAGE-----"
        assertEquals(locked, Pgp.armorIn("Bonjour,\n$locked\nÀ bientôt"))
        assertNull(Pgp.armorIn("Bonjour"))
        assertNull(Pgp.armorIn("-----BEGIN PGP MESSAGE-----\n\nhF4D"))
        assertTrue(Pgp.isLocked(locked))
        assertFalse(Pgp.isLocked(signed))
        assertFalse(Pgp.isLocked("-----BEGIN PGP MESSAGE-----\nhF4DAAAAAAAAAAAS\n-----END PGP MESSAGE-----"))
    }
}
