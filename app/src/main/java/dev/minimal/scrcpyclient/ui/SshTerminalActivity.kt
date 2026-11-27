package dev.minimal.scrcpyclient.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.minimal.scrcpyclient.ssh.ActiveSshShell
import dev.minimal.scrcpyclient.ssh.SshAuthMode
import dev.minimal.scrcpyclient.ssh.SshConnector
import java.io.InputStreamReader

/** Conduit-style SSH terminal workspace with per-login password prompt (askpw) and extra keys. */
class SshTerminalActivity : AppCompatActivity() {

    private lateinit var termView: TextView
    private lateinit var termScroll: ScrollView
    private lateinit var cmdInput: EditText
    private var shellSession: ActiveSshShell? = null
    @Volatile private var readerActive = false
    @Volatile private var ctrlArmed = false
    @Volatile private var altArmed = false

    private val history = mutableListOf<String>()
    private var historyIdx = -1

    private var label: String = "SSH Host"
    private var host: String = ""
    private var port: Int = 22
    private var username: String = ""
    private var authMode: SshAuthMode = SshAuthMode.PASSWORD
    private var keyPath: String = ""
    private var keyStub: String = ""
    private var timeoutSec: Int = 12
    private var startTmux: Boolean = false
    private var tmuxPrefix: String = "Ctrl-B"
    private var useMosh: Boolean = false
    private var moshLocale: String = "C.UTF-8"

    private val bgPage = Color.parseColor("#0D1117")
    private val bgTerm = Color.parseColor("#090C10")
    private val bgCard = Color.parseColor("#161B22")
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

        label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty().ifEmpty { "SSH Host" }
        host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        port = intent.getIntExtra(EXTRA_PORT, 22)
        username = intent.getStringExtra(EXTRA_USER)?.trim().orEmpty()
        authMode = SshAuthMode.fromWire(intent.getStringExtra(EXTRA_AUTH_MODE))
        keyPath = intent.getStringExtra(EXTRA_KEY_PATH)?.trim().orEmpty()
        keyStub = intent.getStringExtra(EXTRA_KEY_STUB)?.trim().orEmpty()
        timeoutSec = intent.getIntExtra(EXTRA_TIMEOUT_SEC, 12)
        startTmux = intent.getBooleanExtra(EXTRA_START_TMUX, false)
        tmuxPrefix = intent.getStringExtra(EXTRA_TMUX_PREFIX)?.trim().orEmpty().ifEmpty { "Ctrl-B" }
        useMosh = intent.getBooleanExtra(EXTRA_USE_MOSH, false)
        moshLocale = intent.getStringExtra(EXTRA_MOSH_LOCALE)?.trim().orEmpty().ifEmpty { "C.UTF-8" }

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
                    text = label
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    text = if (host.isNotEmpty() && username.isNotEmpty()) {
                        "$username@$host:$port"
                    } else {
                        "Missing SSH target"
                    }
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(textSecondary)
                })
            })
            addView(pillButton("Re-Auth", btnNeutral, textSecondary) { promptAuthAndConnect() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(6) }
            })
            addView(pillButton("Clear", btnNeutral, textSecondary) { termView.text = "" })
        }

        val sessionTabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(8), 0, px(4))
        }
        val activeTab = pillButton(label, accentBlue, Color.WHITE, fill = true) {}
        val tmuxTab = pillButton("tmux", btnNeutral, textSecondary) {
            sendCommandText("tmux new-session -A -s nexus")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(8) }
        }
        val fontSmaller = pillButton("A-", btnNeutral, textSecondary) {
            termView.textSize = (termView.textSize / dp - 1f).coerceIn(9f, 22f)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(8) }
        }
        val fontLarger = pillButton("A+", btnNeutral, textSecondary) {
            termView.textSize = (termView.textSize / dp + 1f).coerceIn(9f, 22f)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(6) }
        }
        sessionTabStrip.addView(activeTab)
        sessionTabStrip.addView(tmuxTab)
        sessionTabStrip.addView(fontSmaller)
        sessionTabStrip.addView(fontLarger)

        val extraKeysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(6), 0, px(2))
        }
        val extraKeys = listOf(
            "Esc" to byteArrayOf(0x1B),
            "Ctrl" to byteArrayOf(0x03),
            "Alt" to byteArrayOf(0x1B),
            "Tab" to byteArrayOf(0x09),
            "^" to "\u001B[A".toByteArray(Charsets.UTF_8),
            "v" to "\u001B[B".toByteArray(Charsets.UTF_8),
            "<" to "\u001B[D".toByteArray(Charsets.UTF_8),
            ">" to "\u001B[C".toByteArray(Charsets.UTF_8),
        )
        extraKeys.forEachIndexed { idx, (kLabel, seq) ->
            extraKeysRow.addView(pillButton(kLabel, btnNeutral, textSecondary) {
                when (kLabel) {
                    "Ctrl" -> {
                        ctrlArmed = !ctrlArmed
                        appendTerm("[Ctrl ${if (ctrlArmed) "armed (next char)" else "off"}]\n")
                    }
                    "Alt" -> {
                        altArmed = !altArmed
                        appendTerm("[Alt ${if (altArmed) "armed (next char)" else "off"}]\n")
                    }
                    "^" -> cycleHistory(-1, seq)
                    "v" -> cycleHistory(1, seq)
                    else -> sendBytes(seq)
                }
            }.apply {
                if (idx < extraKeys.lastIndex) {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(6) }
                }
            })
        }

        termView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextIsSelectable(true)
            setPadding(px(12), px(12), px(12), px(12))
        }

        termScroll = ScrollView(this).apply {
            background = cardDrawable(bgTerm, borderCard, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = px(6)
                bottomMargin = px(6)
            }
            addView(termView)
        }

        cmdInput = EditText(this).apply {
            hint = "Send terminal command..."
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
                    sendCurrentInput()
                    true
                } else {
                    false
                }
            }
        }

        val sendBtn = pillButton("Send", accentGreen, Color.WHITE, fill = true) {
            sendCurrentInput()
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cmdInput)
            addView(sendBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(36), px(16), px(16))
            addView(header)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(sessionTabStrip)
            })
            addView(termScroll)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(extraKeysRow)
            })
            addView(inputRow.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(8) }
            })
        }

        setContentView(root.apply { setBackgroundColor(bgPage) })

        if (host.isEmpty() || username.isEmpty()) {
            appendTerm("Missing SSH host or username.\n")
        } else {
            promptAuthAndConnect()
        }
    }

    private fun cycleHistory(delta: Int, fallbackSeq: ByteArray) {
        if (history.isEmpty()) {
            sendBytes(fallbackSeq)
            return
        }
        val nextIdx = if (historyIdx == -1) {
            if (delta < 0) history.lastIndex else 0
        } else {
            (historyIdx + delta).coerceIn(0, history.lastIndex)
        }
        historyIdx = nextIdx
        cmdInput.setText(history[historyIdx])
        cmdInput.setSelection(cmdInput.text.length)
    }

    private fun promptAuthAndConnect() {
        closeExistingSession()
        if (authMode == SshAuthMode.PASSWORD) {
            val pwInput = EditText(this).apply {
                hint = "Enter SSH password for $username@$host"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("SSH Login (askpw)")
                .setMessage("Saved profile: $username@$host:$port\nPassword is required for each login and is not saved.")
                .setView(pwInput)
                .setCancelable(false)
                .setPositiveButton("Connect") { _, _ ->
                    val oneTimePw = pwInput.text.toString()
                    pwInput.setText("")
                    connectWithCredentials(oneTimePassword = oneTimePw, passphrase = "")
                }
                .setNegativeButton("Cancel") { _, _ ->
                    pwInput.setText("")
                    finish()
                }
                .show()
        } else {
            val passInput = EditText(this).apply {
                hint = "Key / stub passphrase (optional)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("Connect (${authMode.label})")
                .setMessage("Target: $username@$host:$port")
                .setView(passInput)
                .setCancelable(false)
                .setPositiveButton("Connect") { _, _ ->
                    val pass = passInput.text.toString()
                    passInput.setText("")
                    connectWithCredentials(oneTimePassword = "", passphrase = pass)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    passInput.setText("")
                    finish()
                }
                .show()
        }
    }

    private fun connectWithCredentials(oneTimePassword: String, passphrase: String) {
        if (useMosh) {
            appendTerm("[Mosh enabled (locale=$moshLocale): running over SSH PTY; standalone mosh UDP client is not bundled]\n")
        }
        appendTerm("Connecting to $username@$host:$port ...\n")
        Thread {
            var pwCopy: String? = oneTimePassword
            var passCopy: String? = passphrase
            try {
                val active = SshConnector.connectShell(
                    host = host,
                    port = port,
                    username = username,
                    authMode = authMode,
                    oneTimePassword = pwCopy.orEmpty(),
                    keyPassphrase = passCopy.orEmpty(),
                    privateKeyPath = keyPath,
                    hardwareKeyStub = keyStub,
                    timeoutSec = timeoutSec,
                )
                pwCopy = null
                passCopy = null
                shellSession = active
                readerActive = true
                appendTerm("Connected.\n")
                if (startTmux) {
                    active.sendCommand("tmux new-session -A -s nexus")
                }

                val reader = InputStreamReader(active.input, Charsets.UTF_8)
                val buf = CharArray(1024)
                while (readerActive) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val text = String(buf, 0, n).replace("\r\n", "\n")
                    appendTerm(text)
                }
            } catch (e: Exception) {
                pwCopy = null
                passCopy = null
                appendTerm("\n[SSH error: ${e.javaClass.simpleName}: ${e.message}]\n")
            } finally {
                readerActive = false
            }
        }.start()
    }

    private fun sendCurrentInput() {
        var cmd = cmdInput.text.toString()
        cmdInput.setText("")
        if (cmd.isNotBlank()) {
            history.add(cmd)
            historyIdx = history.size
        }
        if (ctrlArmed && cmd.isNotEmpty()) {
            val ch = cmd.first().lowercaseChar()
            if (ch in 'a'..'z') {
                val code = (ch.code - 'a'.code + 1).toByte()
                ctrlArmed = false
                sendBytes(byteArrayOf(code))
                return
            }
            ctrlArmed = false
        }
        if (altArmed && cmd.isNotEmpty()) {
            altArmed = false
            val payload = byteArrayOf(0x1B) + (cmd + "\n").toByteArray(Charsets.UTF_8)
            sendBytes(payload)
            return
        }
        sendCommandText(cmd)
    }

    private fun sendCommandText(cmd: String) {
        val active = shellSession
        if (active == null || !readerActive) {
            appendTerm("[Not connected - tap Re-Auth]\n")
            return
        }
        Thread {
            try {
                active.sendCommand(cmd)
            } catch (e: Exception) {
                appendTerm("\n[Send failed: ${e.message}]\n")
            }
        }.start()
    }

    private fun sendBytes(bytes: ByteArray) {
        val active = shellSession
        if (active == null || !readerActive) return
        Thread {
            try {
                active.sendRaw(bytes)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun appendTerm(chunk: String) {
        runOnUiThread {
            termView.append(chunk)
            termScroll.post { termScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun closeExistingSession() {
        readerActive = false
        val current = shellSession
        shellSession = null
        if (current != null) {
            Thread { current.close() }.start()
        }
    }

    override fun onDestroy() {
        closeExistingSession()
        super.onDestroy()
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
        setPadding((10 * dp).toInt(), (7 * dp).toInt(), (10 * dp).toInt(), (7 * dp).toInt())
        background = cardDrawable(
            bg,
            if (fill && bg == accentGreen) Color.parseColor("#2EA043") else if (fill && bg == accentBlue) Color.parseColor("#388BFD") else borderCard,
            10
        )
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "username"
        const val EXTRA_AUTH_MODE = "authMode"
        const val EXTRA_KEY_PATH = "keyPath"
        const val EXTRA_KEY_STUB = "keyStub"
        const val EXTRA_TIMEOUT_SEC = "timeoutSec"
        const val EXTRA_START_TMUX = "startTmux"
        const val EXTRA_TMUX_PREFIX = "tmuxPrefix"
        const val EXTRA_USE_MOSH = "useMosh"
        const val EXTRA_MOSH_LOCALE = "moshLocale"
    }
}