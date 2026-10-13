package org.foopgp.foomms

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Spinner

/** What the person sets, a heading and a line of explanation above each, as foodjis lays its own out. */
class ConfActivity : Activity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.configure)
        val column = Look.column(this)
        column.addView(Look.words(this, getString(R.string.configure_around), size = 19f).apply { setTypeface(typeface, Typeface.BOLD) })
        column.addView(Look.words(this, getString(R.string.configure_around_about), size = 14f, muted = true))
        val choices = (Around.LEAST..Around.MOST).toList()
        column.addView(
            Spinner(this).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices)
                setSelection(choices.indexOf(Around.reach(context)))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, at: Int, id: Long) = Around.set(context, choices[at])
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            },
        )
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        Menus.build(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        Menus.select(this, item) || super.onOptionsItemSelected(item)
}
