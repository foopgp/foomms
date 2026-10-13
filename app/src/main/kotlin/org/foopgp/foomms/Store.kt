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

    /** What an MMS carries beside its words: a picture, a sound, a file. */
    class Piece(val id: Long, val type: String, val name: String?) {
        val uri: Uri get() = Uri.withAppendedPath(PARTS, id.toString())
    }

    class Line(
        val at: Long, val incoming: Boolean, val body: String, val pieces: List<Piece> = emptyList(),
        /** Where the phone keeps it: its number there, and in which of the two stores. */
        val id: Long = -1, val mms: Boolean = false,
        /** When it left its sender, when the phone knows; who that was, as the network wrote it. */
        val sentAt: Long = 0, val address: String? = null,
    ) {
        val attachments: Int get() = pieces.size
        val uri: Uri get() = Uri.parse(if (mms) "content://mms/$id" else "content://sms/$id")
    }

    /** How many messages the phone holds, and how many of them are PGP; the same among those not read yet. */
    class Counts(val pgp: Int, val all: Int, val unreadPgp: Int, val unread: Int)

    /** A message found by its words. */
    class Hit(val thread: Long, val number: String?, val at: Long, val incoming: Boolean, val words: String)

    /**
     * The [most] latest conversations, newest first, each by its last message.
     *
     * Read from the phone's own list of conversations, which is short; going
     * through every message to find the last of each took the time it takes
     * to read years of them.
     */
    fun talks(context: Context, most: Int): List<Talk> {
        val talks = ArrayList<Talk>()
        val listed = runCatching {
            context.contentResolver.query(THREADS, arrayOf("_id", "date", "snippet", "recipient_ids", "read"), null, null, "date DESC")
        }.getOrNull() ?: return everyTalk(context).take(most)
        listed.use { rows ->
            while (rows.moveToNext() && talks.size < most) {
                val thread = rows.getLong(0)
                val number = rows.getString(3)?.trim()?.split(' ')?.firstOrNull()?.toLongOrNull()?.let { address(context, it) }
                val last = last(context, thread)
                talks += Talk(
                    thread, number ?: number(context, thread), last?.at ?: rows.getLong(1),
                    last?.body ?: rows.getString(2).orEmpty(), last?.incoming ?: true, rows.getInt(4) == 0,
                )
            }
        }
        return talks
    }

    /** The address the phone's list of conversations names by a number of its own. */
    private fun address(context: Context, id: Long): String? = runCatching {
        context.contentResolver.query(Uri.parse("content://mms-sms/canonical-address/$id"), null, null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
    }.getOrNull()

    /** The last SMS of a conversation, whole: the phone's list keeps only its first words. */
    private fun last(context: Context, thread: Long): Line? =
        context.contentResolver.query(
            SMS, arrayOf("date", "body", "type"), "thread_id = ?", arrayOf(thread.toString()), "date DESC LIMIT 1",
        )?.use { rows ->
            if (rows.moveToFirst()) Line(rows.getLong(0), rows.getInt(2) == SMS_INBOX, rows.getString(1).orEmpty()) else null
        }

    /** The same found the long way, for a phone that keeps no such list. */
    private fun everyTalk(context: Context): List<Talk> {
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
                val (words, pieces) = parts(context, rows.getLong(0))
                offer(Talk(thread, number(context, rows.getLong(0), incoming), at, words.ifEmpty { "📎".repeat(minOf(pieces.size, 3)) }, incoming, rows.getInt(4) == 0))
            }
        }
        return latest.values.sortedByDescending { it.at }
    }

    /** One conversation, oldest first. */
    fun lines(context: Context, thread: Long): List<Line> {
        val lines = ArrayList<Line>()
        context.contentResolver.query(
            SMS, arrayOf("date", "body", "type", "_id", "date_sent", "address"), "thread_id = ?", arrayOf(thread.toString()), null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                lines += Line(
                    rows.getLong(0), rows.getInt(2) == SMS_INBOX, rows.getString(1).orEmpty(),
                    id = rows.getLong(3), sentAt = rows.getLong(4), address = rows.getString(5),
                )
            }
        }
        context.contentResolver.query(
            MMS, arrayOf("_id", "date", "msg_box", "date_sent"), "thread_id = ?", arrayOf(thread.toString()), null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                val (words, pieces) = parts(context, rows.getLong(0))
                val incoming = rows.getInt(2) == MMS_INBOX
                lines += Line(
                    rows.getLong(1) * 1000, incoming, words, pieces,
                    id = rows.getLong(0), mms = true, sentAt = rows.getLong(3) * 1000, address = number(context, rows.getLong(0), incoming),
                )
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
                // A number however it is written; a sender that is a name, by that name.
                if (Phone.same(address, number) || address.equals(number, ignoreCase = true)) return rows.getLong(0)
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

    /** The words of an MMS, and its parts that are something else. */
    private fun parts(context: Context, message: Long): Pair<String, List<Piece>> {
        val words = StringBuilder()
        val pieces = ArrayList<Piece>()
        context.contentResolver.query(PARTS, arrayOf("_id", "ct", "text", "name", "cl"), "mid = ?", arrayOf(message.toString()), null)?.use { rows ->
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
                    else -> pieces += Piece(rows.getLong(0), type, rows.getString(3) ?: rows.getString(4))
                }
            }
        }
        return words.toString() to pieces
    }

    fun counts(context: Context): Counts {
        fun count(table: Uri, where: String?): Int = runCatching {
            context.contentResolver.query(table, arrayOf("_id"), where, null, null)?.use { it.count }
        }.getOrNull() ?: 0
        val armored = "body LIKE '%$BANNER%'"
        // An MMS is PGP by a text part of it; which of those are unread is asked message by message.
        val sealed = HashSet<Long>()
        runCatching {
            context.contentResolver.query(PARTS, arrayOf("mid"), "text LIKE '%$BANNER%'", null, null)?.use { rows ->
                while (rows.moveToNext()) sealed += rows.getLong(0)
            }
        }
        val unreadMms = HashSet<Long>()
        runCatching {
            context.contentResolver.query(MMS, arrayOf("_id"), "read = 0", null, null)?.use { rows ->
                while (rows.moveToNext()) unreadMms += rows.getLong(0)
            }
        }
        return Counts(
            pgp = count(SMS, armored) + sealed.size,
            all = count(SMS, null) + count(MMS, null),
            unreadPgp = count(SMS, "$armored AND read = 0") + sealed.count { it in unreadMms },
            unread = count(SMS, "read = 0") + unreadMms.size,
        )
    }

    /**
     * The messages that hold [wanted], newest first, in one conversation or
     * in all. A PGP message is looked into only if it is open: what a closed
     * one says is not known to anybody here.
     */
    fun search(context: Context, wanted: String, thread: Long?, most: Int = 200): List<Hit> {
        val asked = wanted.trim()
        if (asked.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        val within = thread?.let { " AND thread_id = $it" }.orEmpty()
        val like = "%" + asked.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val columns = arrayOf("thread_id", "address", "date", "body", "type")
        context.contentResolver.query(
            SMS, columns, "body LIKE ? ESCAPE '\\' AND body NOT LIKE '%$BANNER%'$within", arrayOf(like), "date DESC",
        )?.use { rows ->
            while (rows.moveToNext() && hits.size < most) {
                hits += Hit(rows.getLong(0), rows.getString(1), rows.getLong(2), rows.getInt(4) == SMS_INBOX, rows.getString(3).orEmpty())
            }
        }
        context.contentResolver.query(SMS, columns, "body LIKE '%$BANNER%'$within", null, "date DESC")?.use { rows ->
            while (rows.moveToNext()) {
                val words = Pgp.armorIn(rows.getString(3).orEmpty())?.let { Opened.words(it)?.words } ?: continue
                if (words.contains(asked, ignoreCase = true)) {
                    hits += Hit(rows.getLong(0), rows.getString(1), rows.getLong(2), rows.getInt(4) == SMS_INBOX, words)
                }
            }
        }
        // The MMS whose words hold it: found by their text parts, then asked where and when they belong.
        val found = LinkedHashMap<Long, String>()
        runCatching {
            context.contentResolver.query(PARTS, arrayOf("mid", "text"), "ct = 'text/plain' AND text LIKE ? ESCAPE '\\'", arrayOf(like), null)?.use { rows ->
                while (rows.moveToNext()) {
                    val text = rows.getString(1).orEmpty()
                    val armor = Pgp.armorIn(text)
                    val words = if (armor == null) text else Opened.words(armor)?.words?.takeIf { it.contains(asked, ignoreCase = true) } ?: continue
                    found.putIfAbsent(rows.getLong(0), words)
                }
            }
        }
        for ((message, words) in found) {
            context.contentResolver.query(
                Uri.withAppendedPath(MMS, message.toString()), arrayOf("thread_id", "date", "msg_box"), null, null, null,
            )?.use { rows ->
                if (rows.moveToFirst() && (thread == null || rows.getLong(0) == thread)) {
                    val incoming = rows.getInt(2) == MMS_INBOX
                    hits += Hit(rows.getLong(0), number(context, message, incoming), rows.getLong(1) * 1000, incoming, words)
                }
            }
        }
        return hits.sortedByDescending { it.at }.take(most)
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
    private val THREADS = Uri.parse("content://mms-sms/conversations?simple=true")
    private val PARTS = Uri.parse("content://mms/part")
    private const val SMS_INBOX = 1
    private const val BANNER = "-----BEGIN PGP MESSAGE-----"
    private const val MMS_INBOX = 1
    private const val FROM = 137
    private const val TO = 151
}
