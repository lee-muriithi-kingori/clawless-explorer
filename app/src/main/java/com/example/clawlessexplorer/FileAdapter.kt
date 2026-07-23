package com.example.clawlessexplorer

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private var allFiles: List<File>,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val onMoreClick: (File) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var filteredFiles: List<File> = allFiles
    private val selectedFiles = mutableSetOf<File>()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    var isSelectionMode = false
        private set

    /** File type → (iconRes, badgeBgRes, accentColorRes) */
    private data class FileTypeStyle(
        val iconRes: Int,
        val badgeRes: Int,
        val accentRes: Int
    )

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val badgeBackground: View = view.findViewById(R.id.badgeBackground)
        val icon: ImageView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
        val lockBadge: ImageView = view.findViewById(R.id.lockBadge)
        val moreIcon: ImageView = view.findViewById(R.id.moreIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = filteredFiles[position]
        val ctx = holder.itemView.context

        holder.name.text = file.name
        holder.lockBadge.visibility = if (file.name.endsWith(".locked")) View.VISIBLE else View.GONE

        val isSelected = selectedFiles.contains(file)
        holder.card.isChecked = isSelected

        // Determine file type → style
        val style = styleFor(file, ctx)
        holder.badgeBackground.setBackgroundResource(style.badgeRes)
        holder.icon.setImageResource(style.iconRes)
        holder.icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, style.accentRes)
        )

        // Meta line
        val dateStr = dateFormat.format(Date(file.lastModified()))
        holder.meta.text = if (file.isDirectory) {
            val count = try { file.list()?.size ?: 0 } catch (e: Exception) { 0 }
            "$count items · $dateStr"
        } else {
            val sizeStr = Formatter.formatShortFileSize(ctx, file.length())
            "$sizeStr · $dateStr"
        }

        // Click handlers
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

        holder.moreIcon.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (isSelectionMode) {
                toggleSelection(file)
            } else {
                onMoreClick(file)
            }
        }

        // Subtle entry animation on first bind
        if (!holder.itemView.hasBeenAnimated) {
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 12f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setStartDelay((position.coerceAtMost(12) * 20L))
                .start()
            holder.itemView.hasBeenAnimated = true
        }
    }

    private var View.hasBeenAnimated: Boolean
        get() = getTag(R.id.tag_animated) as? Boolean ?: false
        set(value) = setTag(R.id.tag_animated, value)

    /** Build the (icon, bg, accent) tuple for a file. */
    private fun styleFor(file: File, ctx: android.content.Context): FileTypeStyle {
        if (file.name.endsWith(".locked")) {
            return FileTypeStyle(R.drawable.ic_file_locked, R.drawable.bg_badge_locked, R.color.file_locked)
        }
        if (file.isDirectory) {
            return FileTypeStyle(R.drawable.ic_file_folder, R.drawable.bg_badge_folder, R.color.file_folder)
        }
        val ext = file.extension.lowercase()
        return when {
            ext in listOf("jpg", "jpeg", "png", "webp", "gif") ->
                FileTypeStyle(R.drawable.ic_file_image, R.drawable.bg_badge_image, R.color.file_image)
            ext in listOf("mp4", "mkv", "avi", "mov", "webm") ->
                FileTypeStyle(R.drawable.ic_file_video, R.drawable.bg_badge_video, R.color.file_video)
            ext in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a") ->
                FileTypeStyle(R.drawable.ic_file_audio, R.drawable.bg_badge_audio, R.color.file_audio)
            ext in listOf("pdf", "doc", "docx", "txt", "rtf", "odt", "epub", "log", "conf", "prop", "md") ->
                FileTypeStyle(R.drawable.ic_file_document, R.drawable.bg_badge_document, R.color.file_document)
            ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ->
                FileTypeStyle(R.drawable.ic_file_archive, R.drawable.bg_badge_archive, R.color.file_archive)
            ext in listOf("kt", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "rb", "go", "rs", "swift", "sh", "html", "css", "scss", "json", "xml", "yml", "yaml") ->
                FileTypeStyle(R.drawable.ic_file_code, R.drawable.bg_badge_code, R.color.file_code)
            ext == "apk" ->
                FileTypeStyle(R.drawable.ic_file_apk, R.drawable.bg_badge_apk, R.color.file_apk)
            else ->
                FileTypeStyle(R.drawable.ic_file_generic, R.drawable.bg_badge_generic, R.color.file_generic)
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

        // Reset animation flag on holder creation
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
