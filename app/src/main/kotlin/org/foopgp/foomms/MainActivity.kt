package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
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
 * The conversations the phone holds, newest first: who, the last words, when,
 * and down the edge the colour of the last message.
 */
class MainActivity : Activity() {

    class Row(val talk: Store.Talk, val person: Book.Person?, val face: Bitmap?, val name: String, val verdict: Verdict, val last: String)

    private lateinit var list: ListView
    private lateinit var asking: LinearLayout
    private var rows: List<Row> = emptyList()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.app_title)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        asking = Look.column(this)
        list = ListView(this).apply {
            divider = null
            adapter = talks
            setOnItemClickListener { _, _, at, _ -> rows[at].talk.number?.let { open(it) } }
        }
        column.addView(asking)
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        synchronized(people) { people.clear() }
        asking.removeAllViews()
        val missing = NEEDED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.READ_SMS in missing) {
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.about), muted = true))
            asking.addView(Look.words(this, getString(R.string.why_read)).apply { setPadding(0, Look.dp(context, 12), 0, Look.dp(context, 12)) })
            asking.addView(Look.button(this, getString(R.string.allow)) { requestPermissions(missing.toTypedArray(), 1) })
            return
        }
        // Not the phone's messaging application yet: it can be asked for, and until then another receives.
        if (Keep.ours(this)) asking.visibility = View.GONE
        else {
            asking.visibility = View.VISIBLE
            asking.addView(Look.words(this, getString(R.string.not_default), size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })
            asking.addView(Look.button(this, getString(R.string.become_default)) { becomeDefault() })
        }
        fill()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) = onResume()

    private val arrivals = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(self: Boolean) = fill()
    }

    override fun onStart() {
        super.onStart()
        runCatching { contentResolver.registerContentObserver(android.net.Uri.parse("content://mms-sms/"), true, arrivals) }
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(arrivals)
        super.onStop()
    }

    /** Who a number is, asked of the address book once for as long as this page is shown. */
    private val people = HashMap<String, Book.Person?>()

    /**
     * The latest conversations first, shown as soon as they are read; the
     * rest behind them. A phone holds years of messages, and what one opens
     * the application for is at the top.
     */
    private fun fill() {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val turn = ++turns
        thread {
            val foodjis = Foodjis.of(this)
            fun row(talk: Store.Talk): Row {
                val person = talk.number?.takeIf { contacts }?.let { number ->
                    synchronized(people) { if (people.containsKey(number)) return@let people[number] }
                    Book.who(this, number).also { synchronized(people) { people[number] = it } }
                }
                val face = person?.fingerprint?.takeIf { person.photo == null }?.let { foodjis.face(it) }
                val armor = Pgp.armorIn(talk.last)
                val verdict = when {
                    armor == null -> Verdict.RED
                    else -> Opened.verdict(this, armor) ?: Verdict.of(Pgp.isLocked(armor), false)
                }
                val last = when {
                    armor == null -> talk.last
                    else -> Opened.words(armor)?.words ?: getString(if (Pgp.isLocked(armor)) R.string.locked_message else R.string.signed_message)
                }
                return Row(talk, person, face, person?.name ?: talk.number ?: getString(R.string.unknown_number), verdict, last)
            }
            fun show(found: List<Row>) = runOnUiThread {
                if (isFinishing || isDestroyed || turn != turns) return@runOnUiThread
                rows = found
                talks.notifyDataSetChanged()
            }
            val first = runCatching { Store.talks(this, FIRST) }.getOrDefault(emptyList()).map(::row)
            show(first)
            if (first.size < FIRST) return@thread
            show(runCatching { Store.talks(this, Int.MAX_VALUE) }.getOrDefault(emptyList()).map(::row))
        }
    }

    /** Which filling is the latest asked for: an older one that finishes late shows nothing. */
    private var turns = 0

    private val talks = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(at: Int) = rows[at]
        override fun getItemId(at: Int) = rows[at].talk.thread

        override fun getView(at: Int, old: View?, parent: ViewGroup): View {
            val row = rows[at]
            val context = this@MainActivity
            val pad = Look.dp(context, 12)
            val names = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            names.addView(
                Look.words(context, row.name, size = 17f).apply {
                    setTypeface(typeface, if (row.talk.unread) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1 ; ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            names.addView(Look.words(context, ago(row.talk.at), size = 13f, muted = true))
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, 0, 0, 0)
                addView(names)
                addView(
                    Look.words(context, row.last.replace('\n', ' '), size = 14f, muted = !row.talk.unread).apply {
                        maxLines = 1 ; ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The colour of the last message, down the edge of the conversation.
                addView(View(context).apply { background = Look.rounded(Look.colour(row.verdict), Look.dp(context, 3)) }, LinearLayout.LayoutParams(Look.dp(context, 6), Look.dp(context, 44)))
                addView(View(context), LinearLayout.LayoutParams(pad, 1))
                addView(Look.face(context, 44, row.person?.photo, row.face))
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setPadding(Look.dp(context, 4), pad, pad, pad)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, WRITE, 0, R.string.write).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, CONFIGURE, 1, R.string.configure)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == CONFIGURE) return startActivity(Intent(this, ConfActivity::class.java)).let { true }
        if (item.itemId != WRITE) return super.onOptionsItemSelected(item)
        val number = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE ; setHint(R.string.number_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.write)
            .setView(number)
            .setPositiveButton(android.R.string.ok) { _, _ -> Phone.plain(number.text.toString())?.let { open(it) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    /** Android asks the person; nothing changes unless they say so. */
    private fun becomeDefault() {
        val ask = if (android.os.Build.VERSION.SDK_INT >= 29) {
            getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
        } else {
            Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(ask, 3)
    }

    private fun open(number: String) =
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number))

    private fun ago(at: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)

    private companion object {
        const val WRITE = 1
        const val CONFIGURE = 2

        /** How many conversations are shown before the rest is read. */
        const val FIRST = 20
        val NEEDED = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
    }
}
