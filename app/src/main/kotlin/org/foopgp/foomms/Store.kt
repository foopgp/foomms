package org.foopgp.foomms

import android.content.Context
import android.net.Uri

/**
 * The messages the phone holds, SMS and MMS together, as conversations and as
 * the lines of one. Read from where the phone's messaging application keeps
 * them: nothing is copied.
 */
object Store {

    /** [form]: what the last message travelled as -- its words, or a PGP message it carries as a part. */
    class Talk(
        val thread: Long, val number: String?, val at: Long, val last: String, val incoming: Boolean, val unread: Boolean, val form: Form = Form.of(last),
        /** Everybody the conversation is with: more than one, it is a group's. */
        val members: List<String> = listOfNotNull(number),
        /** Several numbers, but each message to one of them alone: a sending to each, which another application filed as one. */
        val each: Boolean = false,
        /** Who wrote the last message, when it was received. */
        val from: String? = null,
    ) {
        val group: Boolean get() = members.size > 1
    }

    /**
     * Whether a conversation of several numbers holds what was sent to each
     * of them -- an envoi multiple, or a group MMS in clear, which networks
     * deliver as they please -- rather than an encrypted group's: one of its
     * latest MMS carries the members part ([Members]).
     */
    fun toEach(context: Context, thread: Long): Boolean = !encryptedGroup(context, thread)

    fun encryptedGroup(context: Context, thread: Long): Boolean = runCatching {
        val ids = context.contentResolver.query(MMS, arrayOf("_id"), "thread_id = ?", arrayOf(thread.toString()), "date DESC")?.use { rows ->
            buildList { while (rows.moveToNext() && size < LOOKED_AT) add(rows.getLong(0)) }
        }.orEmpty()
        if (ids.isEmpty()) return@runCatching false
        context.contentResolver.query(
            PARTS, arrayOf("_id"), "mid IN (${ids.joinToString(",")}) AND (name = ? OR cl = ?)", arrayOf(Members.PART, Members.PART), null,
        )?.use { it.count > 0 } == true
    }.getOrDefault(false)

    /** How many of a conversation's latest MMS are looked at for a members part. */
    private const val LOOKED_AT = 20

    /** How the phone's store tells an MMS's recipients: To and Cc, as the PDU names them (OMA-MMS-ENC, table 12). */
    private val TO_OR_CC = setOf(0x97, 0x82)

    /**
     * Whom an SMS sent at [at] went to, when it was sent to each of several:
     * the copies of the same words sent within [ALONGSIDE] of it -- this
     * application writes them all at one moment, others one after the other
     * --, each number once, the first written first.
     */
    fun sentWith(context: Context, at: Long, body: String): List<String> = runCatching {
        val found = ArrayList<String>()
        context.contentResolver.query(
            SMS, arrayOf("address"), "type = ${android.provider.Telephony.Sms.MESSAGE_TYPE_SENT} AND body = ? AND date BETWEEN ? AND ?",
            arrayOf(body, (at - ALONGSIDE).toString(), (at + ALONGSIDE).toString()), "date ASC",
        )?.use { rows -> while (rows.moveToNext()) rows.getString(0)?.let { one -> if (found.none { Phone.same(it, one) || it == one }) found += one } }
        found
    }.getOrDefault(emptyList())

    /**
     * What was sent to [number] as one of several, filed in one conversation
     * of all of them as messaging applications file a sending to each: shown
     * in the conversation with [number] too, where an answer is written, and
     * kept in its backup. Its SMS -- an SMS goes to one number: in a
     * conversation of several, it can only be a copy of a sending to each,
     * a group's conversation included --, and its MMS where they were sent
     * to each.
     */
    fun sentToEach(context: Context, number: String): List<Line> = runCatching {
        val found = ArrayList<Line>()
        for (thread in eachWith(context, number)) {
            val each = toEach(context, thread)
            for (line in lines(context, thread)) {
                if (line.incoming || (line.mms && !each)) continue
                val to = addressOf(context, line) ?: continue
                if (Phone.same(to, number) || to == number) found += line
            }
        }
        found
    }.getOrDefault(emptyList())

    /** The conversations of several numbers, [number] among them: where sendings to each are filed. */
    private fun eachWith(context: Context, number: String): List<Long> {
        val tail = Phone.key(number).takeIf { it.all(Char::isDigit) } ?: return emptyList()
        // The phone's own numbering of the addresses that are [number], however written.
        val ids = HashSet<String>()
        context.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), null, "address LIKE ?", arrayOf("%$tail"), null)?.use { rows ->
            val id = rows.getColumnIndex("_id")
            val address = rows.getColumnIndex("address")
            while (rows.moveToNext()) if (Phone.same(rows.getString(address).orEmpty(), number)) ids += rows.getLong(id).toString()
        }
        if (ids.isEmpty()) return emptyList()
        val found = ArrayList<Long>()
        context.contentResolver.query(THREADS, arrayOf("_id", "recipient_ids"), "trim(recipient_ids) LIKE '% %'", null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val members = rows.getString(1)?.trim()?.split(' ').orEmpty()
                if (members.any { it in ids }) found += rows.getLong(0)
            }
        }
        return found
    }

    /**
     * Whom a message sent went to, when it was sent to each of several:
     * the copies of the same message. An SMS: the same words sent within
     * [ALONGSIDE] of it -- this application writes them all at one moment,
     * others one after the other. An MMS: those of its conversation sent in
     * the same second. Each number once, the first written first.
     */
    fun sentWith(context: Context, line: Line): List<String> =
        if (!line.mms) sentWith(context, line.at, line.body)
        else if (line.incoming) runCatching {
            // Received: those it went to besides this phone, as its "To" says.
            val found = ArrayList<String>()
            context.contentResolver.query(Uri.parse("content://mms/${line.id}/addr"), arrayOf("address", "type"), null, null, null)?.use { rows ->
                while (rows.moveToNext()) {
                    if (rows.getInt(1) !in TO_OR_CC) continue
                    val one = rows.getString(0) ?: continue
                    if (!Me.mine(context, one) && found.none { Phone.same(it, one) || it == one }) found += one
                }
            }
            found
        }.getOrDefault(emptyList())
        else runCatching {
            val found = ArrayList<String>()
            context.contentResolver.query(
                MMS, arrayOf("_id"), "thread_id = ? AND msg_box = $MMS_SENT AND date = ?", arrayOf(line.thread.toString(), (line.at / 1000).toString()), "_id ASC",
            )?.use { rows ->
                while (rows.moveToNext()) {
                    val to = number(context, rows.getLong(0), false) ?: continue
                    if (found.none { Phone.same(it, to) || it == to }) found += to
                }
            }
            found
        }.getOrDefault(emptyList())

    /** How far apart the copies of one sending to each may have been written. */
    private const val ALONGSIDE = 2 * 60 * 1000L

    /** What an MMS carries beside its words: a picture, a sound, a file. */
    class Piece(val id: Long, val type: String, val name: String?) {
        val uri: Uri get() = Uri.withAppendedPath(PARTS, id.toString())

        /** A part that is itself a PGP message: by its label, or by the name given to what was locked. */
        val sealed: Boolean
            get() = name.orEmpty().lowercase().let { type == Pgp.TYPE || it.endsWith(".pgp") || it.endsWith(".gpg") || it.endsWith(".asc") }

        companion object {
            /** [sealed], as the phone's store is asked it: the name is the part's own, or else where it came from. */
            val SEALED: String = listOf(".pgp", ".gpg", ".asc").joinToString(" OR ", "(ct = '${Pgp.TYPE}' OR ", ")") {
                "IFNULL(name, cl) LIKE '%$it'"
            }
        }
    }

    class Line(
        val at: Long, val incoming: Boolean, val body: String, val pieces: List<Piece> = emptyList(),
        /** Where the phone keeps it: its number there, and in which of the two stores. */
        val id: Long = -1, val mms: Boolean = false,
        /** When it left its sender, when the phone knows; who that was, as the network wrote it. */
        val sentAt: Long = 0, val address: String? = null,
        /** Not read yet; and the conversation it belongs to. */
        val unread: Boolean = false, val thread: Long = -1,
    ) {
        val attachments: Int get() = pieces.size
        val uri: Uri get() = Uri.parse(if (mms) "content://mms/$id" else "content://sms/$id")
    }

    /** How many messages the phone holds, and how many of them are PGP; the same among those not read yet. */
    class Counts(
        val pgp: Int, val all: Int, val unreadPgp: Int, val unread: Int, val sms: Int = 0, val mms: Int = 0, val talks: Int = 0, val unreadTalks: Int = 0,
        val pgpSms: Int = 0, val pgpMms: Int = 0, val bytes: Long = 0,
        /** The groups, each once however many conversations Android keeps for it; and those conversations, which [talks] counts too. */
        val groups: Int = 0, val groupTalks: Int = 0,
    ) {
        /** As kept between two runs, and read back. */
        fun kept(): String = listOf(pgp, all, unreadPgp, unread, sms, mms, talks, unreadTalks, pgpSms, pgpMms, bytes, groups, groupTalks).joinToString(",")

        companion object {
            fun of(kept: String?): Counts? {
                val n = kept?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
                if (n.size < 8) return null
                fun at(i: Int) = n.getOrElse(i) { 0L }.toInt()
                return Counts(at(0), at(1), at(2), at(3), at(4), at(5), at(6), at(7), at(8), at(9), n.getOrElse(10) { 0L }, at(11), at(12))
            }
        }
    }

    /** A message found by its words. */
    class Hit(val thread: Long, val number: String?, val at: Long, val incoming: Boolean, val words: String)

    /**
     * The [most] latest conversations, newest first, each by its last message.
     *
     * Read from the phone's own list of conversations, which is short; going
     * through every message to find the last of each took the time it takes
     * to read years of them.
     */
    /** [groupsOnly]: only the conversations with several numbers, read for themselves -- a few among hundreds. */
    /** [from]: how many of the latest to pass over -- the list read a block at a time, as it is scrolled. */
    fun talks(context: Context, most: Int, unreadOnly: Boolean = false, given: () -> Boolean = { false }, groupsOnly: Boolean = false, from: Int = 0): List<Talk> {
        val talks = ArrayList<Talk>()
        // The unread, by their messages ([unreadThreads]).
        val unread = if (unreadOnly) unreadThreads(context).ifEmpty { return talks } else null
        val listed = runCatching {
            // Only those that hold something unread, when that is all that is asked: a hundred to read rather than all.
            context.contentResolver.query(
                THREADS, arrayOf("_id", "date", "snippet", "recipient_ids", "read"),
                listOfNotNull(unread?.let { "_id IN (${it.joinToString(",")})" }, "trim(recipient_ids) LIKE '% %'".takeIf { groupsOnly }).joinToString(" AND ").ifEmpty { null },
                null, "date DESC",
            )
        }.getOrNull() ?: return everyTalk(context).filter { (!unreadOnly || it.unread) && (!groupsOnly || it.group) }.drop(from).take(most)
        listed.use { rows ->
            if (from > 0 && !rows.moveToPosition(from - 1)) return talks
            // [given] up: whoever asked no longer wants the answer, and reading on would only slow what they asked next.
            while (rows.moveToNext() && talks.size < most && !given()) {
                val thread = rows.getLong(0)
                val members = rows.getString(3)?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull()?.let { id -> address(context, id) } }.orEmpty()
                val number = members.firstOrNull()
                // An empty conversation -- made for a message never sent, or left by one moved to its group's -- is none
                // to show: told by its messages, the count Android keeps being not updated when a message moves.
                val last = last(context, thread) ?: continue
                val words = last.body
                talks += Talk(
                    thread, number ?: number(context, thread), last.at,
                    words, last.incoming, rows.getInt(4) == 0, formOf(context, words, last.pieces),
                    members.ifEmpty { listOfNotNull(number ?: number(context, thread)) },
                    members.size > 1 && toEach(context, thread),
                    if (last.incoming && members.size > 1) addressOf(context, last) else null,
                )
            }
        }
        return talks
    }

    /**
     * What a message travelled as, its parts included: a PGP part says
     * whether it is locked in its first bytes, read without the rest.
     */
    private fun formOf(context: Context, words: String, pieces: List<Piece>): Form {
        val sealed = pieces.filter { it.sealed }
        if (sealed.isEmpty()) return Form.of(words)
        val locked = sealed.any { piece ->
            runCatching {
                context.contentResolver.openInputStream(piece.uri)?.use { from ->
                    val head = ByteArray(HEAD)
                    val read = from.read(head)
                    read > 0 && Pgp.isLocked(head.copyOf(read))
                }
            }.getOrNull() == true
        }
        return Form.of(locked || Form.of(words) == Form.LOCKED, true)
    }

    /** Enough of a PGP part to tell locked from signed: an armor's headers and its first line, or a packet's head. */
    private const val HEAD = 512

    /** The address the phone's list of conversations names by a number of its own. */
    private fun address(context: Context, id: Long): String? = runCatching {
        context.contentResolver.query(Uri.parse("content://mms-sms/canonical-address/$id"), null, null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
    }.getOrNull()

    /**
     * The last message of a conversation, whole -- the phone's list keeps only
     * its first words --: the later of its last SMS and its last MMS. An MMS
     * says its words, or what it carries when it has none.
     */
    private fun last(context: Context, thread: Long): Line? {
        val sms = context.contentResolver.query(
            SMS, arrayOf("date", "body", "type"), "thread_id = ?", arrayOf(thread.toString()), "date DESC LIMIT 1",
        )?.use { rows ->
            if (rows.moveToFirst()) Line(rows.getLong(0), rows.getInt(2) == SMS_INBOX, rows.getString(1).orEmpty()) else null
        }
        // An MMS's date is in seconds, an SMS's in milliseconds.
        val mms = runCatching {
            context.contentResolver.query(
                MMS, arrayOf("_id", "date", "msg_box"), "thread_id = ?", arrayOf(thread.toString()), "date DESC LIMIT 1",
            )?.use { rows ->
                if (!rows.moveToFirst()) return@use null
                val (words, pieces) = parts(context, rows.getLong(0))
                Line(rows.getLong(1) * 1000, rows.getInt(2) == MMS_INBOX, words.ifEmpty { pieces.joinToString(" ") { "📎 " + (it.name ?: it.type) } }, pieces, mms = true)
            }
        }.getOrNull()
        return listOfNotNull(sms, mms).maxByOrNull { it.at }
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
            SMS, arrayOf("date", "body", "type", "_id", "date_sent", "address", "read"), "thread_id = ?", arrayOf(thread.toString()), null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                lines += Line(
                    rows.getLong(0), rows.getInt(2) == SMS_INBOX, rows.getString(1).orEmpty(),
                    id = rows.getLong(3), sentAt = rows.getLong(4), address = rows.getString(5),
                    unread = rows.getInt(6) == 0, thread = thread,
                )
            }
        }
        // The MMS, then all their parts in one asking rather than one each: a conversation of many MMS opened in a
        // fraction of the time. Who sent each is not asked here, being only shown in its details ([addressOf]).
        class Head(val id: Long, val at: Long, val incoming: Boolean, val sentAt: Long, val unread: Boolean)
        val heads = ArrayList<Head>()
        context.contentResolver.query(
            MMS, arrayOf("_id", "date", "msg_box", "date_sent", "read"), "thread_id = ?", arrayOf(thread.toString()), null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                heads += Head(rows.getLong(0), rows.getLong(1) * 1000, rows.getInt(2) == MMS_INBOX, rows.getLong(3) * 1000, rows.getInt(4) == 0)
            }
        }
        val parts = parts(context, heads.map { it.id })
        for (head in heads) {
            val (words, pieces) = parts[head.id] ?: ("" to emptyList())
            lines += Line(head.at, head.incoming, words, pieces, id = head.id, mms = true, sentAt = head.sentAt, unread = head.unread, thread = thread)
        }
        return lines.sortedBy { it.at }
    }

    /** Everybody a conversation is with, as the phone lists them. */
    fun members(context: Context, thread: Long): List<String> = runCatching {
        context.contentResolver.query(THREADS, arrayOf("recipient_ids"), "_id = ?", arrayOf(thread.toString()), null)?.use { rows ->
            if (!rows.moveToFirst()) return@use emptyList()
            rows.getString(0)?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull()?.let { id -> address(context, id) } }.orEmpty()
        }
    }.getOrNull().orEmpty()

    /** The conversations holding something not read yet. */
    /** The conversations of the group of [numbers]: with [me] among them or without, newest first. */
    fun groupThreads(context: Context, numbers: List<String>, mine: List<String>): List<Long> = runCatching {
        val key = Phone.group(numbers, mine)
        context.contentResolver.query(THREADS, arrayOf("_id", "recipient_ids"), "trim(recipient_ids) LIKE '% %'", null, "date DESC")?.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val members = rows.getString(1)?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull()?.let { id -> address(context, id) } }.orEmpty()
                    if (Phone.group(members, mine) == key && !toEach(context, rows.getLong(0))) add(rows.getLong(0))
                }
            }
        }
    }.getOrNull().orEmpty()

    /**
     * The conversations that hold a message not read yet, the latest first:
     * told by their messages, the mark Android keeps on a conversation not
     * following a message moved to another.
     */
    fun unreadThreads(context: Context): List<Long> = runCatching {
        val at = HashMap<Long, Long>()
        context.contentResolver.query(SMS, arrayOf("thread_id", "date"), "read = 0", null, null)?.use { rows ->
            while (rows.moveToNext()) at.merge(rows.getLong(0), rows.getLong(1), ::maxOf)
        }
        context.contentResolver.query(MMS, arrayOf("thread_id", "date"), "read = 0", null, null)?.use { rows ->
            while (rows.moveToNext()) at.merge(rows.getLong(0), rows.getLong(1) * 1000, ::maxOf)
        }
        at.entries.sortedByDescending { it.value }.map { it.key }
    }.getOrNull().orEmpty()

    /** Who sent a message, or whom it went to: as the network wrote it, an MMS's asked of the phone only now. */
    fun addressOf(context: Context, line: Line): String? = line.address ?: if (line.mms) number(context, line.id, line.incoming) else null

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
    private fun parts(context: Context, message: Long): Pair<String, List<Piece>> = parts(context, listOf(message))[message] ?: ("" to emptyList())

    /** The words and the other parts of several MMS, by message, asked a few hundred at a time. */
    private fun parts(context: Context, messages: List<Long>): Map<Long, Pair<String, List<Piece>>> {
        val words = HashMap<Long, StringBuilder>()
        val pieces = HashMap<Long, ArrayList<Piece>>()
        for (some in messages.chunked(400)) {
            context.contentResolver.query(
                PARTS, arrayOf("_id", "ct", "text", "name", "cl", "mid"), "mid IN (${some.joinToString(",")})", null, "mid, _id",
            )?.use { rows ->
                while (rows.moveToNext()) {
                    val mid = rows.getLong(5)
                    val type = rows.getString(1).orEmpty()
                    when {
                        type == "application/smil" -> Unit
                        type.startsWith("text/plain") -> {
                            val text = rows.getString(2) ?: runCatching {
                                context.contentResolver.openInputStream(Uri.withAppendedPath(PARTS, rows.getString(0)))
                                    ?.use { String(it.readBytes(), Charsets.UTF_8) }
                            }.getOrNull()
                            if (!text.isNullOrEmpty()) words.getOrPut(mid) { StringBuilder() }.let { it.append(if (it.isEmpty()) "" else "\n").append(text) }
                        }
                        else -> pieces.getOrPut(mid) { ArrayList() } += Piece(rows.getLong(0), type, rows.getString(3) ?: rows.getString(4))
                    }
                }
            }
        }
        return messages.associateWith { (words[it]?.toString().orEmpty()) to pieces[it].orEmpty() }
    }

    fun counts(context: Context): Counts {
        fun count(table: Uri, where: String?): Int = runCatching {
            context.contentResolver.query(table, arrayOf("_id"), where, null, null)?.use { it.count }
        }.getOrNull() ?: 0
        val armored = "body LIKE '%$BANNER%'"
        // An MMS is PGP by a text part of it, or by a part that is a PGP message itself, as the conversation
        // shows them; which of those are unread is asked message by message.
        val sealed = HashSet<Long>()
        runCatching {
            context.contentResolver.query(PARTS, arrayOf("mid"), "text LIKE '%$BANNER%' OR ${Piece.SEALED}", null, null)?.use { rows ->
                while (rows.moveToNext()) sealed += rows.getLong(0)
            }
        }
        val unreadMms = HashSet<Long>()
        runCatching {
            context.contentResolver.query(MMS, arrayOf("_id"), "read = 0", null, null)?.use { rows ->
                while (rows.moveToNext()) unreadMms += rows.getLong(0)
            }
        }
        val sms = count(SMS, null)
        val mms = count(MMS, null)
        val pgpSms = count(SMS, armored)
        // What it all weighs: the words of the SMS as they are kept, and the size each MMS was given.
        var bytes = 0L
        runCatching {
            context.contentResolver.query(SMS, arrayOf("body"), null, null, null)?.use { rows ->
                while (rows.moveToNext()) bytes += rows.getString(0)?.toByteArray()?.size ?: 0
            }
        }
        runCatching {
            context.contentResolver.query(MMS, arrayOf("m_size"), null, null, null)?.use { rows ->
                while (rows.moveToNext()) bytes += rows.getLong(0)
            }
        }
        // The groups by their members but this phone: the same group may be two conversations.
        val mine = Me.all(context)
        val groups = ArrayList<String>()
        runCatching {
            context.contentResolver.query(THREADS, arrayOf("recipient_ids", "_id"), "trim(recipient_ids) LIKE '% %'", null, null)?.use { rows ->
                while (rows.moveToNext()) {
                    // A sending to each, filed as one conversation: not a group.
                    if (toEach(context, rows.getLong(1))) continue
                    val members = rows.getString(0)?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull()?.let { id -> address(context, id) } }.orEmpty()
                    groups += Phone.group(members, mine)
                }
            }
        }
        return Counts(
            pgp = pgpSms + sealed.size, pgpSms = pgpSms, pgpMms = sealed.size, bytes = bytes,
            all = sms + mms,
            unreadPgp = count(SMS, "$armored AND read = 0") + sealed.count { it in unreadMms },
            unread = count(SMS, "read = 0") + unreadMms.size,
            sms = sms, mms = mms,
            talks = count(THREADS, null),
            unreadTalks = unreadThreads(context).size,
            // Several members besides this phone: a group; one, a conversation between two all the same.
            groups = groups.filter { ',' in it }.distinct().size, groupTalks = groups.count { ',' in it },
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
    private const val MMS_SENT = 2
    private const val FROM = 137
    private const val TO = 151
}
