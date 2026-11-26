package dev.minimal.scrcpyclient.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.adb.AdbKeyStore
import dev.minimal.scrcpyclient.adb.ScrcpyDiagnostic
import dev.minimal.scrcpyclient.data.AppStore
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import dev.minimal.scrcpyclient.data.Units
import java.util.UUID

/** Clean dark-mode launcher for wireless scrcpy device profiles, settings, and diagnostics. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var keys: AdbKeyStore
    private lateinit var list: LinearLayout
    private lateinit var logCard: LinearLayout
    private lateinit var logView: TextView
    @Volatile private var busy = false

    private val bgPage = Color.parseColor("#0D1117")
    private val bgCard = Color.parseColor("#161B22")
    private val borderCard = Color.parseColor("#30363D")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val textMuted = Color.parseColor("#484F58")
    private val accentGreen = Color.parseColor("#238636")
    private val btnNeutral = Color.parseColor("#21262D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bgPage
        window.navigationBarColor = bgPage
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        store = AppStore(this)
        keys = AdbKeyStore(this).also { it.ensureKey() }

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(textSecondary)
            setTextIsSelectable(true)
        }

        logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = cardDrawable(bgCard, borderCard, 14)
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(16) }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Diagnostics Log"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(pillButton("Clear", btnNeutral, textSecondary) {
                    logView.text = ""
                    logCard.visibility = View.GONE
                })
            }
            addView(header)
            addView(logView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(8) }
            })
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }

            addView(pillButton("+ Add Device", accentGreen, Color.WHITE, fill = true) { editProfile(null) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = px(8) }
            })
            addView(pillButton("Settings", btnNeutral, textPrimary, fill = true) { editSettings() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = px(8) }
            })
            addView(pillButton("ADB Keys", btnNeutral, textPrimary, fill = true) {
                startActivity(Intent(context, KeysActivity::class.java))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val footer = TextView(this).apply {
            text = "Powered by phyluke"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, px(28), 0, px(12))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(44), px(18), px(16))
            addView(TextView(context).apply {
                text = "Nexus"
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            addView(TextView(context).apply {
                text = "Wireless ADB Remote Control"
                textSize = 13f
                setTextColor(textSecondary)
                setPadding(0, px(2), 0, px(18))
            })
            addView(list)
            addView(actionBar)
            addView(logCard)
            addView(spacer)
            addView(footer)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgPage)
            isFillViewport = true
            addView(root)
        })
        renderList()
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
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(fg)
        gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), (9 * dp).toInt(), (14 * dp).toInt(), (9 * dp).toInt())
        background = cardDrawable(bg, if (fill && bg == accentGreen) Color.parseColor("#2EA043") else borderCard, 10)
        setOnClickListener { onClick() }
    }

    private fun renderList() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        list.removeAllViews()

        val profiles = store.profiles()
        if (profiles.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No devices configured yet. Tap + Add Device to begin."
                textSize = 13f
                setTextColor(textSecondary)
                background = cardDrawable(bgCard, borderCard, 14)
                setPadding(px(16), px(20), px(16), px(20))
            })
            return
        }

        for (p in profiles) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgCard, borderCard, 14)
                setPadding(px(16), px(14), px(16), px(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(12) }

                val infoRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(context).apply {
                            text = p.label
                            textSize = 17f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(textPrimary)
                        })
                        addView(TextView(context).apply {
                            text = "${p.host}:${p.port}"
                            textSize = 12f
                            typeface = Typeface.MONOSPACE
                            setTextColor(textSecondary)
                            setPadding(0, px(2), 0, 0)
                        })
                    })
                    addView(pillButton("Connect", accentGreen, Color.WHITE) { openStream(p) })
                }

                val subActions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = px(12) }
                    addView(pillButton("Test Link", btnNeutral, textSecondary) { connect(p) }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginEnd = px(8) }
                    })
                    addView(pillButton("Edit", btnNeutral, textSecondary) { editProfile(p) }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }

                addView(infoRow)
                addView(subActions)
            }
            list.addView(card)
        }
    }

    private fun openStream(p: Profile) {
        startActivity(Intent(this, StreamActivity::class.java).putExtra("profileId", p.id))
    }

    private fun connect(p: Profile) {
        if (busy) return
        busy = true
        logCard.visibility = View.VISIBLE
        logView.text = "Running diagnostic on ${p.host}:${p.port}...\n"
        val settings = store.settings()
        Thread {
            ScrcpyDiagnostic(applicationContext, keys).run(p, settings) { line ->
                runOnUiThread { logView.append(line + "\n") }
            }
            busy = false
        }.start()
    }

    private fun editProfile(existing: Profile?) {
        val label = field("Label (e.g. Redmi Note 8 Pro)", existing?.label ?: "")
        val host = field("IP address", existing?.host ?: "")
        val port = field("Port", (existing?.port ?: 5555).toString(), numeric = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(label); addView(host); addView(port)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Device" else "Edit Device")
            .setView(box)
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
            store.saveProfiles(store.profiles().filter { it.id != existing.id })
            renderList()
        }
        b.show()
    }

    private fun editSettings() {
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

    private fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }
}

