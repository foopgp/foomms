package org.foopgp.foomms

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.provider.ContactsContract
import kotlin.concurrent.thread

/**
 * What can be done about a conversation and about who it is with: the same
 * menu from the list of conversations, pressed long, and from the head of
 * the conversation itself.
 */
object TalkMenu {

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
        // Calling, where the phone can: the dialler opens with the number, and nothing is dialled without the person.
        val dial = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", sender, null))
        if (number != null && dial.resolveActivity(activity.packageManager) != null) {
            choices += activity.getString(R.string.call, sender) to { runCatching { activity.startActivity(dial) } }
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
                val foodjis = if (activity.packageName.endsWith(".dev")) Provider.FOODJIS_DEV else Provider.FOODJIS
                runCatching {
                    activity.startActivity(
                        Intent("org.foopgp.foodjis.action.SHOW_CERTIFICATE").setPackage(foodjis).putExtra("fingerprint", tied),
                    )
                }.onFailure {
                    AlertDialog.Builder(activity).setMessage(R.string.no_provider).setPositiveButton(R.string.close, null).show()
                }
            }
            add(R.string.cert_refresh, refresh)
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
