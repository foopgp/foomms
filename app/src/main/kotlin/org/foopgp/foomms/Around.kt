package org.foopgp.foomms

/**
 * Which messages one use of the security key opens with the one pressed: the
 * messages shown before it and after it, as many on each side as the person
 * set. Every one shown counts, PGP or not, open or not; of those, the ones
 * still closed are what is opened.
 */
object Around {

    /** The places, among [count] messages shown, within [reach] of the one at [chosen], that one left out. */
    fun others(count: Int, chosen: Int, reach: Int): List<Int> =
        (maxOf(0, chosen - reach)..minOf(count - 1, chosen + reach)).filter { it != chosen }
}
