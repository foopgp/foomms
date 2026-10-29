package org.foopgp.foomms

import android.content.Context
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
    private val file = File(File(context.cacheDir, "voice").apply { mkdirs() }, "voice.3gp")

    val recording: Boolean get() = recorder != null

    /** How long it has been recording. */
    val millis: Long get() = if (recorder == null) 0 else System.currentTimeMillis() - started

    /** Begin, keeping under [most] bytes. False when the phone would not record. */
    fun start(most: Int): Boolean {
        if (recorder != null) return true
        file.delete()
        return runCatching {
            @Suppress("DEPRECATION")
            val made = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            made.setAudioSource(MediaRecorder.AudioSource.MIC)
            made.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            made.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            made.setAudioSamplingRate(8000)
            made.setAudioEncodingBitRate(12200)
            made.setMaxFileSize(most.toLong())
            made.setOutputFile(file.absolutePath)
            made.prepare()
            made.start()
            recorder = made
            started = System.currentTimeMillis()
        }.isSuccess
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
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        file.delete()
        return Media.Attachment("audio/3gpp", Media.named("audio", started, "3gp"), bytes, long)
    }

    /** Let go of the microphone, keeping nothing. */
    fun drop() {
        recorder?.let { runCatching { it.stop() } ; it.release() }
        recorder = null
        file.delete()
    }

    private companion object {
        /** Under this, a press was a slip and nothing was said. */
        const val SHORTEST = 600L
    }
}
