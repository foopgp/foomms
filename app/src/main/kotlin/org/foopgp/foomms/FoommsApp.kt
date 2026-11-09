package org.foopgp.foomms

import android.app.Application
import java.io.File

/**
 * The application as it starts, whatever starts it -- a page, a message
 * arriving: what was opened and handed to another application last time is
 * gone first. A process ended while something was open left it in the cache,
 * in clear, until a page of foomms was shown again.
 */
class FoommsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Outbox.shut(this)
        // The journal of what was sent, kept by earlier versions and read by none: who was written to, and when.
        deleteSharedPreferences("sent")
        // What is opened is held as long as set, from the start: a message arriving, or foodjis answering, before any page.
        Opened.holdFor = Conf.hold(this).seconds * 1000L
        // Where earlier versions played and watched what was opened from: emptied, should one have been left.
        for (left in listOf("playing", "listening", "watching.3gp")) File(cacheDir, left).delete()
    }
}
