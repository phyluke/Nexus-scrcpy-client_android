package dev.minimal.scrcpyclient.ui

import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.minimal.scrcpyclient.adb.AdbKeyStore
import dev.minimal.scrcpyclient.adb.ScrcpySession
import dev.minimal.scrcpyclient.data.AppStore
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** Fullscreen view-only stream: ADB -> scrcpy video socket -> MediaCodec -> SurfaceView. */
class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var session: ScrcpySession? = null
    private var videoW = 0
    private var videoH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        surfaceView = SurfaceView(this)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(24, 48, 24, 24) }
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        setContentView(container)
        surfaceView.holder.addCallback(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        container.post { if (videoW > 0) fit(videoW, videoH) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startWorker(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = stopWorker()
    override fun onDestroy() { stopWorker(); super.onDestroy() }

    private fun setStatus(text: String) = runOnUiThread {
        status.text = text; status.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun startWorker(surface: Surface) {
        val store = AppStore(this)
        val profile = store.profiles().firstOrNull { it.id == intent.getStringExtra("profileId") }
        if (profile == null) { setStatus("Device not found"); return }
        val settings = store.settings()
        val keys = AdbKeyStore(this).also { it.ensureKey() }
        running = true
        worker = Thread {
            var decoder: MediaCodec? = null
            try {
                val s = ScrcpySession.open(applicationContext, keys, profile, settings) { setStatus(it) }
                session = s
                if (!running) { s.close(); return@Thread }
                val inp = DataInputStream(BufferedInputStream(s.video.openInputStream(), 1 shl 16))
                inp.readByte()                                  // dummy byte (forward tunnel)
                inp.readFully(ByteArray(64))                    // device name
                val codec = inp.readInt()
                if (codec != 0x68323634) throw IOException("Unsupported codec 0x${Integer.toHexString(codec)} (need h264)")

                val hdr = ByteArray(12)
                var firstFrame = true
                while (running) {
                    inp.readFully(hdr)
                    val bb = ByteBuffer.wrap(hdr)               // big-endian
                    if ((hdr[0].toInt() and 0x80) != 0) {       // session packet: new video size
                        val w = bb.getInt(4); val h = bb.getInt(8)
                        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
                        decoder = createDecoder(surface, w, h)
                        videoW = w; videoH = h
                        runOnUiThread { fit(w, h) }
                    } else {                                    // media packet
                        val first = bb.getLong(0)
                        val isConfig = ((first ushr 62) and 1L) == 1L
                        val isKey = ((first ushr 61) and 1L) == 1L
                        val pts = first and 0x1FFFFFFFFFFFFFFFL
                        val size = bb.getInt(8)
                        val data = ByteArray(size)
                        inp.readFully(data)
                        decoder?.let { feed(it, data, pts, isConfig, isKey) }
                        if (firstFrame && !isConfig) { firstFrame = false; setStatus("") }
                    }
                }
            } catch (e: Exception) {
                if (running) setStatus("Stopped: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
                session?.close(); session = null
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopWorker() {
        running = false
        Thread { session?.close() }.start()   // unblocks the reader; off the UI thread
        worker = null
    }

    private fun createDecoder(surface: Surface, w: Int, h: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, surface, null, 0); start()
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
                buf.clear(); buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, pts, flags)
                drain(c)
                return
            }
        }
        // decoder stuck: drop packet
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> c.releaseOutputBuffer(o, true)       // render immediately (low latency)
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    /** Letterbox the SurfaceView to the video's aspect ratio. */
    private fun fit(w: Int, h: Int) {
        val cw = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ch = container.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val scale = minOf(cw.toFloat() / w, ch.toFloat() / h)
        surfaceView.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
    }
}
