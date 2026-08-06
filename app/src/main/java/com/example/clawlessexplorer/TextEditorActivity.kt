package com.example.clawlessexplorer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.clawlessexplorer.databinding.ActivityTextEditorBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedList

class TextEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextEditorBinding
    private var file: File? = null
    private var isModified = false
    private var syntaxLang: String = "text"

    // Undo/Redo stacks
    private val undoStack = LinkedList<EditAction>()
    private val redoStack = LinkedList<EditAction>()
    private var isUndoRedo = false
    private var lastEditTime = 0L

    data class EditAction(
        val start: Int,
        val oldText: String,
        val newText: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra("extra_path")
        if (filePath == null) {
            Toast.makeText(this, "No file path provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        file = File(filePath)
        supportActionBar?.title = file?.name
        binding.toolbar.title = file?.name
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        detectSyntax(filePath)
        loadFile()

        // Track modifications for undo/redo
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isUndoRedo) {
                    val oldText = s?.substring(start, start + count) ?: ""
                    // Will be used in onTextChanged
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isUndoRedo) {
                    val oldText = if (before > 0) {
                        // We don't have the old text in onTextChanged, approximate
                        ""
                    } else ""
                    val newText = s?.substring(start, start + count)?.toString() ?: ""
                    if (newText.isNotEmpty() || before > 0) {
                        val now = System.currentTimeMillis()
                        // Merge with last action if < 500ms apart and adjacent
                        if (now - lastEditTime < 500 && undoStack.isNotEmpty()) {
                            val last = undoStack.last
                            if (last.start + last.newText.length == start) {
                                undoStack.removeLast()
                                undoStack.add(EditAction(last.start, last.oldText, last.newText + newText))
                                redoStack.clear()
                                lastEditTime = now
                                isModified = true
                                updateStatus()
                                return
                            }
                        }
                        undoStack.add(EditAction(start, "", newText))
                        redoStack.clear()
                        lastEditTime = now
                    }
                }
                isModified = true
                updateStatus()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Save FAB
        binding.fabSave.setOnClickListener { saveFile() }

        // Find/Replace
        binding.btnReplaceNext.setOnClickListener { replaceNext() }

        updateStatus()
    }

    private fun detectSyntax(path: String) {
        syntaxLang = when {
            path.endsWith(".kt") || path.endsWith(".java") -> "kotlin"
            path.endsWith(".py") -> "python"
            path.endsWith(".js") -> "javascript"
            path.endsWith(".xml") || path.endsWith(".html") -> "xml"
            path.endsWith(".css") -> "css"
            path.endsWith(".sh") -> "shell"
            path.endsWith(".json") -> "json"
            else -> "text"
        }
    }

    private fun loadFile() {
        val f = file ?: return
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (f.length() > 2 * 1024 * 1024) {
                        f.readText().take(2 * 1024 * 1024)
                    } else {
                        f.readText()
                    }
                }
                binding.editor.setText(content)
                isModified = false
                updateStatus()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error loading file: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFile() {
        val f = file ?: return
        lifecycleScope.launch {
            try {
                val content = binding.editor.text.toString()
                withContext(Dispatchers.IO) {
                    f.writeText(content)
                }
                isModified = false
                updateStatus()
                Snackbar.make(binding.root, "File saved", Snackbar.LENGTH_SHORT)
                    .setAnchorView(binding.fabSave).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error saving: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        isUndoRedo = true
        val action = undoStack.removeLast()
        redoStack.add(action)
        val editable = binding.editor.text
        editable.replace(action.start, action.start + action.newText.length, action.oldText)
        isUndoRedo = false
        updateStatus()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        isUndoRedo = true
        val action = redoStack.removeLast()
        undoStack.add(action)
        val editable = binding.editor.text
        editable.insert(action.start, action.newText)
        isUndoRedo = false
        updateStatus()
    }

    private fun replaceNext() {
        val search = binding.searchInput.text.toString()
        val replace = binding.replaceInput.text.toString()
        if (search.isEmpty()) return
        
        val text = binding.editor.text.toString()
        val index = text.indexOf(search, binding.editor.selectionStart)
        if (index >= 0) {
            binding.editor.text.replace(index, index + search.length, replace)
        } else {
            Snackbar.make(binding.root, "Not found", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val lines = binding.editor.text.count { it == '\n' } + 1
        val chars = binding.editor.text.length
        val mod = if (isModified) " •" else ""
        binding.statusBar.text = "$syntaxLang │ $lines lines │ $chars chars$mod"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("Find/Replace").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add("Undo").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add("Redo").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.title?.toString()) {
            "Find/Replace" -> {
                binding.searchBar.visibility = if (binding.searchBar.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
            "Undo" -> { undo(); true }
            "Redo" -> { redo(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        // Warn about unsaved changes would go here in production
        super.onDestroy()
    }
}
