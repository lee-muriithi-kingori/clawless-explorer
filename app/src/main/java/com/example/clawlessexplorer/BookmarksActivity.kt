package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityBookmarksBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var adapter: BookmarkAdapter
    private val dateFormat = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarkManager = BookmarkManager(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.btnBack.setOnClickListener { finish() }

        adapter = BookmarkAdapter(
            onClick = { bookmark -> openBookmark(bookmark) },
            onRemove = { bookmark -> removeBookmark(bookmark) }
        )
        binding.bookmarksList.layoutManager = LinearLayoutManager(this)
        binding.bookmarksList.adapter = adapter

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val bookmark = adapter.getItemAt(position)
                if (bookmark != null) {
                    removeBookmark(bookmark)
                }
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.bookmarksList)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val bookmarks = bookmarkManager.getBookmarks()
        adapter.update(bookmarks)
        binding.emptyState.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openBookmark(bookmark: BookmarkManager.Bookmark) {
        bookmarkManager.updateLastAccessed(bookmark.path)
        val file = File(bookmark.path)
        if (!file.exists()) {
            Snackbar.make(binding.root, "File no longer exists", Snackbar.LENGTH_SHORT).show()
            refresh()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_PATH, bookmark.path)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun removeBookmark(bookmark: BookmarkManager.Bookmark) {
        bookmarkManager.removeBookmark(bookmark.path)
        refresh()
        Snackbar.make(binding.root, "Bookmark removed", Snackbar.LENGTH_SHORT)
            .setAction("Undo") {
                bookmarkManager.addBookmark(bookmark.path)
                refresh()
            }
            .show()
    }

    inner class BookmarkAdapter(
        private val onClick: (BookmarkManager.Bookmark) -> Unit,
        private val onRemove: (BookmarkManager.Bookmark) -> Unit
    ) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

        private var items: List<BookmarkManager.Bookmark> = emptyList()

        fun update(newItems: List<BookmarkManager.Bookmark>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun getItemAt(position: Int): BookmarkManager.Bookmark? {
            return items.getOrNull(position)
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view as MaterialCardView
            val badgeBackground: View = view.findViewById(R.id.badgeBackground)
            val icon: ImageView = view.findViewById(R.id.fileIcon)
            val name: TextView = view.findViewById(R.id.bookmarkName)
            val path: TextView = view.findViewById(R.id.bookmarkPath)
            val date: TextView = view.findViewById(R.id.bookmarkDate)
            val btnRemove: View = view.findViewById(R.id.btnRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val bookmark = items[position]
            val file = File(bookmark.path)

            holder.name.text = bookmark.name
            holder.path.text = file.parent ?: bookmark.path
            holder.date.text = "Added ${dateFormat.format(Date(bookmark.addedTimestamp))}"

            val style = styleFor(file)
            holder.badgeBackground.setBackgroundResource(style.first)
            holder.icon.setImageResource(style.second)
            holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@BookmarksActivity, style.third)
            )

            holder.itemView.setOnClickListener { onClick(bookmark) }
            holder.btnRemove.setOnClickListener { onRemove(bookmark) }
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
        private const val EXTRA_OPEN_PATH = "extra_open_path"

        fun intent(context: Context): Intent {
            return Intent(context, BookmarksActivity::class.java)
        }
    }
}
