package com.phyluke.conduit.adb

import android.content.Context
import android.os.Handler
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class NexusAdbBridge(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(context.mainLooper)
    private val keyStore = AdbKeyStore(context)

    @Volatile
    private var manager: AdbManager? = null

    fun handle(call: io.flutter.plugin.common.MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "ensureKey" -> runAsync(result) {
                keyStore.ensureKey()
                keyStatePayload()
            }
            "generateKey" -> runAsync(result) {
                keyStore.generate()
                keyStatePayload()
            }
            "importKey" -> {
                val pem = call.argument<String>("pem")?.trim().orEmpty()
                if (pem.isEmpty()) {
                    result.error("bad_arguments", "Private key PEM is required.", null)
                    return
                }
                runAsync(result) {
                    keyStore.importPrivateKeyPem(pem)
                    keyStatePayload()
                }
            }
            "publicKey" -> runAsync(result) {
                keyStore.ensureKey()
                keyStore.publicKeyAdbFormat("nexus@conduit")
            }
            "connect" -> {
                val host = call.argument<String>("host")?.trim().orEmpty()
                val port = call.argument<Int>("port") ?: 5555
                if (host.isEmpty()) {
                    result.error("bad_arguments", "Host is required.", null)
                    return
                }
                runAsync(result) {
                    connectInternal(host, port)
                    mapOf(
                        "connected" to true,
                        "host" to host,
                        "port" to port,
                    )
                }
            }
            "testConnection" -> {
                val host = call.argument<String>("host")?.trim().orEmpty()
                val port = call.argument<Int>("port") ?: 5555
                if (host.isEmpty()) {
                    result.error("bad_arguments", "Host is required.", null)
                    return
                }
                runAsync(result) {
                    keyStore.ensureKey()
                    connectInternal(host, port)
                    val model = runShellInternal("getprop ro.product.model").trim()
                    mapOf(
                        "connected" to true,
                        "host" to host,
                        "port" to port,
                        "fingerprint" to keyStore.fingerprint(),
                        "model" to model,
                    )
                }
            }
            "shell" -> {
                val command = call.argument<String>("command")?.trim().orEmpty()
                if (command.isEmpty()) {
                    result.error("bad_arguments", "Command is required.", null)
                    return
                }
                runAsync(result) {
                    runShellInternal(command)
                }
            }
            "startScrcpy" -> {
                val host = call.argument<String>("host")?.trim().orEmpty()
                val port = call.argument<Int>("port") ?: 5555
                val maxSize = call.argument<Int>("maxSize") ?: 1024
                val bitRate = call.argument<Int>("bitRate") ?: 2_500_000
                val maxFps = call.argument<Int>("maxFps") ?: 60
                val screenOff = call.argument<Boolean>("screenOff") ?: false
                if (host.isEmpty()) {
                    result.error("bad_arguments", "Host is required.", null)
                    return
                }
                runAsync(result) {
                    keyStore.ensureKey()
                    // Release any existing standalone ADB socket before opening dedicated scrcpy session
                    runCatching { manager?.close() }
                    manager = null
                    val intent = ScrcpyStreamActivity.createIntent(
                        context = context,
                        host = host,
                        port = port,
                        maxSize = maxSize,
                        bitRate = bitRate,
                        maxFps = maxFps,
                        screenOff = screenOff,
                    )
                    mainHandler.post {
                        context.startActivity(intent)
                    }
                    true
                }
            }
            "disconnect" -> runAsync(result) {
                manager?.close()
                manager = null
                true
            }
            else -> result.notImplemented()
        }
    }

    private fun connectInternal(host: String, port: Int) {
        keyStore.ensureKey()
        val nextManager = AdbManager(keyStore)
        val connected = nextManager.connect(host, port)
        if (!connected) {
            throw IllegalStateException("Failed to connect to $host:$port")
        }
        manager?.close()
        manager = nextManager
    }

    private fun runShellInternal(command: String): String {
        val active = manager ?: throw IllegalStateException("Not connected to an ADB target.")
        val stream = active.openStream("shell:$command")
        return stream.use { s ->
            BufferedReader(InputStreamReader(s.openInputStream())).use { reader ->
                reader.readText()
            }
        }
    }

    private fun keyStatePayload(): Map<String, Any> = mapOf(
        "hasKey" to keyStore.hasKey(),
        "fingerprint" to if (keyStore.hasKey()) keyStore.fingerprint() else "",
    )

    private fun <T> runAsync(result: MethodChannel.Result, block: () -> T) {
        executor.execute {
            try {
                val value = block()
                mainHandler.post {
                    result.success(value)
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    result.error("adb_error", error.message ?: "ADB operation failed.", null)
                }
            }
        }
    }
}
