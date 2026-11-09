package org.foopgp.foomms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class QuoteTest {

    private fun at(said: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(said)!!.time

    @Test fun `the same day the time alone, another day the day first`() {
        assertEquals("> 18:42:07> On se voit ?", Quote.line(at("2026-11-08 18:42:07"), "On se voit ?", at("2026-11-08 19:00:00")))
        assertEquals("> 2026-11-07 18:42:07> On se voit ?", Quote.line(at("2026-11-07 18:42:07"), "On se voit ?", at("2026-11-08 09:00:00")))
    }

    @Test fun `nothing to cite, the time alone`() {
        assertEquals("> 18:42:07>", Quote.line(at("2026-11-08 18:42:07"), "", at("2026-11-08 19:00:00")))
    }

    @Test fun `one line, cut at fifty characters, not bytes`() {
        val long = "é".repeat(30) + "\n" + "😀".repeat(30)
        val cut = Quote.excerpt(long)
        assertEquals(51, cut.codePointCount(0, cut.length))
        assert(cut.endsWith("…"))
        assertEquals("a b", Quote.excerpt("a\n\n b"))
    }

    @Test fun `a citation is not cited again`() {
        assertEquals("Oui, 18 h.", Quote.excerpt("> 18:42:07> On se voit demain ?\nOui, 18 h."))
    }

    @Test fun `a reply read back, its citation apart`() {
        val (cited, rest) = Quote.split("> 2026-11-07 18:42:07> On se voit…\nOui, 18 h.")!!
        assertEquals("2026-11-07", cited.day)
        assertEquals("18:42:07", cited.time)
        assertEquals("On se voit…", cited.words)
        assertEquals("Oui, 18 h.", rest)
        assertEquals("", Quote.split("> 18:42:07>\n")!!.first.words)
        assertNull(Quote.split("> a line quoted by another tool\nhi"))
    }

    @Test fun `found by its time, the words choosing among the near ones`() {
        val cited = Quote.split("> 18:42:07> Deux\nok")!!.first
        val reply = at("2026-11-08 18:50:00")
        val ats = listOf(at("2026-11-08 18:42:05"), at("2026-11-08 18:42:30"), at("2026-11-08 17:00:00"))
        val words = listOf("Un", "Deux", "Deux")
        assertEquals(1, Quote.find(cited, reply, ats) { words[it] })
        assertNull(Quote.find(Quote.split("> 12:00:00>\n")!!.first, reply, ats) { words[it] })
    }

    @Test fun `what foomms wrote of a part or a lock is told from words`() {
        assert(Quote.split("> 18:42:07> image/jpeg photo.jpg 183476 +1\nok")!!.first.part)
        assert(!Quote.split("> 18:42:07> On se voit à 18 h\nok")!!.first.part)
        assert(Quote.split("> 18:42:07> ${Quote.LOCKED}\nok")!!.first.part)
    }

    @Test fun `a part's name is shortened, never its type or size`() {
        val said = Quote.part("application/octet-stream", "sms-Association-foopgp-20261015-1501.xml", 5263, 1)
        assertEquals(Quote.MOST, said.codePointCount(0, said.length))
        assert(said.startsWith("application/octet-stream sms-"))
        assert(said.endsWith("… 5263 +1"))
        assertEquals("image/jpeg a.jpg 12", Quote.part("image/jpeg", "a.jpg", 12, 0))
        assert(Quote.split("> 01:31:35> application/octet-stream sms-Association-foopgp-20…\nok")!!.first.part)
    }
}
