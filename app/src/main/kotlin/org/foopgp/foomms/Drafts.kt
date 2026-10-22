package org.foopgp.foomms

import android.content.Context

/**
 * What was being written to a number and not sent, kept until it is: Android
 * may close the conversation's page while another application is in front,
 * or a notification may open it anew, and the words would be gone.
 */
object Drafts {

    fun of(context: Context, number: String): String = kept(context).getString(key(number), null).orEmpty()

    /** Kept, or forgotten when there is nothing left to keep. */
    fun keep(context: Context, number: String, words: String) {
        val edit = kept(context).edit()
        if (words.isBlank()) edit.remove(key(number)) else edit.putString(key(number), words)
        edit.apply()
    }

    /** A number however it is written; a sender that is a name, by that name. */
    private fun key(number: String): String = Phone.plain(number) ?: number.trim().lowercase()

    private fun kept(context: Context) = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
}
