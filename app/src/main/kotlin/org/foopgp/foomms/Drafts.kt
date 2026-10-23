package org.foopgp.foomms

import android.content.Context

/**
 * What was being written to a number and not sent, kept until it is: Android
 * may close the conversation's page while another application is in front,
 * or a notification may open it anew, and the words would be gone.
 */
object Drafts {

    fun of(context: Context, number: String): String = of(context, listOf(number))

    /** To several: the same numbers, in whatever order, find the same words. */
    fun of(context: Context, numbers: List<String>): String = kept(context).getString(key(numbers), null).orEmpty()

    /** Kept, or forgotten when there is nothing left to keep. */
    fun keep(context: Context, number: String, words: String) = keep(context, listOf(number), words)

    fun keep(context: Context, numbers: List<String>, words: String) {
        val edit = kept(context).edit()
        if (words.isBlank()) edit.remove(key(numbers)) else edit.putString(key(numbers), words)
        edit.apply()
    }

    /** Each number however it is written; a sender that is a name, by that name. */
    private fun key(numbers: List<String>): String =
        numbers.map { Phone.plain(it) ?: it.trim().lowercase() }.sorted().joinToString(",")

    private fun kept(context: Context) = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
}
