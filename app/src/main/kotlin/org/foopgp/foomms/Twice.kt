package org.foopgp.foomms

/**
 * A second press on the send button, told from a second message: the same
 * words, the same attachment, to the same people, again within a moment.
 * Encrypted only, foodjis answers without a screen, and two presses made two
 * messages; a person writing again types first.
 */
object Twice {

    private var last: Int? = null
    private var at = 0L

    /** True when [what] was sent a moment ago: the press is dropped. Main thread only. */
    fun again(vararg what: Any?): Boolean = at(android.os.SystemClock.elapsedRealtime(), *what)

    /** The same, at [now]: what a test sets. */
    internal fun at(now: Long, vararg what: Any?): Boolean {
        val key = what.contentDeepHashCode()
        if (key == last && now - at < WINDOW_MS) return true
        last = key
        at = now
        return false
    }

    private const val WINDOW_MS = 2_000L
}
