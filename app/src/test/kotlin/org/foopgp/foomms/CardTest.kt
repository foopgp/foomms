package org.foopgp.foomms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CardTest {

    private val card = Card.of(
        listOf(
            "BEGIN:VCARD",
            "VERSION:4.0",
            "FN:Aline Example",
            "UID:urn:eid:u4AAAAAAAAAAAAAAAAAAAAAAe_42.17-002.76",
            "TEL:+33600000000",
            "ADR:;;1 rue de l'Exemple;Marseille;;13001;France",
            "NOTE:Born in Marseille\\, a long time ago\\; that is all this note sa",
            " ys.",
            "EMAIL;PREF=1:aline@example.org",
            "EMAIL:aline@example.net",
            "KEY;MEDIATYPE=application/pgp-keys:https://keys.example.org/pks/lookup?op=get&search=0x0123456789ABCDEF0123456789ABCDEF01234567",
            "KEY:data:application/pgp-keys;base64,mDMEAAAA",
            "PHOTO:data:image/jpeg;base64,/9j/4AAQ",
            "END:VCARD",
        ).joinToString("\r\n"),
    )

    @Test fun `a card gives what a contact can take`() {
        assertEquals("Aline Example", card.name)
        assertEquals(listOf("aline@example.org", "aline@example.net"), card.emails)
        assertEquals(listOf("+33600000000"), card.phones)
        assertEquals(listOf("1 rue de l'Exemple\nMarseille\n13001\nFrance"), card.addresses)
        assertEquals(listOf("Born in Marseille, a long time ago; that is all this note says."), card.notes)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10), card.photo)
    }

    @Test fun `the key it names is the address, and holds the fingerprint`() {
        assertEquals("0123456789ABCDEF0123456789ABCDEF01234567", Fingerprint.of(card.key!!))
    }

    @Test fun `what would be written is told a line each`() {
        assertEquals(
            listOf(
                "Aline Example", "aline@example.org", "aline@example.net", "+33600000000",
                "1 rue de l'Exemple Marseille 13001 France", "Born in Marseille, a long time ago; that is all this note says.",
            ),
            card.told(),
        )
    }

    @Test fun `the same card in the words of 3_0 gives the same`() {
        val old = Card.of(
            listOf(
                "BEGIN:VCARD",
                "VERSION:3.0",
                "N:Aline Example;;;;",
                "FN:Aline Example",
                "TEL:+33600000000",
                "EMAIL;TYPE=INTERNET,PREF:aline@example.org",
                "EMAIL;TYPE=INTERNET:aline@example.net",
                "KEY;TYPE=PGP;VALUE=uri:https://keys.example.org/pks/lookup?op=get&search=0x0123456789ABCDEF0123456789ABCDEF01234567",
                "KEY;TYPE=PGP;ENCODING=b:mDMEAAAA",
                "PHOTO;TYPE=JPEG;ENCODING=b:/9j/4AAQ",
                "END:VCARD",
            ).joinToString("\r\n"),
        )
        assertEquals("Aline Example", old.name)
        assertEquals(listOf("aline@example.org", "aline@example.net"), old.emails)
        assertEquals("0123456789ABCDEF0123456789ABCDEF01234567", Fingerprint.of(old.key!!))
        assertArrayEquals(card.photo, old.photo)
    }
}
