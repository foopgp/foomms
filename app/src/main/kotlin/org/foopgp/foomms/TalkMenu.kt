package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlin.concurrent.thread

/**
 * What can be done about a conversation and about who it is with: the same
 * menu from the list of conversations, pressed long, and from the head of
 * the conversation itself.
 */
object TalkMenu {

    /**
     * foodjis made to hold the certificate [fingerprint] names, then [then]:
     * when it does not, it is asked to fetch it from its keyservers first --
     * the number may mean a certificate this phone never had, or has lost.
     * [changed] is told when it came, what shows the number's standing being
     * out of date.
     */
    private fun holding(activity: Activity, fingerprint: String, changed: () -> Unit, refresh: Boolean = false, then: () -> Unit) {
        thread {
            // Asked to refresh, it is asked of the keyservers again even when held: it may have changed there.
            val held = !refresh && runCatching { Foodjis.of(activity).card(fingerprint) }.getOrNull() != null
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (held) return@runOnUiThread then()
                val keyId = runCatching { java.lang.Long.parseUnsignedLong(fingerprint.takeLast(16), 16) }.getOrNull() ?: return@runOnUiThread then()
                // Shown while foodjis looks, and gone when it answers.
                val looking = AlertDialog.Builder(activity).setMessage(R.string.cert_fetching).setPositiveButton(R.string.close, null).show()
                val provider = Provider(activity)
                provider.ask(Intent(Provider.GET_KEY).putExtra(Provider.EXTRA_KEY_ID, keyId).putExtra(Provider.EXTRA_REFRESH, refresh), null) { answer ->
                    provider.close()
                    // The page left while foodjis looked: its window went with it, and closing it now would throw.
                    if (activity.isFinishing || activity.isDestroyed) return@ask
                    runCatching { looking.dismiss() }
                    when (answer) {
                        is Provider.Answer.Done -> {
                            Foodjis.of(activity).fresh()
                            changed()
                            then()
                        }
                        // foodjis not letting this application in: the action itself says so, as before.
                        is Provider.Answer.Ask -> then()
                        is Provider.Answer.Failed ->
                            AlertDialog.Builder(activity).setMessage(R.string.cert_not_found).setPositiveButton(R.string.close, null).show()
                    }
                }
            }
        }
    }

    /** The number waiting for Android's leave to place a call. */
    private var calling: String? = null

    /** [number] called; asked first, when foomms may not place calls yet. */
    fun call(activity: Activity, number: String) {
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.fromParts("tel", number, null))) }
                .onFailure { dial(activity, number) }
            return
        }
        calling = number
        activity.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), CALL)
    }

    /** Android's answer, from the page that asked: the call placed, or else the dialler with the number. */
    fun answered(activity: Activity, request: Int, results: IntArray): Boolean {
        if (request != CALL) return false
        val number = calling.also { calling = null } ?: return true
        if (results.any { it == PackageManager.PERMISSION_GRANTED }) call(activity, number) else dial(activity, number)
        return true
    }

    private fun dial(activity: Activity, number: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null))) }
    }

    /** Asking Android to let foomms place calls: a code no page uses for anything else. */
    private const val CALL = 7101

    fun show(
        activity: Activity,
        name: String,
        sender: String,
        person: Book.Person?,
        thread: Long?,
        /** Say whose certificate the number means: asked of the page that can show foodjis's list. */
        tie: () -> Unit,
        /** Read the certificate again from foodjis, and write in the contact what it says now. */
        refresh: () -> Unit,
        /** Something said of the sender changed, and what shows it is to be drawn again. */
        changed: () -> Unit,
        /** The conversation is no more. */
        gone: () -> Unit,
        /** Save this conversation to a file. */
        save: () -> Unit,
        /** foodjis keeps its door shut: ask to be let in. */
        letIn: (() -> Unit)? = null,
    ) {
        val number = Phone.plain(sender)
        val muted = Marks.muted(activity, sender)
        val choices = ArrayList<Pair<String, () -> Unit>>()
        fun add(words: Int, then: () -> Unit) { choices += activity.getString(words) to then }
        if (letIn != null) add(R.string.let_in, letIn)
        // Calling, where the phone can: the call is placed at once -- the person chose it in this menu --, once
        // Android has let foomms place calls; refused, the dialler opens with the number, as it did before.
        val dial = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", sender, null))
        if (number != null && dial.resolveActivity(activity.packageManager) != null) {
            choices += activity.getString(R.string.call, sender) to { call(activity, sender) }
        }
        if (person != null) {
            // The phone's own card for the contact, shown at once: asking "open with" brought up
            // applications that take any address and do nothing with a contact's.
            add(R.string.contact_show) {
                runCatching {
                    ContactsContract.QuickContact.showQuickContact(
                        activity, activity.window.decorView,
                        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, person.contact),
                        ContactsContract.QuickContact.MODE_LARGE, null,
                    )
                }
            }
        } else if (number != null) {
            add(R.string.contact_add) {
                runCatching {
                    activity.startActivity(
                        Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, sender),
                    )
                }
            }
        }
        val tied = person?.fingerprint
        if (tied != null) {
            // A number that already means a certificate: see it, read it again, or let go of it.
            add(R.string.cert_show) {
                holding(activity, tied, changed) {
                    val foodjis = Provider.foodjis(activity)
                    runCatching {
                        activity.startActivity(
                            Intent("org.foopgp.foodjis.action.SHOW_CERTIFICATE").setPackage(foodjis).putExtra("fingerprint", tied),
                        )
                    }.onFailure {
                        AlertDialog.Builder(activity).setMessage(R.string.no_provider).setPositiveButton(R.string.close, null).show()
                    }
                }
            }
            add(R.string.cert_refresh) { holding(activity, tied, changed, refresh = true, then = refresh) }
            add(R.string.cert_forget) {
                thread {
                    runCatching { Book.untie(activity, person.contact) }
                    activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) changed() }
                }
            }
        } else if (number != null) {
            add(R.string.tie, tie)
        }
        add(if (muted) R.string.unmute else R.string.mute) { Marks.mute(activity, sender, !muted) ; changed() }
        if (thread != null) add(R.string.backup_one, save)
        if (thread != null) {
            add(R.string.forget_talk) {
                AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.forget_talk_ask, name))
                    .setPositiveButton(R.string.forget_yes) { _, _ ->
                        thread {
                            val went = Keep.forgetAll(activity, thread)
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                if (went) gone()
                                else AlertDialog.Builder(activity)
                                    .setMessage(if (Keep.ours(activity)) R.string.forget_failed else R.string.forget_not_ours)
                                    .setPositiveButton(R.string.close, null).show()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(name)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }
}
