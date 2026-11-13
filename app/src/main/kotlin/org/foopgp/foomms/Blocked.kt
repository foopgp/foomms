package org.foopgp.foomms

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract

/**
 * The senders Android drops before any application receives them: its own
 * list, shared by messages and calls, open to the messaging application of
 * the phone alone. A sender is kept as it is written -- a name as well as a
 * number --, and a name is matched as written.
 */
object Blocked {

    /** Whether foomms may read and change the list: as the phone's messaging application. */
    fun can(context: Context): Boolean =
        Keep.ours(context) && runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    fun of(context: Context, sender: String): Boolean =
        runCatching { BlockedNumberContract.isBlocked(context, sender) }.getOrDefault(false)

    /** [sender] blocked, or let through again: whether Android took it. */
    fun set(context: Context, sender: String, on: Boolean): Boolean = runCatching {
        if (on) {
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, sender) },
            ) != null
        } else {
            BlockedNumberContract.unblock(context, sender) > 0
        }
    }.getOrDefault(false)

    /** Android's page of the list, where it is seen and emptied. */
    fun manage(activity: Activity) {
        val telecom = activity.getSystemService(android.telecom.TelecomManager::class.java)
        runCatching { telecom?.createManageBlockedNumbersIntent()?.let { activity.startActivity(it) } }
    }
}
