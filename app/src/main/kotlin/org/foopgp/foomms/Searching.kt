package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent

/**
 * The search button, the same on every page: in the contacts, in every
 * conversation, or on the page itself -- what it shows, filtered as words
 * are typed.
 */
object Searching {

    fun ask(activity: Activity, onPage: () -> Unit) {
        val choices = listOf<Pair<Int, () -> Unit>>(
            R.string.search_contacts to { open(activity, SearchActivity.IN_CONTACTS) },
            R.string.search_all to { open(activity, SearchActivity.IN_TALKS) },
            R.string.search_page to onPage,
        )
        AlertDialog.Builder(activity)
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    private fun open(activity: Activity, where: String) =
        activity.startActivity(Intent(activity, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_IN, where))
}
