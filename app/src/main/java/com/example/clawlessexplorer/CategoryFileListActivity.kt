package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityCategoryFileListBinding
import java.io.File
import java.text.DecimalFormat

class CategoryFileListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryFileListBinding

    companion object {
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
        const val EXTRA_FILE_PATHS = "extra_file_paths"
        const val EXTRA_FILE_SIZES = "extra_file_sizes"

        fun intent(
            context: Context,
            categoryName: String,
            filePaths: Array<String>,
            fileSizes: LongArray
        ): Intent {
            return Intent(context, CategoryFileListActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY_NAME, categoryName)
                putExtra(EXTRA_FILE_PATHS, filePaths)
                putExtra(EXTRA_FILE_SIZES, fileSizes)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCategoryFileListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME) ?: "Files"
        val filePaths = intent.getStringArrayExtra(EXTRA_FILE_PATHS) ?: emptyArray()
        val fileSizes = intent.getLongArrayExtra(EXTRA_FILE_SIZES) ?: longArrayOf()

        binding.toolbarTitle.text = categoryName
        binding.backButton.setOnClickListener { finish() }

        val entries = filePaths.indices.map { i ->
            FileEntry(
                path = filePaths[i],
                size = if (i < fileSizes.size) fileSizes[i] else 0L
            )
        }.sortedByDescending { it.size }

        binding.fileCount.text = "${entries.size} files"

        binding.fileListRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.fileListRecyclerView.adapter = FileListAdapter(entries)
    }

    data class FileEntry(val path: String, val size: Long)

    class FileListAdapter(
        private val entries: List<FileEntry>
    ) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
            val entry = entries[position]
            val file = File(entry.path)
            holder.fileName.text = file.name
            holder.filePath.text = file.parent ?: ""
            holder.fileSize.text = formatBytes(entry.size)
        }

        override fun getItemCount() = entries.size

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
