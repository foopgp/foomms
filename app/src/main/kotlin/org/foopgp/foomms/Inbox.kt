package org.foopgp.foomms

import android.content.Context
import android.net.Uri

/**
 * The PGP messages among the MMS this phone holds.
 *
 * The phone's messaging application receives every MMS and keeps it; this
 * one looks through what is kept for parts that are PGP messages, told by
 * their first bytes ([Pgp]), and knows nothing of the rest: no photo is
 * opened, no text is read past where a banner would stand.
 */
object Inbox {

    class Letter(val part: Long, val message: Long, val at: Long, val incoming: Boolean, val number: String?)

    fun letters(context: Context): List<Letter> {
        val found = mutableListOf<Pair<Long, Long>>()
        context.contentResolver.query(PARTS, arrayOf("_id", "mid", "ct", "text"), null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val type = rows.getString(2).orEmpty()
                if (SKIPPED.any { type.startsWith(it) }) continue
                // A text part may live in the table itself, with no file behind it.
                val start = rows.getString(3)?.take(64)?.toByteArray() ?: start(context, rows.getLong(0)) ?: continue
                if (Pgp.isMessage(start)) found += rows.getLong(0) to rows.getLong(1)
            }
        }
        return found.mapNotNull { (part, message) -> letter(context, part, message) }.sortedByDescending { it.at }
    }

    fun bytes(context: Context, part: Long): ByteArray? {
        context.contentResolver.query(PARTS, arrayOf("text"), "_id = ?", arrayOf(part.toString()), null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0)?.let { return it.toByteArray() }
        }
        return runCatching {
            context.contentResolver.openInputStream(Uri.withAppendedPath(PARTS, part.toString()))?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun start(context: Context, part: Long): ByteArray? = runCatching {
        context.contentResolver.openInputStream(Uri.withAppendedPath(PARTS, part.toString()))?.use { stream ->
            val buffer = ByteArray(64)
            val read = stream.read(buffer)
            if (read <= 0) null else buffer.copyOf(read)
        }
    }.getOrNull()

    private fun letter(context: Context, part: Long, message: Long): Letter? {
        val about = Uri.withAppendedPath(MESSAGES, message.toString())
        val (at, box) = context.contentResolver.query(about, arrayOf("date", "msg_box"), null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getLong(0) * 1000 to rows.getInt(1) else null
        } ?: return null
        val incoming = box == BOX_INBOX
        // Who wrote, for a message received; whom it went to, otherwise.
        val wanted = if (incoming) FROM else TO
        val number = context.contentResolver
            .query(Uri.withAppendedPath(about, "addr"), arrayOf("address", "type"), null, null, null)?.use { rows ->
                var number: String? = null
                while (rows.moveToNext()) if (rows.getInt(1) == wanted) number = number ?: rows.getString(0)
                number
            }
        return Letter(part, message, at, incoming, number)
    }

    private val MESSAGES = Uri.parse("content://mms")
    private val PARTS = Uri.parse("content://mms/part")

    /** What a PGP message is never labelled as, and so is not even opened. */
    private val SKIPPED = listOf("image/", "video/", "audio/", "application/smil", "text/x-vcard", "text/vcard")
    private const val BOX_INBOX = 1
    private const val FROM = 137
    private const val TO = 151
}
