package com.example.clawlessexplorer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * In-app terminal. Runs commands via `sh -c` (with cwd tracking) or `su -c`
 * for root-elevation when the binary is present. Output streams live into
 * a monospace TextView; up/down arrows recall command history.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var session: TerminalSession
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: android.widget.EditText
    private lateinit var prompt: TextView
    private val sb = SpannableStringBuilder()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        output = findViewById(R.id.terminalOutput)
        scroll = findViewById(R.id.scrollView)
        input = findViewById(R.id.commandInput)
        prompt = findViewById(R.id.promptLabel)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnClear).setOnClickListener { clearOutput() }

        session = TerminalSession()
        output.setTextColor(0xFFE8E9F0.toInt())
        output.typeface = Typeface.MONOSPACE

        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit(input.text.toString())
                true
            } else false
        }
        // Disable system spell-checker / autocorrect that would mess with the prompt.
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
        appendPrompt("Clawless Explorer · in-app terminal\n")
        appendPrompt("Sh: ${systemShell()}\n")
        appendPrompt("Cwd: ${session.cwd.absolutePath}\n")
        appendPrompt("Type a command and press Enter. Use ↑/↓ for history.\n\n")
        refreshPrompt()

        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun submit(raw: String) {
        val cmd = raw.trim()
        input.setText("")
        if (cmd.isEmpty()) {
            // blank Enter → just print a fresh prompt
            printPrompt()
            return
        }
        if (running) {
            append("> $cmd\n", promptColor = 0xFFFF6B9D.toInt())
            append("(busy — wait for the current command to finish)\n", error = true)
            printPrompt()
            return
        }

        appendPrompt("$cmd\n")
        running = true

        session.execute(cmd, listener = object : TerminalSession.OutputListener {
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
        val rel = session.cwd.absolutePath.replace(android.os.Environment.getExternalStorageDirectory().absolutePath, "~")
        prompt.text = "$rel \$ "
    }

    private fun clearOutput() {
        sb.clear()
        output.text = sb
        appendPrompt("Clawless Explorer · terminal cleared\n\n")
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
        // Scroll to bottom
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun systemShell(): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "echo \u0024SHELL").start()
            process.inputStream.bufferedReader().readText().trim().ifEmpty { "/system/bin/sh" }
        }.getOrDefault("/system/bin/sh")
    }
}
