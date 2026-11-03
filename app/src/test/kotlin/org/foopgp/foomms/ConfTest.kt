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

    @Test fun `the person's limit moves where a text becomes an MMS`() {
        assertFalse(Conf.asMms(mms = true, pieces = 20, most = 20))
        assertTrue(Conf.asMms(mms = true, pieces = 21, most = 20))
        assertTrue(Conf.asMms(mms = true, pieces = 2, most = 1))
    }

    @Test fun `the longest setting keeps an armored message under what foodjis shows as text`() {
        assertTrue(Conf.SMS_MOST_EVER * 153 <= 8 * 1024)
    }
}
