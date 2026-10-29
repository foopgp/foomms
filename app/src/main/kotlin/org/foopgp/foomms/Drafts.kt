package org.foopgp.foomms

import android.content.Context

/**
 * What was being written to a number and not sent, kept until it is: Android
 * may close the conversation's page while another application is in front,
 * or a notification may open it anew, and the words would be gone.
 *
 * With the words, when they were last touched and how they were to leave --
 * to one, to a group, to each --: the list shows them by that moment, and
 * the page that started them opens again on them.
 */
object Drafts {

    /** How a draft is to leave: to one number, as a group MMS, or to each of several. */
    enum class How { ONE, GROUP, EACH }

    class Draft(val numbers: List<String>, val words: String, val at: Long, val how: How)

    fun of(context: Context, number: String): String = of(context, listOf(number))

    /** To several: the same numbers, in whatever order, find the same words. */
    fun of(context: Context, numbers: List<String>): String = kept(context).getString(key(numbers), null).orEmpty()

    /** How the draft to these numbers was to leave, when there is one. */
    fun how(context: Context, numbers: List<String>): How? {
        val key = key(numbers)
        val kept = kept(context)
        if (kept.getString(key, null).isNullOrBlank()) return null
        return kept.getString(key + HOW, null)?.let { runCatching { How.valueOf(it) }.getOrNull() }
    }

    /** Kept, or forgotten when there is nothing left to keep. */
    fun keep(context: Context, number: String, words: String) = keep(context, listOf(number), words, How.ONE)

    fun keep(context: Context, numbers: List<String>, words: String, how: How = How.ONE) {
        val key = key(numbers)
        val kept = kept(context)
        val edit = kept.edit()
        if (words.isBlank()) {
            edit.remove(key).remove(key + AT).remove(key + HOW)
        } else if (words != kept.getString(key, null) || kept.getString(key + HOW, null) != how.name) {
            // Only what changed moves the draft up the list: a page opened and closed again leaves it where it was.
            edit.putString(key, words).putLong(key + AT, System.currentTimeMillis()).putString(key + HOW, how.name)
        }
        edit.apply()
    }

    /** Every draft to numbers, the latest first: those to a sender that is a name cannot be written to. */
    fun all(context: Context): List<Draft> {
        val kept = kept(context)
        return kept.all.keys.filter { '@' !in it }.mapNotNull { key ->
            val words = kept.getString(key, null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val numbers = key.split(',').filter { Phone.plain(it) != null }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val how = kept.getString(key + HOW, null)?.let { runCatching { How.valueOf(it) }.getOrNull() }
                ?: if (numbers.size > 1) How.EACH else How.ONE
            Draft(numbers, words, kept.getLong(key + AT, 0L), how)
        }.sortedByDescending { it.at }
    }

    /** Each number however it is written; a sender that is a name, by that name. */
    private fun key(numbers: List<String>): String =
        numbers.map { Phone.plain(it) ?: it.trim().lowercase() }.sorted().joinToString(",")

    private const val AT = "@at"
    private const val HOW = "@how"

    private fun kept(context: Context) = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
}
