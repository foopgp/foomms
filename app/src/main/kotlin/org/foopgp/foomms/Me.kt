package org.foopgp.foomms

import android.content.Context

/**
 * This phone's own number, to name a group by all its members, this phone
 * among them, when a message is sent to several. Nothing to ask the person:
 * an MMS to this phone alone carries it, in its only recipient; until one
 * has come, the number the person's own certificate carries in foodjis, or
 * else that of the contact tied to it.
 */
object Me {

    fun number(context: Context): String? = kept(context).let { it.getString(LEARNT, null) ?: it.getString(CERTIFIED, null) }

    /** An MMS that went to one number alone came to this phone: that number is this phone's. */
    fun learn(context: Context, to: List<String>) {
        val only = to.singleOrNull()?.takeIf { Phone.plain(it) != null } ?: return
        if (kept(context).getString(LEARNT, null) != only) kept(context).edit().putString(LEARNT, only).apply()
    }

    /** The number of the person's certificate, of the key foodjis signs with, or of their contact tied to it. Not on the main thread. */
    fun certified(context: Context, keyId: Long) {
        val cert = Foodjis.of(context).signer(keyId) ?: return
        val carried = Phone.carried(cert.userIds).firstOrNull() ?: Book.numbers(context, cert.fingerprint).firstOrNull() ?: return
        if (kept(context).getString(CERTIFIED, null) != carried) kept(context).edit().putString(CERTIFIED, carried).apply()
    }

    private const val LEARNT = "learnt_number"
    private const val CERTIFIED = "certified_number"

    private fun kept(context: Context) = context.getSharedPreferences("me", Context.MODE_PRIVATE)
}
