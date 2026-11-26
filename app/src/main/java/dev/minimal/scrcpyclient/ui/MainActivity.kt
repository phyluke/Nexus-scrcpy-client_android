package dev.minimal.scrcpyclient.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.minimal.scrcpyclient.adb.AdbKeyStore
import dev.minimal.scrcpyclient.adb.ScrcpyDiagnostic
import dev.minimal.scrcpyclient.data.AppStore
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import dev.minimal.scrcpyclient.data.Units
import java.util.UUID

/** Device list + settings + diagnostic connect (video screen comes next). */
class MainActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var keys: AdbKeyStore
    private lateinit var list: LinearLayout
    private lateinit var logView: TextView
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        keys = AdbKeyStore(this).also { it.ensureKey() }

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            addView(TextView(context).apply { text = "Devices"; textSize = 22f })
            addView(list)
            addView(button("Add device") { editProfile(null) })
            addView(button("Stream settings") { editSettings() })
            addView(button("ADB keys") { startActivity(Intent(context, KeysActivity::class.java)) })
            addView(TextView(context).apply { text = "\nLog"; textSize = 16f })
            addView(logView)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        for (p in store.profiles()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = "${p.label}\n${p.host}:${p.port}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(button("Open") { openStream(p) })
                addView(button("Test") { connect(p) })
                addView(button("Edit") { editProfile(p) })
            }
            list.addView(row)
        }
    }

    private fun openStream(p: Profile) {
        startActivity(Intent(this, StreamActivity::class.java).putExtra("profileId", p.id))
    }

    private fun connect(p: Profile) {
        if (busy) return
        busy = true
        logView.text = ""
        val settings = store.settings()
        Thread {
            ScrcpyDiagnostic(applicationContext, keys).run(p, settings) { line ->
                runOnUiThread { logView.append(line + "\n") }
            }
            busy = false
        }.start()
    }

    private fun editProfile(existing: Profile?) {
        val label = field("Label", existing?.label ?: "")
        val host = field("IP address", existing?.host ?: "")
        val port = field("Port", (existing?.port ?: 5555).toString(), numeric = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            addView(label); addView(host); addView(port)
        }
        val b = AlertDialog.Builder(this).setTitle(if (existing == null) "Add device" else "Edit device").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val updated = Profile(
                    existing?.id ?: UUID.randomUUID().toString(),
                    label.text.toString().trim().ifEmpty { h },
                    h,
                    port.text.toString().toIntOrNull() ?: 5555,
                )
                val all = store.profiles().filter { it.id != updated.id } + updated
                store.saveProfiles(all.sortedBy { it.label.lowercase() })
                renderList()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Delete") { _, _ ->
            store.saveProfiles(store.profiles().filter { it.id != existing.id }); renderList()
        }
        b.show()
    }

    private fun editSettings() {
        val s = store.settings()
        val maxSize = field("Max size (720, 1080, 0 = native)", s.maxSize.toString(), numeric = true)
        val bitrate = field("Bitrate (2M, 4M, 8M)", Units.formatBitRate(s.bitRate))
        val fps = field("Max FPS", s.maxFps.toString(), numeric = true)
        val nav = CheckBox(this).apply { text = "Show navigation bar overlay"; isChecked = s.navBar }
        val off = CheckBox(this).apply { text = "Turn target screen off while streaming"; isChecked = s.screenOff }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            addView(maxSize); addView(bitrate); addView(fps); addView(nav); addView(off)
        }
        AlertDialog.Builder(this).setTitle("Stream settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                store.saveSettings(
                    StreamSettings(
                        maxSize = maxSize.text.toString().toIntOrNull() ?: s.maxSize,
                        bitRate = Units.parseBitRate(bitrate.text.toString()) ?: s.bitRate,
                        maxFps = fps.text.toString().toIntOrNull() ?: s.maxFps,
                        navBar = nav.isChecked,
                        screenOff = off.isChecked,
                    )
                )
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }
}
