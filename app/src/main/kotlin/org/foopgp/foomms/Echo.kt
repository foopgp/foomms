package org.foopgp.foomms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.security.MessageDigest

/**
 * A group MMS goes to this phone too, and comes back: what comes back is
 * held against what left -- each part, word for word and byte for byte, the
 * presentation (SMIL) aside, which networks rewrite. Different, it was most
 * likely changed on its way, for everyone it went to: the person is warned.
 * What came back is never kept, altered or not; what left is.
 */
object Echo {

    /** What left: the group it went to, and a digest of each of its parts. Kept a day at most. */
    fun sent(context: Context, to: List<String>, pdu: ByteArray) {
        if (to.size < 2) return
        val message = runCatching { PduRead.read(pdu) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val kept = kept(context)
        val edit = kept.edit()
        for ((key, line) in kept.all) if ((line as? String)?.substringBefore('\t')?.toLongOrNull()?.let { now - it > DAY } != false) edit.remove(key)
        edit.putString("$now", "$now\t${Phone.group(to, Me.all(context))}\t${digests(message.parts).joinToString(",")}").apply()
    }

    /**
     * What came back from this phone to a group: held against the oldest
     * that left for the same group and is not yet back. Says whether it was
     * found changed.
     */
    fun back(context: Context, to: List<String>, message: PduRead.Message): Boolean {
        val group = Phone.group(to, Me.all(context))
        val kept = kept(context)
        val waiting = kept.all.mapNotNull { (key, line) -> (line as? String)?.split('\t')?.takeIf { it.size == 3 && it[1] == group }?.let { key to it } }
            .sortedBy { it.first.toLongOrNull() ?: 0L }
        if (waiting.isEmpty()) return false
        val came = digests(message.parts).joinToString(",")
        val same = waiting.firstOrNull { it.second[2] == came }
        val gone = same ?: waiting.first()
        kept.edit().remove(gone.first).apply()
        if (same != null) return false
        warn(context)
        return true
    }

    private fun digests(parts: List<PduRead.Part>): List<String> = parts
        .filter { !it.type.equals("application/smil", ignoreCase = true) }
        .map { part -> MessageDigest.getInstance("SHA-256").digest(part.bytes).joinToString("") { "%02x".format(it) } }
        .sorted()

    private fun warn(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_altered), NotificationManager.IMPORTANCE_HIGH))
        val said = context.getString(R.string.altered)
        runCatching {
            manager.notify(
                CHANNEL.hashCode() + (System.currentTimeMillis() % 1000).toInt(),
                Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.mark)
                    .setContentTitle(context.getString(R.string.altered_title))
                    .setContentText(said).setStyle(Notification.BigTextStyle().bigText(said))
                    .setAutoCancel(true).build(),
            )
        }
    }

    private const val CHANNEL = "altered"
    private const val DAY = 24 * 3600 * 1000L

    private fun kept(context: Context) = context.getSharedPreferences("echo", Context.MODE_PRIVATE)
}
