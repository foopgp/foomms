package org.foopgp.foomms

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * The person, as this phone knows them: their own contact, chosen in the
 * configuration, and this phone's number among its numbers -- one person may
 * carry two phones. The number names a group by all its members, this phone
 * among them, when a message is sent to several.
 *
 * Until it is chosen: the number read from the MMS this phone has received,
 * one that went to a single number having gone to this phone. Never asked of
 * Android, which gives what the network says only behind a permission it
 * names after the calls. Checked, when the person asks, by an SMS to it that
 * must come back here.
 */
object Me {

    fun number(context: Context): String? = chosen(context) ?: learnt(context)

    /** The number chosen among those of one's own contact; none, the one learnt. */
    fun chosen(context: Context): String? = kept(context).getString(CHOSEN, null)

    fun choose(context: Context, number: String?) {
        if (number == chosen(context)) return
        kept(context).edit().apply { if (number == null) remove(CHOSEN) else putString(CHOSEN, number) }.remove(VERIFIED).apply()
    }

    fun learnt(context: Context): String? = kept(context).getString(LEARNT, null)

    /** One's own contact, by its lookup key; none until chosen. */
    fun card(context: Context): String? = kept(context).getString(CARD, null)

    fun card(context: Context, key: String?) =
        kept(context).edit().apply { if (key == null) remove(CARD) else putString(CARD, key) }.apply()

    /** The number an SMS has come back from, here: this phone's for sure. */
    fun verified(context: Context): String? = kept(context).getString(VERIFIED, null)

    /** An MMS that went to one number alone came to this phone: that number is this phone's. */
    fun learn(context: Context, to: List<String>) {
        val only = to.singleOrNull()?.takeIf { Phone.plain(it) != null } ?: return
        if (learnt(context) != only) kept(context).edit().putString(LEARNT, only).apply()
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

    /** Where a check stands: none asked, waiting for its SMS, come back, or not come back in time. */
    enum class Check { NONE, WAITING, BACK, LOST }

    @Volatile var check = Check.NONE
        private set

    /** Told on the main thread when a check comes back or is given up: the configuration draws itself again. */
    @Volatile var onCheck: (() -> Unit)? = null

    private var waiting: Pair<String, String>? = null

    /**
     * An SMS with a code to [number]: back on this phone, the number is this
     * phone's. Neither the SMS sent nor the one received is kept among the
     * conversations: as the phone's messaging application, foomms writes
     * what it sends and receives, and writes neither.
     */
    fun verify(context: Context, number: String) {
        val code = (100000 + java.security.SecureRandom().nextInt(900000)).toString()
        waiting = number to code
        check = Check.WAITING
        runCatching {
            Outbox.manager(context).sendTextMessage(number, null, context.getString(R.string.me_sms, code), null, null)
        }.onFailure { waiting = null ; check = Check.LOST ; return }
        Handler(Looper.getMainLooper()).postDelayed({
            if (waiting?.second == code) { waiting = null ; check = Check.LOST ; onCheck?.invoke() }
        }, CHECK_MS)
    }

    /** An SMS arrived: the check's, it is taken here and goes no further. On the main thread. */
    fun answered(context: Context, body: String): Boolean {
        val (number, code) = waiting ?: return false
        if (!body.contains(code)) return false
        waiting = null
        check = Check.BACK
        kept(context).edit().putString(CHOSEN, number).putString(VERIFIED, number).apply()
        onCheck?.invoke()
        return true
    }

    private const val CHECK_MS = 90_000L

    private val INBOX: Uri = Uri.parse("content://mms/inbox")

    /** How the phone's store tells an MMS's recipients: To and Cc, as the PDU names them (OMA-MMS-ENC, table 12). */
    private val TO_OR_CC = setOf(0x97, 0x82)

    private const val LEARNT = "learnt_number"
    private const val CHOSEN = "chosen_number"
    private const val VERIFIED = "verified_number"
    private const val CARD = "card"

    private fun kept(context: Context) = context.getSharedPreferences("me", Context.MODE_PRIVATE)
}
