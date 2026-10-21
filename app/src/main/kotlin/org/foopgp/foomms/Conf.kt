package org.foopgp.foomms

import android.content.Context

/** What the person sets, kept between two uses of the application. */
object Conf {

    enum class Theme { DARK, SYSTEM, LIGHT }

    /** How long encrypted messages stay open after the last one was opened. */
    enum class Hold(val seconds: Int) {
        HALF_MINUTE(30),
        TWO_MINUTES(120),
        FIVE_MINUTES(300),
        FIFTEEN_MINUTES(900),
        HOUR(3600),
        FOUR_HOURS(14400),
        TWELVE_HOURS(43200),
    }

    const val MOST_AROUND = 15

    /** The most messages not read yet a use of the key opens beyond its own conversation: set, or asked from the list. */
    const val MOST_UNREAD = 10
    private const val USUAL_AROUND = 7
    private const val USUAL_UNREAD_ALL = 3

    /** How many messages shown before the one pressed, and as many after, a use of the key tries to open with it; none, and it opens alone. */
    fun around(context: Context): Int = kept(context).getInt("before", USUAL_AROUND).coerceIn(0, MOST_AROUND)

    fun around(context: Context, most: Int) = kept(context).edit().putInt("before", most.coerceIn(0, MOST_AROUND)).apply()

    /** How many messages not read yet, in the other conversations, a use of the key in one tries to open as well. */
    fun unreadAll(context: Context): Int = kept(context).getInt("unread_all", USUAL_UNREAD_ALL).coerceIn(0, MOST_UNREAD)

    fun unreadAll(context: Context, most: Int) = kept(context).edit().putInt("unread_all", most.coerceIn(0, MOST_UNREAD)).apply()

    fun theme(context: Context): Theme = named(kept(context).getString("theme", null), Theme.SYSTEM)

    fun theme(context: Context, theme: Theme) = kept(context).edit().putString("theme", theme.name).apply()

    fun hold(context: Context): Hold = named(kept(context).getString("hold", null), Hold.FIVE_MINUTES)

    fun hold(context: Context, hold: Hold) = kept(context).edit().putString("hold", hold.name).apply()

    /** A locked message is locked for its sender too, who can then read it again. */
    fun toMyself(context: Context): Boolean = kept(context).getBoolean("to_myself", true)

    fun toMyself(context: Context, on: Boolean) = kept(context).edit().putBoolean("to_myself", on).apply()

    /** A text that takes more than one SMS leaves as an MMS; otherwise only one that takes more than [MOST_SMS]. */
    /** Where the search of the list of conversations looks: the contacts, or the messages. */
    fun searchContacts(context: Context): Boolean = kept(context).getBoolean("search_contacts", true)

    fun searchContacts(context: Context, on: Boolean) = kept(context).edit().putBoolean("search_contacts", on).apply()

    fun preferMms(context: Context): Boolean = kept(context).getBoolean("prefer_mms", false)

    fun preferMms(context: Context, on: Boolean) = kept(context).edit().putBoolean("prefer_mms", on).apply()

    /** Send MMS as heavy as the operator says it takes, beyond the standard's 300 kB. */
    fun operatorSize(context: Context): Boolean = kept(context).getBoolean("operator_size", false)

    fun operatorSize(context: Context, on: Boolean) = kept(context).edit().putBoolean("operator_size", on).apply()

    /** Ask before a message leaves as an MMS, which an operator may charge for. */
    fun warnMms(context: Context): Boolean = kept(context).getBoolean("warn_mms", false)

    fun warnMms(context: Context, on: Boolean) = kept(context).edit().putBoolean("warn_mms", on).apply()

    /** Beyond this many SMS, a text leaves as one MMS whatever was set. */
    const val MOST_SMS = 5

    /** Whether a text that would take [pieces] SMS leaves as an MMS. */
    fun asMms(pieces: Int, prefer: Boolean): Boolean = pieces > if (prefer) 1 else MOST_SMS

    private inline fun <reified T : Enum<T>> named(name: String?, otherwise: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: otherwise

    private fun kept(context: Context) = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
}
