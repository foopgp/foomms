package org.foopgp.foomms

/**
 * Which messages one use of the security key opens with the one pressed: the
 * nearest that are still closed, before it and after it in turn, up to as
 * many as the person set.
 */
object Around {

    /**
     * The places, among [count] messages, of at most [most] [closed] ones
     * nearest to [chosen], that one left out; of two as near, the earlier first.
     */
    fun others(count: Int, chosen: Int, most: Int, closed: (Int) -> Boolean): List<Int> {
        val found = ArrayList<Int>()
        var step = 1
        while (found.size < most && (chosen - step >= 0 || chosen + step < count)) {
            for (at in listOf(chosen - step, chosen + step)) {
                if (found.size < most && at in 0 until count && closed(at)) found += at
            }
            step++
        }
        return found
    }
}
