package org.foopgp.foomms

import android.content.Context
import android.net.Uri

/** No calls in foomms as Google Play has it: Play lets a messaging application read no call history. See foomms+. */
object Calls {
    const val offered = false
    val PERMISSION: String? = null
    val CHANGES: Uri? = null

    fun with(context: Context, number: String): List<Store.Line> = emptyList()
}
