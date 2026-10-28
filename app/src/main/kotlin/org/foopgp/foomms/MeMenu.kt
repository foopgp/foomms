package org.foopgp.foomms

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Oneself, as the groups' page shows it: one's own contact and this phone's
 * number, and the menu of its dots -- the menu of a conversation, made for
 * oneself. With no contact yet: the contacts that carry one of the numbers
 * this phone has received MMS at, or one made to carry it.
 */
object MeMenu {

    /** What is known of oneself, read off the main thread ([read]). */
    class State(
        val own: Book.Own?, val ownCert: Foodjis.Cert?, val mine: Foodjis.Cert?,
        /** The contacts that carry one of this phone's numbers: those one's own may be, the one tied to one's certificate first. */
        val candidates: List<Book.Own>, val number: String?,
    )

    /**
     * [mineKey]: the key of the identity foodjis holds, when it has said.
     * The number messages go out with kept among one's contact's numbers:
     * the latest this phone received at that the contact carries, else its
     * first. Not on the main thread.
     */
    fun read(activity: AskingActivity, mineKey: Long?): State {
        val foodjis = Foodjis.of(activity)
        val own = Me.card(activity)?.let { Book.own(activity, it) }
        // No contact of one's own, or gone from the address book: no number of one's own either.
        if (own == null) Me.choose(activity, null)
        if (own != null) {
            val chosen = Me.chosen(activity)
            if (chosen == null || own.numbers.none { Phone.same(it, chosen) || it == chosen }) {
                Me.choose(activity, Me.numbers(activity).firstNotNullOfOrNull { one -> own.numbers.firstOrNull { Phone.same(it, one) } } ?: own.numbers.firstOrNull())
            }
        }
        val mine = mineKey?.let { foodjis.signer(it) }
        val candidates = Book.carrying(activity, Me.numbers(activity)).filter { it.key != own?.key }
            .sortedByDescending { mine != null && it.fingerprint.equals(mine.fingerprint, ignoreCase = true) }
        return State(own, own?.fingerprint?.let { foodjis.card(it) }, mine, candidates, Me.number(activity))
    }

    /** The menu of the dots; [changed] is told when something said of oneself changed, to be read again. */
    fun show(activity: AskingActivity, state: State, changed: () -> Unit) {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        fun add(words: String, then: () -> Unit) { choices += words to then }
        val own = state.own
        if (own == null) {
            // No contact of one's own yet: one that carries this phone's number, or a new one to carry it.
            return choose(activity, state, changed)
        } else {
            // Where "call" stands in a conversation's: which of its numbers is this phone's, and checking it.
            if (own.numbers.size > 1) add(activity.getString(R.string.me_default)) { chooseNumber(activity, own, changed) }
            add(activity.getString(R.string.contact_show)) {
                runCatching {
                    ContactsContract.QuickContact.showQuickContact(
                        activity, activity.window.decorView, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, own.contact),
                        ContactsContract.QuickContact.MODE_LARGE, null,
                    )
                }
            }
            val tied = own.fingerprint
            if (tied != null) {
                add(activity.getString(R.string.cert_show)) {
                    runCatching {
                        activity.startActivity(Intent("org.foopgp.foodjis.action.SHOW_CERTIFICATE").setPackage(Provider.foodjis(activity)).putExtra("fingerprint", tied))
                    }.onFailure { say(activity, activity.getString(R.string.no_provider)) }
                }
                add(activity.getString(R.string.cert_refresh)) { tieOwn(activity, own, tied, changed) }
                add(activity.getString(R.string.cert_forget)) {
                    thread {
                        runCatching { Book.untie(activity, own.contact) }
                        activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) changed() }
                    }
                }
            } else {
                add(activity.getString(R.string.tie)) { tieWhich(activity, own, state.mine, changed) }
            }
            add(activity.getString(R.string.me_change)) { choose(activity, state, changed) }
        }
        AlertDialog.Builder(activity)
            .setTitle(own?.name?.let { "$it ${activity.getString(R.string.me_tag)}" } ?: activity.getString(R.string.me_tag))
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /**
     * The contacts that carry one of this phone's numbers, to tie as one's
     * own; and making one to carry it, filled from one's certificate.
     */
    private fun choose(activity: AskingActivity, state: State, changed: () -> Unit) {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        val numbers = Me.numbers(activity)
        for (one in state.candidates) {
            val carried = numbers.firstOrNull { number -> one.numbers.any { Phone.same(it, number) } } ?: one.numbers.firstOrNull().orEmpty()
            choices += activity.getString(R.string.me_link, one.name, carried) to {
                Me.card(activity, one.key)
                Me.choose(activity, null)
                changed()
                // Several numbers on it: which is this phone's, asked at once.
                if (one.numbers.size > 1) chooseNumber(activity, one, changed)
            }
        }
        choices += activity.getString(R.string.me_create) to { create(activity, state.mine) }
        AlertDialog.Builder(activity)
            .setTitle(numbers.firstOrNull()?.let { activity.getString(R.string.me_choose_for, it) } ?: activity.getString(R.string.me_tag))
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /** Which of one's numbers is this phone's: those it has received MMS at, marked. */
    private fun chooseNumber(activity: AskingActivity, own: Book.Own, changed: () -> Unit) {
        val received = Me.numbers(activity)
        // None chosen yet: the latest this phone received at, offered.
        val now = Me.number(activity) ?: received.firstOrNull { one -> own.numbers.any { Phone.same(it, one) } }
        val shown = own.numbers.map { one -> if (received.any { Phone.same(it, one) }) "$one  ✓" else one }
        AlertDialog.Builder(activity)
            .setTitle(R.string.me_default)
            .setSingleChoiceItems(shown.toTypedArray(), own.numbers.indexOfFirst { now != null && (it == now || Phone.same(it, now)) }) { asked, at ->
                asked.dismiss()
                Me.choose(activity, own.numbers[at])
                changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Android's page for a new contact, filled with what is known: this
     * phone's latest number, and from one's certificate its name, address,
     * face, and the certificate's own address -- tying it as it is made.
     */
    private fun create(activity: AskingActivity, mine: Foodjis.Cert?) {
        thread {
            val card = mine?.let { Foodjis.of(activity).vcard(it.fingerprint) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val made = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra("finishActivityOnSaveCompleted", true)
                (card?.name ?: mine?.name)?.let { made.putExtra(ContactsContract.Intents.Insert.NAME, it) }
                Me.numbers(activity).firstOrNull()?.let {
                    made.putExtra(ContactsContract.Intents.Insert.PHONE, it)
                        .putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
                card?.emails?.firstOrNull()?.let { made.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                val rows = ArrayList<android.content.ContentValues>()
                if (mine != null) {
                    rows += android.content.ContentValues().apply {
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Website.URL, card?.key?.takeIf { Fingerprint.of(it) == mine.fingerprint } ?: Fingerprint.address(mine.fingerprint))
                        put(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_CUSTOM)
                        put(ContactsContract.CommonDataKinds.Website.LABEL, Book.MARK)
                    }
                }
                // A face small enough to travel with the page: a page is handed little.
                card?.photo?.takeIf { it.size < FACE_MOST }?.let { face ->
                    rows += android.content.ContentValues().apply {
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Photo.PHOTO, face)
                    }
                }
                if (rows.isNotEmpty()) made.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
                @Suppress("DEPRECATION")
                runCatching { activity.startActivityForResult(made, CREATE) }
            }
        }
    }

    /** A contact made: kept by its lookup key, which outlives the address book's renumbering. Says whether it was this. */
    fun result(activity: AskingActivity, request: Int, outcome: Int, data: Intent?): Boolean {
        if (request != CREATE) return false
        if (outcome == android.app.Activity.RESULT_OK) {
            data?.data?.let { Book.keyOf(activity, it) }?.let { Me.card(activity, it) ; Me.choose(activity, null) }
        }
        return true
    }

    /** Tied to a certificate as anybody's contact: the identity foodjis holds offered first, any other from foodjis's list. */
    private fun tieWhich(activity: AskingActivity, own: Book.Own, mine: Foodjis.Cert?, changed: () -> Unit) {
        if (mine == null) return pickCertificate(activity, own, changed)
        val choices = listOf(
            activity.getString(R.string.me_tie_mine, mine.name) to { tieOwn(activity, own, mine.fingerprint, changed) },
            activity.getString(R.string.me_tie_other) to { pickCertificate(activity, own, changed) },
        )
        AlertDialog.Builder(activity)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /** foodjis's own list of certificates, over the page: the one pressed is tied to one's own contact. */
    private fun pickCertificate(activity: AskingActivity, own: Book.Own, changed: () -> Unit) {
        fun chosen(says: Intent?): Boolean {
            val keyId = says?.getLongArrayExtra(Provider.EXTRA_KEY_IDS)?.firstOrNull() ?: return false
            tieOwn(activity, own, "%016X".format(keyId), changed)
            return true
        }
        activity.ask(Intent(Provider.GET_KEY_IDS).putExtra(Provider.EXTRA_SINGLE, true), null, { say(activity, it) }, ::chosen) { chosen(it.says) }
    }

    /**
     * One's own contact tied to the certificate [named], or what it says
     * refreshed, through the same window as anybody's ([Tying]): written in
     * that contact, not in whoever the number finds.
     */
    private fun tieOwn(activity: AskingActivity, own: Book.Own, named: String, changed: () -> Unit) {
        val number = Me.number(activity) ?: own.numbers.firstOrNull() ?: ""
        thread {
            val foodjis = Foodjis.of(activity)
            val whose = foodjis.card(named)
            val stale = whose?.let { foodjis.vcard(it.fingerprint) }?.let { Book.stale(activity, number, it, own.contact) }.orEmpty()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    foodjis.shut -> say(activity, activity.getString(R.string.foodjis_shut))
                    whose == null -> say(activity, activity.getString(R.string.certificate_not_held))
                    else -> Tying.offer(activity, number, whose, stale, own.contact) { card, face, note, name ->
                        writeOwn(activity, own, number, whose, card, stale, face, note, name, changed)
                    }
                }
            }
        }
    }

    private fun writeOwn(
        activity: AskingActivity, own: Book.Own, number: String, whose: Foodjis.Cert, card: Card, stale: List<Book.Stale>,
        face: Book.Pick, note: Book.Pick, name: Book.Pick, changed: () -> Unit,
    ) {
        if (activity.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return activity.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS), 2)
        }
        thread {
            val outcome = runCatching {
                Book.tie(activity, number, card, card.key?.takeIf { Fingerprint.of(it) == whose.fingerprint } ?: Fingerprint.address(whose.fingerprint), stale, face, note, own.contact, name)
                activity.getString(R.string.tied, whose.name)
            }.getOrElse { activity.getString(R.string.failed, it.message) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                Toast.makeText(activity, outcome, Toast.LENGTH_LONG).show()
                changed()
            }
        }
    }

    private fun say(activity: AskingActivity, words: String) {
        AlertDialog.Builder(activity).setMessage(words).setPositiveButton(R.string.close, null).show()
    }

    private const val CREATE = 42

    /** The most a face may weigh to go with the page for a new contact. */
    private const val FACE_MOST = 200 * 1024
}
