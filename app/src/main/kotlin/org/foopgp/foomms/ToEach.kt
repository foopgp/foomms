package org.foopgp.foomms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * The same message to each of several, separately: one copy each, a
 * second apart -- networks hold many messages from one sender in one
 * moment for spam --, on its way even once the page that asked is closed.
 * Every copy is filed in one conversation of all of them, as messaging
 * applications file a sending to each, at the moment it was asked: what
 * each copy's conversation shows, and how the copies are known for one
 * ([Store.sentWith]). Each copy's own leaving is its date sent.
 */
object ToEach {

    fun send(context: Context, whom: List<Pair<String, String>>, text: String?, piece: Pdu.Part?, mms: Boolean) {
        val at = System.currentTimeMillis()
        // Android names a conversation by its numbers: those of a group MMS may be these very ones. Its conversation
        // is the group's then, and each copy goes to the conversation of whom it went to instead, its moment the same.
        val into = if (Keep.ours(context)) runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(context, whom.map { it.first }.toSet()) }.getOrNull()
            ?.takeIf { Store.toEach(context, it) } else null
        thread {
            val failed = ArrayList<String>()
            for ((rank, one) in whom.withIndex()) {
                if (rank > 0) Thread.sleep(SPACING)
                val (number, name) = one
                runCatching {
                    if (!mms) Outbox.text(context, number, text.orEmpty(), at, into)
                    else {
                        val transaction = "T" + java.lang.Long.toHexString(System.nanoTime())
                        val parts = listOfNotNull(text?.let { Pdu.Part.text(it) }, piece)
                        Outbox.send(context, listOf(number), Pdu.send(transaction, listOf(number), parts), transaction, at, into)
                    }
                }.onFailure { failed += name }
            }
            if (failed.isNotEmpty()) Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.many_failed, failed.joinToString(", ")), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** How long between two copies: networks are said to hold more than a message or two a second from one sender. */
    private const val SPACING = 1000L
}
