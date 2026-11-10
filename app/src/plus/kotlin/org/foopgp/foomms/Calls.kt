package org.foopgp.foomms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog

/**
 * The calls with a number, from the phone's call history: foomms+'s own. Google Play lets a messaging application
 * read neither, which is why foomms has an edition for F-Droid.
 */
object Calls {
    const val offered = true
    val PERMISSION: String? = Manifest.permission.READ_CALL_LOG
    val CHANGES: Uri? = CallLog.Calls.CONTENT_URI

    /** Every call with [number], however it is written, oldest first; none while the history is not to be read. */
    fun with(context: Context, number: String): List<Store.Line> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val found = ArrayList<Store.Line>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            null, null, CallLog.Calls.DATE + " ASC",
        )?.use { rows ->
            while (rows.moveToNext()) {
                val other = rows.getString(0) ?: continue
                if (!Phone.same(other, number)) continue
                val kind = when (rows.getInt(3)) {
                    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> Called.Kind.IN
                    CallLog.Calls.OUTGOING_TYPE -> Called.Kind.OUT
                    CallLog.Calls.MISSED_TYPE -> Called.Kind.MISSED
                    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> Called.Kind.REFUSED
                    // A voicemail is a message of the operator's, not a call.
                    else -> continue
                }
                found += Store.Line(at = rows.getLong(1), incoming = kind != Called.Kind.OUT, body = "", call = Called(kind, rows.getLong(2)))
            }
        }
        return found
    }
}
