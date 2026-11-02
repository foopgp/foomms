package org.foopgp.foomms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import android.util.Xml
import java.io.InputStream
import java.io.OutputStream
import org.xmlpull.v1.XmlPullParser

/**
 * The phone's messages written out, and written back, in the XML of "SMS
 * Backup & Restore": the form most applications on Android read and write,
 * and so the one a backup made here is likeliest to be read by later.
 *
 * Messages are written as they travelled -- a PGP message in its armor, a
 * locked part as the PGP message it is --: nothing is opened to be saved,
 * and nothing saved is readable that was not readable on the way.
 */
object Backup {

    class Done(val sms: Int, val mms: Int, val skipped: Int = 0)

    /** Write every message, or those of one conversation, to [out]. */
    fun write(context: Context, out: OutputStream, thread: Long?): Done {
        val resolver = context.contentResolver
        // One person's conversation: with what was sent to them as one of several, filed in a conversation of all of
        // them. Put back, each goes to the person's conversation: which others it went to is not kept, and need not be.
        val alsoSent = thread?.let { Store.members(context, it).singleOrNull() }?.let { Store.sentToEach(context, it) }.orEmpty()
        fun within(mms: Boolean): String? = thread?.let { mine ->
            val also = alsoSent.filter { it.mms == mms }.map { it.id }
            "thread_id = $mine" + if (also.isEmpty()) "" else " OR _id IN (${also.joinToString(",")})"
        }
        val xml = Xml.newSerializer()
        xml.setOutput(out, "UTF-8")
        xml.startDocument("UTF-8", true)
        // Drafts are not kept: foomms's are a mark in the conversation, their words held by foomms alone.
        val sms = resolver.query(Uri.parse("content://sms"), null, within(false)?.let { "($it) AND ${Store.SMS_NOT_DRAFT}" } ?: Store.SMS_NOT_DRAFT, null, "date ASC")
        val mms = resolver.query(Uri.parse("content://mms"), null, within(true)?.let { "($it) AND ${Store.MMS_NOT_DRAFT}" } ?: Store.MMS_NOT_DRAFT, null, "date ASC")
        var smsCount = 0
        var mmsCount = 0
        xml.startTag(null, "smses")
        xml.attribute(null, "count", ((sms?.count ?: 0) + (mms?.count ?: 0)).toString())
        xml.attribute(null, "backup_set", java.util.UUID.randomUUID().toString())
        xml.attribute(null, "backup_date", System.currentTimeMillis().toString())
        xml.attribute(null, "type", "full")
        sms?.use { rows ->
            while (rows.moveToNext()) {
                xml.startTag(null, "sms")
                for (column in SMS_COLUMNS) {
                    val at = rows.getColumnIndex(column)
                    xml.attribute(null, column, if (at < 0 || rows.isNull(at)) "null" else rows.getString(at))
                }
                xml.endTag(null, "sms")
                smsCount++
            }
        }
        mms?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getLong(rows.getColumnIndexOrThrow("_id"))
                xml.startTag(null, "mms")
                for (column in MMS_COLUMNS) {
                    val at = rows.getColumnIndex(column)
                    var value = if (at < 0 || rows.isNull(at)) "null" else rows.getString(at)
                    // The format counts an MMS's dates in milliseconds, as an SMS's; the phone keeps them in seconds.
                    if ((column == "date" || column == "date_sent") && value != "null") value = (value.toLong() * 1000).toString()
                    xml.attribute(null, column, value)
                }
                val addresses = ArrayList<Pair<String, Int>>()
                resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type", "charset"), null, null, null)?.use { a ->
                    while (a.moveToNext()) addresses += a.getString(0).orEmpty() to a.getInt(1)
                }
                xml.attribute(null, "address", addresses.filter { it.second != FROM || addresses.size == 1 }.joinToString("~") { it.first })
                xml.startTag(null, "parts")
                resolver.query(Uri.parse("content://mms/part"), null, "mid = ?", arrayOf(id.toString()), "seq ASC")?.use { p ->
                    while (p.moveToNext()) {
                        xml.startTag(null, "part")
                        for (column in PART_COLUMNS) {
                            val at = p.getColumnIndex(column)
                            xml.attribute(null, column, if (at < 0 || p.isNull(at)) "null" else p.getString(at))
                        }
                        val type = p.getString(p.getColumnIndexOrThrow("ct")).orEmpty()
                        if (!type.startsWith("text/") && type != "application/smil") {
                            val bytes = runCatching {
                                resolver.openInputStream(Uri.parse("content://mms/part/" + p.getLong(p.getColumnIndexOrThrow("_id"))))?.use { it.readBytes() }
                            }.getOrNull()
                            if (bytes != null) xml.attribute(null, "data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                        xml.endTag(null, "part")
                    }
                }
                xml.endTag(null, "parts")
                xml.startTag(null, "addrs")
                for ((address, type) in addresses) {
                    xml.startTag(null, "addr")
                    xml.attribute(null, "address", address)
                    xml.attribute(null, "type", type.toString())
                    xml.attribute(null, "charset", "106")
                    xml.endTag(null, "addr")
                }
                xml.endTag(null, "addrs")
                xml.endTag(null, "mms")
                mmsCount++
            }
        }
        xml.endTag(null, "smses")
        xml.endDocument()
        xml.flush()
        return Done(smsCount, mmsCount)
    }

    /**
     * Write back what a backup holds, a message already on the phone left
     * as it is. Only the phone's messaging application may.
     */
    fun read(context: Context, from: InputStream): Done {
        val resolver = context.contentResolver
        val xml = Xml.newPullParser()
        xml.setInput(from, null)
        var sms = 0
        var mms = 0
        var skipped = 0
        var message: ContentValues? = null
        val parts = ArrayList<Map<String, String>>()
        val addresses = ArrayList<Pair<String, Int>>()
        fun attrs(): Map<String, String> = (0 until xml.attributeCount).associate { xml.getAttributeName(it) to xml.getAttributeValue(it) }
        fun given(value: String?) = value?.takeIf { it != "null" }
        while (true) {
            when (xml.next()) {
                XmlPullParser.END_DOCUMENT -> return Done(sms, mms, skipped)
                XmlPullParser.START_TAG -> when (xml.name) {
                    "sms" -> {
                        val a = attrs()
                        val address = given(a["address"]) ?: continue
                        val date = given(a["date"]) ?: continue
                        val body = given(a["body"]).orEmpty()
                        val there = resolver.query(
                            Uri.parse("content://sms"), arrayOf("_id"), "address = ? AND date = ? AND body = ?", arrayOf(address, date, body), null,
                        )?.use { it.count > 0 } == true
                        if (there) { skipped++ ; continue }
                        resolver.insert(
                            Uri.parse("content://sms"),
                            ContentValues().apply {
                                put(Telephony.Sms.ADDRESS, address)
                                put(Telephony.Sms.DATE, date.toLong())
                                given(a["date_sent"])?.toLongOrNull()?.let { put(Telephony.Sms.DATE_SENT, it) }
                                put(Telephony.Sms.BODY, body)
                                put(Telephony.Sms.TYPE, given(a["type"])?.toIntOrNull() ?: Telephony.Sms.MESSAGE_TYPE_INBOX)
                                put(Telephony.Sms.READ, given(a["read"])?.toIntOrNull() ?: 1)
                                put(Telephony.Sms.SEEN, 1)
                                given(a["subject"])?.let { put(Telephony.Sms.SUBJECT, it) }
                                given(a["service_center"])?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
                            },
                        )?.let { sms++ }
                    }
                    "mms" -> {
                        val a = attrs()
                        parts.clear() ; addresses.clear()
                        message = ContentValues().apply {
                            for (column in MMS_COLUMNS) {
                                val value = given(a[column]) ?: continue
                                if (column == "date" || column == "date_sent") put(column, (value.toLongOrNull() ?: 0) / 1000) else put(column, value)
                            }
                            put("seen", 1)
                        }
                    }
                    "part" -> if (message != null) parts += attrs()
                    "addr" -> if (message != null) {
                        val a = attrs()
                        given(a["address"])?.let { addresses += it to (given(a["type"])?.toIntOrNull() ?: TO) }
                    }
                }
                XmlPullParser.END_TAG -> if (xml.name == "mms") {
                    val values = message ?: continue
                    message = null
                    val date = values.getAsLong("date") ?: continue
                    val box = values.getAsInteger("msg_box") ?: Telephony.Mms.MESSAGE_BOX_INBOX
                    val there = resolver.query(
                        Uri.parse("content://mms"), arrayOf("_id"), "date = ? AND msg_box = ?", arrayOf(date.toString(), box.toString()), null,
                    )?.use { it.count > 0 } == true
                    if (there) { skipped++ ; continue }
                    val others = addresses.filter { it.second == if (box == Telephony.Mms.MESSAGE_BOX_INBOX) FROM else TO }.map { it.first }.toSet()
                        .ifEmpty { addresses.map { it.first }.toSet() }
                    if (others.isEmpty()) continue
                    values.put("thread_id", Telephony.Threads.getOrCreateThreadId(context, others))
                    val written = resolver.insert(Uri.parse("content://mms"), values) ?: continue
                    val id = written.lastPathSegment ?: continue
                    for ((address, type) in addresses) {
                        resolver.insert(
                            Uri.parse("content://mms/$id/addr"),
                            ContentValues().apply { put("address", address) ; put("type", type) ; put("charset", 106) },
                        )
                    }
                    for (part in parts) {
                        val row = resolver.insert(
                            Uri.parse("content://mms/$id/part"),
                            ContentValues().apply {
                                put("mid", id)
                                for (column in PART_COLUMNS) given(part[column])?.let { if (column != "_id" && column != "mid") put(column, it) }
                            },
                        ) ?: continue
                        given(part["data"])?.let { data ->
                            runCatching { resolver.openOutputStream(row)?.use { it.write(Base64.decode(data, Base64.DEFAULT)) } }
                        }
                    }
                    mms++
                }
            }
        }
    }

    private val SMS_COLUMNS = listOf(
        "protocol", "address", "date", "type", "subject", "body", "toa", "sc_toa", "service_center", "read", "status", "locked", "date_sent",
    )
    private val MMS_COLUMNS = listOf(
        "date", "date_sent", "ct_t", "msg_box", "read", "seen", "sub", "sub_cs", "m_type", "m_id", "tr_id", "m_cls", "m_size", "v",
        "exp", "pri", "rr", "d_rpt", "ct_l", "text_only",
    )
    private val PART_COLUMNS = listOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text")
    private const val FROM = 137
    private const val TO = 151
}
