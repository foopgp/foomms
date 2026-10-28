package org.foopgp.foomms

import android.content.Context
import android.net.Uri

/**
 * The person, as this phone knows them: the numbers this phone has received
 * MMS at -- one that went to a single number went to this phone --, latest
 * first; their own contact, tied to one of those numbers; and the number
 * messages go out with, among that contact's. Never asked of Android, which
 * gives what the network says only behind a permission it names after the
 * calls.
 */
object Me {

    /** The number messages go out with, and a group MMS comes back to: the one chosen on one's contact, else the latest received at. */
    fun number(context: Context): String? = chosen(context) ?: numbers(context).firstOrNull()

    /** Every number this phone is, or was: to tell it among a group's members, whichever it was then. */
    fun all(context: Context): List<String> = once(listOfNotNull(chosen(context)) + numbers(context))

    /** Whether [number] is one of this phone's. */
    fun mine(context: Context, number: String): Boolean = all(context).any { Phone.same(it, number) || it == number }

    /** The numbers this phone has received MMS at, latest first. */
    fun numbers(context: Context): List<String> = kept(context).getString(NUMBERS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun chosen(context: Context): String? = kept(context).getString(CHOSEN, null)

    fun choose(context: Context, number: String?) =
        kept(context).edit().apply { if (number == null) remove(CHOSEN) else putString(CHOSEN, number) }.apply()

    /** One's own contact, by its lookup key; none until tied. */
    fun card(context: Context): String? = kept(context).getString(CARD, null)

    fun card(context: Context, key: String?) =
        kept(context).edit().apply { if (key == null) remove(CARD) else putString(CARD, key) }.apply()

    /** An MMS that went to one number alone came to this phone: that number is this phone's, the latest. */
    fun learn(context: Context, to: List<String>) {
        val only = to.singleOrNull()?.takeIf { Phone.plain(it) != null } ?: return
        val now = numbers(context)
        if (now.firstOrNull() == only) return
        keep(context, once(listOf(only) + now))
    }

    /**
     * The numbers of the MMS received that went to one number alone, among
     * the [most] latest, latest first: the card may have changed, and each
     * was this phone's in its time. Not on the main thread.
     */
    fun look(context: Context, most: Int = 300) {
        val found = ArrayList<String>()
        runCatching {
            context.contentResolver.query(INBOX, arrayOf("_id"), null, null, "date DESC")?.use { rows ->
                var seen = 0
                while (rows.moveToNext() && seen++ < most) {
                    val to = ArrayList<String>()
                    context.contentResolver.query(Uri.parse("content://mms/${rows.getLong(0)}/addr"), arrayOf("address", "type"), null, null, null)?.use { addresses ->
                        while (addresses.moveToNext()) if (addresses.getInt(1) in TO_OR_CC) addresses.getString(0)?.let { to += it }
                    }
                    to.singleOrNull()?.takeIf { Phone.plain(it) != null }?.let { found += it }
                }
            }
        }
        if (found.isNotEmpty()) keep(context, once(found))
    }

    private fun keep(context: Context, numbers: List<String>) = kept(context).edit().putString(NUMBERS, numbers.joinToString("\n")).apply()

    /** Each number once, however it is written; the first writing kept. */
    private fun once(numbers: List<String>): List<String> {
        val all = ArrayList<String>()
        for (one in numbers) if (all.none { Phone.same(it, one) || it == one }) all += one
        return all
    }

    private val INBOX: Uri = Uri.parse("content://mms/inbox")

    /** How the phone's store tells an MMS's recipients: To and Cc, as the PDU names them (OMA-MMS-ENC, table 12). */
    private val TO_OR_CC = setOf(0x97, 0x82)

    private const val NUMBERS = "numbers"
    private const val CHOSEN = "chosen_number"
    private const val CARD = "card"

    private fun kept(context: Context) = context.getSharedPreferences("me", Context.MODE_PRIVATE)
}
