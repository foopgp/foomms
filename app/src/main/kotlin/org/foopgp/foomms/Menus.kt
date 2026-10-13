package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText

/**
 * The same menu on every page, less the entry of the page one is on: a
 * menu that changes from page to page is one to learn each time.
 */
object Menus {

    private const val TALKS = 0x7001
    private const val WRITE = 0x7002
    private const val CONFIGURE = 0x7003

    fun build(activity: Activity, menu: Menu) {
        if (activity !is MainActivity) menu.add(0, TALKS, 10, R.string.talks)
        menu.add(0, WRITE, 11, R.string.write)
        if (activity !is ConfActivity) menu.add(0, CONFIGURE, 12, R.string.configure)
    }

    /** True when the entry was one of these, and what it asks has been started. */
    fun select(activity: Activity, item: MenuItem): Boolean {
        when (item.itemId) {
            TALKS -> activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            WRITE -> write(activity)
            CONFIGURE -> activity.startActivity(Intent(activity, ConfActivity::class.java))
            else -> return false
        }
        return true
    }

    /** A new message begins with whom it is for. */
    private fun write(activity: Activity) {
        val number = EditText(activity).apply { inputType = InputType.TYPE_CLASS_PHONE ; setHint(R.string.number_hint) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.write)
            .setView(number)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Phone.plain(number.text.toString())?.let {
                    activity.startActivity(Intent(activity, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, it))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
