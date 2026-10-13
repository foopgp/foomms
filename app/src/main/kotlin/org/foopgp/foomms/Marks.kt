package org.foopgp.foomms

import android.content.Context

/**
 * What the person said of a sender: blocked, or not to be announced. Kept
 * here, by the number or the name the sender writes under, so that it holds
 * whether or not this is the phone's messaging application.
 *
 * A blocked sender's messages still arrive and are kept: nothing is thrown
 * away on the word of a list. They are not announced, they arrive read, and
 * their conversation shows what it is.
 */
object Marks {

    fun blocked(context: Context, sender: String?): Boolean = held(context, BLOCKED, sender)

    fun muted(context: Context, sender: String?): Boolean = held(context, MUTED, sender)

    fun block(context: Context, sender: String, on: Boolean) = set(context, BLOCKED, sender, on)

    fun mute(context: Context, sender: String, on: Boolean) = set(context, MUTED, sender, on)

    /** Everybody blocked, as they were written when they were. */
    fun allBlocked(context: Context): List<String> = kept(context).getStringSet(BLOCKED, emptySet()).orEmpty().sorted()

    /** A number however it is written, or a name whatever its capitals. */
    internal fun same(one: String, other: String): Boolean = Phone.same(one, other) || one.trim().equals(other.trim(), ignoreCase = true)

    private fun held(context: Context, list: String, sender: String?): Boolean {
        if (sender == null) return false
        return kept(context).getStringSet(list, emptySet()).orEmpty().any { same(it, sender) }
    }

    private fun set(context: Context, list: String, sender: String, on: Boolean) {
        val now = kept(context).getStringSet(list, emptySet()).orEmpty().filterNot { same(it, sender) }.toMutableSet()
        if (on) now += sender.trim()
        kept(context).edit().putStringSet(list, now).apply()
    }

    private fun kept(context: Context) = context.getSharedPreferences("senders", Context.MODE_PRIVATE)

    private const val BLOCKED = "blocked"
    private const val MUTED = "muted"
}
