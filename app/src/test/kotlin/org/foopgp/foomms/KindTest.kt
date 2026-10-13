package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KindTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun `a file is told by its first bytes, whatever it was labelled`() {
        assertEquals("image/jpeg", Kind.of(bytes(0xFF, 0xD8, 0xFF, 0xE0), "application/octet-stream").type)
        assertEquals("image/png", Kind.of(bytes(0x89) + "PNG\r\n".toByteArray(), null).type)
        assertEquals("audio/ogg", Kind.of("OggS".toByteArray() + ByteArray(8), "image/jpeg").type)
        assertEquals("audio/mp4", Kind.of(ByteArray(4) + "ftypM4A ".toByteArray(), null).type)
        assertEquals("video/3gpp", Kind.of(ByteArray(4) + "ftyp3gp4".toByteArray(), null).type)
        assertEquals("audio/3gpp", Kind.of(ByteArray(4) + "ftyp3gp4".toByteArray(), "audio/3gpp").type)
        assertEquals("text/x-vcard", Kind.of("BEGIN:VCARD\r\n".toByteArray(), null).type)
        assertEquals(Pgp.TYPE, Kind.of(bytes(0x84, 0x5E, 0x03), null).type)
    }

    @Test fun `bytes that say nothing leave the label its word`() {
        val told = Kind.of("some bytes".toByteArray(), "audio/amr-wb; rate=16000")
        assertEquals("audio/amr-wb", told.type)
        assertEquals("amrwb", told.extension)
        assertEquals("application/octet-stream", Kind.of(ByteArray(0), null).type)
    }

    @Test fun `a sender is the same however its number is written, or its name capitalised`() {
        assertTrue(Marks.same("06 00 00 00 00", "+33600000000"))
        assertTrue(Marks.same("MaBanque", "mabanque "))
        assertFalse(Marks.same("MaBanque", "MaBanque2"))
        assertFalse(Marks.same("0600000000", "0600000001"))
    }
}
