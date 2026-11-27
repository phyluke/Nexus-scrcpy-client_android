package dev.minimal.scrcpyclient.ssh

import android.content.Context
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID

enum class SshAuthMode(val wire: String, val label: String) {
    PASSWORD("password", "Password"),
    PRIVATE_KEY("private_key", "Private key"),
    HARDWARE_KEY("hardware_key", "Hardware key");

    companion object {
        fun fromWire(v: String?): SshAuthMode =
            entries.firstOrNull { it.wire == v } ?: PASSWORD
    }
}

data class SshHostProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: SshAuthMode = SshAuthMode.PASSWORD,
    val askPasswordEveryLogin: Boolean = true,
    val privateKeyPath: String = "",
    val hardwareKeyStub: String = "",
    val tags: List<String> = emptyList(),
    val timeoutSec: Int = 12,
    val startTmux: Boolean = false,
    val tmuxPrefix: String = "Ctrl-B",
    val useMosh: Boolean = false,
    val moshLocale: String = "C.UTF-8",
    val predictiveEcho: Boolean = false,
    val lastUsedAt: Long = 0L,
)

class SshProfileStore(context: Context) {
    // Deliberately never stores or reuses account passwords (askpw workflow).
    private val prefs = context.getSharedPreferences("nexus_ssh_profiles_v2", Context.MODE_PRIVATE)

    fun profiles(): List<SshHostProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val tagArr = o.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagArr.length()).mapNotNull { i ->
                tagArr.optString(i, "").trim().ifEmpty { null }
            }
            SshHostProfile(
                id = o.getString("id"),
                label = o.getString("label"),
                host = o.getString("host"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                authMode = SshAuthMode.fromWire(o.optString("authMode", "password")),
                askPasswordEveryLogin = true,
                privateKeyPath = o.optString("privateKeyPath", ""),
                hardwareKeyStub = o.optString("hardwareKeyStub", ""),
                tags = tags,
                timeoutSec = o.optInt("timeoutSec", 12),
                startTmux = o.optBoolean("startTmux", false),
                tmuxPrefix = o.optString("tmuxPrefix", "Ctrl-B").ifEmpty { "Ctrl-B" },
                useMosh = o.optBoolean("useMosh", false),
                moshLocale = o.optString("moshLocale", "C.UTF-8").ifEmpty { "C.UTF-8" },
                predictiveEcho = o.optBoolean("predictiveEcho", false),
                lastUsedAt = o.optLong("lastUsedAt", 0L),
            )
        }
    }

    fun saveProfiles(list: List<SshHostProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            val tagArr = JSONArray().apply {
                p.tags.forEach { put(it) }
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("label", p.label)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("username", p.username)
                    .put("authMode", p.authMode.wire)
                    .put("askPasswordEveryLogin", true)
                    .put("privateKeyPath", p.privateKeyPath)
                    .put("hardwareKeyStub", p.hardwareKeyStub)
                    .put("tags", tagArr)
                    .put("timeoutSec", p.timeoutSec)
                    .put("startTmux", p.startTmux)
                    .put("tmuxPrefix", p.tmuxPrefix)
                    .put("useMosh", p.useMosh)
                    .put("moshLocale", p.moshLocale)
                    .put("predictiveEcho", p.predictiveEcho)
                    .put("lastUsedAt", p.lastUsedAt)
            )
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun upsert(profile: SshHostProfile): List<SshHostProfile> {
        val updated = profiles().filterNot { it.id == profile.id } + profile.copy(askPasswordEveryLogin = true)
        val sorted = updated.sortedByDescending { it.lastUsedAt }.sortedBy { it.label.lowercase() }
        saveProfiles(sorted)
        return sorted
    }

    fun touchLastUsed(id: String) {
        val now = System.currentTimeMillis()
        val updated = profiles().map { if (it.id == id) it.copy(lastUsedAt = now) else it }
        saveProfiles(updated)
    }

    fun delete(id: String): List<SshHostProfile> {
        val remaining = profiles().filterNot { it.id == id }
        saveProfiles(remaining)
        return remaining
    }

    fun newId(): String = UUID.randomUUID().toString()
}

class ActiveSshShell(
    private val session: Session,
    private val channel: ChannelShell,
    val input: InputStream,
    val output: OutputStream,
) {
    fun sendCommand(line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun sendRaw(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

object SshConnector {
    fun connectShell(
        host: String,
        port: Int,
        username: String,
        authMode: SshAuthMode = SshAuthMode.PASSWORD,
        oneTimePassword: String = "",
        keyPassphrase: String = "",
        privateKeyPath: String = "",
        hardwareKeyStub: String = "",
        timeoutSec: Int = 12,
    ): ActiveSshShell {
        val timeoutMs = timeoutSec.coerceIn(3, 60) * 1000
        val jsch = JSch()

        when (authMode) {
            SshAuthMode.PRIVATE_KEY -> {
                val keyFile = File(privateKeyPath.trim())
                if (!keyFile.exists() || !keyFile.isFile) {
                    throw IllegalArgumentException("Private key file not found: $privateKeyPath")
                }
                val passBytes = keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity(keyFile.absolutePath, passBytes)
            }
            SshAuthMode.HARDWARE_KEY -> {
                val stubText = hardwareKeyStub.trim()
                if (stubText.isEmpty()) {
                    throw IllegalArgumentException("OpenSSH *_sk hardware key stub is required.")
                }
                val stubFile = if (File(stubText).exists()) {
                    File(stubText)
                } else {
                    File.createTempFile("nexus_sk_", ".stub").apply {
                        writeText(stubText, Charsets.UTF_8)
                        deleteOnExit()
                    }
                }
                val passBytes = keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                runCatching { jsch.addIdentity(stubFile.absolutePath, passBytes) }
                    .getOrElse {
                        throw UnsupportedOperationException(
                            "Hardware security key (*_sk) FIDO2/NFC signing is not supported by JSch 0.1.55 on Android."
                        )
                    }
            }
            SshAuthMode.PASSWORD -> Unit
        }

        val session = jsch.getSession(username, host, port).apply {
            if (authMode == SshAuthMode.PASSWORD) {
                setPassword(oneTimePassword)
            }
            val props = Properties().apply {
                put("StrictHostKeyChecking", "no")
                when (authMode) {
                    SshAuthMode.PASSWORD -> put("PreferredAuthentications", "password,keyboard-interactive")
                    SshAuthMode.PRIVATE_KEY, SshAuthMode.HARDWARE_KEY -> put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                }
            }
            setConfig(props)
            timeout = timeoutMs
            connect(timeoutMs)
        }
        val channel = (session.openChannel("shell") as ChannelShell).apply {
            setPty(true)
            setPtyType("xterm-256color")
            connect(timeoutMs)
        }
        return ActiveSshShell(
            session = session,
            channel = channel,
            input = channel.inputStream,
            output = channel.outputStream,
        )
    }
}