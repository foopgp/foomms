package org.foopgp.foomms

import android.content.Context
import android.net.Uri

/**
 * The messages the phone holds, SMS and MMS together, as conversations and as
 * the lines of one. Read from where the phone's messaging application keeps
 * them: nothing is copied.
 */
object Store {

    class Talk(val thread: Long, val number: String?, val at: Long, val last: String, val incoming: Boolean, val unread: Boolean)

    class Line(val at: Long, val incoming: Boolean, val body: String, val attachments: Int)

    /** Every conversation, by its latest message, newest first. */
    fun talks(context: Context): List<Talk> {
        val latest = HashMap<Long, Talk>()
        fun offer(talk: Talk) {
            val known = latest[talk.thread]
            if (known == null || known.at < talk.at) latest[talk.thread] = talk
        }
        context.contentResolver.query(
            SMS, arrayOf("thread_id", "address", "date", "body", "type", "read"), null, null, "date DESC",
        )?.use { rows ->
            val seen = HashSet<Long>()
            while (rows.moveToNext()) {
                val thread = rows.getLong(0)
                // Sorted newest first: the first of a conversation is its latest.
                if (!seen.add(thread)) continue
                offer(Talk(thread, rows.getString(1), rows.getLong(2), rows.getString(3).orEmpty(), rows.getInt(4) == SMS_INBOX, rows.getInt(5) == 0))
            }
        }
        context.contentResolver.query(MMS, arrayOf("_id", "thread_id", "date", "msg_box", "read"), null, null, "date DESC")?.use { rows ->
            val seen = HashSet<Long>()
            while (rows.moveToNext()) {
                val thread = rows.getLong(1)
                if (!seen.add(thread)) continue
                val at = rows.getLong(2) * 1000
                if ((latest[thread]?.at ?: 0) >= at) continue
                val incoming = rows.getInt(3) == MMS_INBOX
                val (words, attachments) = parts(context, rows.getLong(0))
                offer(Talk(thread, number(context, rows.getLong(0), incoming), at, words.ifEmpty { "📎".repeat(minOf(attachments, 3)) }, incoming, rows.getInt(4) == 0))
            }
        }
        return latest.values.sortedByDescending { it.at }
    }

    /** One conversation, oldest first. */
    fun lines(context: Context, thread: Long): List<Line> {
        val lines = ArrayList<Line>()
        context.contentResolver.query(
            SMS, arrayOf("date", "body", "type"), "thread_id = ?", arrayOf(thread.toString()), null,
        )?.use { rows ->
            while (rows.moveToNext()) lines += Line(rows.getLong(0), rows.getInt(2) == SMS_INBOX, rows.getString(1).orEmpty(), 0)
        }
        context.contentResolver.query(
            MMS, arrayOf("_id", "date", "msg_box"), "thread_id = ?", arrayOf(thread.toString()), null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                val (words, attachments) = parts(context, rows.getLong(0))
                lines += Line(rows.getLong(1) * 1000, rows.getInt(2) == MMS_INBOX, words, attachments)
            }
        }
        return lines.sortedBy { it.at }
    }

    /**
     * The conversation a number has, or none: found among the SMS the phone
     * holds, since asking the phone for it would make one.
     */
    fun thread(context: Context, number: String): Long? {
        context.contentResolver.query(SMS, arrayOf("thread_id", "address"), null, null, "date DESC")?.use { rows ->
            val seen = HashSet<Long>()
            while (rows.moveToNext()) {
                if (!seen.add(rows.getLong(0))) continue
                val address = rows.getString(1) ?: continue
                if (Phone.same(address, number) || Phone.plain(address) == Phone.plain(number)) return rows.getLong(0)
            }
        }
        return null
    }

    /** The number of a conversation that has only MMS, or whose SMS did not say. */
    fun number(context: Context, thread: Long): String? {
        context.contentResolver.query(SMS, arrayOf("address"), "thread_id = ?", arrayOf(thread.toString()), "date DESC")?.use { rows ->
            while (rows.moveToNext()) rows.getString(0)?.let { return it }
        }
        context.contentResolver.query(MMS, arrayOf("_id", "msg_box"), "thread_id = ?", arrayOf(thread.toString()), "date DESC")?.use { rows ->
            while (rows.moveToNext()) number(context, rows.getLong(0), rows.getInt(1) == MMS_INBOX)?.let { return it }
        }
        return null
    }

    /** The words of an MMS, and how many of its parts are something else. */
    private fun parts(context: Context, message: Long): Pair<String, Int> {
        val words = StringBuilder()
        var attachments = 0
        context.contentResolver.query(PARTS, arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(message.toString()), null)?.use { rows ->
            while (rows.moveToNext()) {
                val type = rows.getString(1).orEmpty()
                when {
                    type == "application/smil" -> Unit
                    type.startsWith("text/plain") -> {
                        val text = rows.getString(2) ?: runCatching {
                            context.contentResolver.openInputStream(Uri.withAppendedPath(PARTS, rows.getString(0)))
                                ?.use { String(it.readBytes(), Charsets.UTF_8) }
                        }.getOrNull()
                        if (!text.isNullOrEmpty()) words.append(if (words.isEmpty()) "" else "\n").append(text)
                    }
                    else -> attachments++
                }
            }
        }
        return words.toString() to attachments
    }

    private fun number(context: Context, message: Long, incoming: Boolean): String? {
        val wanted = if (incoming) FROM else TO
        return context.contentResolver
            .query(Uri.parse("content://mms/$message/addr"), arrayOf("address", "type"), null, null, null)?.use { rows ->
                var number: String? = null
                while (rows.moveToNext()) if (rows.getInt(1) == wanted) number = number ?: rows.getString(0)
                number
            }
    }

    private val SMS = Uri.parse("content://sms")
    private val MMS = Uri.parse("content://mms")
    private val PARTS = Uri.parse("content://mms/part")
    private const val SMS_INBOX = 1
    private const val MMS_INBOX = 1
    private const val FROM = 137
    private const val TO = 151
}
