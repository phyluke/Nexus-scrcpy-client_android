package dev.minimal.scrcpyclient.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Full ADB toolkit over libadb-android: shell commands, TCP/Wireless ADB, sync file push, and APK install. */
object AdbToolkit {

    fun testTcpPort(host: String, port: Int, timeoutMs: Int = 3000): Boolean =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

    fun <T> withConnection(
        keys: AdbKeyStore,
        host: String,
        port: Int,
        log: (String) -> Unit = {},
        block: (AbsAdbConnectionManager) -> T,
    ): T {
        val mgr = AdbManager(keys)
        log("Connecting to $host:$port ...")
        if (!mgr.connect(host, port)) {
            runCatching { mgr.disconnect() }
            throw IOException("ADB connect failed to $host:$port")
        }
        return try {
            block(mgr)
        } finally {
            runCatching { mgr.disconnect() }
        }
    }

    fun runShell(
        keys: AdbKeyStore,
        host: String,
        port: Int,
        command: String,
        onLine: (String) -> Unit = {},
    ): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ""
        return withConnection(keys, host, port, onLine) { mgr ->
            val stream: AdbStream = mgr.openStream("shell:$trimmed")
            stream.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                val lineBuf = StringBuilder()
                val inp = s.openInputStream()
                while (true) {
                    val read = inp.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    val chunk = String(buf, 0, read, Charsets.UTF_8)
                    for (ch in chunk) {
                        if (ch == '\n') {
                            onLine(lineBuf.toString().trimEnd('\r'))
                            lineBuf.clear()
                        } else {
                            lineBuf.append(ch)
                        }
                    }
                }
                if (lineBuf.isNotEmpty()) {
                    onLine(lineBuf.toString().trimEnd('\r'))
                }
                out.toString(Charsets.UTF_8.name())
            }
        }
    }

    fun switchToTcpip(
        keys: AdbKeyStore,
        host: String,
        currentPort: Int,
        newTcpPort: Int = 5555,
        onLine: (String) -> Unit = {},
    ): String = withConnection(keys, host, currentPort, onLine) { mgr ->
        mgr.openStream("tcpip:$newTcpPort").use { s ->
            val resp = s.openInputStream().readBytes().toString(Charsets.UTF_8).trim()
            val msg = if (resp.isNotEmpty()) resp else "Switched adbd to TCP port $newTcpPort"
            onLine(msg)
            msg
        }
    }

    /** Attempts Wireless Debugging pairing when supported by the bundled libadb-android API. */
    fun pairWireless(
        keys: AdbKeyStore,
        host: String,
        pairPort: Int,
        pairingCode: String,
        onLine: (String) -> Unit = {},
    ): String {
        val code = pairingCode.trim()
        if (code.isEmpty()) throw IOException("Pairing code cannot be empty")
        val mgr = AdbManager(keys)
        val candidates = listOf("pair", "pairDevice", "connectPair")
        for (methodName in candidates) {
            val method = mgr.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 3
            }
            if (method != null) {
                onLine("Pairing with $host:$pairPort via $methodName ...")
                val ok = method.invoke(mgr, host, pairPort, code)
                val res = "Pair ($methodName) result: $ok"
                onLine(res)
                return res
            }
        }
        throw IOException(
            "Direct pair() method not exposed on this libadb-android build. " +
                "Pair via PC/Wireless ADB once, then connect to $host:<connect-port>."
        )
    }

    fun pushBytes(
        keys: AdbKeyStore,
        host: String,
        port: Int,
        data: ByteArray,
        remotePath: String,
        modeOctal: Int = 33188, // 0644
        onLine: (String) -> Unit = {},
    ) {
        withConnection(keys, host, port, onLine) { mgr ->
            onLine("Pushing ${data.size} bytes to $remotePath ...")
            val stream = mgr.openStream("sync:")
            try {
                val out = stream.openOutputStream()
                val inp = stream.openInputStream()
                val spec = "$remotePath,$modeOctal".toByteArray(Charsets.UTF_8)
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
                if (id != "OKAY") throw IOException("ADB sync push failed: $id")
                out.write("QUIT".toByteArray() + le(0))
                out.flush()
                onLine("Push complete: $remotePath")
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    fun installApk(
        keys: AdbKeyStore,
        host: String,
        port: Int,
        apkBytes: ByteArray,
        onLine: (String) -> Unit = {},
    ): String {
        val remoteApk = "/data/local/tmp/nexus_install_tmp.apk"
        pushBytes(keys, host, port, apkBytes, remoteApk, 33188, onLine)
        return runShell(keys, host, port, "pm install -r $remoteApk && rm -f $remoteApk", onLine)
    }

    fun collectDeviceInfo(
        keys: AdbKeyStore,
        host: String,
        port: Int,
        onLine: (String) -> Unit = {},
    ): String {
        val cmd = "echo MODEL=\$(getprop ro.product.model) && " +
            "echo ANDROID=\$(getprop ro.build.version.release) && " +
            "echo SDK=\$(getprop ro.build.version.sdk) && " +
            "echo SIZE=\$(wm size) && " +
            "dumpsys battery | grep -E 'level|status|temperature' && " +
            "ip -4 addr show wlan0 2>/dev/null | grep inet || true"
        return runShell(keys, host, port, cmd, onLine)
    }

    private fun le(v: Int) = byteArrayOf(
        v.toByte(),
        (v shr 8).toByte(),
        (v shr 16).toByte(),
        (v shr 24).toByte(),
    )

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(buf, got, n - got)
            if (r < 0) throw IOException("ADB sync stream closed after $got/$n bytes")
            got += r
        }
        return buf
    }
}