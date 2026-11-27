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

/** Stage 1 Nexus main screen: Header + (...), 2 summary tiles, Terminal card, SSH card, ADB/scrcpy tiles, footer. */
class MainActivity : AppCompatActivity() {

    private data class LocalTermProfile(
        val id: String,
        val label: String,
        val shellCmd: String,
    )

    private lateinit var store: AppStore
    private lateinit var sshStore: SshProfileStore

    private lateinit var savedCountView: TextView
    private lateinit var liveAdbCountView: TextView

    private lateinit var termSearchInput: EditText
    private lateinit var termList: LinearLayout
    private var termFilterQuery: String = ""

    private lateinit var sshSearchInput: EditText
    private lateinit var sshList: LinearLayout
    private var sshFilterQuery: String = ""

    private val bgPage = Color.parseColor("#0D1117")
    private val bgCard = Color.parseColor("#161B22")
    private val bgInput = Color.parseColor("#0D1117")
    private val borderCard = Color.parseColor("#30363D")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val textMuted = Color.parseColor("#484F58")
    private val accentGreen = Color.parseColor("#238636")
    private val accentBlue = Color.parseColor("#1F6FEB")
    private val accentPink = Color.parseColor("#DB61A2")
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

        // 1. Top Header: Nexus (top-left) + (...) menu (top-right: Add Device, Settings)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "Nexus"
                    textSize = 26f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(TextView(context).apply {
                    text = "SSH workspaces - Terminal - ADB - scrcpy"
                    textSize = 13f
                    setTextColor(textSecondary)
                    setPadding(0, px(2), 0, 0)
                })
            })
            addView(pillButton("...", btnNeutral, textPrimary) {
                showTopOverflowMenu()
            })
        }

        // 2. Two tiles side by side: Saved machines | Live / ADB
        savedCountView = TextView(this).apply {
            text = "0"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        liveAdbCountView = TextView(this).apply {
            text = "0"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgCard, borderCard, 12)
                setPadding(px(14), px(10), px(14), px(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = px(8) }
                addView(TextView(context).apply {
                    text = "Saved machines"
                    textSize = 11f
                    setTextColor(textSecondary)
                })
                addView(savedCountView)
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgCard, borderCard, 12)
                setPadding(px(14), px(10), px(14), px(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "Live / ADB"
                    textSize = 11f
                    setTextColor(textSecondary)
                })
                addView(liveAdbCountView)
            })
        }

        // 3. Terminal (local & Distro) card (replaces Machines card)
        termList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val terminalCard = buildTerminalCard(dp)

        // 4. SSH machines card (replaces scrcpy card)
        sshList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sshMachinesCard = buildSshMachinesCard(dp)

        // 5. Two tiles side by side: ADB | scrcpy
        val modeTilesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgCard, borderCard, 14)
                setPadding(px(14), px(14), px(14), px(14))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = px(8) }
                addView(TextView(context).apply {
                    text = "ADB"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(TextView(context).apply {
                    text = "Saved hosts & shell"
                    textSize = 12f
                    setTextColor(textSecondary)
                    setPadding(0, px(2), 0, 0)
                })
                setOnClickListener {
                    startActivity(Intent(context, AdbFullScreenActivity::class.java))
                }
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgCard, borderCard, 14)
                setPadding(px(14), px(14), px(14), px(14))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "scrcpy"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(TextView(context).apply {
                    text = "Live mirror & stream"
                    textSize = 12f
                    setTextColor(textSecondary)
                    setPadding(0, px(2), 0, 0)
                })
                setOnClickListener {
                    startActivity(Intent(context, ScrcpyFullScreenActivity::class.java))
                }
            })
        }

        // 6. Bottom middle footer: powered by phyluke
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
            setPadding(px(18), px(40), px(18), px(16))
            addView(headerRow)
            addView(statsRow)
            addView(terminalCard)
            addView(sshMachinesCard)
            addView(modeTilesRow)
            addView(footer)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgPage)
            isFillViewport = true
            addView(root)
        })

        renderTerminalList()
        renderSshList()
    }

    override fun onResume() {
        super.onResume()
        if (::termList.isInitialized) renderTerminalList()
        if (::sshList.isInitialized) renderSshList()
    }

    private fun refreshStats() {
        savedCountView.text = (sshStore.profiles().size + loadLocalTerminals().size).toString()
        liveAdbCountView.text = store.profiles().size.toString()
    }

    private fun showTopOverflowMenu() {
        val options = arrayOf("Add Device", "Settings")
        AlertDialog.Builder(this)
            .setTitle("Nexus")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addAdbDeviceDialog()
                    1 -> editStreamSettingsDialog()
                }
            }
            .show()
    }

    private fun buildTerminalCard(dp: Float): LinearLayout {
        fun px(v: Int) = (v * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 14)
            setPadding(px(14), px(14), px(14), px(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Terminal (local & Distro)"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(pillButton("+ New", accentBlue, Color.WHITE, fill = true) {
                    editLocalTerminalDialog(null)
                })
            }
            addView(topRow)

            termSearchInput = EditText(context).apply {
                hint = "Search / Local / Distro"
                setHintTextColor(textMuted)
                setTextColor(textPrimary)
                textSize = 12f
                background = cardDrawable(bgInput, borderCard, 10)
                setPadding(px(12), px(9), px(12), px(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(10) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        termFilterQuery = s?.toString()?.trim().orEmpty()
                        renderTerminalList()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            addView(termSearchInput)

            addView(termList.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(10) }
            })
        }
    }

    private fun renderTerminalList() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        termList.removeAllViews()
        refreshStats()

        val q = termFilterQuery.lowercase()
        val all = loadLocalTerminals()
        val filtered = if (q.isEmpty()) all else all.filter {
            it.label.lowercase().contains(q) || it.shellCmd.lowercase().contains(q)
        }

        for (t in filtered) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(bgInput, borderCard, 12)
                setPadding(px(12), px(10), px(12), px(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(8) }

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = t.label
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textPrimary)
                    })
                    addView(TextView(context).apply {
                        text = t.shellCmd
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setTextColor(textSecondary)
                    })
                })
                addView(pillButton("Open", accentGreen, Color.WHITE, fill = true) {
                    startActivity(
                        Intent(context, AdbShellActivity::class.java)
                            .putExtra(AdbShellActivity.EXTRA_LABEL, t.label)
                    )
                })
            }
            termList.addView(row)
        }
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
                renderTerminalList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildSshMachinesCard(dp: Float): LinearLayout {
        fun px(v: Int) = (v * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 14)
            setPadding(px(14), px(14), px(14), px(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "SSH machines"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(pillButton("+ New", accentPink, Color.WHITE, fill = true) {
                    editSshProfile(null)
                })
            }
            addView(topRow)

            sshSearchInput = EditText(context).apply {
                hint = "Search machines, hosts, tags..."
                setHintTextColor(textMuted)
                setTextColor(textPrimary)
                textSize = 12f
                background = cardDrawable(bgInput, borderCard, 10)
                setPadding(px(12), px(9), px(12), px(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(10) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        sshFilterQuery = s?.toString()?.trim().orEmpty()
                        renderSshList()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            addView(sshSearchInput)

            addView(sshList.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(10) }
            })
        }
    }

    private fun renderSshList() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        sshList.removeAllViews()
        refreshStats()

        val q = sshFilterQuery.lowercase()
        val allHosts = sshStore.profiles()
        val hosts = if (q.isEmpty()) allHosts else allHosts.filter { h ->
            h.label.lowercase().contains(q) ||
                h.host.lowercase().contains(q) ||
                h.username.lowercase().contains(q) ||
                h.tags.any { it.lowercase().contains(q) }
        }

        if (hosts.isEmpty()) {
            sshList.addView(TextView(this).apply {
                text = if (allHosts.isEmpty()) {
                    "No SSH machines saved yet. Tap + New to add one."
                } else {
                    "No matching machines for \"$sshFilterQuery\"."
                }
                textSize = 12f
                setTextColor(textSecondary)
                background = cardDrawable(bgInput, borderCard, 10)
                setPadding(px(12), px(12), px(12), px(12))
            })
            return
        }

        for (h in hosts) {
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgInput, borderCard, 12)
                setPadding(px(12), px(11), px(12), px(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(8) }

                val top = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(context).apply {
                            text = h.label
                            textSize = 15f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(textPrimary)
                        })
                        addView(TextView(context).apply {
                            text = "${h.username}@${h.host}:${h.port}"
                            textSize = 12f
                            typeface = Typeface.MONOSPACE
                            setTextColor(textSecondary)
                        })
                    })
                    addView(pillButton("Connect", accentGreen, Color.WHITE, fill = true) {
                        openSshTerminal(h)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = px(6) }
                    })
                    addView(pillButton("Edit", btnNeutral, textSecondary) {
                        editSshProfile(h)
                    })
                }
                addView(top)

                val metaRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, px(8), 0, 0)
                }
                val authText = if (h.authMode == SshAuthMode.PASSWORD) "askpw" else h.authMode.label
                metaRow.addView(badgePill(authText, btnNeutral, textSecondary).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(6) }
                })
                if (h.startTmux) {
                    metaRow.addView(badgePill("tmux:${h.tmuxPrefix}", btnNeutral, textSecondary).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = px(6) }
                    })
                }
                val lastUsedStr = if (h.lastUsedAt > 0L) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.lastUsedAt))
                } else {
                    "never"
                }
                metaRow.addView(badgePill(lastUsedStr, btnNeutral, textMuted))
                addView(metaRow)

                if (h.tags.isNotEmpty()) {
                    val tagStrip = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, px(6), 0, 0)
                    }
                    h.tags.forEachIndexed { i, tag ->
                        tagStrip.addView(badgePill("# $tag", bgCard, textSecondary).apply {
                            if (i < h.tags.lastIndex) {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { marginEnd = px(6) }
                            }
                        })
                    }
                    addView(HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(tagStrip)
                    })
                }
            }
            sshList.addView(rowCard)
        }
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
                renderSshList()
            }
            .setNegativeButton("Cancel", null)

        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                sshStore.delete(existing.id)
                renderSshList()
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
                refreshStats()
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
        background = cardDrawable(bg, if (fill && bg == accentGreen) Color.parseColor("#2EA043") else borderCard, 10)
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }
}