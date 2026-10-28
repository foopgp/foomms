package org.foopgp.foomms

import android.content.Context
import android.net.Uri

/**
 * This phone's own number, to name a group by all its members, this phone
 * among them, when a message is sent to several. Read from the MMS this
 * phone has received: one that went to a single number went to this phone.
 * Nothing to ask the person, and no permission beyond the messages': the
 * card seldom says its number, and Android gives what the network says only
 * behind a permission it names after the calls.
 */
object Me {

    fun number(context: Context): String? = kept(context).getString(LEARNT, null)

    /** An MMS that went to one number alone came to this phone: that number is this phone's. */
    fun learn(context: Context, to: List<String>) {
        val only = to.singleOrNull()?.takeIf { Phone.plain(it) != null } ?: return
        if (number(context) != only) kept(context).edit().putString(LEARNT, only).apply()
    }

    /**
     * The number of the latest MMS received that went to one number alone,
     * among the [most] latest: the card may have changed since the older
     * ones. Not on the main thread.
     */
    fun look(context: Context, most: Int = 200) {
        runCatching {
            context.contentResolver.query(INBOX, arrayOf("_id"), null, null, "date DESC")?.use { rows ->
                var seen = 0
                while (rows.moveToNext() && seen++ < most) {
                    val id = rows.getLong(0)
                    val to = ArrayList<String>()
                    context.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { addresses ->
                        while (addresses.moveToNext()) if (addresses.getInt(1) in TO_OR_CC) addresses.getString(0)?.let { to += it }
                    }
                    if (to.size == 1 && Phone.plain(to[0]) != null) return learn(context, to)
                }
            }
        }
    }

    private val INBOX: Uri = Uri.parse("content://mms/inbox")

    /** How the phone's store tells an MMS's recipients: To and Cc, as the PDU names them (OMA-MMS-ENC, table 12). */
    private val TO_OR_CC = setOf(0x97, 0x82)

    private const val LEARNT = "learnt_number"

    private fun kept(context: Context) = context.getSharedPreferences("me", Context.MODE_PRIVATE)
}
