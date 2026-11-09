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
 * Each copy is filed in the conversation of whom it went to, where an
 * answer comes, dated the moment the sending was asked -- the same for
 * every copy, which is how they are known for one ([Store.sentWith]), and
 * survives a backup --; each copy's own leaving is its date sent.
 */
object ToEach {

    /** One copy: to whom, by number and name, and what it carries -- the same for all, or encrypted for each alone. */
    class Copy(val number: String, val name: String, val text: String?, val piece: Pdu.Part?)

    fun send(context: Context, copies: List<Copy>, mms: Boolean) {
        val at = System.currentTimeMillis()
        thread {
            val failed = ArrayList<String>()
            for ((rank, one) in copies.withIndex()) {
                if (rank > 0) Thread.sleep(SPACING)
                runCatching {
                    if (!mms) Outbox.text(context, one.number, one.text.orEmpty(), at)
                    else {
                        val transaction = Outbox.transaction()
                        val parts = listOfNotNull(one.text?.let { Pdu.Part.text(it) }, one.piece)
                        Outbox.send(context, listOf(one.number), Pdu.send(transaction, listOf(one.number), parts), transaction, at)
                    }
                }.onFailure { failed += one.name }
            }
            if (failed.isNotEmpty()) Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.many_failed, failed.joinToString(", ")), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** How long between two copies: networks are said to hold more than a message or two a second from one sender. */
    private const val SPACING = 1000L
}
