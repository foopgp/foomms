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

    fun arrived(context: Context, number: String, body: String) {
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
        val who = runCatching { Book.who(context, number)?.name }.getOrNull() ?: number
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
            .setColor(Look.INDIGO)
            .build()
        runCatching { manager.notify(number.hashCode(), shown) }
    }

    fun seen(context: Context, number: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(number.hashCode())
    }

    private const val CHANNEL = "messages"
}
