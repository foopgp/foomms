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
 * oneself. With no contact yet, the ways to one: the phone's own profile,
 * the contact tied to one's certificate, one picked, or one made.
 */
object MeMenu {

    /** What is known of oneself, read off the main thread ([read]). */
    class State(
        val own: Book.Own?, val ownCert: Foodjis.Cert?, val mine: Foodjis.Cert?,
        val suggested: Book.Own?, val profile: Book.Own?, val number: String?,
    )

    /** [mineKey]: the key of the identity foodjis holds, when it has said. Not on the main thread. */
    fun read(activity: AskingActivity, mineKey: Long?): State {
        val foodjis = Foodjis.of(activity)
        val own = Me.card(activity)?.let { Book.own(activity, it) }
        val mine = mineKey?.let { foodjis.signer(it) }
        return State(
            own, own?.fingerprint?.let { foodjis.card(it) }, mine,
            mine?.fingerprint?.let { Book.tiedTo(activity, it) }?.takeIf { it != own?.key }?.let { Book.own(activity, it) },
            Book.profile(activity)?.takeIf { own?.key != Book.PROFILE },
            Me.number(activity),
        )
    }

    /** The menu of the dots; [changed] is told when something said of oneself changed, to be read again. */
    fun show(activity: AskingActivity, state: State, changed: () -> Unit) {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        fun add(words: String, then: () -> Unit) { choices += words to then }
        val own = state.own
        if (own == null) {
            // No contact of one's own yet: the phone's profile first, then the one tied to one's certificate, or another.
            state.profile?.let { profile -> add(activity.getString(R.string.me_take_profile, profile.name)) { take(activity, profile.key, changed) } }
            state.suggested?.let { tied -> add(activity.getString(R.string.me_suggest, tied.name)) { take(activity, tied.key, changed) } }
            add(activity.getString(R.string.me_choose)) { pick(activity) }
            add(activity.getString(R.string.me_create)) { create(activity, state.mine) }
        } else {
            // Where "call" stands in a conversation's: which of its numbers is this phone's, and checking it.
            if (own.numbers.size > 1) add(activity.getString(R.string.me_default)) { chooseNumber(activity, own, changed) }
            state.number?.let { number -> add(activity.getString(R.string.me_verify_one, number)) { verify(activity, number, changed) } }
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
            add(activity.getString(R.string.me_change)) { pick(activity) }
        }
        AlertDialog.Builder(activity)
            .setTitle(own?.name?.let { "$it ${activity.getString(R.string.me_tag)}" } ?: activity.getString(R.string.me_tag))
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /** One's own contact taken: this phone's number to choose again among its numbers. */
    private fun take(activity: AskingActivity, key: String, changed: () -> Unit) {
        Me.card(activity, key)
        Me.choose(activity, null)
        changed()
    }

    /** Which of one's numbers is this phone's: the one checked so, marked. */
    private fun chooseNumber(activity: AskingActivity, own: Book.Own, changed: () -> Unit) {
        val now = Me.number(activity)
        val verified = Me.verified(activity)
        val shown = own.numbers.map { if (verified != null && Phone.same(it, verified)) "$it  ✓" else it }
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

    /** An SMS to [number], which must come back here: said when it does, or when it does not in time. */
    private fun verify(activity: AskingActivity, number: String, changed: () -> Unit) {
        if (activity.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return activity.requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 1)
        }
        Me.onCheck = {
            Me.onCheck = null
            if (!activity.isFinishing && !activity.isDestroyed) {
                say(activity, activity.getString(if (Me.check == Me.Check.BACK) R.string.me_verified else R.string.me_not_back))
                changed()
            }
        }
        Me.verify(activity, number)
        Toast.makeText(activity, activity.getString(R.string.me_verifying, number), Toast.LENGTH_LONG).show()
    }

    /** Android's own list of contacts, to pick one's own from: no permission asked, a page everybody knows. */
    private fun pick(activity: AskingActivity) = runCatching {
        @Suppress("DEPRECATION")
        activity.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), PICK)
    }

    /** Android's page for a new contact, with what is known: the certificate's name, the number learnt. */
    private fun create(activity: AskingActivity, mine: Foodjis.Cert?) = runCatching {
        val made = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra("finishActivityOnSaveCompleted", true)
        mine?.name?.let { made.putExtra(ContactsContract.Intents.Insert.NAME, it) }
        Me.learnt(activity)?.let {
            made.putExtra(ContactsContract.Intents.Insert.PHONE, it)
                .putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(made, CREATE)
    }

    /** A contact picked or made: kept by its lookup key, which outlives the address book's renumbering. Says whether it was one of these. */
    fun result(activity: AskingActivity, request: Int, outcome: Int, data: Intent?): Boolean {
        if (request != PICK && request != CREATE) return false
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

    private const val PICK = 41
    private const val CREATE = 42
}
