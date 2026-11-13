package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTest {

    @Test fun plainTextIsKept() = assertEquals("Bonjour, ça va ? 😀", Backup.escaped("Bonjour, ça va ? 😀"))

    @Test fun marksAreEscaped() = assertEquals("a &lt;b&gt; &amp; &quot;c&quot; &apos;d&apos;", Backup.escaped("a <b> & \"c\" 'd'"))

    @Test fun lineEndsAndTabsAreNumbers() = assertEquals("a&#10;b&#13;c&#9;d", Backup.escaped("a\nb\rc\td"))

    // An SMS on a real phone held U+0005: written as is, the backup failed with "Illegal character".
    @Test fun controlCharactersAreLeftOut() = assertEquals("STOP", Backup.escaped("STOP\u0005"))

    @Test fun halfASurrogatePairIsReplaced() = assertEquals("a�b", Backup.escaped("a\uD83Db"))
}
