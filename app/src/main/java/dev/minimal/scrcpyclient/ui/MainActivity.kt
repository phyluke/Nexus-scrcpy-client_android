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

    internal data class LocalTermProfile(val id: String, val label: String, val shellCmd: String)

    private lateinit var store: AppStore
    private lateinit var sshStore: SshProfileStore

    private lateinit var savedCountView: TextView
    private lateinit var liveCountView: TextView

    private lateinit var machineSearchInput: EditText
    private lateinit var machineList: LinearLayout
    private var machineFilterQuery: String = ""

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
        val px: (Int) -> Int = { (it * dp).toInt() }

        // 1. Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
                })
            })
            addView(pillButton("...", btnNeutral, textPrimary) { showTopOverflowMenu() })
        }

        // 2. Stat cards
        savedCountView = TextView(this).apply { text = "0"; textSize = 24f; setTypeface(null, Typeface.BOLD); setTextColor(textPrimary) }
        liveCountView = TextView(this).apply { text = "0"; textSize = 24f; setTypeface(null, Typeface.BOLD); setTextColor(textPrimary) }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) }
            addView(statCard("Saved machines", savedCountView, px))
            addView(statCard("Live sessions", liveCountView, px))
        }

        // 3. Resume / Machines / Search
        val resumeCard = buildResumeCard(px)
        
        val machinesHeader = TextView(this).apply {
            text = "Machines" // Count updated in refreshStats
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) }
        }

        machineSearchInput = EditText(this).apply {
            hint = "Search machines, hosts, tags..."
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 12f
            background = cardDrawable(bgCard, borderCard, 10)
            setPadding(px(12), px(9), px(12), px(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
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

        setContentView(ScrollView(this).apply { setBackgroundColor(bgPage); isFillViewport = true; addView(root) })
        renderMachineList()
    }

    override fun onResume() { super.onResume(); if (::machineList.isInitialized) renderMachineList() }

    private fun statCard(title: String, countView: TextView, px: (Int) -> Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) }
            addView(TextView(context).apply { text = title; textSize = 11f; setTextColor(textSecondary) })
            addView(countView.apply { setPadding(0, px(4), 0, 0) })
        }
    }

    private fun buildResumeCard(px: (Int) -> Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) }
            addView(TextView(context).apply { text = "Resume sessions"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(textPrimary) })
        }
    }

    private fun refreshStats() {
        savedCountView.text = (sshStore.profiles().size + loadLocalTerminals().size).toString()
        liveCountView.text = store.profiles().size.toString()
    }

    private fun showTopOverflowMenu() {
        val options = arrayOf("Add Device", "Settings")
        AlertDialog.Builder(this).setTitle("Nexus").setItems(options) { _, which ->
            when (which) {
                0 -> addAdbDeviceDialog()
                1 -> editStreamSettingsDialog()
            }
        }.show()
    }

    private fun renderMachineList() {
        val px: (Int) -> Int = { (it * resources.displayMetrics.density).toInt() }
        machineList.removeAllViews()
        refreshStats()
        val q = machineFilterQuery.lowercase()
        val items = mutableListOf<MachineItem>()
        for (p in store.profiles()) if (p.label.lowercase().contains(q)) items.add(MachineItem.Adb(p))
        for (h in sshStore.profiles()) if (h.label.lowercase().contains(q)) items.add(MachineItem.Ssh(h))
        for (t in loadLocalTerminals()) if (t.label.lowercase().contains(q)) items.add(MachineItem.Local(t))
        for (item in items) machineList.addView(buildMachineRow(item, px))
    }

    private fun buildMachineRow(item: MachineItem, px: (Int) -> Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 12)
            setPadding(px(12), px(11), px(12), px(11))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }
            // Simplified row logic
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = item.displayLabel; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(textPrimary); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(pillButton("Connect", accentPink, Color.WHITE, fill = true) { /* connect action */ })
            }
            addView(top)
        }
    }

    private fun showMachineOverflow(item: MachineItem) { /* impl */ }
    private fun showNewMachineDialog() { /* impl */ }
    private fun loadLocalTerminals(): List<LocalTermProfile> = listOf(LocalTermProfile("local_sh", "Android", "/system/bin/sh"))
    private fun saveCustomLocalTerminals(items: List<LocalTermProfile>) { }
    private fun editLocalTerminalDialog(existing: LocalTermProfile?) { }
    private fun editSshProfile(existing: SshHostProfile?) { }
    private fun openSshTerminal(profile: SshHostProfile) { }
    private fun editAdbDevice(p: Profile) { }
    private fun addAdbDeviceDialog() { }
    private fun editStreamSettingsDialog() { }
    
    private fun cardDrawable(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply { setColor(fill); cornerRadius = radiusDp * dp; setStroke(1, stroke) }
    }

    private fun pillButton(label: String, bg: Int, fg: Int, fill: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        val dp = resources.displayMetrics.density
        text = label; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(fg); gravity = Gravity.CENTER
        setPadding(px(12), px(8), px(12), px(8)); background = cardDrawable(bg, borderCard, 10)
        setOnClickListener { onClick() }
    }
    
    private fun modeTile(title: String, subtitle: String, color: Int, onClick: () -> Unit, px: (Int) -> Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = cardDrawable(bgCard, borderCard, 14); setPadding(px(14), px(14), px(14), px(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) }
            addView(TextView(context).apply { text = title; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(textPrimary) })
            setOnClickListener { onClick() }
        }
    }

    private fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply { this.hint = hint; setText(value); if (numeric) inputType = InputType.TYPE_CLASS_NUMBER }

    sealed class MachineItem {
        abstract val displayLabel: String
        data class Adb(val profile: Profile) : MachineItem() { override val displayLabel = profile.label }
        data class Ssh(val profile: SshHostProfile) : MachineItem() { override val displayLabel = profile.label }
        data class Local(val terminal: LocalTermProfile) : MachineItem() { override val displayLabel = terminal.label }
    }
}
