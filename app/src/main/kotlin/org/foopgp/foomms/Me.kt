package org.foopgp.foomms

import android.content.Context

/**
 * This phone's own number: what the person wrote in the configuration, or
 * else what foomms learnt. Android would give it only with a permission it
 * shows as "make and manage phone calls"; an MMS to this phone alone carries
 * it, in its only recipient, and that is where it is learnt. Needed to tell,
 * in a message to several, which of the numbers is this phone's.
 */
object Me {

    fun number(context: Context): String? = Conf.myNumber(context) ?: learnt(context)

    fun learnt(context: Context): String? = kept(context).getString(LEARNT, null)

    /** An MMS that went to one number alone came to this phone: that number is this phone's. */
    fun learn(context: Context, to: List<String>) {
        val only = to.singleOrNull()?.takeIf { Phone.plain(it) != null } ?: return
        if (learnt(context) != only) kept(context).edit().putString(LEARNT, only).apply()
    }

    private const val LEARNT = "learnt_number"

    private fun kept(context: Context) = context.getSharedPreferences("me", Context.MODE_PRIVATE)
}
