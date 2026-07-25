package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ViewFlipper
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityBatchRenameBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class BatchRenameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchRenameBinding
    private val selectedFiles = mutableListOf<Uri>()
    private var previewAdapter: RenamePreviewAdapter? = null

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedFiles.clear()
            selectedFiles.addAll(uris)
            onFilesSelected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBatchRenameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressedDispatcher.onBackPressed()
        }

        previewAdapter = RenamePreviewAdapter()
        binding.rvPreview.apply {
            layoutManager = LinearLayoutManager(this@BatchRenameActivity)
            adapter = previewAdapter
        }

        binding.btnSelectFiles.setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnFindReplace -> binding.modeFlipper.displayedChild = 0
                R.id.btnPrefix -> binding.modeFlipper.displayedChild = 1
                R.id.btnSuffix -> binding.modeFlipper.displayedChild = 2
                R.id.btnSequential -> binding.modeFlipper.displayedChild = 3
                R.id.btnRegex -> binding.modeFlipper.displayedChild = 4
            }
            updatePreview()
        }

        binding.etFind.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etReplace.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etPrefix.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etSuffix.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etSeqPrefix.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etStartNumber.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etPaddingLength.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etRegexPattern.addTextChangedListener(simpleWatcher { updatePreview() })
        binding.etRegexReplacement.addTextChangedListener(simpleWatcher { updatePreview() })

        binding.btnApply.setOnClickListener {
            applyRename()
        }
    }

    private fun onFilesSelected() {
        if (selectedFiles.isEmpty()) {
            binding.cardPreview.visibility = View.GONE
            binding.btnApply.isEnabled = false
            return
        }
        binding.cardPreview.visibility = View.VISIBLE
        binding.btnApply.isEnabled = true
        updatePreview()
    }

    private fun updatePreview() {
        if (selectedFiles.isEmpty()) return
        val previews = selectedFiles.mapNotNull { uri ->
            val oldName = getFileNameFromUri(uri) ?: return@mapNotNull null
            val newName = computeNewName(oldName)
            RenamePreview(oldName, newName)
        }
        previewAdapter?.submitList(previews)
        binding.tvPreviewHeader.text = "Rename Preview (${previews.size} files)"
    }

    private fun computeNewName(oldName: String): String {
        val dotIndex = oldName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) oldName.substring(0, dotIndex) else oldName
        val extension = if (dotIndex > 0) oldName.substring(dotIndex) else ""

        return when (binding.modeFlipper.displayedChild) {
            0 -> {
                val find = binding.etFind.text?.toString() ?: ""
                val replace = binding.etReplace.text?.toString() ?: ""
                if (find.isEmpty()) oldName
                else oldName.replace(find, replace, ignoreCase = true)
            }
            1 -> {
                val prefix = binding.etPrefix.text?.toString() ?: ""
                "${prefix}${oldName}"
            }
            2 -> {
                val suffix = binding.etSuffix.text?.toString() ?: ""
                "${baseName}${suffix}${extension}"
            }
            3 -> {
                val seqPrefix = binding.etSeqPrefix.text?.toString() ?: ""
                val startStr = binding.etStartNumber.text?.toString() ?: "1"
                val paddingStr = binding.etPaddingLength.text?.toString() ?: "3"
                val index = selectedFiles.indexOfFirst { getFileNameFromUri(it) == oldName }.coerceAtLeast(0)
                val startNumber = startStr.toIntOrNull() ?: 1
                val paddingLength = paddingStr.toIntOrNull() ?: 3
                val number = startNumber + index
                val padded = number.toString().padStart(paddingLength, '0')
                "${seqPrefix}${padded}${extension}"
            }
            4 -> {
                val pattern = binding.etRegexPattern.text?.toString() ?: ""
                val replacement = binding.etRegexReplacement.text?.toString() ?: ""
                if (pattern.isEmpty()) oldName
                else try {
                    oldName.replace(Regex(pattern), replacement)
                } catch (_: Exception) {
                    oldName
                }
            }
            else -> oldName
        }
    }

    private fun applyRename() {
        if (selectedFiles.isEmpty()) return

        val previews = previewAdapter?.currentList() ?: return
        val renamePairs = selectedFiles.zip(previews).filter { (uri, preview) ->
            preview.oldName != preview.newName
        }

        if (renamePairs.isEmpty()) {
            Snackbar.make(binding.root, "No files to rename", Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.btnApply.isEnabled = false
        binding.progressRename.visibility = View.VISIBLE

        var successCount = 0
        var failCount = 0

        for ((uri, preview) in renamePairs) {
            try {
                val oldFile = getFilePathFromUri(uri)
                if (oldFile != null) {
                    val file = File(oldFile)
                    if (file.exists()) {
                        val newFile = File(file.parent, preview.newName)
                        if (file.renameTo(newFile)) {
                            successCount++
                        } else {
                            failCount++
                        }
                    } else {
                        failCount++
                    }
                } else {
                    failCount++
                }
            } catch (_: Exception) {
                failCount++
            }
        }

        binding.progressRename.visibility = View.GONE
        binding.btnApply.isEnabled = true

        val message = buildString {
            append("$successCount file(s) renamed")
            if (failCount > 0) append(", $failCount failed")
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()

        if (successCount > 0) {
            selectedFiles.clear()
            previewAdapter?.submitList(emptyList())
            binding.cardPreview.visibility = View.GONE
            binding.btnApply.isEnabled = false
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    name = cursor.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        if (uri.scheme == "content") {
            val docId = uri.lastPathSegment ?: return null
            if (docId.contains(':')) {
                val path = docId.substringAfter(':')
                return "/storage/emulated/0/$path"
            }
        }
        return null
    }

    private fun simpleWatcher(afterChange: () -> Unit): android.text.TextWatcher {
        return object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                afterChange()
            }
        }
    }

    data class RenamePreview(
        val oldName: String,
        val newName: String
    )

    inner class RenamePreviewAdapter : RecyclerView.Adapter<RenamePreviewAdapter.VH>() {

        private var items: List<RenamePreview> = emptyList()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvOldName: TextView = view.findViewById(R.id.tvOldName)
            val tvNewName: TextView = view.findViewById(R.id.tvNewName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_rename_preview, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvOldName.text = item.oldName
            holder.tvOldName.paintFlags = holder.tvOldName.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            holder.tvNewName.text = item.newName
        }

        override fun getItemCount() = items.size

        fun submitList(newItems: List<RenamePreview>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun currentList(): List<RenamePreview> = items
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, BatchRenameActivity::class.java)
        }
    }
}
