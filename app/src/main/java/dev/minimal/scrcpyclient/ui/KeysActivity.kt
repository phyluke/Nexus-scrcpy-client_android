package dev.minimal.scrcpyclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.minimal.scrcpyclient.adb.AdbKeyStore

/** Temporary screen to exercise key generate / import / export. Device list comes next. */
class KeysActivity : AppCompatActivity() {

    private lateinit var keys: AdbKeyStore
    private lateinit var info: TextView

    private val importKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val pem = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            keys.importPrivateKeyPem(pem)
            toast("Key imported")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
        refresh()
    }

    private val exportPub = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(keys.publicKeyAdbFormat().toByteArray()) }
            toast("Saved adbkey.pub")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = AdbKeyStore(this)
        keys.ensureKey()

        info = TextView(this).apply { setTextIsSelectable(true) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(info)
            addView(button("Import ADB key (adbkey)") { importKey.launch(arrayOf("*/*")) })
            addView(button("Save adbkey.pub to file") { exportPub.launch("adbkey.pub") })
            addView(button("Share public key") { sharePub() })
            addView(button("Copy public key") { copyPub() })
            addView(button("Generate new key") { confirmRegenerate() })
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        info.text = "ADB key ready\nFingerprint: ${keys.fingerprint()}\n\nPublic key:\n${keys.publicKeyAdbFormat()}"
    }

    private fun sharePub() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, keys.publicKeyAdbFormat())
        }
        startActivity(Intent.createChooser(send, "Share adbkey.pub"))
    }

    private fun copyPub() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adbkey.pub", keys.publicKeyAdbFormat()))
        toast("Copied")
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setMessage("Replace the current key? Devices that authorized it will need the new public key.")
            .setPositiveButton("Replace") { _, _ -> keys.generate(); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
