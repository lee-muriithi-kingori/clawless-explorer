package com.example.clawlessexplorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.clawlessexplorer.databinding.ActivityCodeViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class CodeViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeViewerBinding
    private var fontSize = 12f
    private var wordWrap = false
    private var fileContent = ""
    private var searchQuery = ""
    private var matchPositions = mutableListOf<Int>()
    private var currentMatchIndex = -1
    private var searchDebounceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCodeViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            insets
        }

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)
        if (!file.exists()) { finish(); return }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.titleText.text = file.name

        binding.btnShare.setOnClickListener { shareFile(file) }
        binding.btnShareBottom.setOnClickListener { shareFile(file) }

        binding.btnCopyAll.setOnClickListener {
            val clip = ClipData.newPlainText(file.name, fileContent)
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnWrap.setOnClickListener { toggleWordWrap() }

        binding.btnFontMinus.setOnClickListener { changeFontSize(-1f) }
        binding.btnFontPlus.setOnClickListener { changeFontSize(1f) }

        // Search
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.searchInput.text.toString())
                true
            } else false
        }

        binding.btnFindPrev.setOnClickListener { navigateMatch(-1) }
        binding.btnFindNext.setOnClickListener { navigateMatch(1) }
        binding.btnFindPrevBottom.setOnClickListener { navigateMatch(-1) }
        binding.btnFindNextBottom.setOnClickListener { navigateMatch(1) }

        loadFile(file)
    }

    private fun loadFile(file: File) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val raf = RandomAccessFile(file, "r")
                    val fileLen = raf.length()
                    val truncated = fileLen > MAX_FILE_SIZE
                    val readLen = if (truncated) MAX_FILE_SIZE else fileLen
                    val bytes = ByteArray(readLen.toInt())
                    raf.readFully(bytes)
                    raf.close()

                    val content = String(bytes, Charsets.UTF_8)
                    Result.success(content to truncated)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            result.onSuccess { (content, truncated) ->
                fileContent = content
                displayContent(content)
                if (truncated) {
                    binding.truncatedNotice.visibility = View.VISIBLE
                }
            }.onFailure {
                binding.lineCountText.text = "Error reading file"
            }
        }
    }

    private fun displayContent(content: String) {
        val lines = content.split("\n")
        val lineCount = lines.size
        binding.lineCountText.text = "$lineCount lines"

        // Line numbers
        val sb = StringBuilder()
        for (i in 1..lineCount) {
            sb.appendLine(i.toString())
        }
        binding.lineNumbers.text = sb.toString()

        // Syntax-colored code
        val ext = binding.titleText.text.toString().substringAfterLast('.', "").lowercase()
        val colored = colorizeCode(content, ext)
        binding.codeContent.text = colored
        binding.codeContent.typeface = Typeface.MONOSPACE
        binding.codeContent.textSize = fontSize
        binding.lineNumbers.typeface = Typeface.MONOSPACE
        binding.lineNumbers.textSize = fontSize
    }

    private fun colorizeCode(code: String, ext: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(code)
        val colorDefault = 0xFFE8E9F0.toInt()
        val colorKeyword = 0xFFFF6B9D.toInt()
        val colorString = 0xFF10B981.toInt()
        val colorComment = 0xFF6B7280.toInt()
        val colorNumber = 0xFFF59E0B.toInt()
        val colorAnnotation = 0xFF8B5CF6.toInt()

        // Set default color for entire text
        ssb.setSpan(
            ForegroundColorSpan(colorDefault), 0, code.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Line-by-line basic coloring
        val lines = code.split("\n")
        var offset = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            val lineStart = offset
            val lineEnd = offset + line.length

            if (trimmed.startsWith("//") || trimmed.startsWith("#") ||
                trimmed.startsWith("/*") || trimmed.startsWith("*") ||
                (ext == "py" && trimmed.startsWith("def "))
            ) {
                // Comment-like lines
                val color = if (trimmed.startsWith("//") || trimmed.startsWith("#") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("*")
                ) colorComment else colorKeyword
                ssb.setSpan(
                    ForegroundColorSpan(color), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // Keywords
            val keywords = when (ext) {
                "kt", "java" -> listOf(
                    "fun", "val", "var", "class", "object", "interface", "return", "if", "else",
                    "when", "for", "while", "do", "try", "catch", "finally", "throw", "import",
                    "package", "private", "public", "protected", "internal", "override", "open",
                    "abstract", "data", "sealed", "companion", "suspend", "lazy", "lateinit",
                    "true", "false", "null", "this", "super", "new", "static", "void", "int",
                    "string", "boolean", "long", "float", "double", "char", "byte"
                )
                "py" -> listOf(
                    "def", "class", "return", "if", "else", "elif", "for", "while", "import",
                    "from", "as", "try", "except", "finally", "raise", "with", "yield", "lambda",
                    "pass", "break", "continue", "True", "False", "None", "and", "or", "not",
                    "in", "is", "self", "print", "async", "await"
                )
                "js", "ts" -> listOf(
                    "function", "const", "let", "var", "return", "if", "else", "for", "while",
                    "do", "switch", "case", "break", "continue", "try", "catch", "finally",
                    "throw", "new", "delete", "typeof", "instanceof", "in", "of", "class",
                    "extends", "super", "import", "export", "default", "from", "async", "await",
                    "yield", "this", "true", "false", "null", "undefined", "void"
                )
                "xml", "html" -> listOf(
                    "xmlns", "android", "app", "tools"
                )
                else -> listOf()
            }

            for (keyword in keywords) {
                val regex = Regex("\\b${Regex.escape(keyword)}\\b")
                regex.findAll(line).forEach { match ->
                    val start = lineStart + match.range.first
                    val end = lineStart + match.range.last + 1
                    ssb.setSpan(
                        ForegroundColorSpan(colorKeyword), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            // Strings (double or single quoted)
            val stringRegex = Regex("\"[^\"]*\"|'[^']*'|`[^`]*`")
            stringRegex.findAll(line).forEach { match ->
                val start = lineStart + match.range.first
                val end = lineStart + match.range.last + 1
                ssb.setSpan(
                    ForegroundColorSpan(colorString), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // Annotations / decorators
            if (ext == "kt" || ext == "java") {
                val annotationRegex = Regex("@\\w+")
                annotationRegex.findAll(line).forEach { match ->
                    val start = lineStart + match.range.first
                    val end = lineStart + match.range.last + 1
                    ssb.setSpan(
                        ForegroundColorSpan(colorAnnotation), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            // Numbers
            val numberRegex = Regex("\\b\\d+\\.?\\d*[fFlLdD]?\\b")
            numberRegex.findAll(line).forEach { match ->
                val start = lineStart + match.range.first
                val end = lineStart + match.range.last + 1
                ssb.setSpan(
                    ForegroundColorSpan(colorNumber), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            offset = lineEnd + 1 // +1 for \n
        }

        return ssb
    }

    private fun toggleWordWrap() {
        wordWrap = !wordWrap
        if (wordWrap) {
            binding.codeContent.isSingleLine = false
            binding.codeContent.maxLines = Int.MAX_VALUE
            binding.codeContent.scrollBarFadeDuration = 0
            // Hide horizontal scroll when wrapping
            binding.horizontalScroll.isHorizontalScrollBarEnabled = false
            // Re-layout parent
            val parent = binding.horizontalScroll.parent
            if (parent is android.widget.FrameLayout) {
                binding.horizontalScroll.layoutParams =
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
            }
        } else {
            binding.codeContent.isSingleLine = true
            binding.codeContent.maxLines = Int.MAX_VALUE
            binding.codeContent.scrollBarFadeDuration = 5000
            binding.horizontalScroll.isHorizontalScrollBarEnabled = true
            val parent = binding.horizontalScroll.parent
            if (parent is android.widget.FrameLayout) {
                binding.horizontalScroll.layoutParams =
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
            }
        }
    }

    private fun changeFontSize(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(8f, 32f)
        binding.codeContent.textSize = fontSize
        binding.lineNumbers.textSize = fontSize
    }

    private fun performSearch(query: String) {
        searchQuery = query
        matchPositions.clear()
        currentMatchIndex = -1

        if (query.isEmpty()) {
            hideSearchResults()
            // Rebuild without highlighting
            displayContent(fileContent)
            return
        }

        val lowerContent = fileContent.lowercase()
        val lowerQuery = query.lowercase()
        var index = lowerContent.indexOf(lowerQuery)
        while (index >= 0) {
            matchPositions.add(index)
            index = lowerContent.indexOf(lowerQuery, index + 1)
        }

        if (matchPositions.isNotEmpty()) {
            currentMatchIndex = 0
            showSearchResults()
            highlightMatches()
            scrollToMatch(0)
        } else {
            binding.searchCountText.text = "No results"
            binding.searchCountText.visibility = View.VISIBLE
            binding.btnFindPrev.visibility = View.GONE
            binding.btnFindNext.visibility = View.GONE
        }
    }

    private fun highlightMatches() {
        displayContent(fileContent)
        // Add highlight spans over the colored code
        val current = binding.codeContent.text as? SpannableStringBuilder ?: return
        val colorHighlight = 0x40FFD700
        for (pos in matchPositions) {
            val end = (pos + searchQuery.length).coerceAtMost(current.length)
            if (pos < current.length) {
                current.setSpan(
                    ForegroundColorSpan(Color.YELLOW), pos, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun navigateMatch(direction: Int) {
        if (matchPositions.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + direction + matchPositions.size) % matchPositions.size
        binding.searchCountText.text = "${currentMatchIndex + 1}/${matchPositions.size}"
        scrollToMatch(currentMatchIndex)
    }

    private fun scrollToMatch(index: Int) {
        if (index < 0 || index >= matchPositions.size) return
        val pos = matchPositions[index]
        val text = binding.codeContent.text.toString()
        val lineNum = text.substring(0, pos).count { it == '\n' }
        val lineHeight = binding.codeContent.lineHeight
        binding.horizontalScroll.scrollTo(0, lineNum * lineHeight)

        binding.searchCountText.text = "${index + 1}/${matchPositions.size}"
        binding.searchCountText.visibility = View.VISIBLE
    }

    private fun showSearchResults() {
        binding.searchCountText.text = "1/${matchPositions.size}"
        binding.searchCountText.visibility = View.VISIBLE
        binding.btnFindPrev.visibility = View.VISIBLE
        binding.btnFindNext.visibility = View.VISIBLE
        binding.btnFindPrevBottom.visibility = View.VISIBLE
        binding.btnFindNextBottom.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
    }

    private fun hideSearchResults() {
        binding.searchCountText.visibility = View.GONE
        binding.btnFindPrev.visibility = View.GONE
        binding.btnFindNext.visibility = View.GONE
        binding.btnFindPrevBottom.visibility = View.GONE
        binding.btnFindNextBottom.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun shareFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, fileContent)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share file"))
    }

    companion object {
        const val EXTRA_PATH = "extra_code_path"
        private const val MAX_FILE_SIZE = 500L * 1024 // 500 KB

        fun intent(context: Context, path: String): Intent =
            Intent(context, CodeViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
            }
    }
}
