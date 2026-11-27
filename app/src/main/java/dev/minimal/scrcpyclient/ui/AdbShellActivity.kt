package dev.minimal.scrcpyclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.adb.AdbKeyStore
import dev.minimal.scrcpyclient.adb.AdbToolkit
import dev.minimal.scrcpyclient.data.AppStore
import java.util.Locale

/** Full-screen ADB shell terminal view for a target wireless ADB device. */
class AdbShellActivity : AppCompatActivity() {

    private lateinit var keys: AdbKeyStore
    private lateinit var outputView: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var commandInput: EditText
    @Volatile private var running = false

    private var host: String = ""
    private var port: Int = 5555
    private var label: String = "Device"

    private val bgPage = Color.parseColor("#0D1117")
    private val bgTerminal = Color.parseColor("#090C10")
    private val bgCard = Color.parseColor("#161B22")
    private val borderCard = Color.parseColor("#30363D")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val textMuted = Color.parseColor("#484F58")
    private val accentBlue = Color.parseColor("#1F6FEB")
    private val accentGreen = Color.parseColor("#238636")
    private val btnNeutral = Color.parseColor("#21262D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bgPage
        window.navigationBarColor = bgPage
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        keys = AdbKeyStore(this).also { it.ensureKey() }

        val firstProfile = AppStore(this).profiles().firstOrNull()
        host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty().ifEmpty { firstProfile?.host.orEmpty() }
        port = intent.getIntExtra(EXTRA_PORT, firstProfile?.port ?: 5555)
        label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty().ifEmpty {
            if (host.isNotEmpty()) host else "ADB Target"
        }

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            addView(pillButton("Back", btnNeutral, textPrimary) { finish() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(10) }
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "ADB Shell — $label"
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    text = if (host.isNotEmpty()) "$host:$port" else "No target configured"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(textSecondary)
                })
            })

            addView(pillButton("Copy", btnNeutral, textSecondary) { copyOutput() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(6) }
            })
            addView(pillButton("Clear", btnNeutral, textSecondary) {
                outputView.text = ""
            })
        }

        val presets = listOf(
            "Device Info" to "echo MODEL=\$(getprop ro.product.model); echo ANDROID=\$(getprop ro.build.version.release); wm size",
            "Battery" to "dumpsys battery | grep -E 'level|status|temperature'",
            "IP / Net" to "ip -4 addr show wlan0",
            "ps -A" to "ps -A | head -n 30",
            "3P Apps" to "pm list packages -3",
            "Home" to "input keyevent 3",
            "Power" to "input keyevent 26",
        )
        val presetStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, px(8))
        }
        presets.forEachIndexed { idx, (chipLabel, cmd) ->
            presetStrip.addView(pillButton(chipLabel, btnNeutral, textSecondary) {
                commandInput.setText(cmd)
                executeCommand(cmd)
            }.apply {
                if (idx < presets.lastIndex) {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(6) }
                }
            })
        }

        outputView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextIsSelectable(true)
            setPadding(px(12), px(12), px(12), px(12))
        }

        outputScroll = ScrollView(this).apply {
            background = cardDrawable(bgTerminal, borderCard, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = px(10) }
            addView(outputView)
        }

        commandInput = EditText(this).apply {
            hint = "Enter ADB shell command..."
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            background = cardDrawable(bgCard, borderCard, 10)
            setPadding(px(12), px(10), px(12), px(10))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginEnd = px(8) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val cmd = text.toString().trim()
                    if (cmd.isNotEmpty()) executeCommand(cmd)
                    true
                } else {
                    false
                }
            }
        }

        val sendBtn = pillButton("Run", accentGreen, Color.WHITE, fill = true) {
            val cmd = commandInput.text.toString().trim()
            if (cmd.isNotEmpty()) executeCommand(cmd)
        }

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(commandInput)
            addView(sendBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(36), px(16), px(16))
            addView(headerRow)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(presetStrip)
            })
            addView(outputScroll)
            addView(inputBar)
        }

        setContentView(root.apply { setBackgroundColor(bgPage) })

        if (host.isEmpty()) {
            appendLine("No ADB host configured. Go back, select or add a device, and tap Enter Shell.")
        } else {
            appendLine("Full ADB Shell ready for $label ($host:$port).")
        }
    }

    private fun executeCommand(cmd: String) {
        if (host.isEmpty()) {
            appendLine("No ADB host configured.")
            return
        }
        if (running) {
            appendLine("A command is already running...")
            return
        }
        running = true
        val promptHost = if (host.isNotEmpty()) "$host:$port" else "adb"
        appendLine("\n$ $promptHost > $cmd")
        Thread {
            try {
                AdbToolkit.runShell(keys, host, port, cmd) { line ->
                    appendLine(line)
                }
            } catch (e: Exception) {
                appendLine("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
            }
        }.start()
    }

    private fun appendLine(line: String) {
        runOnUiThread {
            outputView.append(line + "\n")
            outputScroll.post { outputScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun copyOutput() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Nexus ADB Shell", outputView.text.toString()))
        appendLine("[Copied shell output to clipboard]")
    }

    private fun cardDrawable(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * dp
            setStroke((1 * dp).toInt(), stroke)
        }
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
        setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (9 * dp).toInt())
        background = cardDrawable(
            bg,
            if (fill && bg == accentGreen) Color.parseColor("#2EA043") else if (fill && bg == accentBlue) Color.parseColor("#388BFD") else borderCard,
            10
        )
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_LABEL = "label"
    }
}