package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * TRIAL ONLY (branch mneme/video-trial): a video filmed here, in one of the
 * two shapes an MMS has carried -- 176×144 in H.263 with AMR, or 320×240 in
 * H.264 with AAC --, stopped by the operator's size before it is too heavy.
 */
@Suppress("DEPRECATION")
class VideoActivity : Activity(), SurfaceHolder.Callback {

    private var camera: android.hardware.Camera? = null
    private var recorder: MediaRecorder? = null
    private var started = 0L
    private lateinit var surface: SurfaceView
    private lateinit var button: TextView
    private lateinit var told: TextView
    private val qvga by lazy { intent.getBooleanExtra(EXTRA_QVGA, false) }
    private val most by lazy { intent.getLongExtra(EXTRA_MOST, 300 * 1024L) }
    private val ticking = object : Runnable {
        override fun run() {
            if (recorder == null) return
            told.text = "${Media.clock(SystemClock.elapsedRealtime() - started)}  ·  ${file(this@VideoActivity).length() / 1024} / ${most / 1024} kB"
            told.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        surface = SurfaceView(this).apply { holder.addCallback(this@VideoActivity) }
        told = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            text = if (qvga) "320×240 · H.264 + AAC" else "176×144 · H.263 + AMR"
        }
        button = Look.button(this, "● REC") { if (recorder == null) start() else stop(true) }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, 0, 0, Look.dp(context, 48))
                        addView(told)
                        addView(button)
                    },
                    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
                )
            },
        )
        val wanted = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (wanted.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(wanted, 1)
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        if (results.any { it != PackageManager.PERMISSION_GRANTED }) return finish()
        if (surface.holder.surface.isValid) preview(surface.holder)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) preview(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = release()

    private fun preview(holder: SurfaceHolder) {
        if (camera != null) return
        runCatching {
            camera = android.hardware.Camera.open().apply {
                setDisplayOrientation(90)
                setPreviewDisplay(holder)
                startPreview()
            }
        }.onFailure { say("camera: ${it.message}") }
    }

    private fun start() {
        val camera = camera ?: return
        val out = file(this).apply { delete() }
        runCatching {
            camera.unlock()
            recorder = MediaRecorder().apply {
                setCamera(camera)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                if (qvga) {
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoSize(320, 240)
                    setVideoEncodingBitRate(180_000)
                    setAudioEncodingBitRate(24_000)
                    setAudioSamplingRate(16_000)
                } else {
                    setVideoEncoder(MediaRecorder.VideoEncoder.H263)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setVideoSize(176, 144)
                    setVideoEncodingBitRate(64_000)
                    setAudioEncodingBitRate(12_200)
                    setAudioSamplingRate(8_000)
                }
                setAudioChannels(1)
                setVideoFrameRate(15)
                setOrientationHint(90)
                setMaxFileSize(most)
                setOutputFile(out.path)
                setPreviewDisplay(this@VideoActivity.surface.holder.surface)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop(true) }
                prepare()
                start()
            }
            started = SystemClock.elapsedRealtime()
            button.text = "■ STOP"
            told.post(ticking)
        }.onFailure {
            say("recorder: ${it.message}")
            recorder?.release()
            recorder = null
            runCatching { camera.lock() }
        }
    }

    private fun stop(keep: Boolean) {
        val going = recorder ?: return
        recorder = null
        val ok = runCatching { going.stop() }.isSuccess
        going.release()
        runCatching { camera?.lock() }
        if (keep && ok && file(this).length() > 0) setResult(RESULT_OK)
        finish()
    }

    private fun release() {
        recorder?.let { runCatching { it.stop() } ; it.release() }
        recorder = null
        camera?.let { runCatching { it.stopPreview() } ; it.release() }
        camera = null
    }

    override fun onPause() {
        super.onPause()
        if (recorder != null) stop(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    private fun say(words: String) {
        told.text = words
        android.util.Log.w("foomms-video", words)
    }

    companion object {
        const val EXTRA_QVGA = "qvga"
        const val EXTRA_MOST = "most"
        fun file(context: android.content.Context) = File(context.cacheDir, "video.3gp")
    }
}
