package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openintents.openpgp.OpenPgpSignatureResult

class SignerTest {

    @Test fun `no signature, a missing key or a broken one say nobody, and do not throw`() {
        // The provider sends no user ids then, and openpgp-api's reader of them throws: the phone crashed on it.
        for (result in listOf(
            OpenPgpSignatureResult.createWithNoSignature(),
            OpenPgpSignatureResult.createWithKeyMissing(0x0123456789ABCDEFL),
            OpenPgpSignatureResult.createWithInvalidSignature(),
        )) {
            val said = Signer.of(result)
            assertEquals(result.result, said.result)
            assertEquals("", said.who)
            assertNull(Signer.eid(result))
        }
    }

    @Test fun `a valid signature names its signer, short identifier included`() {
        val result = OpenPgpSignatureResult.createWithValidSignature(
            OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED, "Aline Example <aline@example.org>", 0x0123456789ABCDEFL,
            arrayListOf("FN:Aline Example", "UID:urn:eid:u4abcdefghij"), arrayListOf(), null,
        )
        val said = Signer.of(result)
        assertTrue(said.who.startsWith("Aline Example"))
        assertEquals("u4abcdefghij", Signer.eid(result))
    }
}
