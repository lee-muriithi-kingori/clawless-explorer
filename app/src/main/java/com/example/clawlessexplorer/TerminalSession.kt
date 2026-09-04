package com.example.clawlessexplorer

import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Long-lived shell session. Wraps a single [sh -c] process and tracks
 * current working directory + command history.
 *
 * Each [execute] call uses a one-shot `sh -c "<command>"` so we don't
 * keep a persistent process around (saves battery, simpler lifecycle).
 * The cwd is updated client-side by intercepting `cd <path>` from
 * command text and applied to subsequent commands as a `cd && ` prefix.
 */
class TerminalSession {

    @Volatile
    var cwd: File = Environment.getExternalStorageDirectory()
        private set

    val history: MutableList<String> = mutableListOf()
    private var historyIndex: Int = -1

    private var activeJob: Job? = null

    /** Callback for output lines from the running command. */
    fun interface OutputListener {
        fun onOutput(line: String, isError: Boolean)
    }

    /** Execute a single command line. Returns immediately; output streams via [listener]. */
    fun execute(command: String, listener: OutputListener, onComplete: (Int) -> Unit = {}): Job {
        // Cancel any running command.
        activeJob?.cancel()

        // Update history + index.
        if (command.isNotBlank()) {
            if (history.isEmpty() || history.last() != command) {
                history.add(command)
                if (history.size > MAX_HISTORY) history.removeAt(0)
            }
            historyIndex = history.size
        }

        // Bare cd is handled in-process: no shell to spawn, and a bad target
        // reports a real error instead of executing garbage.
        if (command.trim().isEmpty()) {
            onComplete(0)
            return Job().also { it.complete() }
        }
        val cdTarget = matchCd(command)
        if (cdTarget != null) {
            val dest = resolveCdTarget(cdTarget)
            return if (dest != null) {
                cwd = dest
                onComplete(0)
                Job().also { it.complete() }
            } else {
                listener.onOutput("cd: $cdTarget: No such file or directory", true)
                onComplete(1)
                Job().also { it.complete() }
            }
        }

        val effective = "cd '${escape(cwd.absolutePath)}' && ${command.trim()}"

        val job = CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
            val exitCode = runCommand(effective, listener)
            withContext(Dispatchers.Main) { onComplete(exitCode) }
        }
        activeJob = job
        return job
    }

    /** Recalled previous command. Returns null if at the start of history. */
    fun previousCommand(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return history[historyIndex]
    }

    /** Recalled next command. Returns null if at the end of history. */
    fun nextCommand(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(history.size)
        return if (historyIndex < history.size) history[historyIndex] else null
    }

    /**
     * The cd target when [raw] is a bare `cd` command, null for anything else.
     * Empty string means plain `cd` (home).
     */
    private fun matchCd(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val match = Regex("^cd\\s*(.*)$").matchEntire(trimmed) ?: return null
        return match.groupValues[1].trim()
    }

    /** Resolve a cd target against [cwd], or null when it is not a directory. */
    private fun resolveCdTarget(target: String): File? {
        val dest = when {
            target.isEmpty() || target == "~" -> Environment.getExternalStorageDirectory()
            target == ".." -> cwd.parentFile ?: cwd
            target.startsWith("/") -> File(target)
            else -> File(cwd, target)
        }
        return if (dest.isDirectory) dest else null
    }

    private suspend fun runCommand(command: String, listener: OutputListener): Int = withContext(Dispatchers.IO) {
        if (command.isEmpty()) return@withContext 0
        try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(cwd)
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()

            val stdoutJob = launch(Dispatchers.IO) {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { listener.onOutput(it, isError = false) }
                }
            }
            val stderrJob = launch(Dispatchers.IO) {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { listener.onOutput(it, isError = true) }
                }
            }
            val exit = process.waitFor()
            stdoutJob.join()
            stderrJob.join()
            exit
        } catch (e: Exception) {
            listener.onOutput("error: ${e.message}", isError = true)
            -1
        }
    }

    private fun escape(s: String) = s.replace("'", "'\\''")

    companion object {
        private const val MAX_HISTORY = 100
    }
}
