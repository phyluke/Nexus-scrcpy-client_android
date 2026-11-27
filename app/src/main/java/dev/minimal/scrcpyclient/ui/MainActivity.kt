package dev.minimal.scrcpyclient.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.data.AppStore
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import dev.minimal.scrcpyclient.data.Units
import dev.minimal.scrcpyclient.ssh.SshAuthMode
import dev.minimal.scrcpyclient.ssh.SshHostProfile
import dev.minimal.scrcpyclient.ssh.SshProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Nexus main hub: stat cards, resume card, unified machine list, per-machine overflow actions. */
class MainActivity : AppCompatActivity() {

    private data class LocalTermProfile(
        val id: String,
        val label: String,
        val shellCmd: String,
    )

    private sealed class MachineItem {
        abstract val displayLabel: String
        abstract val displaySubtitle: String
        abstract val badge: String
        abstract val lastUsed: String
        abstract val tags: List<String>

        data class Adb(val profile: Profile) : MachineItem() {
            override val displayLabel: String get() = profile.label
            override val displaySubtitle: String get() = "${profile.host}:${profile.port}"
            override val badge: String get() = "ADB"
            override val lastUsed: String get() = "never"
            override val tags: List<String> get() = emptyList()
        }
        data class Ssh(val profile: SshHostProfile) : MachineItem() {
            override val displayLabel: String get() = profile.label
            override val displaySubtitle: String get() = "${profile.username}@${profile.host}:${profile.port}"
            override val badge: String get() =
                if (profile.authMode == SshAuthMode.PASSWORD) "askpw" else profile.authMode.label
            override val lastUsed: String get() =
                if (profile.lastUsedAt > 0L)
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(profile.lastUsedAt))
                else "never"
            override val tags: List<String> get() = profile.tags
        }
        data class Local(val terminal: LocalTermProfile) : MachineItem() {
            override val displayLabel: String get() = terminal.label
            override val displaySubtitle: String get() = terminal.shellCmd
            override val badge: String get() = "Local"
            override val lastUsed: String get() = "never"
            override val tags: List<String> get() = emptyList()
        }
    }

    private lateinit var store: AppStore
    private lateinit var sshStore: SshProfileStore

    private lateinit var savedCountView: TextView
    private lateinit var liveCountView: TextView
    private lateinit var machinesHeader: TextView
    private lateinit var machineList: LinearLayout
    private lateinit var machineSearchInput: EditText
    private var machineFilterQuery: String = ""

    private val bgPage = Color.parseColor("#0D1117")
    private val bgCard = Color.parseColor("#161B22")
    private val borderCard = Color.parseColor("#30363D")
    private val accentPink = Color.parseColor("#DB61A2")
    private val accentBlue = Color.parseColor("#1F6FEB")
    private val accentGreen = Color.parseColor("#238636")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val textMuted = Color.parseColor("#484F58")
    private val btnNeutral = Color.parseColor("#21262D")

    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bgPage
        window.navigationBarColor = bgPage
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        store = AppStore(this)
        sshStore = SshProfileStore(this)

        // Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Nexus"
                    textSize = 28f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "SSH workspaces · Terminal · ADB · scrcpy"
                    textSize = 13f
                    setTextColor(textSecondary)
                    setPadding(0, px(2), 0, 0)
                })
            })
            addView(pillButton("...", btnNeutral, textPrimary) { showTopOverflowMenu() })
        }

        // Stats
        savedCountView = TextView(this).apply {
            text = "0"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        liveCountView = TextView(this).apply {
            text = "0"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }
            addView(statCard("Saved machines", savedCountView))
            addView(statCard("Live sessions", liveCountView))
        }

        // Resume card
        val resumeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }
            addView(TextView(this@MainActivity).apply {
                text = "Resume sessions"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Open a machine below to start a session."
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(0, px(2), 0, 0)
            })
        }

        // Machines header + new button
        machinesHeader = TextView(this).apply {
            text = "Machines"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        val machinesHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(16) }
            addView(machinesHeader.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pillButton("+ New machine", accentPink, Color.WHITE) { showNewMachineDialog() })
        }

        // Search
        machineSearchInput = EditText(this).apply {
            hint = "Search machines, hosts, tags..."
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 12f
            background = cardDrawable(bgCard, borderCard, 10)
            setPadding(px(12), px(9), px(12), px(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    machineFilterQuery = s?.toString()?.trim().orEmpty()
                    renderMachineList()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        machineList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(10) }
            setPadding(0, 0, 0, px(10))
        }

        val footer = TextView(this).apply {
            text = "powered by phyluke"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, px(24), 0, px(12))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(36), px(18), px(16))
            addView(headerRow)
            addView(statsRow)
            addView(resumeCard)
            addView(machinesHeaderRow)
            addView(machineSearchInput)
            addView(machineList)
            addView(footer)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgPage)
            isFillViewport = true
            addView(root)
        })

        renderMachineList()
    }

    override fun onResume() {
        super.onResume()
        if (::machineList.isInitialized) renderMachineList()
    }

    // ---- helpers ----

    private fun statCard(title: String, countView: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(8) }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 11f
                setTextColor(textSecondary)
            })
            addView(countView.apply { setPadding(0, px(4), 0, 0) })
        }

    private fun refreshStats() {
        val adb = store.profiles().size
        val ssh = sshStore.profiles().size
        val local = loadLocalTerminals().size
        savedCountView.text = (ssh + local).toString()
        liveCountView.text = adb.toString()
        machinesHeader.text = "Machines ${adb + ssh + local}"
    }

    private fun showTopOverflowMenu() {
        AlertDialog.Builder(this)
            .setTitle("Nexus")
            .setItems(arrayOf("Add ADB device", "Settings")) { _, which ->
                when (which) {
                    0 -> addAdbDeviceDialog()
                    1 -> editStreamSettingsDialog()
                }
            }
            .show()
    }

    private fun showNewMachineDialog() {
        AlertDialog.Builder(this)
            .setTitle("New machine")
            .setItems(arrayOf("ADB device", "SSH machine", "Local terminal")) { _, which ->
                when (which) {
                    0 -> addAdbDeviceDialog()
                    1 -> editSshProfile(null)
                    2 -> editLocalTerminalDialog(null)
                }
            }
            .show()
    }

    private fun renderMachineList() {
        machineList.removeAllViews()
        refreshStats()

        val q = machineFilterQuery.lowercase()
        val items = mutableListOf<MachineItem>()

        store.profiles().forEach { p ->
            if (q.isEmpty() || p.label.lowercase().contains(q) || p.host.lowercase().contains(q)) {
                items.add(MachineItem.Adb(p))
            }
        }
        sshStore.profiles().forEach { h ->
            val hit = q.isEmpty() || h.label.lowercase().contains(q) || h.host.lowercase().contains(q) ||
                h.username.lowercase().contains(q) || h.tags.any { it.lowercase().contains(q) }
            if (hit) items.add(MachineItem.Ssh(h))
        }
        loadLocalTerminals().forEach { t ->
            if (q.isEmpty() || t.label.lowercase().contains(q) || t.shellCmd.lowercase().contains(q)) {
                items.add(MachineItem.Local(t))
            }
        }

        if (items.isEmpty()) {
            machineList.addView(TextView(this).apply {
                text = if (q.isEmpty()) "No machines yet. Tap + New machine." else "No matches for \"$machineFilterQuery\"."
                textSize = 12f
                setTextColor(textSecondary)
                background = cardDrawable(bgCard, borderCard, 10)
                setPadding(px(12), px(12), px(12), px(12))
            })
            return
        }

        items.forEach { machineList.addView(buildMachineRow(it)) }
    }

    private fun buildMachineRow(item: MachineItem): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(12), px(11), px(12), px(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(8) }

            // top: label / subtitle / overflow
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = item.displayLabel
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textPrimary)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = item.displaySubtitle
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(textSecondary)
                    })
                })
                addView(pillButton("•••", btnNeutral, textSecondary) { showMachineOverflow(item) })
            })

            // badges
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, 0)
                addView(badgePill(item.badge).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(6) }
                })
                addView(badgePill(item.lastUsed))
            })

            // tags
            if (item.tags.isNotEmpty()) {
                val strip = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, px(6), 0, 0)
                }
                item.tags.forEachIndexed { i, tag ->
                    strip.addView(badgePill("# $tag").apply {
                        if (i < item.tags.lastIndex) {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = px(6) }
                        }
                    })
                }
                addView(HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                })
            }

            // actions
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, 0)
                when (item) {
                    is MachineItem.Adb -> {
                        addView(pillButton("ADB shell", accentPink, Color.WHITE) { openAdbShell(item.profile) }
                            .apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { marginEnd = px(6) }
                            })
                        addView(pillButton("scrcpy", accentBlue, Color.WHITE) { openScrcpy() })
                    }
                    is MachineItem.Ssh -> {
                        addView(pillButton("Connect", accentPink, Color.WHITE) { openSshTerminal(item.profile) }
                            .apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { marginEnd = px(6) }
                            })
                        addView(pillButton("Edit", btnNeutral, textSecondary) { editSshProfile(item.profile) })
                    }
                    is MachineItem.Local -> {
                        addView(pillButton("Open", accentPink, Color.WHITE) { openLocalShell(item.terminal) })
                    }
                }
            })
        }

    private fun showMachineOverflow(item: MachineItem) {
        AlertDialog.Builder(this)
            .setTitle(item.displayLabel)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> when (item) {
                        is MachineItem.Adb -> editAdbDevice(item.profile)
                        is MachineItem.Ssh -> editSshProfile(item.profile)
                        is MachineItem.Local -> editLocalTerminalDialog(item.terminal)
                    }
                    1 -> when (item) {
                        is MachineItem.Adb -> {
                            store.saveProfiles(store.profiles().filterNot { it.id == item.profile.id })
                            renderMachineList()
                        }
                        is MachineItem.Ssh -> {
                            sshStore.delete(item.profile.id)
                            renderMachineList()
                        }
                        is MachineItem.Local -> {
                            saveCustomLocalTerminals(loadLocalTerminals().filterNot { it.id == item.terminal.id })
                            renderMachineList()
                        }
                    }
                }
            }
            .show()
    }

    // ---- navigation ----

    private fun openAdbShell(p: Profile) {
        startActivity(Intent(this, AdbShellActivity::class.java).apply {
            putExtra(AdbShellActivity.EXTRA_HOST, p.host)
            putExtra(AdbShellActivity.EXTRA_PORT, p.port)
            putExtra(AdbShellActivity.EXTRA_LABEL, p.label)
        })
    }

    private fun openLocalShell(t: LocalTermProfile) {
        startActivity(Intent(this, AdbShellActivity::class.java).apply {
            putExtra(AdbShellActivity.EXTRA_LABEL, t.label)
        })
    }

    private fun openScrcpy() {
        startActivity(Intent(this, ScrcpyFullScreenActivity::class.java))
    }

    private fun openSshTerminal(profile: SshHostProfile) {
        sshStore.touchLastUsed(profile.id)
        startActivity(Intent(this, SshTerminalActivity::class.java).apply {
            putExtra(SshTerminalActivity.EXTRA_LABEL, profile.label)
            putExtra(SshTerminalActivity.EXTRA_HOST, profile.host)
            putExtra(SshTerminalActivity.EXTRA_PORT, profile.port)
            putExtra(SshTerminalActivity.EXTRA_USER, profile.username)
            putExtra(SshTerminalActivity.EXTRA_AUTH_MODE, profile.authMode.wire)
            putExtra(SshTerminalActivity.EXTRA_KEY_PATH, profile.privateKeyPath)
            putExtra(SshTerminalActivity.EXTRA_KEY_STUB, profile.hardwareKeyStub)
            putExtra(SshTerminalActivity.EXTRA_TIMEOUT_SEC, profile.timeoutSec)
            putExtra(SshTerminalActivity.EXTRA_START_TMUX, profile.startTmux)
            putExtra(SshTerminalActivity.EXTRA_TMUX_PREFIX, profile.tmuxPrefix)
            putExtra(SshTerminalActivity.EXTRA_USE_MOSH, profile.useMosh)
            putExtra(SshTerminalActivity.EXTRA_MOSH_LOCALE, profile.moshLocale)
        })
    }

    // ---- local terminals ----

    private fun loadLocalTerminals(): List<LocalTermProfile> {
        val prefs = getSharedPreferences("nexus_local_terminals", Context.MODE_PRIVATE)
        val raw = prefs.getString("items", null)
        val defaults = listOf(LocalTermProfile("local_sh", "Android Local Shell", "/system/bin/sh"))
        if (raw.isNullOrEmpty()) return defaults
        return try {
            val arr = JSONArray(raw)
            val custom = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LocalTermProfile(o.getString("id"), o.getString("label"), o.getString("shellCmd"))
            }
            defaults + custom
        } catch (_: Exception) {
            defaults
        }
    }

    private fun saveCustomLocalTerminals(items: List<LocalTermProfile>) {
        val arr = JSONArray()
        items.filterNot { it.id == "local_sh" }.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("label", t.label).put("shellCmd", t.shellCmd))
        }
        getSharedPreferences("nexus_local_terminals", Context.MODE_PRIVATE)
            .edit().putString("items", arr.toString()).apply()
    }

    private fun editLocalTerminalDialog(existing: LocalTermProfile?) {
        val labelField = field("Name (e.g. Ubuntu / Alpine / Local)", existing?.label ?: "")
        val cmdField = field("Shell / Distro command", existing?.shellCmd ?: "/system/bin/sh")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(12), px(24), px(4))
            addView(labelField)
            addView(cmdField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New terminal" else "Edit terminal")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val lbl = labelField.text.toString().trim()
                val cmd = cmdField.text.toString().trim().ifEmpty { "/system/bin/sh" }
                if (lbl.isEmpty()) return@setPositiveButton
                val updated = loadLocalTerminals().filterNot { it.id == existing?.id } +
                    LocalTermProfile(existing?.id ?: UUID.randomUUID().toString(), lbl, cmd)
                saveCustomLocalTerminals(updated)
                renderMachineList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- adb device ----

    private fun addAdbDeviceDialog() {
        val label = field("Label (e.g. Redmi Note 8 Pro)", "")
        val host = field("IP address", "")
        val port = field("Port (5555)", "5555", numeric = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(12), px(24), px(4))
            addView(label); addView(host); addView(port)
        }
        AlertDialog.Builder(this)
            .setTitle("Add ADB device")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val p = Profile(
                    UUID.randomUUID().toString(),
                    label.text.toString().trim().ifEmpty { h },
                    h,
                    port.text.toString().toIntOrNull() ?: 5555,
                )
                store.saveProfiles((store.profiles() + p).sortedBy { it.label.lowercase() })
                renderMachineList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editAdbDevice(p: Profile) {
        val label = field("Label", p.label)
        val host = field("IP address", p.host)
        val port = field("Port", p.port.toString(), numeric = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(12), px(24), px(4))
            addView(label); addView(host); addView(port)
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Edit device")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val updated = Profile(
                    p.id,
                    label.text.toString().trim().ifEmpty { h },
                    h,
                    port.text.toString().toIntOrNull() ?: 5555,
                )
                store.saveProfiles((store.profiles().filterNot { it.id == p.id } + updated)
                    .sortedBy { it.label.lowercase() })
                renderMachineList()
            }
            .setNegativeButton("Cancel", null)
        b.setNeutralButton("Delete") { _, _ ->
            store.saveProfiles(store.profiles().filterNot { it.id == p.id })
            renderMachineList()
        }
        b.show()
    }

    // ---- ssh machine ----

    private fun editSshProfile(existing: SshHostProfile?) {
        val labelField = field("Display name", existing?.label ?: "")
        val hostField = field("Host or IP", existing?.host ?: "")
        val portField = field("Port (22)", (existing?.port ?: 22).toString(), numeric = true)
        val userField = field("Username", existing?.username ?: "")

        val authGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbPassword = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Password (askpw per login)"
            setTextColor(textPrimary)
            isChecked = (existing?.authMode ?: SshAuthMode.PASSWORD) == SshAuthMode.PASSWORD
        }
        val rbPrivateKey = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Private key (PEM / OpenSSH)"
            setTextColor(textPrimary)
            isChecked = existing?.authMode == SshAuthMode.PRIVATE_KEY
        }
        val rbHardwareKey = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Hardware key (*_sk stub)"
            setTextColor(textPrimary)
            isChecked = existing?.authMode == SshAuthMode.HARDWARE_KEY
        }
        authGroup.addView(rbPassword)
        authGroup.addView(rbPrivateKey)
        authGroup.addView(rbHardwareKey)

        val keyPathField = field("Private key file path", existing?.privateKeyPath ?: "")
        val keyStubField = field("*_sk stub path or text", existing?.hardwareKeyStub ?: "")
        val tagsField = field("Tags (comma separated)", existing?.tags?.joinToString(", ") ?: "")
        val timeoutField = field("Timeout (sec)", (existing?.timeoutSec ?: 12).toString(), numeric = true)
        val tmuxBox = CheckBox(this).apply {
            text = "Start tmux on connect"
            setTextColor(textSecondary)
            isChecked = existing?.startTmux ?: false
        }
        val tmuxPrefixField = field("Tmux prefix", existing?.tmuxPrefix ?: "Ctrl-B")
        val moshBox = CheckBox(this).apply {
            text = "Connect with Mosh"
            setTextColor(textSecondary)
            isChecked = existing?.useMosh ?: false
        }
        val moshLocaleField = field("Mosh locale", existing?.moshLocale ?: "C.UTF-8")

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(12), px(24), px(4))
            addView(TextView(this@MainActivity).apply {
                text = "Password is never saved; you are asked on every login."
                textSize = 11f
                setTextColor(textMuted)
                setPadding(0, 0, 0, px(6))
            })
            addView(labelField); addView(hostField); addView(portField); addView(userField)
            addView(authGroup)
            addView(keyPathField); addView(keyStubField)
            addView(tagsField); addView(timeoutField)
            addView(tmuxBox); addView(tmuxPrefixField)
            addView(moshBox); addView(moshLocaleField)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New SSH machine" else "Edit SSH machine")
            .setView(ScrollView(this).apply { addView(inner) })
            .setPositiveButton("Save") { _, _ ->
                val hostVal = hostField.text.toString().trim()
                val userVal = userField.text.toString().trim()
                if (hostVal.isEmpty() || userVal.isEmpty()) return@setPositiveButton
                val lbl = labelField.text.toString().trim().ifEmpty { "$userVal@$hostVal" }
                val mode = when (authGroup.checkedRadioButtonId) {
                    rbPrivateKey.id -> SshAuthMode.PRIVATE_KEY
                    rbHardwareKey.id -> SshAuthMode.HARDWARE_KEY
                    else -> SshAuthMode.PASSWORD
                }
                sshStore.upsert(
                    SshHostProfile(
                        id = existing?.id ?: sshStore.newId(),
                        label = lbl,
                        host = hostVal,
                        port = portField.text.toString().toIntOrNull() ?: 22,
                        username = userVal,
                        authMode = mode,
                        askPasswordEveryLogin = true,
                        privateKeyPath = keyPathField.text.toString().trim(),
                        hardwareKeyStub = keyStubField.text.toString().trim(),
                        tags = tagsField.text.toString().split(",")
                            .map { it.trim().removePrefix("#").trim() }
                            .filter { it.isNotEmpty() },
                        timeoutSec = timeoutField.text.toString().toIntOrNull() ?: 12,
                        startTmux = tmuxBox.isChecked,
                        tmuxPrefix = tmuxPrefixField.text.toString().trim().ifEmpty { "Ctrl-B" },
                        useMosh = moshBox.isChecked,
                        moshLocale = moshLocaleField.text.toString().trim().ifEmpty { "C.UTF-8" },
                        predictiveEcho = false,
                        lastUsedAt = existing?.lastUsedAt ?: 0L,
                    )
                )
                renderMachineList()
            }
            .setNegativeButton("Cancel", null)

        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                sshStore.delete(existing.id)
                renderMachineList()
            }
        }
        builder.show()
    }

    // ---- stream settings ----

    private fun editStreamSettingsDialog() {
        val s = store.settings()
        val maxSize = field("Max size (720, 1024, 0 = native)", s.maxSize.toString(), numeric = true)
        val bitrate = field("Bitrate (2M, 4M, 8M)", Units.formatBitRate(s.bitRate))
        val fps = field("Max FPS", s.maxFps.toString(), numeric = true)
        val off = CheckBox(this).apply {
            text = "Turn target screen off on connect"
            setTextColor(textSecondary)
            isChecked = s.screenOff
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(12), px(24), px(4))
            addView(maxSize); addView(bitrate); addView(fps); addView(off)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                store.saveSettings(
                    StreamSettings(
                        maxSize = maxSize.text.toString().toIntOrNull() ?: s.maxSize,
                        bitRate = Units.parseBitRate(bitrate.text.toString()) ?: s.bitRate,
                        maxFps = fps.text.toString().toIntOrNull() ?: s.maxFps,
                        navBar = false,
                        screenOff = off.isChecked,
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- widget helpers ----

    private fun cardDrawable(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke(px(1), stroke)
        }

    private fun badgePill(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(textSecondary)
            setPadding(px(8), px(3), px(8), px(3))
            background = cardDrawable(btnNeutral, borderCard, 8)
        }

    private fun pillButton(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(px(12), px(8), px(12), px(8))
            background = cardDrawable(bg, borderCard, 10)
            setOnClickListener { onClick() }
        }

    private fun field(hint: String, value: String, numeric: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(textMuted)
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
}
