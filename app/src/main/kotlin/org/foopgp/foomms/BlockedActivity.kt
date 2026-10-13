package org.foopgp.foomms

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Everybody the person blocked, each with the way to unblock them. */
class BlockedActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.blocked_title)
        column = Look.column(this)
        setContentView(ScrollView(this).apply { addView(column) })
        draw()
    }

    private fun draw() {
        column.removeAllViews()
        val all = Marks.allBlocked(this)
        if (all.isEmpty()) column.addView(Look.words(this, getString(R.string.blocked_none), muted = true))
        val contacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        for (sender in all) {
            val name = if (contacts) Book.who(this, sender)?.name else null
            column.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Look.dp(context, 6), 0, Look.dp(context, 6))
                    addView(
                        Look.words(context, if (name != null && name != sender) "$name\n$sender" else sender, size = 16f),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(Look.button(context, getString(R.string.unblock)) { Marks.block(context, sender, false) ; draw() })
                },
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Menus.build(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = Menus.select(this, item) || super.onOptionsItemSelected(item)
}
