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
        // Where earlier versions played and watched what was opened from: emptied, should one have been left.
        for (left in listOf("playing", "listening", "watching.3gp")) File(cacheDir, left).delete()
    }
}
