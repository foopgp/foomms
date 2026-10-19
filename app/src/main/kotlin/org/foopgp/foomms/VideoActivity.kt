package org.foopgp.foomms

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * TRIAL ONLY (branch mneme/video-trial): a video filmed for an MMS, 320×240
 * in H.264 with AAC, stopped by the operator's size before it is too heavy.
 * The camera at the back or the front, its light, and its focus where the
 * finger touches. 176×144 was too poor, 352×288 and 640×480 too short under
 * 300 kB: dropped.
 */
@Suppress("DEPRECATION")
class VideoActivity : Activity(), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private var facing = Camera.CameraInfo.CAMERA_FACING_BACK
    private var lit = false
    private var recorder: MediaRecorder? = null
    private var started = 0L
    private var began = 0L
    private lateinit var surface: SurfaceView
    private lateinit var button: TextView
    private lateinit var turn: TextView
    private lateinit var light: TextView
    private lateinit var told: TextView
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
        surface = SurfaceView(this).apply {
            holder.addCallback(this@VideoActivity)
            // Touched, the camera focuses there.
            setOnTouchListener { view, event -> if (event.action == MotionEvent.ACTION_UP) focus(event.x / view.width, event.y / view.height) ; true }
        }
        told = TextView(this).apply { setTextColor(android.graphics.Color.WHITE) ; textSize = 16f ; text = "320×240 · H.264 + AAC" }
        button = Look.button(this, "● REC") { if (recorder == null) start() else stop(true) }
        turn = Look.button(this, "↻") { turn() }
        light = Look.button(this, "☼") { light() }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        val pad = Look.dp(context, 16)
                        setPadding(pad, Look.dp(context, 40), pad, 0)
                        addView(light)
                        addView(android.view.View(context), LinearLayout.LayoutParams(pad, 1))
                        addView(turn)
                    },
                    FrameLayout.LayoutParams(-1, -2, Gravity.TOP),
                )
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
        if (surface.holder.surface.isValid) preview()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) preview()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = release()

    /** The camera facing as asked, its picture upright, focusing by itself while it films. */
    private fun preview() {
        if (camera != null) return
        runCatching {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull { info(it).facing == facing } ?: 0
            val info = info(id)
            camera = Camera.open(id).apply {
                // A camera at the front is seen as in a mirror: turned the other way.
                setDisplayOrientation(if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - info.orientation) % 360 else info.orientation)
                parameters = parameters.apply {
                    if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedFocusModes.orEmpty()) focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                }
                setPreviewDisplay(surface.holder)
                startPreview()
            }
            lit = false
            light.visibility = if (torchable()) android.view.View.VISIBLE else android.view.View.INVISIBLE
            turn.visibility = if (Camera.getNumberOfCameras() > 1) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }.onFailure { say("camera: ${it.message}") }
    }

    private fun info(id: Int) = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }

    private fun torchable() = Camera.Parameters.FLASH_MODE_TORCH in camera?.parameters?.supportedFlashModes.orEmpty()

    /** The other camera, before filming. */
    private fun turn() {
        if (recorder != null) return
        release()
        facing = if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        preview()
    }

    /** The light on or off, where the camera has one. */
    private fun light() {
        val camera = camera ?: return
        if (recorder != null || !torchable()) return
        lit = !lit
        runCatching { camera.parameters = camera.parameters.apply { flashMode = if (lit) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF } }
        light.text = if (lit) "☀" else "☼"
    }

    /** Focus where the finger touched, [x] and [y] from 0 to 1 of what is shown; then back to focusing by itself. */
    private fun focus(x: Float, y: Float) {
        val camera = camera ?: return
        if (recorder != null) return
        runCatching {
            val params = camera.parameters
            if (params.maxNumFocusAreas == 0 || Camera.Parameters.FOCUS_MODE_AUTO !in params.supportedFocusModes.orEmpty()) return
            // The screen is upright and the sensor lies on its side: what is across the one is along the other.
            val sx = (y * 2000 - 1000).toInt()
            val sy = ((1 - x) * 2000 - 1000).toInt()
            val area = Rect((sx - 100).coerceIn(-1000, 900), (sy - 100).coerceIn(-1000, 900), (sx + 100).coerceIn(-900, 1000), (sy + 100).coerceIn(-900, 1000))
            camera.cancelAutoFocus()
            camera.parameters = params.apply {
                focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                focusAreas = listOf(Camera.Area(area, 1000))
            }
            camera.autoFocus { _, held ->
                runCatching {
                    held.parameters = held.parameters.apply {
                        if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedFocusModes.orEmpty()) focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                    }
                }
            }
        }
    }

    private fun start() {
        val camera = camera ?: return
        val out = file(this).apply { delete() }
        val id = (0 until Camera.getNumberOfCameras()).firstOrNull { info(it).facing == facing } ?: 0
        runCatching {
            camera.unlock()
            recorder = MediaRecorder().apply {
                setCamera(camera)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(320, 240)
                setVideoEncodingBitRate(180_000)
                setAudioEncodingBitRate(24_000)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setVideoFrameRate(15)
                setOrientationHint(info(id).orientation)
                setMaxFileSize(most)
                setOutputFile(out.path)
                setPreviewDisplay(this@VideoActivity.surface.holder.surface)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop(true) }
                prepare()
                start()
            }
            started = SystemClock.elapsedRealtime()
            began = System.currentTimeMillis()
            button.text = "■ STOP"
            // Once filming, the camera is the recorder's: what cannot be changed is not shown.
            turn.visibility = android.view.View.INVISIBLE
            light.visibility = android.view.View.INVISIBLE
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
        if (keep && ok && file(this).length() > 0) setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_NAME, Media.named("v", began, "3gp")))
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
        const val EXTRA_MOST = "most"
        const val EXTRA_NAME = "name"
        fun file(context: android.content.Context) = File(context.cacheDir, "video.3gp")
    }
}
