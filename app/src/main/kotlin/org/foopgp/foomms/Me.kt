package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * This phone's own number, to name a group by all its members, this phone
 * among them, when a message is sent to several: the number of the SIM card
 * messages go out by, as the phone's settings show it. The person's
 * certificate cannot say it -- one person, two phones, two numbers.
 * Until the person lets foomms read it, or when the card does not say it:
 * the number an MMS to this phone alone carries, in its only recipient.
 */
object Me {

    const val PERMISSION = android.Manifest.permission.READ_PHONE_NUMBERS

    /** What asking for [PERMISSION] is answered under. */
    const val ASKED = 77

    fun number(context: Context): String? = fromCard(context) ?: context.getSharedPreferences("me", Context.MODE_PRIVATE).getString(LEARNT, null)

    /** The number of the card messages go out by: none without the permission, or when the card and the network do not say it. */
    fun fromCard(context: Context): String? {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return null
        val card = SubscriptionManager.getDefaultSmsSubscriptionId()
        val number = runCatching {
            @Suppress("MissingPermission")
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(SubscriptionManager::class.java).getPhoneNumber(card)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(TelephonyManager::class.java).let { if (card >= 0) it.createForSubscriptionId(card) else it }.line1Number
            }
        }.getOrNull()
        return number?.takeIf { Phone.plain(it) != null }
    }

    /** An MMS that went to one number alone came to this phone: that number is this phone's. */
    fun learn(context: Context, to: List<String>) {
        val only = to.singleOrNull()?.takeIf { Phone.plain(it) != null } ?: return
        val kept = context.getSharedPreferences("me", Context.MODE_PRIVATE)
        if (kept.getString(LEARNT, null) != only) kept.edit().putString(LEARNT, only).apply()
    }

    /**
     * Where a group needs it: the permission asked, after a word on why --
     * Android names it after the calls it shares a group with. Not asked
     * again once refused for good.
     */
    fun ask(activity: Activity) {
        if (activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return
        val kept = activity.getSharedPreferences("me", Context.MODE_PRIVATE)
        if (kept.getBoolean(ASKED_ONCE, false) && !activity.shouldShowRequestPermissionRationale(PERMISSION)) return
        AlertDialog.Builder(activity)
            .setMessage(R.string.my_number_why)
            .setPositiveButton(R.string.allow) { _, _ ->
                kept.edit().putBoolean(ASKED_ONCE, true).apply()
                activity.requestPermissions(arrayOf(PERMISSION), ASKED)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private const val LEARNT = "learnt_number"
    private const val ASKED_ONCE = "asked"
}
