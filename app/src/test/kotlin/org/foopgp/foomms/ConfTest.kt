package org.foopgp.foomms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfTest {

    @Test fun `with MMS allowed a text of eight SMS or more is an MMS, with MMS not allowed never`() {
        assertFalse(Conf.asMms(mms = true, pieces = 1))
        assertFalse(Conf.asMms(mms = true, pieces = 7))
        assertTrue(Conf.asMms(mms = true, pieces = 8))
        assertFalse(Conf.asMms(mms = false, pieces = 2))
        assertFalse(Conf.asMms(mms = false, pieces = 40))
    }
}
