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
 *
 * Held upright or on its side, the video says in its file, as it begins,
 * which way up it was filmed; the page itself stays as it is.
 */
@Suppress("DEPRECATION")
class VideoActivity : Activity(), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private var facing = Camera.CameraInfo.CAMERA_FACING_BACK
    private var lit = false

    /** How far the phone is turned from upright, clockwise, to the nearest quarter: 0, 90, 180 or 270. */
    private var turned = 0
    private val tilt by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || recorder != null) return
                val now = (degrees + 45) / 90 * 90 % 360
                turned = now
            }
        }
    }
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
            told.text = "$SHAPE  ·  ${Media.clock(SystemClock.elapsedRealtime() - started)}  ·  ${file(this@VideoActivity).length() / 1024} / ${most / 1024} kB"
            told.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Look.fullScreen(this)
        surface = SurfaceView(this).apply {
            holder.addCallback(this@VideoActivity)
            // Touched, the camera focuses there.
            setOnTouchListener { view, event -> if (event.action == MotionEvent.ACTION_UP) focus(event.x / view.width, event.y / view.height) ; true }
        }
        told = TextView(this).apply { setTextColor(android.graphics.Color.argb(0xCC, 0xFF, 0xFF, 0xFF)) ; textSize = 13f ; text = SHAPE }
        button = Look.knob(this, "●", getString(R.string.attach_video)) { if (recorder == null) start() else stop(true) }.apply { textSize = 40f }
        turn = Look.knob(this, "↻", getString(R.string.video_camera)) { turn() }.apply { textSize = 26f }
        light = Look.knob(this, "☼", getString(R.string.video_light)) { light() }.apply { textSize = 26f }
        val round = Look.dp(this, 64)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(surface, framed())
                // What is filmed and how, over the picture's top, and the camera's own settings beside it.
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Look.dp(context, 20), Look.dp(context, 28), Look.dp(context, 12), Look.dp(context, 20))
                        background = Look.shade(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM)
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(TextView(context).apply {
                                    text = getString(R.string.attach_video) ; textSize = 18f
                                    setTextColor(android.graphics.Color.WHITE) ; setTypeface(typeface, android.graphics.Typeface.BOLD)
                                })
                                addView(told)
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                    },
                    FrameLayout.LayoutParams(-1, -2, Gravity.TOP),
                )
                // Light, film, camera: the one that films in the middle, and the largest.
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, Look.dp(context, 20), 0, Look.dp(context, 40))
                        background = Look.shade(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP)
                        val apart = Look.dp(context, 32)
                        addView(light, LinearLayout.LayoutParams(round, round))
                        addView(button, LinearLayout.LayoutParams(Look.dp(context, 92), Look.dp(context, 92)).apply { marginStart = apart ; marginEnd = apart })
                        addView(turn, LinearLayout.LayoutParams(round, round))
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
                setDisplayOrientation(upright(info))
                parameters = parameters.apply {
                    if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedFocusModes.orEmpty()) focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                    // Seen at 4:3, as it is filmed: the largest such picture up to 1280 wide.
                    supportedPreviewSizes.orEmpty().filter { it.width * 3 == it.height * 4 && it.width <= 1280 }.maxByOrNull { it.width }
                        ?.let { setPreviewSize(it.width, it.height) }
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

    /**
     * What is filmed is what is shown: the picture at 4:3, as large as the
     * screen allows, nothing out of its frame -- upright, four thirds as
     * high as wide; on its side, four thirds as wide as high.
     */
    private fun framed(): FrameLayout.LayoutParams {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return if (h >= w) FrameLayout.LayoutParams(w, minOf(h, w * 4 / 3), Gravity.CENTER)
        else FrameLayout.LayoutParams(minOf(w, h * 4 / 3), h, Gravity.CENTER)
    }

    /** How far the screen is turned, as the display says it: 0, 90, 180 or 270. */
    @Suppress("DEPRECATION")
    private fun screenTurn() = when (windowManager.defaultDisplay.rotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }

    /** The camera's picture upright on the screen as it now stands; at the front, as in a mirror. */
    private fun upright(info: Camera.CameraInfo): Int =
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + screenTurn()) % 360) % 360
        else (info.orientation - screenTurn() + 360) % 360

    /** Turned, the page is not built again: the picture is fitted and set upright, the camera going on. */
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        surface.layoutParams = framed()
        val id = (0 until Camera.getNumberOfCameras()).firstOrNull { info(it).facing == facing } ?: return
        runCatching { camera?.setDisplayOrientation(upright(info(id))) }
    }

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
                // Which way up it was filmed: the sensor's own turn and the phone's, mirrored at the front.
                val sensor = info(id).orientation
                setOrientationHint(if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (sensor - turned + 360) % 360 else (sensor + turned) % 360)
                setMaxFileSize(most)
                setOutputFile(out.path)
                setPreviewDisplay(this@VideoActivity.surface.holder.surface)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop(true) }
                prepare()
                start()
            }
            started = SystemClock.elapsedRealtime()
            began = System.currentTimeMillis()
            button.text = "■"
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

    override fun onResume() {
        super.onResume()
        if (tilt.canDetectOrientation()) tilt.enable()
    }

    override fun onPause() {
        super.onPause()
        tilt.disable()
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
        /** What is filmed, said under the title: the picture, the codecs, the pace. */
        private const val SHAPE = "320×240 · H.264 + AAC · 15 i/s"
        const val EXTRA_MOST = "most"
        const val EXTRA_NAME = "name"
        fun file(context: android.content.Context) = File(context.cacheDir, "video.3gp")
    }
}
