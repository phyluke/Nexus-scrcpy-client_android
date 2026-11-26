package dev.minimal.scrcpyclient.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

/** Fullscreen touch-controlled stream: ADB -> scrcpy video socket -> MediaCodec -> SurfaceView. */
class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private var settings = StreamSettings()
    private var worker: Thread? = null
    private var lastVolDownTime = 0L

    @Volatile private var running = false
    @Volatile private var session: ScrcpySession? = null
    @Volatile private var controller: Controller? = null
    @Volatile private var activeDecoder: MediaCodec? = null
    @Volatile private var currentSurface: Surface? = null
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
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = 20 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#55FFFFFF"))
            }
            setOnClickListener { onClick() }
        }

        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Screen") {
                targetScreenOn = !targetScreenOn
                controller?.send(ControlMessages.displayPower(targetScreenOn))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
            })
            addView(pill("✕ Exit") { exitSession() })
        }

        val topControlsLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (36 * dp).toInt()
            marginEnd = (12 * dp).toInt()
        }

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
            addView(topControls, topControlsLp)
        }

        setContentView(container)
        surfaceView.holder.addCallback(this)
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
            c.send(ControlMessages.touch(action, i, px(i), py(i), w, h), droppable)

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

    override fun surfaceCreated(holder: SurfaceHolder) {
        currentSurface = holder.surface
        if (!running) {
            startWorker(holder.surface)
        } else {
            runCatching { activeDecoder?.setOutputSurface(holder.surface) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        currentSurface = holder.surface
        runCatching { activeDecoder?.setOutputSurface(holder.surface) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        currentSurface = null
    }

    override fun onDestroy() {
        stopWorker()
        super.onDestroy()
    }

    private fun setStatus(text: String) = runOnUiThread {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startWorker(initialSurface: Surface) {
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
                        activeDecoder = null
                        videoW = w
                        videoH = h
                        runOnUiThread { fit(w, h) }
                        val surf = currentSurface ?: initialSurface
                        decoder = createDecoder(surf, w, h)
                        activeDecoder = decoder
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
                            targetScreenOn = true
                            ctl.send(ControlMessages.displayPower(true))
                            ctl.send(ControlMessages.backOrScreenOn(ControlMessages.ACTION_DOWN))
                            ctl.send(ControlMessages.backOrScreenOn(ControlMessages.ACTION_UP))
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) setStatus("Stopped: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                activeDecoder = null
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
        worker = null
        Thread {
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
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> c.releaseOutputBuffer(o, true)
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
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
