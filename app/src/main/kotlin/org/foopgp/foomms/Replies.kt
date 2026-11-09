package org.foopgp.foomms

import android.content.Context

/**
 * The message an answer begun here cites, kept with the draft: left and come back to, the
 * conversation cites it again. By the conversation's numbers, as drafts are.
 */
object Replies {

    private fun kept(context: Context) = context.getSharedPreferences("replies", Context.MODE_PRIVATE)

    private fun key(numbers: List<String>) = numbers.map(Phone::key).sorted().joinToString(",")

    /** [line] the message answered ("m12", "s34"), or none any more. */
    fun keep(context: Context, numbers: List<String>, line: String?) {
        val edit = kept(context).edit()
        if (line == null) edit.remove(key(numbers)) else edit.putString(key(numbers), line)
        edit.apply()
    }

    fun of(context: Context, numbers: List<String>): String? = kept(context).getString(key(numbers), null)
}
