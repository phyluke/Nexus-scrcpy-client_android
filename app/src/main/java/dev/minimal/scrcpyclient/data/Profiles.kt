package dev.minimal.scrcpyclient.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(val id: String, val label: String, val host: String, val port: Int = 5555)

/** maxSize 0 = native. bitRate in bits/s. */
data class StreamSettings(
    val maxSize: Int = 720,
    val bitRate: Int = 2_000_000,
    val maxFps: Int = 30,
    val navBar: Boolean = true,
    val screenOff: Boolean = true,
)

object Units {
    fun parseBitRate(s: String): Int? {
        val t = s.trim().uppercase()
        return when {
            t.endsWith("M") -> t.dropLast(1).toDoubleOrNull()?.let { (it * 1_000_000).toInt() }
            t.endsWith("K") -> t.dropLast(1).toDoubleOrNull()?.let { (it * 1_000).toInt() }
            else -> t.toIntOrNull()
        }
    }

    fun formatBitRate(v: Int): String =
        if (v % 1_000_000 == 0) "${v / 1_000_000}M" else if (v % 1_000 == 0) "${v / 1_000}K" else "$v"
}

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun profiles(): List<Profile> {
        val raw = prefs.getString("profiles", null)
        if (raw == null) {
            // Quick-connect preset
            val seed = listOf(Profile(UUID.randomUUID().toString(), "NAS (Redmi Note 8 Pro)", "172.28.28.90", 5555))
            saveProfiles(seed)
            return seed
        }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Profile(o.getString("id"), o.getString("label"), o.getString("host"), o.optInt("port", 5555))
        }
    }

    fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("host", it.host).put("port", it.port))
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun settings(): StreamSettings = StreamSettings(
        maxSize = prefs.getInt("maxSize", 720),
        bitRate = prefs.getInt("bitRate", 2_000_000),
        maxFps = prefs.getInt("maxFps", 30),
        navBar = prefs.getBoolean("navBar", true),
        screenOff = prefs.getBoolean("screenOff", true),
    )

    fun saveSettings(s: StreamSettings) {
        prefs.edit().putInt("maxSize", s.maxSize).putInt("bitRate", s.bitRate)
            .putInt("maxFps", s.maxFps).putBoolean("navBar", s.navBar)
            .putBoolean("screenOff", s.screenOff).apply()
    }
}
