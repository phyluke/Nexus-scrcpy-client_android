package dev.minimal.scrcpyclient.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.data.AppStore
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import dev.minimal.scrcpyclient.data.Units
import java.util.UUID

/** Full-screen scrcpy session launcher with `+` to add ADB hosts and `>` to start streaming. */
class ScrcpyFullScreenActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var sessionList: LinearLayout

    private val bgPage = Color.parseColor("#0D1117")
    private val bgCard = Color.parseColor("#161B22")
    private val borderCard = Color.parseColor("#30363D")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val accentGreen = Color.parseColor("#238636")
    private val btnNeutral = Color.parseColor("#21262D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bgPage
        window.navigationBarColor = bgPage
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        store = AppStore(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pillButton("<- Back", btnNeutral, textPrimary) { finish() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(8) }
            })
            addView(TextView(context).apply {
                text = "scrcpy Sessions"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pillButton("+", accentGreen, Color.WHITE, fill = true) { showAddHostDialog() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(6) }
            })
            addView(pillButton("ADB Keys", btnNeutral, textPrimary) {
                startActivity(Intent(context, KeysActivity::class.java))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(6) }
            })
            addView(pillButton("Stream Settings", btnNeutral, textPrimary) {
                editStreamSettings()
            })
        }

        sessionList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(38), px(18), px(20))
            addView(topBar)
            addView(sessionList)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgPage)
            isFillViewport = true
            addView(root)
        })
        renderSessions()
    }

    override fun onResume() {
        super.onResume()
        renderSessions()
    }

    private fun renderSessions() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        sessionList.removeAllViews()

        val profiles = store.profiles()
        if (profiles.isEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = "No ADB sessions saved. Tap + to add a host."
                textSize = 13f
                setTextColor(textSecondary)
                background = cardDrawable(bgCard, borderCard, 12)
                setPadding(px(14), px(16), px(14), px(16))
            })
            return
        }

        for (p in profiles) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(bgCard, borderCard, 12)
                setPadding(px(14), px(12), px(14), px(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(10) }

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = p.label
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textPrimary)
                    })
                    addView(TextView(context).apply {
                        text = "${p.host}:${p.port}"
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(textSecondary)
                    })
                })

                addView(pillButton(">", accentGreen, Color.WHITE, fill = true) {
                    startActivity(Intent(context, StreamActivity::class.java).putExtra("profileId", p.id))
                })
            }
            sessionList.addView(card)
        }
    }

    private fun showAddHostDialog() {
        val labelField = EditText(this).apply { hint = "Label (e.g. Redmi Note 8 Pro)" }
        val hostField = EditText(this).apply { hint = "Host or IP" }
        val portField = EditText(this).apply {
            hint = "Port (5555)"
            setText("5555")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(labelField)
            addView(hostField)
            addView(portField)
        }
        AlertDialog.Builder(this)
            .setTitle("Add ADB Host")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val h = hostField.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val lbl = labelField.text.toString().trim().ifEmpty { h }
                val prt = portField.text.toString().toIntOrNull() ?: 5555
                val updated = store.profiles() + Profile(UUID.randomUUID().toString(), lbl, h, prt)
                store.saveProfiles(updated.sortedBy { it.label.lowercase() })
                renderSessions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editStreamSettings() {
        val s = store.settings()
        val maxSize = EditText(this).apply {
            hint = "Max size (720, 1024, 0 = native)"
            setText(s.maxSize.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val bitrate = EditText(this).apply {
            hint = "Bitrate (2M, 4M, 8M)"
            setText(Units.formatBitRate(s.bitRate))
        }
        val fps = EditText(this).apply {
            hint = "Max FPS"
            setText(s.maxFps.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
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
            .setTitle("Stream Settings")
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

    private fun pillButton(
        label: String, bg: Int, fg: Int, fill: Boolean = false, onClick: () -> Unit
    ) = TextView(this).apply {
        val dp = resources.displayMetrics.density
        text = label
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(fg)
        gravity = Gravity.CENTER
        setPadding((11 * dp).toInt(), (8 * dp).toInt(), (11 * dp).toInt(), (8 * dp).toInt())
        background = cardDrawable(bg, borderCard, 10)
        setOnClickListener { onClick() }
    }
}