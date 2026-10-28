package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/** What the person sets, a heading and a line of explanation above each, as foodjis lays its own out. */
class ConfActivity : AskingActivity() {

    private lateinit var column: LinearLayout

    /** Whether foodjis holds an identity to encrypt to: unknown until it has said. */
    private var holdsOne: Boolean? = null

    /** One's own contact, once read; the certificate of the identity foodjis holds, and the contact tied to it. */
    @Volatile private var own: Book.Own? = null
    @Volatile private var mineCert: Foodjis.Cert? = null
    @Volatile private var suggested: Book.Own? = null

    /** The certificate one's own contact is tied to, as foodjis tells it. */
    @Volatile private var ownCert: Foodjis.Cert? = null

    override fun onCreate(state: Bundle?) {
        Look.dress(this)
        super.onCreate(state)
        title = getString(R.string.configure)
        column = Look.column(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        // The software product and its version, as the About page names them: foomms-dev for a trial build.
        setContentView(Swipes.under(this, ScrollView(this).apply { addView(column) }, "${getString(R.string.app_name)} — $version"))
        Edges.fit(this)
        draw()
        // No identity in foodjis, nothing to encrypt to oneself: that switch is greyed, and off.
        provider.ask(Intent(Provider.GET_SIGN_KEY_ID), null) { answer ->
            holdsOne = answer is Provider.Answer.Done && answer.says.getLongExtra(Provider.EXTRA_SIGN_KEY_ID, 0L) != 0L
            val key = (answer as? Provider.Answer.Done)?.says?.getLongExtra(Provider.EXTRA_SIGN_KEY_ID, 0L)?.takeIf { it != 0L }
            if (!isFinishing && !isDestroyed) draw()
            // Whose identity it is, and the contact tied to its certificate: offered as one's own.
            if (key != null) kotlin.concurrent.thread {
                mineCert = Foodjis.of(this).signer(key)
                suggested = mineCert?.fingerprint?.let { Book.tiedTo(this, it) }?.let { Book.own(this, it) }
                runOnUiThread { if (!isFinishing && !isDestroyed) draw() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Me.onCheck = { if (!isFinishing && !isDestroyed) draw() }
        readOwn()
        draw()
    }

    override fun onPause() {
        Me.onCheck = null
        super.onPause()
    }

    /** One's own contact read again: it may have been changed in the address book meanwhile. */
    private fun readOwn() = kotlin.concurrent.thread {
        own = Me.card(this)?.let { Book.own(this, it) }
        ownCert = own?.fingerprint?.let { Foodjis.of(this).card(it) }
        runOnUiThread { if (!isFinishing && !isDestroyed) draw() }
    }

    private fun heading(words: Int) =
        column.addView(Look.words(this, getString(words), size = 19f).apply { setTypeface(typeface, Typeface.BOLD) ; setPadding(0, Look.dp(context, 18), 0, 0) })

    /** A setting among others under one heading: worded as a switch's, and as far from what is above it. */
    private fun subheading(words: Int) =
        column.addView(Look.words(this, getString(words), size = 17f).apply { setPadding(0, Look.dp(context, 12), 0, 0) })

    /** A number from 0 to [most], set by sliding, shown beside. */
    private fun slider(now: Int, most: Int, set: (Int) -> Unit) {
        val shown = Look.words(this, now.toString(), size = 18f)
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(
                    SeekBar(context).apply {
                        max = most
                        progress = now
                        setOnSeekBarChangeListener(
                            object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: SeekBar?, value: Int, byHand: Boolean) {
                                    set(value)
                                    shown.text = value.toString()
                                }
                                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(shown, LinearLayout.LayoutParams(Look.dp(context, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
    }

    private fun note(words: Int) = note(getString(words))

    private fun note(words: String) =
        column.addView(Look.words(this, words, size = 14f, muted = true).apply { setPadding(0, 0, 0, Look.dp(context, 8)) })

    private fun draw() {
        column.removeAllViews()

        heading(R.string.conf_language)
        // The language the pages are in, which is not always the phone's: one
        // the application does not speak falls back to English.
        column.addView(Look.words(this, getString(R.string.language_name), size = 17f))
        note(R.string.conf_language_note)
        // Chosen where Android keeps it, for this application alone: from
        // Android 13 the system has a page for exactly that.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            column.addView(
                Look.button(this, getString(R.string.conf_language_change)) {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null)),
                    )
                },
            )
        }

        heading(R.string.conf_theme)
        // Three states in a row, the one in force on the orange of what is pressed.
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val labels = mapOf(Conf.Theme.DARK to R.string.theme_dark, Conf.Theme.SYSTEM to R.string.theme_system, Conf.Theme.LIGHT to R.string.theme_light)
                val now = Conf.theme(context)
                for ((theme, label) in labels) {
                    addView(
                        Look.button(context, getString(label)) {
                            Conf.theme(context, theme)
                            recreate()
                        }.apply {
                            if (theme == now) { background = Look.buttonFace(context, Look.ORANGE) ; setTextColor(Look.INDIGO) }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Look.dp(context, 6) },
                    )
                }
            },
        )

        me()

        // What is locked: how much one use of the key opens, how long it stays open, and for whom it is locked.
        heading(R.string.conf_crypto)
        subheading(R.string.conf_around)
        note(R.string.conf_around_note)
        slider(Conf.around(this), Conf.MOST_AROUND) { Conf.around(this, it) }
        subheading(R.string.conf_unread_all)
        note(R.string.conf_unread_all_note)
        slider(Conf.unreadAll(this), Conf.MOST_UNREAD) { Conf.unreadAll(this, it) }

        subheading(R.string.conf_hold)
        note(R.string.conf_hold_note)
        val choices = Conf.Hold.entries
        val current = Conf.hold(this)
        column.addView(
            Look.button(this, "${getString(R.string.conf_hold)} — ${label(current)}") {
                AlertDialog.Builder(this)
                    .setTitle(R.string.conf_hold_after)
                    .setSingleChoiceItems(choices.map { label(it) }.toTypedArray(), choices.indexOf(current)) { dialog, which ->
                        dialog.dismiss()
                        Conf.hold(this, choices[which])
                        Look.dress(this)
                        draw()
                    }
                    .show()
            },
        )

        column.addView(
            flip(R.string.conf_to_myself, Conf.toMyself(this), usable = holdsOne != false) { Conf.toMyself(this, it) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Look.dp(this@ConfActivity, 12) },
        )
        note(R.string.conf_to_myself_note)

        heading(R.string.backup_all)
        note(R.string.backup_about)
        column.addView(Look.button(this, getString(R.string.backup_save)) { Saving.save(this) })
        column.addView(Look.button(this, getString(R.string.backup_restore)) { if (!Saving.restore(this)) backedUp(getString(R.string.forget_not_ours)) })
        if (backup.isNotEmpty()) column.addView(Look.words(this, backup, size = 15f))

        heading(R.string.conf_mms)
        column.addView(flip(R.string.conf_prefer_mms, Conf.preferMms(this)) { Conf.preferMms(this, it) ; draw() })
        note(R.string.conf_prefer_mms_note)
        // Left to itself, a long text still becomes an MMS: then one can ask to be told first.
        // Heavier than the standard only where the operator says it takes more: greyed, and off, otherwise.
        val theirs = Outbox.operators(this)
        column.addView(
            flip(R.string.conf_mms_size, Conf.operatorSize(this), usable = (theirs ?: 0) > Outbox.STANDARD) { Conf.operatorSize(this, it) }
                .apply { (theirs ?: Outbox.STANDARD).let { most -> text = getString(R.string.conf_mms_size, most / 1024, (most - Outbox.MARGIN) / 1024) } },
        )
        note(getString(R.string.conf_mms_size_note, Outbox.STANDARD / 1024, (Outbox.STANDARD - Outbox.MARGIN) / 1024))
        if (!Conf.preferMms(this)) {
            column.addView(flip(R.string.conf_warn_mms, Conf.warnMms(this)) { Conf.warnMms(this, it) })
            note(R.string.conf_warn_mms_note)
        }

    }

    /**
     * Oneself: one's own contact -- chosen, made, or the one tied to one's
     * certificate --, this phone's number among its numbers, the SMS that
     * checks it, and the tie to one's certificate.
     */
    private fun me() {
        heading(R.string.conf_me)
        note(R.string.conf_me_note)
        val own = own
        val cert = mineCert
        val pad = Look.dp(this, 12)
        if (own != null) {
            column.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, pad / 3, 0, pad / 3)
                    val standing = ownCert?.validity
                    addView(Look.face(context, 44, own.photo, null, standing, framable = true, seed = Look.seed(own.fingerprint, own.numbers.firstOrNull())))
                    addView(Look.words(context, own.name, size = 17f).apply { setPadding(pad, 0, 0, 0) })
                },
            )
        }
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Look.button(context, getString(if (own == null) R.string.me_choose else R.string.me_change)) { pick() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                if (own == null) addView(Look.button(context, getString(R.string.me_create)) { create() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            },
        )
        val offered = suggested
        if (own == null && offered != null) {
            column.addView(Look.button(this, getString(R.string.me_suggest, offered.name)) { Me.card(this, offered.key) ; readOwn() })
        }

        subheading(R.string.me_number)
        val learnt = Me.learnt(this)
        note(learnt?.let { getString(R.string.me_learnt, it) } ?: getString(R.string.me_learnt_none))
        val numbers = own?.numbers.orEmpty()
        if (own != null && numbers.isEmpty()) note(R.string.me_no_number)
        val current = Me.chosen(this)?.takeIf { chosen -> own == null || numbers.any { Phone.same(it, chosen) || it == chosen } }
            ?: numbers.firstOrNull { learnt != null && Phone.same(it, learnt) }
        if (numbers.isNotEmpty()) {
            column.addView(
                android.widget.RadioGroup(this).apply {
                    for (one in numbers) {
                        addView(
                            android.widget.RadioButton(context).apply {
                                id = View.generateViewId()
                                text = one
                                textSize = 17f
                                setTextColor(Look.INK)
                                isChecked = current != null && (one == current || Phone.same(one, current))
                                setOnClickListener { Me.choose(this@ConfActivity, one) ; draw() }
                            },
                        )
                    }
                },
            )
        }
        // The number to check: the one chosen, else the one learnt.
        val toCheck = current ?: learnt
        val verified = Me.verified(this)
        val said = when {
            Me.check == Me.Check.WAITING -> getString(R.string.me_verifying, toCheck.orEmpty())
            toCheck != null && verified != null && Phone.same(verified, toCheck) -> getString(R.string.me_verified)
            Me.check == Me.Check.LOST -> getString(R.string.me_not_back)
            else -> null
        }
        said?.let { column.addView(Look.words(this, it, size = 15f).apply { setPadding(0, pad / 3, 0, pad / 3) }) }
        column.addView(
            Look.button(this, getString(R.string.me_verify)) {
                val number = toCheck ?: return@button
                if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return@button requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 1)
                }
                Me.verify(this, number)
                draw()
            }.apply {
                isEnabled = toCheck != null && Me.check != Me.Check.WAITING
                alpha = if (isEnabled) 1f else 0.38f
            },
        )
        note(R.string.me_verify_note)

        // Tied to a certificate as anybody's contact, the same way: the identity foodjis holds offered first --
        // set, one hopes for a while only, on another entity, it is not the only choice --, any other from foodjis's list.
        if (own != null) {
            val tied = own.fingerprint
            if (tied != null) note(getString(R.string.me_tied_to, ownCert?.name ?: tied.takeLast(16)))
            if (cert != null && !cert.fingerprint.equals(tied, ignoreCase = true)) {
                column.addView(Look.button(this, getString(R.string.me_tie_mine, cert.name)) { tieOwn(cert.fingerprint) })
            }
            column.addView(Look.button(this, getString(if (tied != null) R.string.me_tie_refresh else R.string.me_tie_other)) {
                if (tied != null) tieOwn(tied) else pickCertificate()
            })
            if (tied != null) column.addView(Look.button(this, getString(R.string.me_tie_other)) { pickCertificate() })
        }
    }

    /** Android's own list of contacts, to pick one's own from: no permission asked, a page everybody knows. */
    private fun pick() = runCatching {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI), PICK)
    }

    /** Android's page for a new contact, with what is known: the certificate's name, the number learnt. */
    private fun create() = runCatching {
        val made = Intent(android.provider.ContactsContract.Intents.Insert.ACTION).setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra("finishActivityOnSaveCompleted", true)
        mineCert?.name?.let { made.putExtra(android.provider.ContactsContract.Intents.Insert.NAME, it) }
        Me.learnt(this)?.let {
            made.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it)
                .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE_TYPE, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(made, CREATE)
    }

    /** foodjis's own list of certificates, over this page: the one pressed is tied to one's own contact. */
    private fun pickCertificate() {
        fun chosen(says: Intent?): Boolean {
            val keyId = says?.getLongArrayExtra(Provider.EXTRA_KEY_IDS)?.firstOrNull() ?: return false
            tieOwn("%016X".format(keyId))
            return true
        }
        ask(Intent(Provider.GET_KEY_IDS).putExtra(Provider.EXTRA_SINGLE, true), null, ::say, ::chosen) { chosen(it.says) }
    }

    /**
     * One's own contact tied to the certificate [named], or what it says
     * refreshed, through the same window as anybody's ([Tying]): written in
     * that contact, not in whoever the number finds.
     */
    private fun tieOwn(named: String) {
        val own = own ?: return
        val number = Me.number(this) ?: own.numbers.firstOrNull() ?: ""
        kotlin.concurrent.thread {
            val foodjis = Foodjis.of(this)
            val whose = foodjis.card(named)
            val stale = whose?.let { foodjis.vcard(it.fingerprint) }?.let { Book.stale(this, number, it, own.contact) }.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    foodjis.shut -> say(getString(R.string.foodjis_shut))
                    whose == null -> say(getString(R.string.certificate_not_held))
                    else -> Tying.offer(this, number, whose, stale, own.contact) { card, face, note, name -> writeOwn(own, number, whose, card, stale, face, note, name) }
                }
            }
        }
    }

    private fun writeOwn(
        own: Book.Own, number: String, whose: Foodjis.Cert, card: Card, stale: List<Book.Stale>, face: Book.Pick, note: Book.Pick, name: Book.Pick,
    ) {
        if (checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS), 2)
        }
        kotlin.concurrent.thread {
            val outcome = runCatching {
                Book.tie(this, number, card, card.key?.takeIf { Fingerprint.of(it) == whose.fingerprint } ?: Fingerprint.address(whose.fingerprint), stale, face, note, own.contact, name)
                getString(R.string.tied, whose.name)
            }.getOrElse { getString(R.string.failed, it.message) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.widget.Toast.makeText(this, outcome, android.widget.Toast.LENGTH_LONG).show()
                readOwn()
            }
        }
    }

    private fun say(words: String) {
        AlertDialog.Builder(this).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    /** How the last saving or restoring went: the page is drawn again on return, and it is kept for that. */
    private var backup = ""

    private fun backedUp(words: String) { backup = words ; draw() }

    override fun onActivityResult(request: Int, outcome: Int, data: Intent?) {
        super.onActivityResult(request, outcome, data)
        // One's own contact, picked or made: kept by its lookup key, which outlives the address book's renumbering.
        if (request == PICK || request == CREATE) {
            if (outcome == RESULT_OK) data?.data?.let { Book.keyOf(this, it) }?.let { Me.card(this, it) ; Me.choose(this, null) }
            readOwn()
            return
        }
        Saving.done(this, request, outcome, data, ::backedUp)
    }

    /** A switch of the configuration; greyed, it shows off, and what it is set to is kept for when it is usable again. */
    private fun flip(words: Int, on: Boolean, usable: Boolean = true, then: (Boolean) -> Unit) = android.widget.Switch(this).apply {
        text = getString(words)
        textSize = 17f
        setTextColor(Look.INK)
        isChecked = on && usable
        isEnabled = usable
        if (!usable) alpha = 0.38f
        setOnCheckedChangeListener { _, now -> then(now) }
    }

    private fun label(hold: Conf.Hold): String = getString(
        when (hold) {
            Conf.Hold.HALF_MINUTE -> R.string.hold_half_minute
            Conf.Hold.TWO_MINUTES -> R.string.hold_two_minutes
            Conf.Hold.FIVE_MINUTES -> R.string.hold_five_minutes
            Conf.Hold.FIFTEEN_MINUTES -> R.string.hold_fifteen_minutes
            Conf.Hold.HOUR -> R.string.hold_hour
            Conf.Hold.FOUR_HOURS -> R.string.hold_four_hours
            Conf.Hold.TWELVE_HOURS -> R.string.hold_twelve_hours
        },
    )

    private companion object {
        const val PICK = 41
        const val CREATE = 42
    }
}
