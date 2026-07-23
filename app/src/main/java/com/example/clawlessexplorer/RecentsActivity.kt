package com.example.clawlessexplorer

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityRecentsBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentsBinding
    private lateinit var settings: SettingsManager
    private lateinit var adapter: RecentAdapter
    private val dateFormat = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.btnBack.setOnClickListener { finish() }

        adapter = RecentAdapter(
            onClick = { file -> openFile(file) },
            onLongClick = { file -> confirmRemove(file) }
        )
        binding.recentsList.layoutManager = LinearLayoutManager(this)
        binding.recentsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val recents = settings.validRecents()
        adapter.update(recents)
        binding.emptyState.visibility = if (recents.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openFile(file: File) {
        settings.addRecent(file.absolutePath)
        if (file.isDirectory) {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_PATH, file.absolutePath)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_PATH, file.absolutePath)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    private fun confirmRemove(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove from recents?")
            .setMessage("This will only remove ${file.name} from the recent files list. The file itself will not be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                val current = settings.recents.toMutableList()
                current.remove(file.absolutePath)
                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                prefs.edit().putString("recents_json", com.google.gson.Gson().toJson(current)).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============ Adapter ============

    inner class RecentAdapter(
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<RecentAdapter.VH>() {

        private var items: List<File> = emptyList()

        fun update(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view as MaterialCardView
            val badgeBackground: View = view.findViewById(R.id.badgeBackground)
            val icon: ImageView = view.findViewById(R.id.fileIcon)
            val name: TextView = view.findViewById(R.id.fileName)
            val meta: TextView = view.findViewById(R.id.fileMeta)
            val lockBadge: ImageView = view.findViewById(R.id.lockBadge)
            val moreIcon: ImageView = view.findViewById(R.id.moreIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            holder.name.text = file.name
            holder.lockBadge.visibility = if (file.name.endsWith(".locked")) View.VISIBLE else View.GONE
            holder.moreIcon.visibility = View.GONE

            val style = styleFor(file)
            holder.badgeBackground.setBackgroundResource(style.first)
            holder.icon.setImageResource(style.second)
            holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@RecentsActivity, style.third)
            )

            val dateStr = dateFormat.format(Date(file.lastModified()))
            holder.meta.text = if (file.isDirectory) {
                "$dateStr · Folder"
            } else {
                val sizeStr = Formatter.formatShortFileSize(this@RecentsActivity, file.length())
                "$sizeStr · $dateStr"
            }

            holder.itemView.setOnClickListener { onClick(file) }
            holder.itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongClick(file)
                true
            }
        }

        override fun getItemCount() = items.size

        private fun styleFor(file: File): Triple<Int, Int, Int> {
            if (file.name.endsWith(".locked")) {
                return Triple(R.drawable.bg_badge_locked, R.drawable.ic_file_locked, R.color.file_locked)
            }
            if (file.isDirectory) {
                return Triple(R.drawable.bg_badge_folder, R.drawable.ic_file_folder, R.color.file_folder)
            }
            val ext = file.extension.lowercase()
            return when {
                ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") ->
                    Triple(R.drawable.bg_badge_image, R.drawable.ic_file_image, R.color.file_image)
                ext in listOf("mp4", "mkv", "avi", "mov", "webm", "flv") ->
                    Triple(R.drawable.bg_badge_video, R.drawable.ic_file_video, R.color.file_video)
                ext in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma") ->
                    Triple(R.drawable.bg_badge_audio, R.drawable.ic_file_audio, R.color.file_audio)
                ext in listOf("pdf", "doc", "docx", "txt", "rtf", "odt", "epub", "log", "conf", "prop", "md", "csv", "xls", "xlsx", "ppt", "pptx") ->
                    Triple(R.drawable.bg_badge_document, R.drawable.ic_file_document, R.color.file_document)
                ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz") ->
                    Triple(R.drawable.bg_badge_archive, R.drawable.ic_file_archive, R.color.file_archive)
                ext in listOf("kt", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "rb", "go", "rs", "swift", "sh", "html", "css", "scss", "json", "xml", "yml", "yaml") ->
                    Triple(R.drawable.bg_badge_code, R.drawable.ic_file_code, R.color.file_code)
                ext == "apk" ->
                    Triple(R.drawable.bg_badge_apk, R.drawable.ic_file_apk, R.color.file_apk)
                else ->
                    Triple(R.drawable.bg_badge_generic, R.drawable.ic_file_generic, R.color.file_generic)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PATH = "extra_open_path"
    }
}
