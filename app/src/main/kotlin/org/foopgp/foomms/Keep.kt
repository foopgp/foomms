package org.foopgp.foomms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

/**
 * Writing in the phone's own store of messages, which Android lets its
 * messaging application alone do: what arrives, what leaves, and that a
 * conversation has been read. Messages are kept there as they travelled -- a
 * PGP message in its armor -- so that any other application the person turns
 * to finds them.
 */
object Keep {

    /**
     * Whether this application is the phone's messaging application: from
     * Android 10, whether it holds the SMS role. The older setting it replaced
     * is left empty on some phones (an Android 16 one) while the role is held,
     * and the page then kept asking for a role already given.
     */
    fun ours(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    fun smsReceived(context: Context, from: String, body: String, sentAt: Long, subscription: Int): Uri? =
        context.contentResolver.insert(
            Telephony.Sms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, sentAt)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                if (subscription >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscription)
            },
        )

    fun smsSent(context: Context, to: String, body: String): Uri? =
        context.contentResolver.insert(
            Telephony.Sms.Sent.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, to)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            },
        )

    /** A sending the network refused is said so where it was written. */
    fun smsFailed(context: Context, written: Uri) {
        context.contentResolver.update(
            written, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null,
        )
    }

    fun mmsReceived(context: Context, message: PduRead.Message, size: Int, subscription: Int): Uri? {
        val from = message.from ?: return null
        return mms(context, Telephony.Mms.Inbox.CONTENT_URI, setOf(from), from, message.to, message, size, incoming = true, subscription)
    }

    fun mmsSent(context: Context, to: String, pdu: ByteArray): Uri? =
        mms(context, Telephony.Mms.Sent.CONTENT_URI, setOf(to), null, listOf(to), PduRead.read(pdu), pdu.size, incoming = false, -1)

    private fun mms(
        context: Context, box: Uri, others: Set<String>, from: String?, to: List<String>,
        message: PduRead.Message, size: Int, incoming: Boolean, subscription: Int,
    ): Uri? {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis() / 1000
        val written = resolver.insert(
            box,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(context, others))
                put(Telephony.Mms.DATE, now)
                put(Telephony.Mms.DATE_SENT, message.date ?: now)
                put(Telephony.Mms.READ, if (incoming) 0 else 1)
                put(Telephony.Mms.SEEN, if (incoming) 0 else 1)
                put(Telephony.Mms.MESSAGE_TYPE, if (incoming) PduRead.RETRIEVED else PduRead.SEND)
                put(Telephony.Mms.MMS_VERSION, 0x12)
                put(Telephony.Mms.CONTENT_TYPE, message.type ?: "application/vnd.wap.multipart.mixed")
                put(Telephony.Mms.MESSAGE_CLASS, "personal")
                put(Telephony.Mms.MESSAGE_SIZE, size)
                message.subject?.let { put(Telephony.Mms.SUBJECT, it) ; put(Telephony.Mms.SUBJECT_CHARSET, UTF_8) }
                message.id?.let { put(Telephony.Mms.MESSAGE_ID, it) }
                message.transaction?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
                if (subscription >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subscription)
            },
        ) ?: return null
        val id = written.lastPathSegment ?: return null
        fun address(number: String, kind: Int) {
            resolver.insert(
                Uri.parse("content://mms/$id/addr"),
                ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, number) ; put(Telephony.Mms.Addr.TYPE, kind) ; put(Telephony.Mms.Addr.CHARSET, UTF_8)
                },
            )
        }
        // A sender the network was left to fill in is written as the phone's store names it.
        address(from ?: "insert-address-token", FROM)
        to.forEach { address(it, TO) }
        for (part in message.parts) {
            val textual = part.isText || part.type == "application/smil"
            val row = resolver.insert(
                Uri.parse("content://mms/$id/part"),
                ContentValues().apply {
                    put(Telephony.Mms.Part.MSG_ID, id)
                    put(Telephony.Mms.Part.CONTENT_TYPE, part.type)
                    part.name?.let { put(Telephony.Mms.Part.NAME, it) }
                    part.location?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                    part.id?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
                    if (textual) { put(Telephony.Mms.Part.CHARSET, UTF_8) ; put(Telephony.Mms.Part.TEXT, part.text()) }
                },
            ) ?: continue
            if (!textual) resolver.openOutputStream(row)?.use { it.write(part.bytes) }
        }
        return written
    }

    /**
     * Take a message, or a whole conversation, out of the phone's store: for
     * good, and only the phone's messaging application can. Says whether
     * anything went.
     */
    fun forget(context: Context, line: Store.Line): Boolean =
        ours(context) && runCatching { context.contentResolver.delete(line.uri, null, null) > 0 }.getOrDefault(false)

    fun forgetAll(context: Context, thread: Long): Boolean = ours(context) && runCatching {
        context.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$thread"), null, null) > 0
    }.getOrDefault(false)

    /** Opening a conversation reads it, but for what is still locked: that is read once opened ([read] of a line). */
    fun read(context: Context, thread: Long, locked: List<Store.Line> = emptyList()) {
        if (!ours(context)) return
        val seen = ContentValues().apply { put("read", 1) ; put("seen", 1) }
        fun but(mms: Boolean) = locked.filter { it.mms == mms }.joinToString(",") { it.id.toString() }.let { if (it.isEmpty()) "" else " AND _id NOT IN ($it)" }
        runCatching {
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, seen, "thread_id = ? AND read = 0" + but(false), arrayOf(thread.toString()))
            context.contentResolver.update(Telephony.Mms.CONTENT_URI, seen, "thread_id = ? AND read = 0" + but(true), arrayOf(thread.toString()))
        }
    }

    /** One message read: opened from its lock, said by foodjis to be locked for somebody else, or said read by the person. */
    fun read(context: Context, line: Store.Line) {
        if (!ours(context) || line.id < 0) return
        runCatching { context.contentResolver.update(line.uri, ContentValues().apply { put("read", 1) ; put("seen", 1) }, "read = 0", null) }
    }

    /** One message not read again, as the person asked: seen all the same, it is not announced a second time. */
    fun unread(context: Context, line: Store.Line) {
        if (!ours(context) || line.id < 0) return
        runCatching { context.contentResolver.update(line.uri, ContentValues().apply { put("read", 0) }, null, null) }
    }

    /** Everything read, locked or not: asked for by the person. */
    fun readAll(context: Context) {
        if (!ours(context)) return
        val seen = ContentValues().apply { put("read", 1) ; put("seen", 1) }
        runCatching {
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, seen, "read = 0", null)
            context.contentResolver.update(Telephony.Mms.CONTENT_URI, seen, "read = 0", null)
        }
    }

    private const val UTF_8 = 106
    private const val FROM = 137
    private const val TO = 151
}
