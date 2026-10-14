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
        /** Something said of the sender changed, and what shows it is to be drawn again. */
        changed: () -> Unit,
        /** The conversation is no more. */
        gone: () -> Unit,
    ) {
        val number = Phone.plain(sender)
        val muted = Marks.muted(activity, sender)
        val choices = ArrayList<Pair<Int, () -> Unit>>()
        if (person != null) {
            // The phone's own card for the contact, shown at once: asking "open with" brought up
            // applications that take any address and do nothing with a contact's.
            choices += R.string.contact_show to {
                runCatching {
                    ContactsContract.QuickContact.showQuickContact(
                        activity, activity.window.decorView,
                        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, person.contact),
                        ContactsContract.QuickContact.MODE_LARGE, null,
                    )
                }
            }
        } else if (number != null) {
            choices += R.string.contact_add to {
                runCatching {
                    activity.startActivity(
                        Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, sender),
                    )
                }
            }
        }
        if (number != null) choices += R.string.tie to tie
        choices += (if (muted) R.string.unmute else R.string.mute) to { Marks.mute(activity, sender, !muted) ; changed() }
        if (thread != null) {
            choices += R.string.forget_talk to {
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
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }
}
