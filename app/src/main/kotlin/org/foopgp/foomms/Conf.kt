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

    /** The most messages not read yet a use of the key may be set to open beyond its own conversation. */
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

    /**
     * MMS on: a text longer than one SMS leaves as an MMS, and parts, voices
     * and group MMS can be sent. Off: a long text leaves in as many SMS as it
     * takes, and none of those. MMS received show all the same.
     */
    fun mms(context: Context): Boolean = kept(context).getBoolean("mms", true)

    fun mms(context: Context, on: Boolean) = kept(context).edit().putBoolean("mms", on).apply()

    /**
     * Whether a text that takes [pieces] SMS leaves as an MMS: only a long
     * one, and only when MMS may be sent. SMS arrive where MMS sometimes do
     * not -- from one network to another --, and a few of them are no burden.
     */
    fun asMms(context: Context, pieces: Int): Boolean = asMms(mms(context), pieces, smsMost(context))

    fun asMms(mms: Boolean, pieces: Int, most: Int = SMS_MOST): Boolean = mms && pieces > most

    /**
     * The most SMS a text leaves in: beyond, an MMS when MMS may be sent, and
     * otherwise the person asked first -- some phones put many back together
     * badly, and each may be charged. Seven passes almost everywhere; what a
     * message can be also depends on the network it arrives on, which
     * nothing here knows ahead.
     */
    fun smsMost(context: Context): Int = kept(context).getInt("sms_most", SMS_MOST).coerceIn(SMS_LEAST, SMS_MOST_EVER)

    fun smsMost(context: Context, most: Int) = kept(context).edit().putInt("sms_most", most.coerceIn(SMS_LEAST, SMS_MOST_EVER)).apply()

    const val SMS_MOST = 7
    const val SMS_LEAST = 1

    /**
     * The longest text foodjis still shows as text is 8 KiB of armor
     * (Envelope.MOST_TEXT); past it, a file. Armor is ASCII, 153 characters
     * to each SMS of a long one (3GPP TS 23.040 §9.2.3.24.1): 53 SMS, kept
     * round at 32.
     */
    const val SMS_MOST_EVER = 32

    /** What an MMS may weigh, in kB, as the person set it; heard only where the operator says it takes more than the standard. */
    fun mmsSize(context: Context): Int? = kept(context).getInt("mms_size", 0).takeIf { it > 0 }

    fun mmsSize(context: Context, kb: Int) = kept(context).edit().putInt("mms_size", kb).apply()

    /** Send MMS as heavy as the operator says it takes, beyond the standard's 300 kB: what [mmsSize] was before it. */
    fun operatorSize(context: Context): Boolean = kept(context).getBoolean("operator_size", false)

    fun operatorSize(context: Context, on: Boolean) = kept(context).edit().putBoolean("operator_size", on).apply()

    /**
     * How many a group MMS may go to, this phone included. Twenty by
     * default: the MMS standard has every network take twenty addresses
     * (OMA-TS-MMS-CONF-V1_3 §10.2.5), and twenty members, oneself among
     * them, are nineteen. A few networks take only ten -- Android's own
     * configuration says so of TELUS --: the least it may be set to.
     */
    fun groupMost(context: Context): Int = kept(context).getInt("group_most", GROUP_MOST).coerceIn(GROUP_LEAST, GROUP_MOST_EVER)

    fun groupMost(context: Context, most: Int) = kept(context).edit().putInt("group_most", most.coerceIn(GROUP_LEAST, GROUP_MOST_EVER)).apply()

    const val GROUP_MOST = 20
    const val GROUP_LEAST = 10
    const val GROUP_MOST_EVER = 255


    private inline fun <reified T : Enum<T>> named(name: String?, otherwise: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: otherwise

    private fun kept(context: Context) = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
}
