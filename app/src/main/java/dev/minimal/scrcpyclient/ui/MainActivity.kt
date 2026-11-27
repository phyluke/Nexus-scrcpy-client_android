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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.adb.AdbKeyStore
import dev.minimal.scrcpyclient.adb.AdbToolkit
import dev.minimal.scrcpyclient.adb.ScrcpyDiagnostic
import dev.minimal.scrcpyclient.data.AppStore
import dev.minimal.scrcpyclient.data.Profile
import dev.minimal.scrcpyclient.data.StreamSettings
import dev.minimal.scrcpyclient.data.Units
import java.io.File
import java.util.UUID

/** Main hub for scrcpy streaming, full ADB toolkit (shell, TCP/wireless ADB, push/install), and diagnostics. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var keys: AdbKeyStore
    private lateinit var list: LinearLayout
    private lateinit var logCard: LinearLayout
    private lateinit var logView: TextView
    private lateinit var cmdInput: EditText
    private lateinit var targetSummary: TextView
    private var selectedProfile: Profile? = null
    @Volatile private var busy = false

    private val bgPage = Color.parseColor("#0D1117")
    private val bgCard = Color.parseColor("#161B22")
    private val bgInput = Color.parseColor("#0D1117")
    private val borderCard = Color.parseColor("#30363D")
    private val textPrimary = Color.parseColor("#F0F6FC")
    private val textSecondary = Color.parseColor("#8B949E")
    private val textMuted = Color.parseColor("#484F58")
    private val accentGreen = Color.parseColor("#238636")
    private val accentBlue = Color.parseColor("#1F6FEB")
    private val btnNeutral = Color.parseColor("#21262D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bgPage
        window.navigationBarColor = bgPage
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        store = AppStore(this)
        keys = AdbKeyStore(this).also { it.ensureKey() }
        selectedProfile = store.profiles().firstOrNull()

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
            ).apply { topMargin = px(14) }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "ADB / Diagnostics Output"
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

        val adbHubCard = buildAdbHubCard(dp)

        val footer = TextView(this).apply {
            text = "Nexus Hub â€¢ phyluke"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, px(24), 0, px(12))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(40), px(18), px(16))
            addView(TextView(context).apply {
                text = "Nexus"
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            addView(TextView(context).apply {
                text = "scrcpy â€¢ ADB â€¢ Terminal Hub"
                textSize = 13f
                setTextColor(textSecondary)
                setPadding(0, px(2), 0, px(16))
            })
            addView(list)
            addView(actionBar)
            addView(adbHubCard)
            addView(logCard)
            addView(footer)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgPage)
            isFillViewport = true
            addView(root)
        })
        renderList()
    }

    private fun buildAdbHubCard(dp: Float): LinearLayout {
        fun px(v: Int) = (v * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(bgCard, borderCard, 14)
            setPadding(px(14), px(14), px(14), px(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }

            addView(TextView(context).apply {
                text = "ADB Command & Wireless Hub"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })

            targetSummary = TextView(context).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(textSecondary)
                setPadding(0, px(2), 0, px(10))
            }
            updateTargetSummary()
            addView(targetSummary)

            cmdInput = EditText(context).apply {
                hint = "ADB shell cmd (e.g. getprop ro.product.model)"
                setHintTextColor(textMuted)
                setTextColor(textPrimary)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                background = cardDrawable(bgInput, borderCard, 8)
                setPadding(px(10), px(8), px(10), px(8))
            }
            addView(cmdInput)

            val runRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(8) }

                addView(pillButton("Run Shell", accentBlue, Color.WHITE, fill = true) {
                    val c = cmdInput.text.toString().trim()
                    if (c.isNotEmpty()) runShellOnTarget(c)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = px(6) }
                })
                addView(pillButton("Enter Shell", accentGreen, Color.WHITE, fill = true) {
                    openFullShell()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = px(6) }
                })
                addView(pillButton("Device Info", btnNeutral, textPrimary) {
                    runDeviceInfoOnTarget()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = px(6) }
                })
                addView(pillButton("TCP/Pair", btnNeutral, textPrimary) {
                    showTcpWirelessDialog()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            addView(runRow)

            val fileRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(8) }

                addView(pillButton("Push File", btnNeutral, textSecondary) {
                    showPushFileDialog(installAfterPush = false)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = px(8) }
                })
                addView(pillButton("Install APK", btnNeutral, textSecondary) {
                    showPushFileDialog(installAfterPush = true)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            addView(fileRow)

            val presets = listOf(
                "Battery" to "dumpsys battery | grep -E 'level|status|temperature'",
                "WM Size" to "wm size",
                "IP / Net" to "ip -4 addr show wlan0",
                "Home Key" to "input keyevent 3",
                "Power Key" to "input keyevent 26",
                "3P Apps" to "pm list packages -3",
            )
            val chipStrip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(10), 0, 0)
            }
            presets.forEachIndexed { idx, (label, shellCmd) ->
                chipStrip.addView(pillButton(label, btnNeutral, textSecondary) {
                    cmdInput.setText(shellCmd)
                    runShellOnTarget(shellCmd)
                }.apply {
                    if (idx < presets.lastIndex) {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = px(6) }
                    }
                })
            }
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipStrip)
            })
        }
    }

    private fun updateTargetSummary() {
        val p = selectedProfile ?: store.profiles().firstOrNull()
        selectedProfile = p
        targetSummary.text = if (p != null) {
            "Active ADB Target: ${p.label} (${p.host}:${p.port})"
        } else {
            "Active ADB Target: None (tap + Add Device or TCP/Pair)"
        }
    }

    private fun requireTarget(): Profile? {
        val p = selectedProfile ?: store.profiles().firstOrNull()
        selectedProfile = p
        if (p == null) {
            openLog("No target device configured. Add a device or use TCP/Pair first.")
            return null
        }
        return p
    }

    private fun openLog(initial: String) {
        logCard.visibility = View.VISIBLE
        logView.text = initial + "\n"
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logCard.visibility = View.VISIBLE
            logView.append(line + "\n")
        }
    }

    private fun openFullShell() {
        val p = selectedProfile ?: store.profiles().firstOrNull()
        val intent = Intent(this, AdbShellActivity::class.java).apply {
            if (p != null) {
                putExtra(AdbShellActivity.EXTRA_HOST, p.host)
                putExtra(AdbShellActivity.EXTRA_PORT, p.port)
                putExtra(AdbShellActivity.EXTRA_LABEL, p.label)
            }
        }
        startActivity(intent)
    }

    private fun runShellOnTarget(command: String) {
        val p = requireTarget() ?: return
        if (busy) return
        busy = true
        openLog("$ ${p.host}:${p.port} > $command")
        Thread {
            try {
                AdbToolkit.runShell(keys, p.host, p.port, command) { line ->
                    appendLog(line)
                }
            } catch (e: Exception) {
                appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun runDeviceInfoOnTarget() {
        val p = requireTarget() ?: return
        if (busy) return
        busy = true
        openLog("Collecting device info from ${p.host}:${p.port} ...")
        Thread {
            try {
                AdbToolkit.collectDeviceInfo(keys, p.host, p.port) { line ->
                    appendLog(line)
                }
            } catch (e: Exception) {
                appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun showTcpWirelessDialog() {
        val current = selectedProfile ?: store.profiles().firstOrNull()
        val hostField = field("Target IP / Host", current?.host ?: "")
        val portField = field("Connect Port (e.g. 5555 or wireless port)", (current?.port ?: 5555).toString(), numeric = true)
        val tcpipPortField = field("Switch adbd to TCP Port (e.g. 5555)", "5555", numeric = true)
        val pairPortField = field("Wireless Pair Port (optional)", "", numeric = true)
        val pairCodeField = field("Wireless Pairing Code (optional)", "")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(hostField)
            addView(portField)
            addView(tcpipPortField)
            addView(pairPortField)
            addView(pairCodeField)
        }

        AlertDialog.Builder(this)
            .setTitle("TCP / Wireless ADB")
            .setView(box)
            .setPositiveButton("Test & Save") { _, _ ->
                val h = hostField.text.toString().trim()
                val p = portField.text.toString().toIntOrNull() ?: 5555
                if (h.isEmpty()) return@setPositiveButton
                val profile = Profile(
                    current?.id ?: UUID.randomUUID().toString(),
                    current?.label ?: h,
                    h,
                    p,
                )
                selectedProfile = profile
                updateTargetSummary()
                if (busy) return@setPositiveButton
                busy = true
                openLog("Testing TCP reachability to $h:$p ...")
                Thread {
                    try {
                        val reachable = AdbToolkit.testTcpPort(h, p)
                        appendLog("TCP $h:$p reachable = $reachable")
                        if (reachable) {
                            AdbToolkit.withConnection(keys, h, p, ::appendLog) {
                                appendLog("ADB handshake OK on $h:$p")
                            }
                        }
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        busy = false
                    }
                }.start()
            }
            .setNeutralButton("Switch TCP Port") { _, _ ->
                val h = hostField.text.toString().trim()
                val curPort = portField.text.toString().toIntOrNull() ?: 5555
                val targetTcp = tcpipPortField.text.toString().toIntOrNull() ?: 5555
                if (h.isEmpty() || busy) return@setNeutralButton
                busy = true
                openLog("Switching $h:$curPort adbd to TCP port $targetTcp ...")
                Thread {
                    try {
                        AdbToolkit.switchToTcpip(keys, h, curPort, targetTcp, ::appendLog)
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        busy = false
                    }
                }.start()
            }
            .setNegativeButton("Pair") { _, _ ->
                val h = hostField.text.toString().trim()
                val pairPort = pairPortField.text.toString().toIntOrNull() ?: 0
                val code = pairCodeField.text.toString().trim()
                if (h.isEmpty() || pairPort <= 0 || code.isEmpty() || busy) return@setNegativeButton
                busy = true
                openLog("Attempting wireless pairing at $h:$pairPort ...")
                Thread {
                    try {
                        AdbToolkit.pairWireless(keys, h, pairPort, code, ::appendLog)
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        busy = false
                    }
                }.start()
            }
            .show()
    }

    private fun showPushFileDialog(installAfterPush: Boolean) {
        val p = requireTarget() ?: return
        val localPathField = field(
            if (installAfterPush) "Local APK path (e.g. /sdcard/Download/app.apk)" else "Local file path (e.g. /sdcard/Download/file.txt)",
            ""
        )
        val remotePathField = field(
            "Remote path on target",
            if (installAfterPush) "/data/local/tmp/nexus_install_tmp.apk" else "/sdcard/Download/"
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
            addView(localPathField)
            if (!installAfterPush) addView(remotePathField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (installAfterPush) "Install APK Over ADB" else "Push File Over ADB Sync")
            .setView(box)
            .setPositiveButton(if (installAfterPush) "Install" else "Push") { _, _ ->
                val localPath = localPathField.text.toString().trim()
                if (localPath.isEmpty() || busy) return@setPositiveButton
                val file = File(localPath)
                if (!file.exists() || !file.isFile) {
                    openLog("Local file not found: $localPath")
                    return@setPositiveButton
                }
                val rawRemote = remotePathField.text.toString().trim()
                val finalRemote = if (rawRemote.endsWith("/")) rawRemote + file.name else rawRemote
                busy = true
                openLog("Preparing transfer from ${file.absolutePath} ...")
                Thread {
                    try {
                        val bytes = file.readBytes()
                        if (installAfterPush) {
                            AdbToolkit.installApk(keys, p.host, p.port, bytes, ::appendLog)
                        } else {
                            AdbToolkit.pushBytes(keys, p.host, p.port, bytes, finalRemote, 33188, ::appendLog)
                        }
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        busy = false
                    }
                }.start()
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
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
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
            updateTargetSummary()
            return
        }

        for (p in profiles) {
            val isSelected = selectedProfile?.id == p.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(bgCard, if (isSelected) accentBlue else borderCard, 14)
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
                            text = if (isSelected) "${p.label}  [ADB]" else p.label
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
                    addView(pillButton("Connect", accentGreen, Color.WHITE) {
                        selectedProfile = p
                        updateTargetSummary()
                        openStream(p)
                    })
                }

                val subActions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = px(12) }
                    addView(pillButton("Select ADB", if (isSelected) accentBlue else btnNeutral, if (isSelected) Color.WHITE else textSecondary) {
                        selectedProfile = p
                        updateTargetSummary()
                        renderList()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginEnd = px(6) }
                    })
                    addView(pillButton("Test Link", btnNeutral, textSecondary) { connect(p) }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginEnd = px(6) }
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
        updateTargetSummary()
    }

    private fun openStream(p: Profile) {
        startActivity(Intent(this, StreamActivity::class.java).putExtra("profileId", p.id))
    }

    private fun connect(p: Profile) {
        if (busy) return
        busy = true
        openLog("Running diagnostic on ${p.host}:${p.port}...")
        val settings = store.settings()
        Thread {
            try {
                ScrcpyDiagnostic(applicationContext, keys).run(p, settings) { line ->
                    appendLog(line)
                }
            } finally {
                busy = false
            }
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
                selectedProfile = updated
                renderList()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Delete") { _, _ ->
            val remaining = store.profiles().filter { it.id != existing.id }
            store.saveProfiles(remaining)
            if (selectedProfile?.id == existing.id) selectedProfile = remaining.firstOrNull()
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