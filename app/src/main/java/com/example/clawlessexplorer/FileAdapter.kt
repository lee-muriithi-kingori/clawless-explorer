package com.example.clawlessexplorer

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private var allFiles: List<File>,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var filteredFiles: List<File> = allFiles
    private val selectedFiles = mutableSetOf<File>()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    var isSelectionMode = false
        private set

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
        val card: com.google.android.material.card.MaterialCardView = view as com.google.android.material.card.MaterialCardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = filteredFiles[position]
        holder.name.text = file.name
        val isSelected = selectedFiles.contains(file)
        holder.card.isChecked = isSelected
        
        val dateStr = dateFormat.format(Date(file.lastModified()))
        
        // Thumbnail/Icon logic
        if (file.isDirectory) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_directions)
            val itemCount = try { file.list()?.size ?: 0 } catch (e: Exception) { 0 }
            holder.meta.text = "$itemCount items | $dateStr"
        } else {
            val extension = file.extension.lowercase()
            when {
                extension in listOf("jpg", "jpeg", "png", "webp", "gif") -> {
                    holder.icon.load(file) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                }
                extension == "apk" -> {
                    val pm = holder.itemView.context.packageManager
                    val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                    if (info != null) {
                        info.applicationInfo.sourceDir = file.absolutePath
                        info.applicationInfo.publicSourceDir = file.absolutePath
                        holder.icon.setImageDrawable(info.applicationInfo.loadIcon(pm))
                    } else {
                        holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
                extension == "pdf" -> {
                    holder.icon.setImageResource(android.R.drawable.ic_menu_edit) // Placeholder for PDF
                    holder.icon.setColorFilter(android.graphics.Color.RED)
                }
                extension in listOf("zip", "rar", "7z", "tar", "gz") -> {
                    holder.icon.setImageResource(android.R.drawable.ic_menu_save)
                    holder.icon.setColorFilter(android.graphics.Color.parseColor("#FFA500")) // Orange
                }
                extension in listOf("mp4", "mkv", "avi") -> {
                    holder.icon.setImageResource(android.R.drawable.ic_media_play)
                    holder.icon.setColorFilter(null)
                }
                extension in listOf("mp3", "wav", "flac") -> {
                    holder.icon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    holder.icon.setColorFilter(null)
                }
                else -> {
                    holder.icon.setImageResource(android.R.drawable.ic_menu_view)
                    holder.icon.setColorFilter(null)
                }
            }
            val sizeStr = Formatter.formatShortFileSize(holder.itemView.context, file.length())
            holder.meta.text = "$sizeStr | $dateStr"
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(file)
            } else {
                onItemClick(file)
            }
        }

        holder.itemView.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (!isSelectionMode) {
                isSelectionMode = true
                toggleSelection(file)
                onItemLongClick(file)
            } else {
                toggleSelection(file)
            }
            true
        }
    }

    override fun getItemCount() = filteredFiles.size

    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
        } else {
            selectedFiles.add(file)
        }
        
        if (selectedFiles.isEmpty()) {
            isSelectionMode = false
        }
        
        notifyItemChanged(filteredFiles.indexOf(file))
        onSelectionChanged(selectedFiles.size)
    }

    fun clearSelection() {
        isSelectionMode = false
        selectedFiles.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedFiles(): List<File> = selectedFiles.toList()

    fun selectAll() {
        isSelectionMode = true
        selectedFiles.clear()
        selectedFiles.addAll(filteredFiles)
        notifyDataSetChanged()
        onSelectionChanged(selectedFiles.size)
    }

    fun updateFiles(newFiles: List<File>) {
        val diffCallback = FileDiffCallback(filteredFiles, newFiles)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        
        allFiles = newFiles
        filteredFiles = newFiles
        selectedFiles.clear()
        isSelectionMode = false
        
        diffResult.dispatchUpdatesTo(this)
    }

    class FileDiffCallback(
        private val oldList: List<File>,
        private val newList: List<File>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = 
            oldList[oldPos].absolutePath == newList[newPos].absolutePath
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = 
            oldList[oldPos] == newList[newPos] && oldList[oldPos].lastModified() == newList[newPos].lastModified()
    }

    fun filter(query: String) {
        filteredFiles = if (query.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }
}
