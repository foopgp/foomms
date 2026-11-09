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

    @Test fun `a PGP file travels as one, whatever Android calls it`() {
        assertEquals(Pgp.TYPE, Pgp.typeOf("essai.pgp", "application/pgp-signature", bytes(0xA3, 0x01)))
        assertEquals(Pgp.TYPE, Pgp.typeOf("Lettre.GPG", "application/octet-stream", bytes(0xA3, 0x01)))
        assertEquals(Pgp.TYPE, Pgp.typeOf("lettre", "application/octet-stream", bytes(0x84, 0x5E, 0x03)))
        assertEquals(Pgp.TYPE, Pgp.typeOf("lettre.asc", "text/plain", "-----BEGIN PGP MESSAGE-----\n".toByteArray()))
        // A detached signature or a certificate under .asc keeps its type, as does a file of another kind.
        assertEquals("text/plain", Pgp.typeOf("lettre.asc", "text/plain", "-----BEGIN PGP SIGNATURE-----\n".toByteArray()))
        assertEquals("application/pdf", Pgp.typeOf("lettre.pdf", "application/pdf", "%PDF-1.7".toByteArray()))
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

    @Test fun `a locked message names the keys it is for`() {
        fun session(old: Boolean, id: Long): ByteArray {
            val body = byteArrayOf(3) + ByteArray(8) { (id ushr (56 - 8 * it)).toByte() } + byteArrayOf(18, 0, 0)
            return byteArrayOf((if (old) 0x84 else 0xC1).toByte(), body.size.toByte()) + body
        }
        val message = session(true, 0x0123456789ABCDEFL) + session(false, -0x0123456789ABCDF0L) +
            session(true, 0L) + session(false, 0x0123456789ABCDEFL) + byteArrayOf(0xD2.toByte(), 2, 1, 0)
        val armor = "-----BEGIN PGP MESSAGE-----\n\n" +
            java.util.Base64.getEncoder().encodeToString(message).chunked(64).joinToString("\n") +
            "\n=AAAA\n-----END PGP MESSAGE-----"
        // Each key once, in the order written; the one that asked not to be named is not.
        assertEquals(listOf(0x0123456789ABCDEFL, -0x0123456789ABCDF0L), Pgp.recipients(armor))
        assertEquals(emptyList<Long>(), Pgp.recipients("-----BEGIN PGP MESSAGE-----\n\nkA0DAAoWAAAAAAAAAAAB\n-----END PGP MESSAGE-----"))
        assertEquals(emptyList<Long>(), Pgp.recipients("not armor"))
        // A part of an MMS: the same message bare, and armored.
        assertEquals(listOf(0x0123456789ABCDEFL, -0x0123456789ABCDF0L), Pgp.recipients(message))
        assertEquals(listOf(0x0123456789ABCDEFL, -0x0123456789ABCDF0L), Pgp.recipients(armor.toByteArray()))
    }
}
