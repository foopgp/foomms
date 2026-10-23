package org.foopgp.foomms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfTest {

    @Test fun `a text becomes an MMS past five SMS, or past one when MMS are preferred`() {
        assertFalse(Conf.asMms(pieces = 1, prefer = false))
        assertFalse(Conf.asMms(pieces = 5, prefer = false))
        assertTrue(Conf.asMms(pieces = 6, prefer = false))
        assertFalse(Conf.asMms(pieces = 1, prefer = true))
        assertTrue(Conf.asMms(pieces = 2, prefer = true))
    }
}
