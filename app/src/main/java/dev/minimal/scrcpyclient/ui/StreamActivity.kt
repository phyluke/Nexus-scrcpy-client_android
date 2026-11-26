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
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // Keep viewer phone awake at both Window and SurfaceView levels
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // System Back gesture / button cleanly exits the stream
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

        // Subtle floating Exit pill in top-right corner so you can always exit with one tap
        val exitBtn = TextView(this).apply {
            text = "✕ Exit"
            textSize = 13f
            setTextColor(Color.WHITE)
            val dp = resources.displayMetrics.density
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = 20 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#55FFFFFF"))
            }
            setOnClickListener { exitSession() }
        }

        val exitLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            val dp = resources.displayMetrics.density
            topMargin = (36 * dp).toInt()
            marginEnd = (14 * dp).toInt()
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
            addView(exitBtn, exitLp)
        }

        setContentView(container)
        surfaceView.holder.addCallback(this)
    }

    /** Double-pressing Volume Down also exits the session cleanly. */
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
                val ctl = Controller(s.control.openOutputStream())
                controller = ctl

                val inp = DataInputStream(BufferedInputStream(s.video.openInputStream(), 1 shl 16))
                inp.readByte() // dummy byte (forward tunnel)
                inp.readFully(ByteArray(64)) // device name
                val codec = inp.readInt()
                if (codec != 0x68323634) {
                    throw IOException("Unsupported codec 0x${Integer.toHexString(codec)} (need h264)")
                }

                val hdr = ByteArray(12)
                var firstFrame = true
                while (running) {
                    inp.readFully(hdr)
                    val bb = ByteBuffer.wrap(hdr) // big-endian
                    if ((hdr[0].toInt() and 0x80) != 0) { // session packet: new video size
                        val w = bb.getInt(4)
                        val h = bb.getInt(8)
                        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
                        decoder = createDecoder(surface, w, h)
                        videoW = w
                        videoH = h
                        runOnUiThread { fit(w, h) }
                    } else { // media packet
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
                            // Turn off Redmi physical screen only after first video frame renders
                            ctl.send(ControlMessages.displayPower(false))
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
        worker = null
        Thread {
            // Restore Redmi display power before disconnecting
            ctl?.send(ControlMessages.displayPower(true))
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
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
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> c.releaseOutputBuffer(o, true)
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    /** Fit the entire video frame cleanly centered on screen without cropping top or bottom. */
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
