package dev.minimal.scrcpyclient.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.minimal.scrcpyclient.adb.AdbKeyStore
import dev.minimal.scrcpyclient.adb.ScrcpySession
import dev.minimal.scrcpyclient.control.ControlMessages
import dev.minimal.scrcpyclient.control.Controller
import dev.minimal.scrcpyclient.data.AppStore
import dev.minimal.scrcpyclient.data.StreamSettings
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.hypot

/** Fullscreen touch-controlled stream: ADB -> scrcpy video/audio sockets -> MediaCodec/AudioTrack. */
class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var screenPill: TextView
    private lateinit var exitPill: TextView
    private lateinit var bubbleView: TextView
    private lateinit var pillStrip: LinearLayout
    private lateinit var floatingDock: LinearLayout
    private var settings = StreamSettings()
    private var worker: Thread? = null
    private var audioThread: Thread? = null
    private var lastVolDownTime = 0L
    private var pillsExpanded = false

    @Volatile private var running = false
    @Volatile private var session: ScrcpySession? = null
    @Volatile private var controller: Controller? = null
    @Volatile private var targetScreenOn = true
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitSession()
            }
        })

        settings = AppStore(this).settings()

        surfaceView = SurfaceView(this).apply {
            keepScreenOn = true
            setOnTouchListener { v, e -> onTouch(v, e); true }
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(32, 64, 32, 24)
        }

        val dp = resources.displayMetrics.density
        fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
            background = pillBg(isGreen = false)
            setOnClickListener { onClick() }
        }

        screenPill = pill("Screen") {
            targetScreenOn = !targetScreenOn
            updateScreenPill()
            controller?.send(ControlMessages.displayPower(targetScreenOn))
        }
        updateScreenPill()

        exitPill = pill("\u2715 Exit") { exitSession() }

        pillStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(screenPill)
            addView(exitPill)
        }

        val bubbleSize = (44 * dp).toInt()
        bubbleView = TextView(this).apply {
            text = "\u2022\u2022\u2022"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC111827"))
                setStroke((1.5f * dp).toInt(), Color.parseColor("#8866BB6A"))
            }
            elevation = 8 * dp
        }

        floatingDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        applyPillDirection(expandRight = false, dp = dp)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false

        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTx = floatingDock.translationX
                    startTy = floatingDock.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxLeft = -((container.width - floatingDock.width - (12 * dp)).coerceAtLeast(0f))
                        val maxRight = (12 * dp)
                        val minTop = -((36 * dp) - (12 * dp))
                        val maxBottom = (container.height - floatingDock.height - (48 * dp)).coerceAtLeast(0f)
                        floatingDock.translationX = (startTx + dx).coerceIn(maxLeft, maxRight)
                        floatingDock.translationY = (startTy + dy).coerceIn(minTop, maxBottom)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val bubbleCenterX = floatingDock.left + floatingDock.translationX +
                            bubbleView.left + bubbleView.width / 2f
                        val shouldExpandRight = bubbleCenterX < container.width / 2f
                        pillsExpanded = !pillsExpanded
                        if (pillsExpanded) {
                            applyPillDirection(expandRight = shouldExpandRight, dp = dp)
                            pillStrip.visibility = View.VISIBLE
                            floatingDock.post { clampDockWithinBounds(dp) }
                        } else {
                            pillStrip.visibility = View.GONE
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val dockLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (36 * dp).toInt()
            marginEnd = (12 * dp).toInt()
        }

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
            addView(floatingDock, dockLp)
        }

        setContentView(container)
        surfaceView.holder.addCallback(this)
    }

    private fun applyPillDirection(expandRight: Boolean, dp: Float) {
        val gap = (8 * dp).toInt()
        screenPill.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = gap }
        exitPill.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val bubbleSize = (44 * dp).toInt()
        val bubbleLp = LinearLayout.LayoutParams(
            bubbleView.width.takeIf { it > 0 } ?: bubbleSize,
            bubbleView.height.takeIf { it > 0 } ?: bubbleSize
        )

        floatingDock.removeAllViews()
        if (expandRight) {
            bubbleLp.marginEnd = gap
            floatingDock.addView(bubbleView, bubbleLp)
            floatingDock.addView(pillStrip)
        } else {
            bubbleLp.marginStart = gap
            floatingDock.addView(pillStrip)
            floatingDock.addView(bubbleView, bubbleLp)
        }
    }

    private fun clampDockWithinBounds(dp: Float) {
        val maxLeft = -((container.width - floatingDock.width - (12 * dp)).coerceAtLeast(0f))
        val maxRight = (12 * dp)
        val minTop = -((36 * dp) - (12 * dp))
        val maxBottom = (container.height - floatingDock.height - (48 * dp)).coerceAtLeast(0f)
        floatingDock.translationX = floatingDock.translationX.coerceIn(maxLeft, maxRight)
        floatingDock.translationY = floatingDock.translationY.coerceIn(minTop, maxBottom)
    }

    private fun pillBg(isGreen: Boolean): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(if (isGreen) Color.parseColor("#662E7D32") else Color.parseColor("#99000000"))
            cornerRadius = 20 * dp
            setStroke(
                (1 * dp).toInt(),
                if (isGreen) Color.parseColor("#AA66BB6A") else Color.parseColor("#55FFFFFF")
            )
        }
    }

    private fun updateScreenPill() {
        runOnUiThread {
            if (::screenPill.isInitialized) {
                screenPill.background = pillBg(isGreen = targetScreenOn)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolDownTime < 600L) {
                exitSession()
                return true
            }
            lastVolDownTime = now
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun exitSession() {
        stopWorker()
        finish()
    }

    private fun onTouch(v: View, e: MotionEvent) {
        val c = controller ?: return
        val w = videoW
        val h = videoH
        if (w <= 0 || h <= 0 || v.width <= 0 || v.height <= 0) return

        fun px(i: Int) = ((e.getX(i) / v.width) * w).toInt().coerceIn(0, w - 1)
        fun py(i: Int) = ((e.getY(i) / v.height) * h).toInt().coerceIn(0, h - 1)
        fun send(action: Int, i: Int, droppable: Boolean = false) =
            c.send(ControlMessages.touch(action, e.getPointerId(i).toLong(), px(i), py(i), w, h), droppable)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                send(ControlMessages.ACTION_DOWN, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                send(ControlMessages.ACTION_UP, e.actionIndex)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) send(ControlMessages.ACTION_MOVE, i, droppable = true)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until e.pointerCount) send(ControlMessages.ACTION_UP, i)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        container.post { if (videoW > 0 && videoH > 0) fit(videoW, videoH) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startWorker(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = stopWorker()
    override fun onDestroy() {
        stopWorker()
        super.onDestroy()
    }

    private fun setStatus(text: String) = runOnUiThread {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startAudioPlayback(scrcpySession: ScrcpySession) {
        val stream = scrcpySession.audio
        if (stream == null) {
            android.util.Log.w("ScrcpyAudio", "scrcpySession.audio is null")
            setStatus("Audio stream unavailable")
            return
        }
        audioThread = Thread {
            var track: AudioTrack? = null
            try {
                android.util.Log.i("ScrcpyAudio", "Opening audio InputStream, waiting for codecId...")
                val inp = DataInputStream(BufferedInputStream(stream.openInputStream(), 1 shl 15))
                val codecId = inp.readInt()
                android.util.Log.i("ScrcpyAudio", "Read audio codecId=0x${Integer.toHexString(codecId)}")
                if (codecId != 0x72617720 && codecId != 0x726177) {
                    val msg = "Audio codec 0x${Integer.toHexString(codecId)} not raw PCM"
                    android.util.Log.w("ScrcpyAudio", msg)
                    setStatus(msg)
                    return@Thread
                }

                val sampleRate = 48_000
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(16_384)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(encoding)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()

                val hdr = ByteArray(12)
                var writtenFrames = 0
                while (running) {
                    inp.readFully(hdr)
                    val bb = ByteBuffer.wrap(hdr)
                    val first = bb.getLong(0)
                    val isConfig = ((first ushr 62) and 1L) == 1L
                    val size = bb.getInt(8)
                    if (size <= 0 || size > 1_048_576) {
                        android.util.Log.w("ScrcpyAudio", "Skipping invalid audio packet size=$size")
                        continue
                    }
                    val pcm = ByteArray(size)
                    inp.readFully(pcm)
                    if (!isConfig) { android.util.Log.v("ScrcpyAudio", "pcm=${pcm.size}")
                        val written = track.write(pcm, 0, pcm.size)
                        if (written < 0) {
                            android.util.Log.e("ScrcpyAudio", "AudioTrack.write error: $written")
                            break
                        }
                        writtenFrames++
                        if (writtenFrames == 1 || writtenFrames % 250 == 0) {
                            android.util.Log.i("ScrcpyAudio", "Wrote audio chunk #$writtenFrames bytes=$written")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ScrcpyAudio", "Audio thread exception", e)
                if (running) setStatus("Audio stopped: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun startWorker(surface: Surface) {
        val store = AppStore(this)
        val profile = store.profiles().firstOrNull { it.id == intent.getStringExtra("profileId") }
        if (profile == null) {
            setStatus("Device not found")
            return
        }
        val keys = AdbKeyStore(this).also { it.ensureKey() }
        running = true
        worker = Thread {
            var decoder: MediaCodec? = null
            try {
                val s = ScrcpySession.open(applicationContext, keys, profile, settings) { setStatus(it) }
                session = s
                if (!running) {
                    s.close()
                    return@Thread
                }
                startAudioPlayback(s)

                val ctl = Controller(s.control.openOutputStream())
                controller = ctl

                val inp = DataInputStream(BufferedInputStream(s.video.openInputStream(), 1 shl 16))
                inp.readByte()
                inp.readFully(ByteArray(64))
                val codec = inp.readInt()
                if (codec != 0x68323634) {
                    throw IOException("Unsupported codec 0x${Integer.toHexString(codec)} (need h264)")
                }

                val hdr = ByteArray(12)
                var firstFrame = true
                while (running) {
                    inp.readFully(hdr)
                    val bb = ByteBuffer.wrap(hdr)
                    if ((hdr[0].toInt() and 0x80) != 0) {
                        val w = bb.getInt(4)
                        val h = bb.getInt(8)
                        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
                        decoder = createDecoder(surface, w, h)
                        videoW = w
                        videoH = h
                        runOnUiThread { fit(w, h) }
                    } else {
                        val first = bb.getLong(0)
                        val isConfig = ((first ushr 62) and 1L) == 1L
                        val isKey = ((first ushr 61) and 1L) == 1L
                        val pts = first and 0x1FFFFFFFFFFFFFFFL
                        val size = bb.getInt(8)
                        val data = ByteArray(size)
                        inp.readFully(data)
                        decoder?.let { feed(it, data, pts, isConfig, isKey) }
                        if (firstFrame && !isConfig) {
                            firstFrame = false
                            setStatus("")
                            ctl.send(ControlMessages.backOrScreenOn(ControlMessages.ACTION_DOWN))
                            ctl.send(ControlMessages.backOrScreenOn(ControlMessages.ACTION_UP))
                            if (settings.screenOff) {
                                targetScreenOn = false
                                updateScreenPill()
                                ctl.send(ControlMessages.displayPower(false))
                            } else {
                                targetScreenOn = true
                                updateScreenPill()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) setStatus("Stopped: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
                controller?.close()
                controller = null
                session?.close()
                session = null
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopWorker() {
        if (!running) return
        running = false
        val ctl = controller
        val s = session
        val restoreScreen = !targetScreenOn
        worker = null
        audioThread = null
        Thread {
            if (restoreScreen) {
                ctl?.send(ControlMessages.displayPower(true))
                runCatching { Thread.sleep(80) }
            }
            ctl?.close()
            s?.close()
        }.start()
    }

    private fun createDecoder(surface: Surface, w: Int, h: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, surface, null, 0)
            start()
        }
    }

    private fun feed(c: MediaCodec, data: ByteArray, pts: Long, config: Boolean, key: Boolean) {
        var flags = 0
        if (config) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        if (key) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        for (attempt in 0 until 20) {
            drain(c)
            val idx = c.dequeueInputBuffer(5_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx)!!
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, pts, flags)
                drain(c)
                return
            }
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var latestIndex = -1
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> {
                    if (latestIndex >= 0) c.releaseOutputBuffer(latestIndex, false)
                    latestIndex = o
                }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> break
            }
        }
        if (latestIndex >= 0) c.releaseOutputBuffer(latestIndex, true)
    }

    private fun fit(w: Int, h: Int) {
        val cw = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ch = container.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val scale = minOf(cw.toFloat() / w, ch.toFloat() / h)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (w * scale).toInt(),
            (h * scale).toInt(),
            Gravity.CENTER
        )
    }
}
