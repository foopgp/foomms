package org.foopgp.foomms

import android.content.Context

/**
 * What the person said of a sender: not to be announced. Kept here, by the
 * number or the name the sender writes under.
 */
object Marks {

    fun muted(context: Context, sender: String?): Boolean {
        if (sender == null) return false
        return kept(context).getStringSet(MUTED, emptySet()).orEmpty().any { same(it, sender) }
    }

    fun mute(context: Context, sender: String, on: Boolean) {
        val now = kept(context).getStringSet(MUTED, emptySet()).orEmpty().filterNot { same(it, sender) }.toMutableSet()
        if (on) now += sender.trim()
        kept(context).edit().putStringSet(MUTED, now).apply()
    }

    /** A number however it is written, or a name whatever its capitals. */
    internal fun same(one: String, other: String): Boolean = Phone.same(one, other) || one.trim().equals(other.trim(), ignoreCase = true)

    private fun kept(context: Context) = context.getSharedPreferences("senders", Context.MODE_PRIVATE)

    private const val MUTED = "muted"
}
