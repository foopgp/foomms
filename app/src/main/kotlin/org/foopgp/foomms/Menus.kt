package org.foopgp.foomms

import android.app.Activity
import android.content.Intent

/**
 * Where the title bars lead -- the configuration -- and the
 * writing of a new message. No page has a menu: what it offers is in its bar.
 */
object Menus {

    fun configure(activity: Activity) = activity.startActivity(Intent(activity, ConfActivity::class.java))

    /** The profile's page, opened over the page asking -- where one's contact is tied: back from it, that page again. */
    fun profile(activity: Activity) = activity.startActivity(Intent(activity, MainActivity::class.java).putExtra(PROFILE, true))

    /** Said to the list's page: open on the profile's page. */
    const val PROFILE = "org.foopgp.foomms.profile"

    /**
     * A new message: its page, where whom it is for is chosen ([WriteActivity]),
     * with what was shared from another application.
     */
    fun write(activity: Activity, text: String? = null, carried: android.net.Uri? = null) {
        activity.startActivity(
            Intent(activity, WriteActivity::class.java).putExtra(Intent.EXTRA_TEXT, text).apply {
                // What was shared goes on with the page, and the right to read it with it.
                carried?.let { putExtra(Intent.EXTRA_STREAM, it) ; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) ; clipData = android.content.ClipData.newRawUri(null, it) }
            },
        )
    }
}
