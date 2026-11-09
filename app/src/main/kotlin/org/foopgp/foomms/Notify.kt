package org.foopgp.foomms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import org.openintents.openpgp.OpenPgpSignatureResult

/**
 * Saying that a message has arrived: from whom, and its words -- or, for a
 * PGP message, what it is. An encrypted one's words are never said, the key
 * not having opened it; a signed one's are, once foodjis has verified it,
 * in the colour of what its signature is worth.
 */
object Notify {

    /**
     * [form]: what the message travelled as, told from [body] when not given
     * -- an MMS carries it in its parts too. [done] is called once nothing
     * more is to be said of it: a signed message is verified after being
     * announced, and the receiver keeps the process alive until then.
     */
    fun arrived(context: Context, number: String, body: String, form: Form = Form.of(body), group: Long? = null, done: () -> Unit = {}) {
        // Not announced: a sender the person asked not to hear of, or a group.
        if (Marks.muted(context, number)) return done()
        if (group != null && Marks.muted(context, "group:" + Phone.group(Store.members(context, group), Me.all(context)))) return done()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return done()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        val armor = Pgp.armorIn(body)
        // A PGP message arrived is likely opened soon: foodjis woken for it.
        if (form != Form.PLAIN) Foodjis.of(context).wake()
        val said = when {
            armor == null -> body
            Pgp.isLocked(armor) -> context.getString(R.string.locked_message)
            else -> context.getString(R.string.signed_message)
        }
        // A group's message says so first, whatever it holds -- once, a message of parts alone having said it already --,
        // and which group, when the person named it.
        val among = Members.glyph(Members.PART)
        val called = group?.let { GroupNames.of(context, Phone.group(Store.members(context, it), Me.all(context))) }
        val head = if (called != null) "$among $called —" else among
        val words = when {
            group == null -> said
            said.startsWith(among) -> head + said.removePrefix(among)
            else -> "$head $said"
        }
        val person = runCatching { Book.who(context, number) }.getOrNull()
        val who = person?.name ?: number
        // The contact's picture, when the address book has one: read with the name, from the same card.
        val face = person?.let { runCatching { Book.face(context, it) }.getOrNull() }
        // Against the notifications' own background: the phone's theme, not this application's.
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun show(text: String, colour: Int) {
            val open = PendingIntent.getActivity(
                context, group?.hashCode() ?: number.hashCode(),
                Intent(context, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_NUMBER, number)
                    .apply { group?.let { putExtra(ThreadActivity.EXTRA_THREAD, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val shown = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.mark)
                .setContentTitle(who)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(open)
                .setAutoCancel(true)
                // Said again once verified: heard once.
                .setOnlyAlertOnce(true)
                .setColor(colour)
                .apply { face?.let { setLargeIcon(it) } }
                .build()
            runCatching { manager.notify(group?.hashCode() ?: number.hashCode(), shown) }
        }
        show(words, Look.colour(form, dark))
        if (armor == null || Pgp.isLocked(armor)) return done()
        // Signed only, foodjis reads it without the key: its words follow the glyph, in the colour of what the signature is worth.
        verify(context.applicationContext, armor, done) { read, certified ->
            // Certified by somebody else than whom the number is tied to: violet, as in the conversation.
            val tied = listOfNotNull(person?.fingerprint).mapNotNull { runCatching { Fingerprint.keyId(it) }.getOrNull() }
            val worth = if (certified) Verdict.received(Verdict.BLUE, Opened.words(armor)?.signer, tied) else Verdict.RED
            show(context.getString(R.string.signed_words, read), Look.colour(worth, dark))
        }
    }

    /** foodjis asked to verify [armor]; [then] hears its words when the signature holds; [done] in any case, and at the latest after [PATIENCE]. */
    private fun verify(context: Context, armor: String, done: () -> Unit, then: (String, Boolean) -> Unit) {
        val provider = Provider(context)
        val over = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun end() {
            if (!over.compareAndSet(false, true)) return
            runCatching { provider.close() }
            done()
        }
        main.postDelayed(::end, PATIENCE)
        // Asked from the main thread, as the link to foodjis is made and told there: a message arriving is
        // received off it.
        main.post { provider.ask(Intent(Provider.DECRYPT_VERIFY), armor.toByteArray()) { answer ->
            if (answer is Provider.Answer.Done && !over.get()) {
                // Kept as the conversation would keep it: opened there, it is read already.
                val signature = runCatching { Signer.keep(context, armor, answer) }.getOrNull()
                // Encrypted after all, whatever its armor looked like: its words are not for a notification.
                if (Provider.encrypted(answer, false)) return@ask end()
                when (signature?.result) {
                    OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> then(String(answer.bytes, Charsets.UTF_8), true)
                    OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> then(String(answer.bytes, Charsets.UTF_8), false)
                }
            }
            end()
        } }
    }

    fun seen(context: Context, number: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(number.hashCode())
    }

    /** Every message's notification taken away: everything was just read. */
    fun seenAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Not an MMS still to fetch: reading every message does not fetch it.
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL && it.id != UNFETCHED }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** An MMS that could not be fetched, said with the way to fetch it again: pressed, it is asked for. */
    fun unfetched(context: Context, from: String?, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        val who = from?.let { runCatching { Book.who(context, it) }.getOrNull()?.name ?: it } ?: context.getString(R.string.unknown_number)
        val again = PendingIntent.getBroadcast(
            context, name.hashCode(),
            Intent(context, Unfetched.Again::class.java).putExtra(Unfetched.EXTRA_NAME, name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.mms_unfetched)
        val shown = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.mark)
            .setContentTitle(who)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(again)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(name, UNFETCHED, shown) }
    }

    fun unfetchedGone(context: Context, name: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(name, UNFETCHED)
    }

    private const val CHANNEL = "messages"
    private const val UNFETCHED = 2

    /** How long foodjis is waited for, in milliseconds: a receiver has ten seconds. */
    private const val PATIENCE = 8_000L
}
