package org.foopgp.foomms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The MMS announced and not fetched: kept by where they wait on the
 * operator's server -- a week, as long as operators usually keep them --,
 * said in a notification that fetches them again when pressed, and asked
 * for again whenever foomms is opened. One whose fetching failed, mobile data
 * off or abroad, was lost without a word.
 */
object Unfetched {

    class Waiting(val name: String, val location: String, val from: String?, val subscription: Int, val at: Long, val failed: Boolean)

    /** Asked for, by the file [name] it is to arrive in. */
    fun note(context: Context, name: String, location: String, from: String?, subscription: Int) {
        kept(context).edit().putString(name, listOf(location, from.orEmpty(), subscription, System.currentTimeMillis(), 0).joinToString("\t")).apply()
    }

    /** Arrived: forgotten here, and its notice taken away. */
    fun done(context: Context, name: String) {
        kept(context).edit().remove(name).apply()
        Notify.unfetchedGone(context, name)
    }

    /** Not arrived: kept as failed, and said -- unless its sender is one the person asked not to hear of. */
    fun failed(context: Context, name: String) {
        val one = all(context).firstOrNull { it.name == name } ?: return
        kept(context).edit().putString(name, listOf(one.location, one.from.orEmpty(), one.subscription, one.at, 1).joinToString("\t")).apply()
        if (one.from == null || !Marks.muted(context, one.from)) Notify.unfetched(context, one.from, name)
    }

    /** Every one asked for and not arrived, those older than a week let go. */
    fun all(context: Context): List<Waiting> {
        val now = System.currentTimeMillis()
        val edit = kept(context).edit()
        val found = kept(context).all.mapNotNull { (name, line) ->
            val parts = (line as? String)?.split('\t')?.takeIf { it.size >= 5 } ?: return@mapNotNull null
            val at = parts[3].toLongOrNull() ?: 0L
            if (now - at > WEEK_MS) { edit.remove(name) ; Notify.unfetchedGone(context, name) ; return@mapNotNull null }
            Waiting(name, parts[0], parts[1].ifEmpty { null }, parts[2].toIntOrNull() ?: -1, at, parts[4] == "1")
        }
        edit.apply()
        return found
    }

    /** Asked for again: under a new file, the failed one forgotten. */
    fun again(context: Context, name: String) {
        val one = all(context).firstOrNull { it.name == name } ?: return
        done(context, name)
        Inbound.fetch(context, one.location, one.from, one.subscription)
    }

    /** Every failed one asked for again: foomms opened, the network is likelier to be there. */
    fun againAll(context: Context) {
        for (one in all(context).filter { it.failed }) again(context, one.name)
    }

    /** Its notification pressed. */
    class Again : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            again(context, intent.getStringExtra(EXTRA_NAME) ?: return)
        }
    }

    const val EXTRA_NAME = "name"
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    private fun kept(context: Context) = context.getSharedPreferences("unfetched", Context.MODE_PRIVATE)
}
