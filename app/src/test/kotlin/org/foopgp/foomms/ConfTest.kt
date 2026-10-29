package org.foopgp.foomms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfTest {

    @Test fun `with MMS on a text past one SMS is an MMS, with MMS off never`() {
        assertFalse(Conf.asMms(mms = true, pieces = 1))
        assertTrue(Conf.asMms(mms = true, pieces = 2))
        assertFalse(Conf.asMms(mms = false, pieces = 2))
        assertFalse(Conf.asMms(mms = false, pieces = 40))
    }
}
