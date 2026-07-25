package com.example.clawlessexplorer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var input: EditText
    private lateinit var prompt: TextView
    private lateinit var btnRoot: MaterialButton
    private lateinit var quickCommands: LinearLayout
    private lateinit var tvWorkDir: TextView
    private val sb = SpannableStringBuilder()
    private var running = false
    private var useRoot = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fullOutput = StringBuilder()
    private var pendingRunPath: String? = null

    private val quickCmds = listOf(
        "ls -la", "pwd", "df -h", "free -m", "top -n 1",
        "ps aux", "cat /proc/cpuinfo", "ip addr", "ping -c 3 google.com",
        "whoami", "id", "uname -a", "du -sh *", "find . -type f | head -20"
    )

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val REQUEST_SCRIPT_PICKER = 2001

        private val SCRIPT_EXTENSIONS = mapOf(
            "sh" to "sh",
            "bash" to "bash",
            "zsh" to "sh",
            "py" to "python",
            "pl" to "perl",
            "rb" to "ruby"
        )

        private val ANSI_COLORS = mapOf(
            "0" to Color.parseColor("#E8E9F0"),
            "1" to Color.parseColor("#FF6B6B"),
            "2" to Color.parseColor("#51CF66"),
            "3" to Color.parseColor("#FCC419"),
            "4" to Color.parseColor("#4DABF7"),
            "5" to Color.parseColor("#CC5DE8"),
            "6" to Color.parseColor("#20C997"),
            "7" to Color.parseColor("#C0C0C0"),
            "90" to Color.parseColor("#6B7280"),
            "91" to Color.parseColor("#FF8787"),
            "92" to Color.parseColor("#8CE99A"),
            "93" to Color.parseColor("#FFE066"),
            "94" to Color.parseColor("#74C0FC"),
            "95" to Color.parseColor("#DA77F2"),
            "96" to Color.parseColor("#63E6BE"),
            "97" to Color.parseColor("#F8F9FA"),
        )

        fun intent(ctx: android.content.Context, filePath: String): Intent {
            return Intent(ctx, TerminalActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        output = findViewById(R.id.terminalOutput)
        scroll = findViewById(R.id.scrollView)
        input = findViewById(R.id.commandInput)
        prompt = findViewById(R.id.promptLabel)
        btnRoot = findViewById(R.id.btnRoot)
        quickCommands = findViewById(R.id.quickCommands)
        tvWorkDir = findViewById(R.id.tvWorkDir)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnClear).setOnClickListener { clearOutput() }

        session = TerminalSession()
        output.typeface = android.graphics.Typeface.MONOSPACE

        setupQuickActions()

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

        // Handle incoming file path
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            val file = File(filePath)
            pendingRunPath = filePath
            val ext = file.extension.lowercase()
            when {
                ext in SCRIPT_EXTENSIONS -> {
                    val interpreter = SCRIPT_EXTENSIONS[ext]
                    appendPrompt("→ Running script: ${file.name}\n")
                    executeScript(filePath)
                }
                file.canExecute() || ext.isEmpty() -> {
                    appendPrompt("→ Running binary: ${file.name}\n")
                    executeBinary(filePath)
                }
                else -> {
                    appendPrompt("→ File: ${file.name} (not a recognized script/binary)\n")
                    appendPrompt("  Use 'Run Script' button for script files.\n")
                }
            }
            pendingRunPath = null
        }

        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return
        val file = File(filePath)
        val ext = file.extension.lowercase()
        when {
            ext in SCRIPT_EXTENSIONS -> {
                appendPrompt("→ Running script: ${file.name}\n")
                executeScript(filePath)
            }
            file.canExecute() || ext.isEmpty() -> {
                appendPrompt("→ Running binary: ${file.name}\n")
                executeBinary(filePath)
            }
            else -> {
                appendPrompt("→ File: ${file.name} (not a recognized script/binary)\n")
            }
        }
    }

    private fun setupQuickActions() {
        findViewById<View>(R.id.btnRunScript).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Select Script"), REQUEST_SCRIPT_PICKER)
        }

        findViewById<View>(R.id.btnSaveLog).setOnClickListener { saveLog() }
        findViewById<View>(R.id.btnShareLog).setOnClickListener { shareLog() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCRIPT_PICKER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val path = getPathFromUri(uri)
            if (path != null) {
                executeScript(path)
            } else {
                Toast.makeText(this, "Could not resolve file path", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getPathFromUri(uri: android.net.Uri): String? {
        // Try content resolver for a direct path
        if (uri.scheme == "file") return uri.path

        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val name = cursor.getString(nameIndex)
                // Copy to cache dir
                val cacheFile = File(cacheDir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                return cacheFile.absolutePath
            }
        }
        return null
    }

    fun executeScript(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            append("Script not found: $filePath\n", error = true)
            printPrompt()
            return
        }

        val ext = file.extension.lowercase()
        val interpreter = SCRIPT_EXTENSIONS[ext] ?: "sh"

        val timestamp = timeFormat.format(Date())
        appendPrompt("[$timestamp] $interpreter ${file.name}\n")

        if (!file.canExecute()) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "+x", filePath)).waitFor()
                append("  [chmod +x applied]\n", promptColor = 0xFF6B7280.toInt())
            } catch (e: Exception) {
                append("  [chmod failed: ${e.message}]\n", error = true)
            }
        }

        running = true
        val cmd = if (useRoot) {
            "su -c '$interpreter \"${escape(filePath)}\"'"
        } else {
            "$interpreter \"${escape(filePath)}\""
        }

        session.execute(cmd, listener = object : TerminalSession.OutputListener {
            override fun onOutput(line: String, isError: Boolean) {
                runOnUiThread {
                    if (isError) {
                        appendAnsi("$line\n", isError = true)
                    } else {
                        appendAnsi("$line\n")
                    }
                }
            }
        }, onComplete = { exitCode ->
            running = false
            runOnUiThread {
                val color = if (exitCode == 0) 0xFF51CF66.toInt() else 0xFFEF4444.toInt()
                append("  [exit: $exitCode]\n", promptColor = color)
                printPrompt()
            }
        })
    }

    fun executeBinary(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            append("Binary not found: $filePath\n", error = true)
            printPrompt()
            return
        }

        val timestamp = timeFormat.format(Date())
        appendPrompt("[$timestamp] ${file.name}\n")

        running = true
        val cmd = if (useRoot) {
            "su -c '${escape(filePath)}'"
        } else {
            "'${escape(filePath)}'"
        }

        session.execute(cmd, listener = object : TerminalSession.OutputListener {
            override fun onOutput(line: String, isError: Boolean) {
                runOnUiThread {
                    if (isError) {
                        appendAnsi("$line\n", isError = true)
                    } else {
                        appendAnsi("$line\n")
                    }
                }
            }
        }, onComplete = { exitCode ->
            running = false
            runOnUiThread {
                val color = if (exitCode == 0) 0xFF51CF66.toInt() else 0xFFEF4444.toInt()
                append("  [exit: $exitCode]\n", promptColor = color)
                printPrompt()
            }
        })
    }

    private fun saveLog() {
        val logText = fullOutput.toString()
        if (logText.isBlank()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val fileName = "terminal_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            val file = File(dir, fileName)
            file.writeText(logText)
            Toast.makeText(this, "Log saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            appendPrompt("\n✓ Log saved to Downloads/$fileName\n\n")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLog() {
        val logText = fullOutput.toString()
        if (logText.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText)
            putExtra(Intent.EXTRA_SUBJECT, "Terminal Log")
        }
        startActivity(Intent.createChooser(intent, "Share Terminal Output"))
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
                runOnUiThread {
                    if (isError) {
                        appendAnsi("$line\n", isError = true)
                    } else {
                        appendAnsi("$line\n")
                    }
                }
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
        val absPath = session.cwd.absolutePath
        val extStorage = Environment.getExternalStorageDirectory().absolutePath
        val rel = if (absPath.startsWith(extStorage)) {
            "~" + absPath.removePrefix(extStorage)
        } else {
            absPath
        }
        tvWorkDir.text = rel
        val prefix = if (useRoot) "root" else "$"
        prompt.text = "$rel $prefix "
    }

    private fun clearOutput() {
        sb.clear()
        fullOutput.clear()
        output.text = sb
        appendPrompt("Terminal cleared\n\n")
        printPrompt()
    }

    private fun appendPrompt(s: String) = append(s, promptColor = 0xFFA8AAB8.toInt())

    private fun append(s: String, promptColor: Int = 0, error: Boolean = false) {
        fullOutput.append(s)
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

    /**
     * Parse basic ANSI escape codes and append with appropriate colors.
     * Supports: \u001b[0m (reset), \u001b[31m (red), \u001b[32m (green),
     * \u001b[33m (yellow), \u001b[34m (blue), \u001b[35m (magenta), \u001b[36m (cyan),
     * \u001b[37m (white), \u001b[90-97m (bright variants)
     */
    private fun appendAnsi(text: String, isError: Boolean = false) {
        val ansiPattern = Regex("\u001b\\[([0-9;]*)m")
        var lastEnd = 0
        var currentColor = if (isError) 0xFFEF4444.toInt() else 0

        for (match in ansiPattern.findAll(text)) {
            // Append text before the escape code
            if (match.range.first > lastEnd) {
                val segment = text.substring(lastEnd, match.range.first)
                if (segment.isNotEmpty()) {
                    appendColored(segment, currentColor)
                }
            }

            // Parse the ANSI code
            val codes = match.groupValues[1].split(";")
            for (code in codes) {
                when (code) {
                    "0", "" -> currentColor = if (isError) 0xFFEF4444.toInt() else 0
                    in ANSI_COLORS -> currentColor = ANSI_COLORS[code] ?: 0
                }
            }
            lastEnd = match.range.last + 1
        }

        // Append remaining text
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd)
            if (remaining.isNotEmpty()) {
                appendColored(remaining, currentColor)
            }
        }
    }

    private fun appendColored(text: String, color: Int) {
        fullOutput.append(text)
        val start = sb.length
        sb.append(text)
        val end = sb.length
        val effectiveColor = if (color != 0) color else 0xFFE8E9F0.toInt()
        sb.setSpan(ForegroundColorSpan(effectiveColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        output.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun escape(s: String) = s.replace("'", "'\\''")

    private fun systemShell(): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "echo \u0024SHELL").start()
            process.inputStream.bufferedReader().readText().trim().ifEmpty { "/system/bin/sh" }
        }.getOrDefault("/system/bin/sh")
    }
}
