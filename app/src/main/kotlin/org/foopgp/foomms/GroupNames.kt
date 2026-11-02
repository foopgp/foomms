package org.foopgp.foomms

import android.content.Context

/**
 * The names the person gives groups, kept on this phone only: a group is
 * the set of its numbers ([Phone.group]), and its name goes with that set
 * -- a member added or gone, it is another group, unnamed.
 */
object GroupNames {

    /** The name of the group of [key] ([Phone.group]), or null. */
    fun of(context: Context, key: String): String? = kept(context).getString(key, null)?.takeIf { it.isNotBlank() }

    /** Named [name], or unnamed when [name] is blank. */
    fun name(context: Context, key: String, name: String?) {
        val edit = kept(context).edit()
        if (name.isNullOrBlank()) edit.remove(key) else edit.putString(key, name.trim())
        edit.apply()
    }

    private fun kept(context: Context) = context.getSharedPreferences("group-names", Context.MODE_PRIVATE)
}
