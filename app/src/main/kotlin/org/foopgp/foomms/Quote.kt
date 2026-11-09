package org.foopgp.foomms

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A reply's first line, citing the message it answers: `> 18:42:07> On se voit demain…`,
 * travelling inside the text itself -- neither SMS nor MMS carries a reference to an earlier
 * message. The time is when the message cited was sent, with its day in ISO 8601 when it was
 * not the reply's; then at most [MOST] characters of it, on one line. Any phone shows it; a
 * locked reply keeps it inside its envelope; foomms draws it apart and goes to what it cites.
 */
object Quote {

    /** What is cited of a message, at most: a line of a phone's width, once the time is before it. */
    const val MOST = 50

    /** How far apart the two phones may say a message was sent: the network's own delays, an MMS's. */
    const val NEAR = 2 * 60 * 1000L

    /** What stands for the words of a message cited closed, or locked and answered in clear: the same in every language. */
    const val LOCKED = "*encrypted message*"

    /** A cited message as a reply's first line says it: [day] when another than the reply's, [time], and some of its words. */
    class Cited(val day: String?, val time: String, val words: String) {
        /** Not words of the message but what foomms said of it: a part's type, name and size, or [LOCKED]. */
        val part: Boolean get() = words == LOCKED || PART.matches(words)
    }

    /** A type first is enough: what an earlier version cut before the size is still a part's. */
    private val PART = Regex("""^[a-z]+/[\w.+-]+( .*)?$""")

    private val LINE = Regex("""^> (?:(\d{4}-\d{2}-\d{2}) )?(\d{2}:\d{2}:\d{2})>(?: (.*))?$""")

    /** The first line of a reply to what was sent [at], saying [words] of it: [now] is when the reply leaves. */
    fun line(at: Long, words: String, now: Long = System.currentTimeMillis()): String {
        val day = if (sameDay(at, now)) "" else SimpleDateFormat("yyyy-MM-dd ", Locale.ROOT).format(at)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(at)
        val said = excerpt(words)
        return if (said.isEmpty()) "> $day$time>" else "> $day$time> $said"
    }

    /**
     * A part as a reply cites it: its type, its name, its size in bytes, and "+N" for the others it came
     * with -- the name shortened, never the rest, to stay within [MOST].
     */
    fun part(type: String, name: String, size: Long?, more: Int): String {
        val tail = listOfNotNull(size?.toString(), if (more > 0) "+$more" else null).joinToString(" ")
        val room = MOST - type.length - tail.length - 2
        val named = when {
            room <= 1 -> ""
            name.codePointCount(0, name.length) <= room -> name
            else -> name.substring(0, name.offsetByCodePoints(0, room - 1)) + "…"
        }
        return listOf(type, named, tail).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Some of a message's words, as a reply cites them: on one line, without the lines it cited
     * itself, cut at [MOST] characters -- characters, not bytes: an accent or an emoji is one -- with "…".
     */
    fun excerpt(words: String): String {
        val own = words.lines().dropWhile { LINE.matches(it.trimEnd()) || it.startsWith(">") }.joinToString(" ")
        val one = own.replace(Regex("""\s+"""), " ").trim()
        if (one.codePointCount(0, one.length) <= MOST) return one
        return one.substring(0, one.offsetByCodePoints(0, MOST)).trimEnd() + "…"
    }

    /** A message's words, its citation set apart: null when it cites nothing. */
    fun split(words: String): Pair<Cited, String>? {
        val first = words.substringBefore('\n')
        val found = LINE.matchEntire(first.trimEnd()) ?: return null
        val (day, time, said) = found.destructured
        return Cited(day.ifEmpty { null }, time, said) to words.substringAfter('\n', "")
    }

    /** When [cited] says the message was sent, for a reply that came [replyAt]: its day, else the reply's. */
    fun whenSaid(cited: Cited, replyAt: Long): Long? = runCatching {
        val day = cited.day ?: SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(replyAt)
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse("$day ${cited.time}")!!.time
    }.getOrNull()

    /**
     * Of the messages sent at [ats], the one [cited] means: within [NEAR] of the time it says, and of
     * those, the one whose words begin as it cites them, or else the nearest. Null when none is near.
     */
    fun find(cited: Cited, replyAt: Long, ats: List<Long>, wordsOf: (Int) -> String?): Int? {
        val meant = whenSaid(cited, replyAt) ?: return null
        val near = ats.indices.filter { kotlin.math.abs(ats[it] - meant) <= NEAR }
        if (near.isEmpty()) return null
        val begins = cited.words.removeSuffix("…").trim()
        if (begins.isNotEmpty()) near.firstOrNull { wordsOf(it)?.let(::excerpt)?.startsWith(begins) == true }?.let { return it }
        return near.minByOrNull { kotlin.math.abs(ats[it] - meant) }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val one = Calendar.getInstance().apply { timeInMillis = a }
        val two = Calendar.getInstance().apply { timeInMillis = b }
        return one.get(Calendar.YEAR) == two.get(Calendar.YEAR) && one.get(Calendar.DAY_OF_YEAR) == two.get(Calendar.DAY_OF_YEAR)
    }
}
