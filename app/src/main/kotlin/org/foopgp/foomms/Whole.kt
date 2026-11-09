package org.foopgp.foomms

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Whether this phone's network takes an MMS asking to be kept whole on the way ([Pdu.whole]). No network says
 * beforehand: Orange takes it, Free refuses the whole MMS. So it is asked, and a network that refuses it once is
 * known by its code (MCC and MNC, from the SIM card, which needs no permission) and never asked again.
 */
object Whole {

    private fun kept(context: Context) = context.getSharedPreferences("whole", Context.MODE_PRIVATE)

    private fun network(context: Context): String =
        runCatching { context.getSystemService(TelephonyManager::class.java)?.simOperator }.getOrNull().orEmpty().ifEmpty { "?" }

    /** Whether to ask this network: not once it refused. */
    fun taken(context: Context): Boolean = !kept(context).getBoolean(network(context), false)

    /** This network refused it: not asked again. */
    fun refused(context: Context) = kept(context).edit().putBoolean(network(context), true).apply()
}
