package org.foopgp.foomms

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * A few words spoken rather than written, while a finger stays on the field:
 * recorded in the lowest quality speech still reads well in -- AMR at eight
 * kilohertz, a kilobyte and a half a second --, which every phone plays and
 * a network carries three minutes of in one MMS.
 */
class Voice(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var started = 0L

    /**
     * What the microphone gave, looked at as it records ([listen]): taken by
     * a call or by another application, it gives this one silence -- exactly
     * nothing, where a quiet room still gives a little --, and Android
     * records on all the same.
     */
    private var looks = 0
    private var mutes = 0
    private var running = 0

    /** Nothing at all was heard, from the first look to the last. */
    var silent = false
        private set

    /** Nothing was heard for part of the time. */
    var partlySilent = false
        private set
    private val file = File(File(context.cacheDir, "voice").apply { mkdirs() }, "voice.3gp")

    val recording: Boolean get() = recorder != null

    /** How long it has been recording. */
    val millis: Long get() = if (recorder == null) 0 else System.currentTimeMillis() - started

    /** Begin, keeping under [most] bytes. False when the phone would not record. */
    fun start(most: Int): Boolean {
        if (recorder != null) return true
        file.delete()
        // Released when it would not start -- the microphone taken by a call --: dropped, it held it.
        var made: MediaRecorder? = null
        return runCatching {
            @Suppress("DEPRECATION")
            val recording = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { made = it }
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recording.setAudioSamplingRate(8000)
            recording.setAudioEncodingBitRate(12200)
            recording.setMaxFileSize(most.toLong())
            recording.setOutputFile(file.absolutePath)
            recording.prepare()
            recording.start()
            recorder = recording
            started = System.currentTimeMillis()
            looks = 0 ; mutes = 0 ; running = 0 ; silent = false ; partlySilent = false
            // The first asking says nothing: what came since the one before, and there was none.
            recording.maxAmplitude
        }.onFailure { made?.release() }.isSuccess
    }

    /**
     * Stop, and give what was said -- or nothing, for a press too short to
     * have said anything, or a recording that failed.
     */
    fun stop(): Media.Attachment? {
        val made = recorder ?: return null
        recorder = null
        val long = System.currentTimeMillis() - started
        val stopped = runCatching { made.stop() }.isSuccess
        made.release()
        if (!stopped || long < SHORTEST) { file.delete() ; return null }
        silent = looks > 0 && mutes == looks
        // A look at nothing now and then is chance; a second of it is the microphone taken.
        partlySilent = !silent && mutes >= 4
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        file.delete()
        return Media.Attachment("audio/3gpp", Media.named("audio", started, "3gp"), bytes, long)
    }

    /**
     * Whether the microphone gives nothing now: no sound at all since the
     * last look, or Android saying it silences this application. Asked
     * while recording, a few times a second.
     */
    fun listen(): Boolean {
        val made = recorder ?: return false
        val nothing = runCatching { made.maxAmplitude }.getOrDefault(0) == 0
        // Asked as it starts, the microphone has not heard anything yet: that look says nothing.
        if (System.currentTimeMillis() - started < LISTENING) return false
        val silenced = Build.VERSION.SDK_INT >= 29 && runCatching {
            context.getSystemService(AudioManager::class.java)?.activeRecordingConfigurations?.any { it.isClientSilenced } == true
        }.getOrDefault(false)
        val mute = nothing || silenced
        looks++
        if (mute) { mutes++ ; running++ } else running = 0
        // Said once it lasts half a second: a look at nothing now and then is chance.
        return running >= 2
    }

    /** Let go of the microphone, keeping nothing. */
    fun drop() {
        recorder?.let { runCatching { it.stop() } ; it.release() }
        recorder = null
        file.delete()
    }

    companion object {
        /** Under this, a press was a slip and nothing was said. */
        private const val SHORTEST = 600L

        /** How long the microphone takes to hear anything once recording starts. */
        private const val LISTENING = 200L

        /** A call going on -- by telephone or by an application --, which holds the microphone. */
        fun inCall(context: Context): Boolean = context.getSystemService(AudioManager::class.java)?.mode.let {
            it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
        }
    }
}
