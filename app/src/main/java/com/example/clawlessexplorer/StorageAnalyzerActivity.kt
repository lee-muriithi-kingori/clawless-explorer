package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityStorageAnalyzerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicBoolean

class StorageAnalyzerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageAnalyzerBinding
    private var scanJob: Job? = null
    private val isScanning = AtomicBoolean(false)

    companion object {
        const val EXTRA_PATH = "extra_path"
        private const val MAX_LARGEST_FILES = 20

        fun intent(context: Context, path: String = ""): Intent {
            return Intent(context, StorageAnalyzerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
            }
        }
    }

    enum class FileCategory(
        val displayName: String,
        val extensions: Set<String>,
        val colorRes: Int
    ) {
        IMAGES("Images", setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "tiff", "ico"), R.color.file_folder),
        VIDEOS("Videos", setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts"), R.color.file_video),
        AUDIO("Audio", setOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus", "amr", "mid"), R.color.file_audio),
        DOCUMENTS("Documents", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "epub", "md"), R.color.file_document),
        ARCHIVES("Archives", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg"), R.color.file_archive),
        CODE("Code", setOf("java", "kt", "py", "js", "ts", "c", "cpp", "h", "cs", "rb", "go", "rs", "swift", "html", "css", "xml", "json", "yaml", "yml", "sh", "sql", "php"), R.color.file_code),
        APKs("APKs", setOf("apk", "xapk", "aab"), R.color.file_apk),
        OTHER("Other", emptySet(), R.color.file_generic)
    }

    data class FileInfo(
        val file: File,
        val size: Long,
        val category: FileCategory
    )

    data class CategoryInfo(
        val category: FileCategory,
        val totalSize: Long,
        val fileCount: Int,
        val files: List<FileInfo>
    )

    private lateinit var startPath: String
    private var totalSpace: Long = 0
    private var usedSpace: Long = 0
    private var freeSpace: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityStorageAnalyzerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startPath = intent.getStringExtra(EXTRA_PATH)
            ?: Environment.getExternalStorageDirectory().absolutePath

        binding.backButton.setOnClickListener { finish() }

        startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
    }

    private fun startScan() {
        if (isScanning.compareAndSet(false, true)) return

        showLoading(true)

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val stat = StatFs(startPath)
                totalSpace = stat.totalBytes
                freeSpace = stat.availableBytes
                usedSpace = totalSpace - freeSpace

                val filesByCategory = mutableMapOf<FileCategory, MutableList<FileInfo>>()
                FileCategory.entries.forEach { filesByCategory[it] = mutableListOf() }

                scanDirectory(File(startPath), filesByCategory)

                val categoryInfos = FileCategory.entries.mapNotNull { cat ->
                    val files = filesByCategory[cat]
                    if (files != null && files.isNotEmpty()) {
                        CategoryInfo(
                            category = cat,
                            totalSize = files.sumOf { it.size },
                            fileCount = files.size,
                            files = files.sortedByDescending { it.size }
                        )
                    } else null
                }.sortedByDescending { it.totalSize }

                val allFiles = filesByCategory.values.flatten()
                val largestFiles = allFiles.sortedByDescending { it.size }.take(MAX_LARGEST_FILES)

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showContent()
                    displayStorageOverview()
                    displayCategoryBreakdown(categoryInfos)
                    displayLargestFiles(largestFiles)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@StorageAnalyzerActivity,
                        "Error scanning storage: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isScanning.set(false)
            }
        }
    }

    private fun scanDirectory(
        directory: File,
        filesByCategory: MutableMap<FileCategory, MutableList<FileInfo>>
    ) {
        try {
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (Thread.currentThread().isInterrupted) return
                if (child.isFile) {
                    val ext = child.extension.lowercase()
                    val category = categorizeFile(ext)
                    val size = try { child.length() } catch (_: Exception) { 0L }
                    if (size > 0) {
                        filesByCategory[category]?.add(FileInfo(child, size, category))
                    }
                } else if (child.isDirectory && !child.name.startsWith(".")) {
                    scanDirectory(child, filesByCategory)
                }
            }
        } catch (_: SecurityException) {
            // Skip directories we don't have permission to read
        } catch (_: Exception) {
            // Skip problematic directories
        }
    }

    private fun categorizeFile(extension: String): FileCategory {
        for (cat in FileCategory.entries) {
            if (extension in cat.extensions) return cat
        }
        return FileCategory.OTHER
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.loadingText.visibility = if (show) View.VISIBLE else View.GONE
        binding.contentScrollView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showContent() {
        binding.contentScrollView.visibility = View.VISIBLE
    }

    private fun displayStorageOverview() {
        val df = DecimalFormat("#.##")

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${df.format(kb)} KB"
            val mb = kb / 1024.0
            if (mb < 1024) return "${df.format(mb)} MB"
            val gb = mb / 1024.0
            return "${df.format(gb)} GB"
        }

        binding.totalSpaceText.text = formatBytes(totalSpace)
        binding.usedSpaceText.text = formatBytes(usedSpace)
        binding.freeSpaceText.text = formatBytes(freeSpace)

        val usagePercent = if (totalSpace > 0) ((usedSpace.toFloat() / totalSpace.toFloat()) * 100).toInt() else 0
        binding.storageProgressBar.progress = usagePercent
    }

    private fun displayCategoryBreakdown(categories: List<CategoryInfo>) {
        binding.categoryContainer.removeAllViews()
        val totalUsed = categories.sumOf { it.totalSize }

        for (catInfo in categories) {
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_storage_category, binding.categoryContainer, false)

            val iconCircle = view.findViewById<View>(R.id.iconCircle)
            val categoryName = view.findViewById<TextView>(R.id.categoryName)
            val categorySizeCount = view.findViewById<TextView>(R.id.categorySizeCount)
            val categoryPercentage = view.findViewById<TextView>(R.id.categoryPercentage)
            val categoryProgressBar = view.findViewById<ProgressBar>(R.id.categoryProgressBar)

            val color = ContextCompat.getColor(this, catInfo.category.colorRes)
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color and 0x33FFFFFF or (0xFF000000.toInt() and 0x00FFFFFF))
                setColor(android.graphics.Color.argb(40, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)))
            }
            iconCircle.background = circleDrawable

            categoryName.text = catInfo.category.displayName

            val df = DecimalFormat("#.##")
            fun formatBytes(bytes: Long): String {
                if (bytes < 1024) return "$bytes B"
                val kb = bytes / 1024.0
                if (kb < 1024) return "${df.format(kb)} KB"
                val mb = kb / 1024.0
                if (mb < 1024) return "${df.format(mb)} MB"
                val gb = mb / 1024.0
                return "${df.format(gb)} GB"
            }

            categorySizeCount.text = "${formatBytes(catInfo.totalSize)} · ${catInfo.fileCount} files"

            val percent = if (totalUsed > 0) ((catInfo.totalSize.toFloat() / totalUsed.toFloat()) * 100).toInt() else 0
            categoryPercentage.text = "$percent%"
            categoryProgressBar.progress = percent
            val progressDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(color)
            }
            categoryProgressBar.progressDrawable = progressDrawable

            view.setOnClickListener {
                openCategoryFiles(catInfo)
            }

            binding.categoryContainer.addView(view)
        }
    }

    private fun displayLargestFiles(files: List<FileInfo>) {
        binding.largestFilesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.largestFilesRecyclerView.adapter = LargestFilesAdapter(files)
    }

    private fun openCategoryFiles(categoryInfo: CategoryInfo) {
        val intent = CategoryFileListActivity.intent(
            this,
            categoryName = categoryInfo.category.displayName,
            filePaths = categoryInfo.files.map { it.file.absolutePath }.toTypedArray(),
            fileSizes = categoryInfo.files.map { it.size }.toLongArray()
        )
        startActivity(intent)
    }

    class LargestFilesAdapter(
        private val files: List<FileInfo>
    ) : RecyclerView.Adapter<LargestFilesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileIconCircle: View = view.findViewById(R.id.fileIconCircle)
            val fileName: TextView = view.findViewById(R.id.fileName)
            val filePath: TextView = view.findViewById(R.id.filePath)
            val fileSize: TextView = view.findViewById(R.id.fileSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_largest_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val fileInfo = files[position]
            val color = ContextCompat.getColor(holder.itemView.context, fileInfo.category.colorRes)
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(40, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)))
            }
            holder.fileIconCircle.background = circleDrawable

            holder.fileName.text = fileInfo.file.name
            holder.filePath.text = fileInfo.file.parent ?: ""
            holder.fileSize.text = formatBytes(fileInfo.size)
        }

        override fun getItemCount() = files.size

        private fun formatBytes(bytes: Long): String {
            val df = DecimalFormat("#.##")
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${df.format(kb)} KB"
            val mb = kb / 1024.0
            if (mb < 1024) return "${df.format(mb)} MB"
            val gb = mb / 1024.0
            return "${df.format(gb)} GB"
        }
    }
}
