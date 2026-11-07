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

    /**
     * The number messages go out with, and a group MMS comes back to: the
     * one chosen on one's own contact. None without it: the numbers received
     * at are only likely to be this phone's.
     */
    fun number(context: Context): String? = chosen(context)

    /**
     * The numbers to tell this phone by among a group's members: its own,
     * once one's contact gives it -- a number it had once may be another
     * phone's now --; until then, every number it has received MMS at.
     */
    fun all(context: Context): List<String> = chosen(context)?.let { listOf(it) } ?: numbers(context)

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
                    val to = Store.addresses(context, rows.getLong(0)).filter { it.type in Store.TO_OR_CC }.map { it.number }
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

    private const val NUMBERS = "numbers"
    private const val CHOSEN = "chosen_number"
    private const val CARD = "card"

    /**
     * Oneself as a small face, last in each group's square, read off the page: one's contact's picture, else the
     * one one's certificate carries, else pixels from one's number -- framed in what one's certificate is worth.
     */
    fun mini(context: Context, foodjis: Foodjis): Look.Mini {
        val own = card(context)?.let { Book.own(context, it) }
        val cert = own?.fingerprint?.let { foodjis.card(it) }
        return Look.Mini(
            own?.photo?.let { Media.thumbnail(context, it, Media.FACE) } ?: own?.fingerprint?.let { foodjis.face(it) },
            cert?.validity, Look.seed(own?.fingerprint, number(context)),
        )
    }

    private fun kept(context: Context) = context.getSharedPreferences("me", Context.MODE_PRIVATE)
}
