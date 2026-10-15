package org.foopgp.foomms

/**
 * Which messages one use of the security key opens with the one pressed: the
 * messages shown before it, as many as the person set -- one way only, which
 * is clearer to everybody. Every one shown counts, PGP or not, open or not;
 * of those, the ones still closed are what is opened.
 */
object Around {

    /** The places, among [count] messages shown, of the [reach] before the one at [chosen]. */
    fun others(count: Int, chosen: Int, reach: Int): List<Int> =
        (maxOf(0, chosen - reach) until minOf(chosen, count)).toList()
}
