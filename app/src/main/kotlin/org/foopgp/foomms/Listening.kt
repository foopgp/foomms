package org.foopgp.foomms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

/**
 * A long voice message, listened to beyond the conversation: it goes on
 * playing when the page is left, with its pause, its play and where it is
 * in the notifications and on the locked screen -- as a podcast does. A
 * short one is played in its bubble, and stops with it.
 *
 * What is played may have been opened from its lock: it is handed over in
 * memory, written to the cache only as long as the player takes to read it.
 */
class Listening : Service() {

    /** What is to be played: by whom, which part, and its bytes. */
    private class Track(val part: Long, val title: String, val bytes: ByteArray)

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var track: Track? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> player?.let { if (it.isPlaying) it.pause() else it.start() ; said() }
            STOP -> end()
            else -> next?.let { next = null ; begin(it) } ?: end()
        }
        return START_NOT_STICKY
    }

    private fun begin(what: Track) {
        player?.let { runCatching { it.stop() } ; it.release() }
        track = what
        playing = what.part
        onChange?.invoke()
        val sound = java.io.File(cacheDir, "listening").apply { writeBytes(what.bytes) }
        player = runCatching {
            MediaPlayer().apply {
                sound.inputStream().use { setDataSource(it.fd) }
                prepare()
                setOnCompletionListener { end() }
                start()
            }
        }.getOrNull()
        sound.delete()
        if (player == null) return end()
        val session = session ?: MediaSession(this, "foomms").also { session = it }
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() { player?.start() ; said() }
                override fun onPause() { player?.pause() ; said() }
                override fun onSeekTo(position: Long) { player?.seekTo(position.toInt()) ; said() }
                override fun onStop() = end()
            },
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, what.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player!!.duration.toLong())
                .build(),
        )
        session.isActive = true
        said()
    }

    /** Where it is and whether it plays, to the session -- the seek bar -- and to the notification. */
    private fun said() {
        val player = player ?: return
        val playing = player.isPlaying
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, player.currentPosition.toLong(), 1f)
                .build(),
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW))
        fun pressed(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, Listening::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val shown = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mark)
            .setContentTitle(track?.title)
            .setOngoing(playing)
            .setDeleteIntent(pressed(STOP))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    getString(if (playing) R.string.listening_pause else R.string.listening_play), pressed(PAUSE),
                ).build(),
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.listening_stop), pressed(STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, shown)
    }

    private fun end() {
        player?.let { runCatching { it.stop() } ; it.release() }
        player = null
        session?.release()
        session = null
        track = null
        playing = null
        onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        session?.release()
        playing = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val ID = 0x115
        private const val PAUSE = "org.foopgp.foomms.LISTENING_PAUSE"
        private const val STOP = "org.foopgp.foomms.LISTENING_STOP"

        /** How long a voice message is before it is listened to beyond its bubble, in milliseconds. */
        const val LONG = 10_000L

        @Volatile private var next: Track? = null

        /** The part being listened to, while one is. */
        @Volatile var playing: Long? = null
            private set

        /** Told when a sound begins or ends here: the page that shows its button. */
        @Volatile var onChange: (() -> Unit)? = null

        fun play(context: Context, part: Long, title: String, bytes: ByteArray) {
            next = Track(part, title, bytes)
            context.startForegroundService(Intent(context, Listening::class.java))
        }

        fun stop(context: Context) {
            if (playing != null) context.startService(Intent(context, Listening::class.java).setAction(STOP))
        }
    }
}
