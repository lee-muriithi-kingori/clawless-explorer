package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityZipToolsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipToolsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_DIR_PATH = "extra_dir_path"

        private const val MODE_COMPRESS = "compress"
        private const val MODE_EXTRACT = "extract"

        fun intent(context: Context, mode: String, path: String = ""): Intent {
            return Intent(context, ZipToolsActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                if (path.isNotEmpty()) putExtra(EXTRA_FILE_PATH, path)
            }
        }
    }

    private lateinit var binding: ActivityZipToolsBinding

    private var currentMode = MODE_COMPRESS
    private val selectedFiles = mutableListOf<Uri>()
    private val zipEntries = mutableListOf<ZipEntryInfo>()
    private var selectedZipUri: Uri? = null
    private var destinationPath: String = ""

    private lateinit var selectedFilesAdapter: ZipEntryAdapter
    private lateinit var zipContentsAdapter: ZipEntryAdapter

    private val pickMultipleFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedFiles.clear()
            selectedFiles.addAll(uris)
            onFilesSelected()
        }
    }

    private val pickZipFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedZipUri = it
            onZipFileSelected(it)
        }
    }

    private val pickDestinationTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destinationPath = it.toString()
            updateDestinationDisplay(it)
        }
    }

    private val createZipFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { outputUri ->
            performCompress(outputUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityZipToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbar()
        setupModeToggle()
        setupCompressUI()
        setupExtractUI()
        handleIntentExtras()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupModeToggle() {
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeCompress -> switchMode(MODE_COMPRESS)
                R.id.btnModeExtract -> switchMode(MODE_EXTRACT)
            }
        }
    }

    private fun switchMode(mode: String) {
        currentMode = mode
        when (mode) {
            MODE_COMPRESS -> {
                binding.modeFlipper.displayedChild = 0
            }
            MODE_EXTRACT -> {
                binding.modeFlipper.displayedChild = 1
            }
        }
    }

    private fun setupCompressUI() {
        selectedFilesAdapter = ZipEntryAdapter(emptyList())
        binding.rvSelectedFiles.apply {
            layoutManager = LinearLayoutManager(this@ZipToolsActivity)
            adapter = selectedFilesAdapter
        }

        binding.btnSelectFiles.setOnClickListener {
            pickMultipleFiles.launch(arrayOf("*/*"))
        }

        binding.btnCompress.setOnClickListener {
            val filename = binding.etOutputFilename.text?.toString()?.trim()
            if (filename.isNullOrEmpty()) {
                binding.tilOutputFilename.error = "Enter a filename"
                return@setOnClickListener
            }
            binding.tilOutputFilename.error = null
            val outputName = if (filename.endsWith(".zip", ignoreCase = true)) filename else "$filename.zip"
            createZipFile.launch(outputName)
        }
    }

    private fun setupExtractUI() {
        zipContentsAdapter = ZipEntryAdapter(emptyList())
        binding.rvZipContents.apply {
            layoutManager = LinearLayoutManager(this@ZipToolsActivity)
            adapter = zipContentsAdapter
        }

        binding.btnSelectZip.setOnClickListener {
            pickZipFile.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }

        binding.btnChangeDestination.setOnClickListener {
            pickDestinationTree.launch(null)
        }

        binding.btnExtract.setOnClickListener {
            selectedZipUri?.let { uri ->
                if (destinationPath.isEmpty()) {
                    showDestinationFromSource(uri)
                }
                performExtract(uri)
            }
        }
    }

    private fun handleIntentExtras() {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_COMPRESS
        when (mode) {
            MODE_COMPRESS -> {
                binding.modeToggleGroup.check(R.id.btnModeCompress)
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                if (!filePath.isNullOrEmpty()) {
                    val file = File(filePath)
                    if (file.exists()) {
                        selectedFiles.clear()
                        selectedFiles.add(Uri.fromFile(file))
                        onFilesSelected()
                    }
                }
                val dirPath = intent.getStringExtra(EXTRA_DIR_PATH)
                if (!dirPath.isNullOrEmpty()) {
                    val dir = File(dirPath)
                    if (dir.isDirectory) {
                        addDirectoryFiles(dir)
                        onFilesSelected()
                    }
                }
            }
            MODE_EXTRACT -> {
                binding.modeToggleGroup.check(R.id.btnModeExtract)
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                if (!filePath.isNullOrEmpty()) {
                    val file = File(filePath)
                    if (file.exists()) {
                        selectedZipUri = Uri.fromFile(file)
                        onZipFileSelected(Uri.fromFile(file))
                    }
                }
            }
        }
    }

    private fun addDirectoryFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                addDirectoryFiles(file)
            } else {
                selectedFiles.add(Uri.fromFile(file))
            }
        }
    }

    private fun onFilesSelected() {
        if (selectedFiles.isEmpty()) {
            binding.cardSelectedFiles.visibility = View.GONE
            binding.btnCompress.isEnabled = false
            return
        }
        binding.cardSelectedFiles.visibility = View.VISIBLE
        binding.btnCompress.isEnabled = true
        val entries = selectedFiles.map { uri ->
            val name = getFileNameFromUri(uri)
            val size = getFileSizeFromUri(uri)
            ZipEntryInfo(name, formatFileSize(size))
        }
        selectedFilesAdapter.updateData(entries)
    }

    private fun onZipFileSelected(uri: Uri) {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                readZipEntries(uri)
            }
            zipEntries.clear()
            zipEntries.addAll(entries)
            zipContentsAdapter.updateData(entries)
            binding.cardZipContents.visibility = View.VISIBLE
            binding.btnExtract.isEnabled = entries.isNotEmpty()

            if (destinationPath.isEmpty()) {
                showDestinationFromSource(uri)
            }

            binding.cardDestination.visibility = View.VISIBLE
        }
    }

    private fun showDestinationFromSource(uri: Uri) {
        val name = getFileNameFromUri(uri)
        val displayName = name.removeSuffix(".zip").removeSuffix(".ZIP")
        binding.tvExtractStatus.text = "Extract to: $displayName/"
        binding.tvExtractStatus.visibility = View.VISIBLE
    }

    private fun updateDestinationDisplay(treeUri: Uri) {
        val treeName = getFileNameFromUri(treeUri)
        binding.tvExtractStatus.text = "Extract to: $treeName/"
        binding.tvExtractStatus.visibility = View.VISIBLE
    }

    private fun performCompress(outputUri: Uri) {
        binding.progressCompress.visibility = View.VISIBLE
        binding.btnCompress.isEnabled = false
        binding.tvCompressStatus.visibility = View.VISIBLE
        binding.tvCompressStatus.text = "Compressing..."

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                        ZipOutputStream(outputStream).use { zipOut ->
                            for (uri in selectedFiles) {
                                compressUri(uri, zipOut)
                            }
                        }
                    }
                }

                val filename = getFileNameFromUri(outputUri)
                val size = withContext(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(outputUri, "r")?.use {
                        it.statSize
                    } ?: 0L
                }

                binding.progressCompress.visibility = View.GONE
                binding.tvCompressStatus.text = "Done! ${formatFileSize(size)}"
                binding.btnCompress.isEnabled = true

                Snackbar.make(
                    binding.root,
                    "Created $filename (${formatFileSize(size)})",
                    Snackbar.LENGTH_LONG
                ).setAction("Open") {
                    openFileIntent(outputUri, "application/zip")
                }.show()

            } catch (e: Exception) {
                binding.progressCompress.visibility = View.GONE
                binding.btnCompress.isEnabled = true
                binding.tvCompressStatus.text = "Failed: ${e.message}"
                Toast.makeText(this@ZipToolsActivity, "Compression failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun compressUri(uri: Uri, zipOut: ZipOutputStream) {
        val entryName = getFileNameFromUri(uri)
        val entry = ZipEntry(entryName)

        contentResolver.openInputStream(uri)?.use { inputStream ->
            zipOut.putNextEntry(entry)
            inputStream.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun performExtract(sourceUri: Uri) {
        binding.progressExtract.visibility = View.VISIBLE
        binding.btnExtract.isEnabled = false
        binding.tvExtractStatus.visibility = View.VISIBLE
        binding.tvExtractStatus.text = "Extracting..."

        val treeUri = destinationPath.ifEmpty { null }?.let { runCatching { Uri.parse(it) }.getOrNull() }

        lifecycleScope.launch {
            try {
                val report: Triple<Int, Int, String> = withContext(Dispatchers.IO) {
                    if (treeUri != null && treeUri.scheme == "content") {
                        extractToTree(sourceUri, treeUri)
                    } else {
                        val destDir = resolveDestinationDirectory(sourceUri)
                        val (extracted, skipped) = extractToDir(sourceUri, destDir)
                        Triple(extracted, skipped, destDir.absolutePath)
                    }
                }
                val (count, skipped, destLabel) = report
                binding.progressExtract.visibility = View.GONE
                val skippedNote = if (skipped > 0) " ($skipped unsafe paths skipped)" else ""
                binding.tvExtractStatus.text = "Done! Extracted $count files to $destLabel$skippedNote"

                Snackbar.make(
                    binding.root,
                    "Extracted $count files to $destLabel$skippedNote",
                    Snackbar.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                binding.progressExtract.visibility = View.GONE
                binding.btnExtract.isEnabled = true
                binding.tvExtractStatus.text = "Failed: ${e.message}"
                Toast.makeText(this@ZipToolsActivity, "Extraction failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Entry names that would escape [destDir] — the Zip-Slip guard. */
    private fun isUnsafeEntry(name: String): Boolean {
        if (name.isBlank()) return true
        val parts = name.replace('\\', '/').split('/')
        if (parts.any { it == ".." }) return true
        if (name.startsWith("/")) return true
        if (name.length > 2 && name[1] == ':') return true
        return false
    }

    private fun safeFile(destDir: File, entryName: String): File? {
        if (isUnsafeEntry(entryName)) return null
        val out = File(destDir, entryName)
        return if (out.canonicalPath == destDir.canonicalPath ||
            out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) out else null
    }

    /** File-based extraction. Returns (extracted count, skipped count). */
    private fun extractToDir(sourceUri: Uri, destDir: File): Pair<Int, Int> {
        var count = 0
        var skipped = 0
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val out = safeFile(destDir, entry.name)
                    if (out == null) {
                        skipped++
                    } else if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zipIn.copyTo(fos) }
                        count++
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
        return count to skipped
    }

    /** SAF extraction into a user-picked tree. Returns (count, skipped, display name). */
    private fun extractToTree(sourceUri: Uri, treeUri: Uri): Triple<Int, Int, String> {
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
            ?: throw IllegalArgumentException("Cannot open destination folder")
        val zipName = getFileNameFromUri(sourceUri).removeSuffix(".zip").removeSuffix(".ZIP")
        val root = tree.createDirectory(zipName.ifEmpty { "extracted" })
            ?: throw IllegalStateException("Cannot create folder in destination")
        var count = 0
        var skipped = 0
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (isUnsafeEntry(entry.name)) {
                        skipped++
                    } else if (!entry.isDirectory) {
                        if (writeTreeEntry(root, entry.name) { out -> zipIn.copyTo(out) }) count++ else skipped++
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
        return Triple(count, skipped, tree.name ?: "chosen folder")
    }

    private fun writeTreeEntry(root: androidx.documentfile.provider.DocumentFile, entryName: String, write: (java.io.OutputStream) -> Unit): Boolean {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        var dir = root
        for (part in parts.dropLast(1)) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(part)
                ?: return false
        }
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(parts.last().substringAfterLast('.', "")) ?: "application/octet-stream"
        val doc = dir.createFile(mime, parts.last()) ?: return false
        return try {
            contentResolver.openOutputStream(doc.uri)?.use(write)
            true
        } catch (_: Exception) {
            doc.delete()
            false
        }
    }

    private fun resolveDestinationDirectory(sourceUri: Uri): File {
        val sourceName = getFileNameFromUri(sourceUri)
        val dirName = sourceName.removeSuffix(".zip").removeSuffix(".ZIP")
        val sourcePath = getFilePathFromUri(sourceUri)

        return if (sourcePath != null) {
            val parent = File(sourcePath).parentFile ?: cacheDir
            val destDir = File(parent, dirName)
            destDir.mkdirs()
            destDir
        } else {
            val destDir = File(cacheDir, dirName)
            destDir.mkdirs()
            destDir
        }
    }

    private fun readZipEntries(uri: Uri): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    entries.add(
                        ZipEntryInfo(
                            name = entry.name,
                            size = formatFileSize(entry.size)
                        )
                    )
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
        return entries
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    name = cursor.getString(idx) ?: "unknown"
                }
            }
        }
        if (name == "unknown") {
            name = uri.lastPathSegment ?: "unknown"
        }
        return name
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0) {
                    return cursor.getLong(idx)
                }
            }
        }
        return 0L
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        return null
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun openFileIntent(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    data class ZipEntryInfo(
        val name: String,
        val size: String
    )

    inner class ZipEntryAdapter(
        private var items: List<ZipEntryInfo>
    ) : RecyclerView.Adapter<ZipEntryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName: android.widget.TextView = view.findViewById(R.id.tvFileName)
            val tvFileSize: android.widget.TextView = view.findViewById(R.id.tvFileSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_zip_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvFileName.text = item.name
            holder.tvFileSize.text = item.size
        }

        override fun getItemCount() = items.size

        fun updateData(newItems: List<ZipEntryInfo>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
