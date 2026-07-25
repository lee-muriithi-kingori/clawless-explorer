package com.example.clawlessexplorer

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TerminalActivity : AppCompatActivity() {

    private lateinit var session: TerminalSession
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: android.widget.EditText
    private lateinit var prompt: TextView
    private lateinit var btnRoot: com.google.android.material.button.MaterialButton
    private lateinit var quickCommands: LinearLayout
    private val sb = SpannableStringBuilder()
    private var running = false
    private var useRoot = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val quickCmds = listOf(
        "ls -la", "pwd", "df -h", "free -m", "top -n 1",
        "ps aux", "cat /proc/cpuinfo", "ip addr", "ping -c 3 google.com",
        "whoami", "id", "uname -a", "du -sh *", "find . -type f | head -20"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        output = findViewById(R.id.terminalOutput)
        scroll = findViewById(R.id.scrollView)
        input = findViewById(R.id.commandInput)
        prompt = findViewById(R.id.promptLabel)
        btnRoot = findViewById(R.id.btnRoot)
        quickCommands = findViewById(R.id.quickCommands)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnClear).setOnClickListener { clearOutput() }

        session = TerminalSession()
        output.setTextColor(0xFFE8E9F0.toInt())
        output.typeface = android.graphics.Typeface.MONOSPACE

        // Root toggle
        btnRoot.setOnClickListener {
            useRoot = !useRoot
            btnRoot.text = if (useRoot) "SU" else "SH"
            btnRoot.setBackgroundColor(if (useRoot) 0xFFEF4444.toInt() else 0xFF10B981.toInt())
            appendPrompt(if (useRoot) "\n⚡ Root mode enabled (su -c)\n\n" else "\n✓ Shell mode (sh -c)\n\n")
            refreshPrompt()
        }

        // Quick command chips
        quickCmds.forEach { cmd ->
            val chip = Chip(this).apply {
                text = cmd
                isCheckable = false
                setOnClickListener {
                    input.setText(cmd)
                    input.setSelection(cmd.length)
                    submit(cmd)
                }
            }
            quickCommands.addView(chip)
        }

        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit(input.text.toString())
                true
            } else false
        }

        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setOnKeyListener { _, keyCode, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val prev = session.previousCommand()
                    if (prev != null) { input.setText(prev); input.setSelection(prev.length) }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val next = session.nextCommand()
                    if (next != null) { input.setText(next); input.setSelection(next.length) }
                    true
                }
                else -> false
            }
        }

        // Welcome banner
        appendPrompt("╔══════════════════════════════════╗\n")
        appendPrompt("║   Clawless Explorer · Terminal  ║\n")
        appendPrompt("╚══════════════════════════════════╝\n")
        appendPrompt("Shell: ${systemShell()}\n")
        appendPrompt("Cwd: ${session.cwd.absolutePath}\n")
        appendPrompt("Type a command. ↑/↓ for history. Tap SU for root.\n\n")
        refreshPrompt()

        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun submit(raw: String) {
        val cmd = raw.trim()
        input.setText("")
        if (cmd.isEmpty()) {
            printPrompt()
            return
        }
        if (running) {
            append("> $cmd\n", promptColor = 0xFFFF6B9D.toInt())
            append("(busy — wait for current command)\n", error = true)
            printPrompt()
            return
        }

        val timestamp = timeFormat.format(Date())
        appendPrompt("[$timestamp] $cmd\n")
        running = true

        val effectiveCmd = if (useRoot) "su -c ${cmd.replace("'", "'\\''")}" else cmd

        session.execute(effectiveCmd, listener = object : TerminalSession.OutputListener {
            override fun onOutput(line: String, isError: Boolean) {
                runOnUiThread { append("$line\n", error = isError) }
            }
        }, onComplete = { _ ->
            running = false
            runOnUiThread { printPrompt() }
        })
    }

    private fun printPrompt() {
        refreshPrompt()
        append(prompt.text.toString(), promptColor = 0xFF00C8B4.toInt())
    }

    private fun refreshPrompt() {
        val rel = session.cwd.absolutePath.replace(
            android.os.Environment.getExternalStorageDirectory().absolutePath, "~"
        )
        val prefix = if (useRoot) "root" else "$"
        prompt.text = "$rel $prefix "
    }

    private fun clearOutput() {
        sb.clear()
        output.text = sb
        appendPrompt("Terminal cleared\n\n")
        printPrompt()
    }

    private fun appendPrompt(s: String) = append(s, promptColor = 0xFFA8AAB8.toInt())

    private fun append(s: String, promptColor: Int = 0, error: Boolean = false) {
        val start = sb.length
        sb.append(s)
        val end = sb.length
        val color = when {
            error -> 0xFFEF4444.toInt()
            promptColor != 0 -> promptColor
            else -> 0xFFE8E9F0.toInt()
        }
        sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        output.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun systemShell(): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "echo \u0024SHELL").start()
            process.inputStream.bufferedReader().readText().trim().ifEmpty { "/system/bin/sh" }
        }.getOrDefault("/system/bin/sh")
    }
}
