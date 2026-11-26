package dev.minimal.scrcpyclient.adb

import android.content.Context
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Step 2 test run: connect over ADB, push scrcpy-server, start it, open the video and
 * control sockets and dump the first bytes received. No video decoding yet.
 */
class ScrcpyDiagnostic(private val context: Context, private val keys: AdbKeyStore) {

    companion object {
        const val SERVER_VERSION = "5.0.1"          // must match the bundled scrcpy-server exactly
        const val REMOTE_JAR = "/data/local/tmp/scrcpy-server.jar"
    }

    fun run(profile: Profile, s: StreamSettings, log: (String) -> Unit) {
        val mgr = AdbManager(keys)
        try {
            log("Connecting to ${profile.host}:${profile.port} ...")
            if (!mgr.connect(profile.host, profile.port)) {
                log("connect() returned false"); return
            }
            log("ADB connected (authorized).")

            val jar = context.assets.open("scrcpy-server").use { it.readBytes() }
            log("Pushing scrcpy-server (${jar.size} bytes) ...")
            push(mgr, jar, REMOTE_JAR)
            log("Push OK.")

            val scid = String.format("%08x", Random().nextInt() and 0x7FFFFFFF)
            val args = buildList {
                add("scid=$scid"); add("log_level=info"); add("tunnel_forward=true")
                add("audio=false"); add("control=true")
                if (s.maxSize > 0) add("max_size=${s.maxSize}")
                add("video_bit_rate=${s.bitRate}"); add("max_fps=${s.maxFps}")
            }.joinToString(" ")
            val cmd = "CLASSPATH=$REMOTE_JAR app_process / com.genymobile.scrcpy.Server $SERVER_VERSION $args"
            log("Starting server: $cmd")
            val shell = mgr.openStream("shell:$cmd")
            Thread {
                try {
                    shell.openInputStream().bufferedReader().forEachLine { log("[server] $it") }
                } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()

            val socketName = "localabstract:scrcpy_$scid"
            val video = openWithRetry(mgr, socketName)
            log("Video socket opened.")
            val control = openWithRetry(mgr, socketName)
            log("Control socket opened.")

            val data = collect(video.openInputStream(), 96, 5000)
            log("Received ${data.size} bytes on the video socket:")
            log(hexDump(data))
            if (data.isNotEmpty()) {
                val nameBytes = data.drop(1).take(64).toByteArray()
                log("Guess: dummy byte=${data[0]}, device name='${String(nameBytes).trim { it == '\u0000' || it == ' ' }}'")
            }
            runCatching { control.close() }
            runCatching { video.close() }
            runCatching { shell.close() }
        } catch (e: Exception) {
            log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { mgr.disconnect() }
            log("Disconnected.")
        }
    }

    // ---- helpers ----

    private fun openWithRetry(mgr: AbsAdbConnectionManager, name: String) = run {
        var last: Exception? = null
        for (i in 0 until 30) {
            try { return@run mgr.openStream(name) } catch (e: Exception) { last = e; Thread.sleep(200) }
        }
        throw last ?: IOException("could not open $name")
    }

    /** ADB sync protocol: SEND path,mode / DATA... / DONE. */
    private fun push(mgr: AbsAdbConnectionManager, data: ByteArray, remote: String) {
        val stream = mgr.openStream("sync:")
        try {
            val out = stream.openOutputStream()
            val inp = stream.openInputStream()
            val spec = "$remote,33261".toByteArray()          // mode 0100755
            out.write("SEND".toByteArray() + le(spec.size) + spec)
            var off = 0
            while (off < data.size) {
                val n = minOf(16 * 1024, data.size - off)
                out.write("DATA".toByteArray() + le(n) + data.copyOfRange(off, off + n))
                off += n
            }
            out.write("DONE".toByteArray() + le((System.currentTimeMillis() / 1000).toInt()))
            out.flush()
            val resp = readFully(inp, 8)
            val id = String(resp, 0, 4)
            if (id != "OKAY") throw IOException("push failed: $id")
            out.write("QUIT".toByteArray() + le(0))
            out.flush()
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(buf, got, n - got)
            if (r < 0) throw IOException("stream closed after $got/$n bytes")
            got += r
        }
        return buf
    }

    /** Reads up to [max] bytes, giving up after [timeoutMs]. */
    private fun collect(inp: InputStream, max: Int, timeoutMs: Long): ByteArray {
        val sink = ByteArrayOutputStream()
        val ex = Executors.newSingleThreadExecutor()
        val f = ex.submit {
            val buf = ByteArray(1024)
            try {
                while (synchronized(sink) { sink.size() } < max) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    synchronized(sink) { sink.write(buf, 0, r) }
                }
            } catch (_: Exception) { }
        }
        try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { f.cancel(true) }
        ex.shutdownNow()
        return synchronized(sink) { sink.toByteArray() }
    }

    private fun hexDump(b: ByteArray): String =
        b.toList().chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02x".format(it) } + "  " +
                row.map { c -> if (c in 32..126) c.toInt().toChar() else '.' }.joinToString("")
        }
}
