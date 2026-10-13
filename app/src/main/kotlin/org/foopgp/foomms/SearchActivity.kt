package org.foopgp.foomms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import kotlin.concurrent.thread

/**
 * Looking for a message by its words: in every conversation, or in the one
 * the search was asked from. A PGP message is found by what it says only
 * while it is open; closed, nobody here knows what it says.
 */
class SearchActivity : Activity() {

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER) }
    private var hits: List<Store.Hit> = emptyList()
    private val names = HashMap<String, String>()
    private var turn = 0

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(if (number == null) R.string.search_all else R.string.search_here)
        val asked = EditText(this).apply {
            setHint(R.string.search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(text: Editable?) = look(text.toString())
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                },
            )
        }
        val list = ListView(this).apply {
            divider = null
            adapter = found
            setOnItemClickListener { _, _, at, _ ->
                val hit = hits[at]
                hit.number?.let {
                    startActivity(
                        Intent(context, ThreadActivity::class.java)
                            .putExtra(ThreadActivity.EXTRA_NUMBER, it).putExtra(ThreadActivity.EXTRA_AT, hit.at),
                    )
                }
            }
        }
        val pad = Look.dp(this, 12)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, 0)
                addView(asked)
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        asked.requestFocus()
    }

    private fun look(wanted: String) {
        val mine = ++turn
        thread {
            val within = number?.let { runCatching { Store.thread(this, it) }.getOrNull() ?: -1L }
            val results = runCatching { Store.search(this, wanted, within) }.getOrDefault(emptyList())
            val contacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (contacts) {
                for (who in results.mapNotNull { it.number }.distinct()) {
                    if (synchronized(names) { who in names }) continue
                    val name = Book.who(this, who)?.name ?: who
                    synchronized(names) { names[who] = name }
                }
            }
            runOnUiThread {
                // An answer to what was typed three letters ago is no answer to what is typed now.
                if (isFinishing || isDestroyed || mine != turn) return@runOnUiThread
                hits = results
                found.notifyDataSetChanged()
            }
        }
    }

    private val found = object : BaseAdapter() {
        override fun getCount() = hits.size
        override fun getItem(at: Int) = hits[at]
        override fun getItemId(at: Int) = at.toLong()

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val hit = hits[at]
            val context = this@SearchActivity
            val who = hit.number?.let { synchronized(names) { names[it] } ?: it } ?: getString(R.string.unknown_number)
            val about = getString(if (hit.incoming) R.string.hit_from else R.string.hit_to, who) + "  ·  " +
                DateUtils.getRelativeTimeSpanString(hit.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = Look.dp(context, 10)
                setPadding(pad / 2, pad, pad / 2, pad)
                addView(Look.words(context, about, size = 13f, muted = true))
                addView(
                    Look.words(context, hit.words.replace('\n', ' '), size = 16f).apply {
                        maxLines = 3 ; ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Menus.build(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = Menus.select(this, item) || super.onOptionsItemSelected(item)

    companion object {
        /** The conversation to look in; none, and every conversation is looked in. */
        const val EXTRA_NUMBER = "number"
    }
}
