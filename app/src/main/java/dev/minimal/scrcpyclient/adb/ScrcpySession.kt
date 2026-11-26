package dev.minimal.scrcpyclient.adb

import android.content.Context
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.IOException
import java.io.InputStream
import java.util.Random

/** A running scrcpy server on the target plus its video, audio, and control sockets. */
class ScrcpySession private constructor(
    private val mgr: AbsAdbConnectionManager,
    private val shell: AdbStream,
    val video: AdbStream,
    val audio: AdbStream?,
    val control: AdbStream,
) {
    fun close() {
        runCatching { control.close() }
        runCatching { audio?.close() }
        runCatching { video.close() }
        runCatching { shell.close() }
        runCatching { mgr.disconnect() }
    }

    companion object {
        const val SERVER_VERSION = "5.0.1"
        const val REMOTE_JAR = "/data/local/tmp/scrcpy-server.jar"

        fun open(
            context: Context, keys: AdbKeyStore, profile: Profile, s: StreamSettings, log: (String) -> Unit,
        ): ScrcpySession {
            val mgr = AdbManager(keys)
            try {
                log("Connecting to ${profile.host}:${profile.port} ...")
                if (!mgr.connect(profile.host, profile.port)) throw IOException("ADB connect failed")

                val jar = context.assets.open("scrcpy-server").use { it.readBytes() }
                log("Pushing server ...")
                push(mgr, jar, REMOTE_JAR)

                val scid = String.format("%08x", Random().nextInt() and 0x7FFFFFFF)
                val effectiveMaxSize = if (s.maxSize > 0) minOf(s.maxSize, 1024) else 1024
                val effectiveBitRate = if (s.bitRate > 0) minOf(s.bitRate, 2_500_000) else 2_500_000
                val effectiveFps = if (s.maxFps > 0) minOf(s.maxFps, 60) else 60

                val args = buildList {
                    add("scid=$scid")
                    add("log_level=info")
                    add("tunnel_forward=true")
                    add("audio=true")
                    add("audio_codec=raw")
                    add("control=true")
                    add("max_size=$effectiveMaxSize")
                    add("video_bit_rate=$effectiveBitRate")
                    add("max_fps=$effectiveFps")
                    add("video_codec_options=i-frame-interval=1,latency=1")
                }.joinToString(" ")

                log("Starting server ...")
                val shell = mgr.openStream(
                    "shell:CLASSPATH=$REMOTE_JAR app_process / com.genymobile.scrcpy.Server $SERVER_VERSION $args"
                )
                Thread {
                    try { shell.openInputStream().bufferedReader().forEachLine { log("[server] $it") } }
                    catch (_: Exception) { }
                }.apply { isDaemon = true }.start()

                val name = "localabstract:scrcpy_$scid"
                val video = openWithRetry(mgr, name)
                val audioStream = runCatching { openWithRetry(mgr, name) }.getOrNull()
                val control = openWithRetry(mgr, name)
                // Drain device->client messages (clipboard etc.) so the socket never fills up
                Thread {
                    try { val b = ByteArray(4096); val i = control.openInputStream(); while (i.read(b) >= 0) { } }
                    catch (_: Exception) { }
                }.apply { isDaemon = true }.start()

                return ScrcpySession(mgr, shell, video, audioStream, control)
            } catch (e: Exception) {
                runCatching { mgr.disconnect() }
                throw e
            }
        }

        private fun openWithRetry(mgr: AbsAdbConnectionManager, name: String): AdbStream {
            var last: Exception? = null
            for (i in 0 until 30) {
                try { return mgr.openStream(name) } catch (e: Exception) { last = e; Thread.sleep(200) }
            }
            throw last ?: IOException("could not open $name")
        }

        /** ADB sync protocol: SEND path,mode / DATA... / DONE. */
        private fun push(mgr: AbsAdbConnectionManager, data: ByteArray, remote: String) {
            val stream = mgr.openStream("sync:")
            try {
                val out = stream.openOutputStream()
                val inp = stream.openInputStream()
                val spec = "$remote,33261".toByteArray()
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
            val buf = ByteArray(n); var got = 0
            while (got < n) {
                val r = inp.read(buf, got, n - got)
                if (r < 0) throw IOException("stream closed after $got/$n bytes")
                got += r
            }
            return buf
        }
    }
}
