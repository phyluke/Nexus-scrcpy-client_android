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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.adb.AdbKeyStore
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

/** Conduit-style Nexus main hub: unified machine list, stat cards, resume sessions, overflow menus. */
class MainActivity : AppCompatActivity() {

    private data class LocalTermProfile(val id: String, val label: String, val shellCmd: String)

    private lateinit var store: AppStore
    private lateinit var sshStore: SshProfileStore

    private lateinit var savedCountView: TextView
    private lateinit var liveCountView: TextView

    private lateinit var machineSearchInput: EditText
    private lateinit var machineList: LinearLayout
    private var machineFilterQuery: String = ""

    // Colors (function over form — dark base, pink accent)
    private val bgPage = Color.parseColor("#0D1117")
    private val bgCard = Color.parseColor("#161B22")
    private val borderCard = Color.parseColor("#30363D")
    private val accentPink = Color.parseColor("#DB61A2")
    private val accentBlue = Color.parseColor("#1F6FEB")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val textMuted = Color.parseColor("#484F58")
    private val btnNeutral = Color.parseColor("#21262D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bgPage
        window.navigationBarColor = bgPage
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        store = AppStore(this)
        sshStore = SshProfileStore(this)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // ---- Top Header ----
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "Nexus"
                    textSize = 28f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(TextView(context).apply {
                    text = "SSH workspaces · Terminal · ADB · scrcpy"
                    textSize = 13f
                    setTextColor(textSecondary)
                    setPadding(0, px(2), 0, 0)
                })
            })
            addView(pillButton("...", btnNeutral, textPrimary) {
                showTopOverflowMenu()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

        // ---- Stat cards ----
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
            addView(statCard("Saved machines", savedCountView, dp, px))
            addView(statCard("Live sessions", liveCountView, dp, px))
        }

        // ---- Resume sessions card ----
        val resumeCard = buildResumeCard(dp, px)

        // ---- Machines section ----
        val machinesHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(16) }
            addView(TextView(context).apply {
                text = "Machines ${store.profiles().size + sshStore.profiles().size}"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pillButton("+ New machine", accentPink, Color.WHITE, fill = true) {
                showNewMachineDialog()
            })
        }
        addView(machinesHeader) // placeholder, added below in root

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

        machineList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Footer ----
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
            addView(machinesHeader)
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

    private fun statCard(title: String, countView: TextView, dp: Float, px: (Int) -> Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(8) }
            addView(TextView(context).apply {
                text = title
                textSize = 11f
                setTextColor(textSecondary)
            })
            addView(countView.apply {
                setPadding(0, px(4), 0, 0)
            })
        }
    }

    private fun buildResumeCard(dp: Float, px: (Int) -> Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }

            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Resume sessions"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "1 active"
                    textSize = 12f
                    setTextColor(textSecondary)
                })
            }
            addView(top)
        }
    }

    private fun refreshStats() {
        savedCountView.text = (sshStore.profiles().size + loadLocalTerminals().size).toString()
        liveCountView.text = store.profiles().size.toString()
        // Update machines header count
    }

    private fun showTopOverflowMenu() {
        val options = arrayOf("Add Device", "Settings")
        AlertDialog.Builder(this)
            .setTitle("Nexus")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNewMachineDialog()
                    1 -> editStreamSettingsDialog()
                }
            }
            .show()
    }

    private fun renderMachineList() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        machineList.removeAllViews()
        refreshStats()

        val q = machineFilterQuery.lowercase()

        // Build combined list: ADB profiles + SSH profiles + local terminals
        val items = mutableListOf<MachineItem>()

        for (p in store.profiles()) {
            if (p.label.lowercase().contains(q) || p.host.lowercase().contains(q)) {
                items.add(MachineItem.Adb(p))
            }
        }
        for (h in sshStore.profiles()) {
            if (h.label.lowercase().contains(q) || h.host.lowercase().contains(q) ||
                h.username.lowercase().contains(q) || h.tags.any { it.lowercase().contains(q) }) {
                items.add(MachineItem.Ssh(h))
            }
        }
        for (t in loadLocalTerminals()) {
            if (t.label.lowercase().contains(q)) {
                items.add(MachineItem.Local(t))
            }
        }

        if (items.isEmpty()) {
            machineList.addView(TextView(this).apply {
                text = "No machines found. Tap + New machine to add one."
                textSize = 12f
                setTextColor(textSecondary)
                background = cardDrawable(bgCard, borderCard, 10)
                setPadding(px(12), px(12), px(12), px(12))
            })
            return
        }

        for (item in items) {
            machineList.addView(buildMachineRow(item, dp, px))
        }
    }

    private fun buildMachineRow(item: MachineItem, dp: Float, px: (Int) -> Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(12), px(11), px(12), px(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(8) }

            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                // Icon
                addView(ImageView(context).apply {
                    setImageResource(when (item) {
                        is MachineItem.Adb -> android.R.drawable.ic_menu_directions
                        is MachineItem.Ssh -> android.R.drawable.ic_menu_info_details
                        is MachineItem.Local -> android.R.drawable.ic_menu_input
                    })
                    setColorFilter(textSecondary)
                    layoutParams = LinearLayout.LayoutParams(px(24), px(24))
                }).apply { layoutParams = LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(10) } }

                // Info
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = item.displayLabel
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textPrimary)
                    })
                    addView(TextView(context).apply {
                        text = item.displaySubtitle
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(textSecondary)
                    })
                })

                // Overflow menu
                addView(pillButton("•••", btnNeutral, textSecondary) {
                    showMachineOverflow(item)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }
            addView(top)

            // Action buttons row
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, 0)
            }

            when (item) {
                is MachineItem.Adb -> {
                    actions.addView(pillButton("Connect", accentPink, Color.WHITE, fill = true) {
                        startActivity(Intent(context, AdbShellActivity::class.java).apply {
                            putExtra(AdbShellActivity.EXTRA_HOST, item.profile.host)
                            putExtra(AdbShellActivity.EXTRA_PORT, item.profile.port)
                            putExtra(AdbShellActivity.EXTRA_LABEL, item.profile.label)
                        })
                    }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) } })
                    actions.addView(pillButton("scrcpy", accentBlue, Color.WHITE, fill = true) {
                        startActivity(Intent(context, ScrcpyFullScreenActivity::class.java))
                    })
                }
                is MachineItem.Ssh -> {
                    actions.addView(pillButton("Connect", accentPink, Color.WHITE, fill = true) {
                        openSshTerminal(item.profile)
                    }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) } })
                    actions.addView(pillButton("Edit", btnNeutral, textSecondary) {
                        editSshProfile(item.profile)
                    })
                }
                is MachineItem.Local -> {
                    actions.addView(pillButton("Open", accentPink, Color.WHITE, fill = true) {
                        startActivity(Intent(context, AdbShellActivity::class.java)
                            .putExtra(AdbShellActivity.EXTRA_LABEL, item.terminal.label))
                    })
                }
            }
            addView(actions)

            // Meta row
            val meta = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, 0)
            }
            meta.addView(badgePill(item.authLabel, btnNeutral, textSecondary).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) }
            })
            meta.addView(badgePill(item.lastUsed, btnNeutral, textMuted))
            addView(meta)

            if (item.tags.isNotEmpty()) {
                val tagStrip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, px(6), 0, 0)
                }
                item.tags.forEachIndexed { i, tag ->
                    tagStrip.addView(badgePill("# $tag", bgCard, textSecondary).apply {
                        if (i < item.tags.lastIndex) {
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) }
                        }
                    })
                }
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(tagStrip)
                })
            }
        }
    }

    private fun showMachineOverflow(item: MachineItem) {
        val options = when (item) {
            is MachineItem.Adb -> arrayOf("Edit", "Delete")
            is MachineItem.Ssh -> arrayOf("Edit", "Delete")
            is MachineItem.Local -> arrayOf("Edit", "Delete")
        }
        AlertDialog.Builder(this)
            .setTitle(item.displayLabel)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        when (item) {
                            is MachineItem.Adb -> editAdbDevice(item.profile)
                            is MachineItem.Ssh -> editSshProfile(item.profile)
                            is MachineItem.Local -> editLocalTerminalDialog(item.terminal)
                        }
                    }
                    1 -> {
                        when (item) {
                            is MachineItem.Adb -> {
                                store.saveProfiles(store.profiles().filter { it.id != item.profile.id })
                                renderMachineList()
                            }
                            is MachineItem.Ssh -> {
                                sshStore.delete(item.profile.id)
                                renderMachineList()
                            }
                            is MachineItem.Local -> {
                                val items = loadLocalTerminals().filterNot { it.id == item.terminal.id }
                                saveCustomLocalTerminals(items)
                                renderMachineList()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun showNewMachineDialog() {
        // Type selector first
        val types = arrayOf("ADB device", "SSH machine", "Local terminal")
        AlertDialog.Builder(this)
            .setTitle("New machine")
            .setItems(types) { _, which ->
                when (which) {
                    0 -> addAdbDeviceDialog()
                    1 -> editSshProfile(null)
                    2 -> editLocalTerminalDialog(null)
                }
            }
            .show()
    }

    private fun loadLocalTerminals(): List<LocalTermProfile> {
        val prefs = getSharedPreferences("nexus_local_terminals", Context.MODE_PRIVATE)
        val raw = prefs.getString("items", null)
        val defaults = listOf(
            LocalTermProfile("local_sh", "Android Local Shell", "/system/bin/sh"),
        )
        if (raw.isNullOrEmpty()) return defaults
        val arr = JSONArray(raw)
        val custom = (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            LocalTermProfile(
                id = o.getString("id"),
                label = o.getString("label"),
                shellCmd = o.getString("shellCmd"),
            )
        }
        return defaults + custom
    }

    private fun saveCustomLocalTerminals(items: List<LocalTermProfile>) {
        val arr = JSONArray()
        items.filterNot { it.id == "local_sh" }.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("label", t.label)
                    .put("shellCmd", t.shellCmd)
            )
        }
        getSharedPreferences("nexus_local_terminals", Context.MODE_PRIVATE)
            .edit()
            .putString("items", arr.toString())
            .apply()
    }

    private fun editLocalTerminalDialog(existing: LocalTermProfile?) {
        val labelField = field("Name (e.g. Ubuntu / Alpine / Local)", existing?.label ?: "")
        val cmdField = field("Shell / Distro command", existing?.shellCmd ?: "/system/bin/sh")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(labelField)
            addView(cmdField)
        }
        AlertDialog.Builder(this)
            .setTitle("New Terminal Profile")
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

        val askPwBox = CheckBox(this).apply {
            text = "Ask for password when logging in (never saved)"
            setTextColor(textSecondary)
            isChecked = true
        }
        val keyPathField = field("Private key file path", existing?.privateKeyPath ?: "")
        val keyStubField = field("OpenSSH *_sk stub path or text", existing?.hardwareKeyStub ?: "")
        val tagsField = field("Tags (comma separated)", existing?.tags?.joinToString(", ") ?: "")
        val timeoutField = field("Connection timeout (sec)", (existing?.timeoutSec ?: 12).toString(), numeric = true)
        val tmuxBox = CheckBox(this).apply {
            text = "Start tmux on connect"
            setTextColor(textSecondary)
            isChecked = existing?.startTmux ?: false
        }
        val tmuxPrefixField = field("Tmux prefix (e.g. Ctrl-B)", existing?.tmuxPrefix ?: "Ctrl-B")
        val moshBox = CheckBox(this).apply {
            text = "Connect with Mosh"
            setTextColor(textSecondary)
            isChecked = existing?.useMosh ?: false
        }
        val moshLocaleField = field("Mosh locale (default C.UTF-8)", existing?.moshLocale ?: "C.UTF-8")

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 12)
            addView(labelField)
            addView(hostField)
            addView(portField)
            addView(userField)
            addView(authGroup)
            addView(askPwBox)
            addView(keyPathField)
            addView(keyStubField)
            addView(tagsField)
            addView(timeoutField)
            addView(tmuxBox)
            addView(tmuxPrefixField)
            addView(moshBox)
            addView(moshLocaleField)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New machine" else "Edit machine")
            .setView(ScrollView(this).apply { addView(inner) })
            .setPositiveButton("Save") { _, _ ->
                val hostVal = hostField.text.toString().trim()
                val userVal = userField.text.toString().trim()
                if (hostVal.isEmpty() || userVal.isEmpty()) return@setPositiveButton
                val lbl = labelField.text.toString().trim().ifEmpty { "$userVal@$hostVal" }
                val prt = portField.text.toString().toIntOrNull() ?: 22
                val mode = when (authGroup.checkedRadioButtonId) {
                    rbPrivateKey.id -> SshAuthMode.PRIVATE_KEY
                    rbHardwareKey.id -> SshAuthMode.HARDWARE_KEY
                    else -> SshAuthMode.PASSWORD
                }
                val parsedTags = tagsField.text.toString()
                    .split(",")
                    .map { it.trim().removePrefix("#").trim() }
                    .filter { it.isNotEmpty() }
                val tSec = timeoutField.text.toString().toIntOrNull() ?: 12
                val tPrefix = tmuxPrefixField.text.toString().trim().ifEmpty { "Ctrl-B" }
                val locale = moshLocaleField.text.toString().trim().ifEmpty { "C.UTF-8" }

                sshStore.upsert(
                    SshHostProfile(
                        id = existing?.id ?: sshStore.newId(),
                        label = lbl,
                        host = hostVal,
                        port = prt,
                        username = userVal,
                        authMode = mode,
                        askPasswordEveryLogin = true,
                        privateKeyPath = keyPathField.text.toString().trim(),
                        hardwareKeyStub = keyStubField.text.toString().trim(),
                        tags = parsedTags,
                        timeoutSec = tSec,
                        startTmux = tmuxBox.isChecked,
                        tmuxPrefix = tPrefix,
                        useMosh = moshBox.isChecked,
                        moshLocale = locale,
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

    private fun openSshTerminal(profile: SshHostProfile) {
        sshStore.touchLastUsed(profile.id)
        val intent = Intent(this, SshTerminalActivity::class.java).apply {
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
        }
        startActivity(intent)
    }

    private fun editAdbDevice(p: Profile) {
        val label = field("Label (e.g. Redmi Note 8 Pro)", p.label)
        val host = field("IP address", p.host)
        val port = field("Port", p.port.toString(), numeric = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(label); addView(host); addView(port)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Device")
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
                val all = store.profiles().filter { it.id != p.id } + updated
                store.saveProfiles(all.sortedBy { it.label.lowercase() })
                renderMachineList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAdbDeviceDialog() {
        val label = field("Label (e.g. Redmi Note 8 Pro)", "")
        val host = field("IP address", "")
        val port = field("Port", "5555", numeric = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(label); addView(host); addView(port)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Device")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val updated = Profile(
                    UUID.randomUUID().toString(),
                    label.text.toString().trim().ifEmpty { h },
                    h,
                    port.text.toString().toIntOrNull() ?: 5555,
                )
                val all = store.profiles() + updated
                store.saveProfiles(all.sortedBy { it.label.lowercase() })
                renderMachineList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editStreamSettingsDialog() {
        val s = store.settings()
        val maxSize = field("Max size (720, 1024, 0 = native)", s.maxSize.toString(), numeric = true)
        val bitrate = field("Bitrate (2M, 4M, 8M)", Units.formatBitRate(s.bitRate))
        val fps = field("Max FPS", s.maxFps.toString(), numeric = true)
        val off = CheckBox(this).apply {
            text = "Turn target screen off automatically on connect"
            isChecked = s.screenOff
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
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

    private fun cardDrawable(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * dp
            setStroke((1 * dp).toInt(), stroke)
        }
    }

    private fun badgePill(label: String, bg: Int, fg: Int) = TextView(this).apply {
        val dp = resources.displayMetrics.density
        text = label
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(fg)
        setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
        background = cardDrawable(bg, borderCard, 8)
    }

    private fun pillButton(
        label: String, bg: Int, fg: Int, fill: Boolean = false, onClick: () -> Unit
    ) = TextView(this).apply {
        val dp = resources.displayMetrics.density
        text = label
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(fg)
        gravity = Gravity.CENTER
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        background = cardDrawable(bg, if (fill) accentPink else borderCard, 10)
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }

    // ---- MachineItem sealed class ----
    sealed class MachineItem {
        abstract val displayLabel: String
        abstract val displaySubtitle: String
        abstract val authLabel: String
        abstract val lastUsed: String
        abstract val tags: List<String>

        data class Adb(val profile: Profile) : MachineItem() {
            override val displayLabel: String = profile.label
            override val displaySubtitle: String = "${profile.host}:${profile.port}  ·  ADB"
            override val authLabel: String = "ADB key"
            override val lastUsed: String = "never"
            override val tags: List<String> = emptyList()
        }
        data class Ssh(val profile: SshHostProfile) : MachineItem() {
            override val displayLabel: String = profile.label
            override val displaySubtitle: String = "${profile.username}@${profile.host}:${profile.port}  ·  SSH"
            override val authLabel: String = if (profile.authMode == SshAuthMode.PASSWORD) "askpw" else profile.authMode.label
            override val lastUsed: String = if (profile.lastUsedAt > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(profile.lastUsedAt))
            } else { "never" }
            override val tags: List<String> = profile.tags
        }
        data class Local(val terminal: LocalTermProfile) : MachineItem() {
            override val displayLabel: String = terminal.label
            override val displaySubtitle: String = terminal.shellCmd + "  ·  Local"
            override val authLabel: String = "Local"
            override val lastUsed: String = "never"
            override val tags: List<String> = emptyList()
        }
    }
}
