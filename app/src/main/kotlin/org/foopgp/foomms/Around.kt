package org.foopgp.foomms

import android.content.Context

/**
 * How much of a conversation one presentation of the key opens: the message
 * chosen -- the last one when none is --, and as many before and after it as
 * the person set.
 */
object Around {

    const val USUAL = 5
    const val MOST = 50

    /** The places of [count] messages within [reach] of the one at [chosen], that one left out. */
    fun others(count: Int, chosen: Int, reach: Int): List<Int> =
        (maxOf(0, chosen - reach)..minOf(count - 1, chosen + reach)).filter { it != chosen }

    fun reach(context: Context): Int = kept(context).getInt("around", USUAL).coerceIn(0, MOST)

    fun set(context: Context, reach: Int) = kept(context).edit().putInt("around", reach.coerceIn(0, MOST)).apply()

    private fun kept(context: Context) = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
}
