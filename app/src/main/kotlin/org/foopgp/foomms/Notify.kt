package org.foopgp.foomms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Saying that a message has arrived: from whom, and its words -- or, for a
 * PGP message, what it is: the words of one are not shown on a locked screen
 * that anybody can read, and cannot be before the security key has opened it.
 */
object Notify {

    /** [form]: what the message travelled as, told from [body] when not given -- an MMS carries it in its parts too. */
    fun arrived(context: Context, number: String, body: String, form: Form = Form.of(body)) {
        // Not announced: a sender the person asked not to hear of.
        if (Marks.muted(context, number)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        val armor = Pgp.armorIn(body)
        val words = when {
            armor == null -> body
            Pgp.isLocked(armor) -> context.getString(R.string.locked_message)
            else -> context.getString(R.string.signed_message)
        }
        val person = runCatching { Book.who(context, number) }.getOrNull()
        val who = person?.name ?: number
        // The contact's picture, when the address book has one: read with the name, from the same card.
        val face = person?.let { runCatching { Book.face(context, it) }.getOrNull() }
        val open = PendingIntent.getActivity(
            context, number.hashCode(),
            Intent(context, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shown = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.mark)
            .setContentTitle(who)
            .setContentText(words)
            .setStyle(Notification.BigTextStyle().bigText(words))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            // The colour of the form, against the notifications' own background: the phone's theme, not this application's.
            .setColor(Look.colour(form, dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES))
            .apply { face?.let { setLargeIcon(it) } }
            .build()
        runCatching { manager.notify(number.hashCode(), shown) }
    }

    fun seen(context: Context, number: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(number.hashCode())
    }

    private const val CHANNEL = "messages"
}
