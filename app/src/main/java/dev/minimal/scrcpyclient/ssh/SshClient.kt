package dev.minimal.scrcpyclient.ssh

import android.content.Context
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID

data class SshHostProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val askPasswordEveryLogin: Boolean = true,
)

class SshProfileStore(context: Context) {
    // Deliberately never stores or reuses passwords (askpw workflow).
    private val prefs = context.getSharedPreferences("nexus_ssh_profiles_no_pw", Context.MODE_PRIVATE)

    fun profiles(): List<SshHostProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            SshHostProfile(
                id = o.getString("id"),
                label = o.getString("label"),
                host = o.getString("host"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                askPasswordEveryLogin = true,
            )
        }
    }

    fun saveProfiles(list: List<SshHostProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("label", p.label)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("username", p.username)
                    .put("askPasswordEveryLogin", true)
            )
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun upsert(profile: SshHostProfile): List<SshHostProfile> {
        val updated = profiles().filterNot { it.id == profile.id } + profile.copy(askPasswordEveryLogin = true)
        val sorted = updated.sortedBy { it.label.lowercase() }
        saveProfiles(sorted)
        return sorted
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
        oneTimePassword: String,
        connectTimeoutMs: Int = 10_000,
    ): ActiveSshShell {
        val jsch = JSch()
        val session = jsch.getSession(username, host, port).apply {
            setPassword(oneTimePassword)
            val props = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive")
            }
            setConfig(props)
            timeout = connectTimeoutMs
            connect(connectTimeoutMs)
        }
        val channel = (session.openChannel("shell") as ChannelShell).apply {
            setPty(true)
            setPtyType("xterm-256color")
            connect(connectTimeoutMs)
        }
        return ActiveSshShell(
            session = session,
            channel = channel,
            input = channel.inputStream,
            output = channel.outputStream,
        )
    }
}