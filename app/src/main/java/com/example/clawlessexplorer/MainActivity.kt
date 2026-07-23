package com.example.clawlessexplorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityMainBinding
import com.example.clawlessexplorer.server.FileServer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private var showHiddenFiles: Boolean = true
    private var fileServer: FileServer? = null
    private var sortType: SortType = SortType.NAME

    enum class SortType { NAME, DATE, SIZE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupToolbar()
        setupHeaderButtons()
        setupDrawer()
        setupRecyclerView()
        setupListeners()
        setupSearch()
        setupBackPress()
        setupFabScrollBehavior()
        updateStorageInfo()
        checkPermissionsAndLoadFiles()
        startFileServer()

        binding.tvTypewriter.setCharacterDelay(100)
        binding.tvTypewriter.animateText("Clawless Explorer")
    }

    private fun startFileServer() {
        fileServer = FileServer(Environment.getExternalStorageDirectory())
        fileServer?.start()
        // Server status hidden from UI by design.
    }

    override fun onDestroy() {
        super.onDestroy()
        fileServer?.stop()
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }
                    adapter.isSelectionMode -> {
                        adapter.clearSelection()
                        resetToolbar()
                    }
                    binding.searchLayout.visibility == View.VISIBLE -> {
                        toggleSearch()
                    }
                    else -> {
                        val root = Environment.getExternalStorageDirectory()
                        if (currentPath.absolutePath != root.absolutePath && currentPath.parentFile != null) {
                            navigateTo(currentPath.parentFile!!)
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        })
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener(null)
    }

    private fun setupHeaderButtons() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnSearch.setOnClickListener {
            toggleSearch()
        }
    }

    private fun setupDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> navigateTo(Environment.getExternalStorageDirectory())
                R.id.nav_root -> navigateTo(File("/"))
                R.id.nav_sdcard -> {
                    val externalFilesDirs = getExternalFilesDirs(null)
                    if (externalFilesDirs.size > 1) {
                        val sdCardPath = externalFilesDirs[1].absolutePath.split("/Android")[0]
                        navigateTo(File(sdCardPath))
                    } else {
                        Toast.makeText(this, "SD Card not found", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.nav_downloads -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                R.id.nav_dcim -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
            }
            binding.drawerLayout.closeDrawers()
            true
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
            }

            binding.fabAdd.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navigationBars.bottom + 20.toPx()
            }

            binding.recyclerView.setPadding(
                binding.recyclerView.paddingLeft,
                binding.recyclerView.paddingTop,
                binding.recyclerView.paddingRight,
                navigationBars.bottom + 120.toPx()
            )

            insets
        }
    }

    private fun Int.toPx() = (this * resources.displayMetrics.density).toInt()

    private fun setupRecyclerView() {
        adapter = FileAdapter(
            allFiles = emptyList(),
            onItemClick = { file ->
                if (file.isDirectory) {
                    navigateTo(file)
                } else {
                    showFileActions(file)
                }
            },
            onItemLongClick = { _ ->
                updateToolbarForSelection()
            },
            onMoreClick = { file ->
                showFileActions(file)
            },
            onSelectionChanged = { count ->
                if (count == 0) {
                    resetToolbar()
                } else {
                    updateSelectionTitle(count)
                }
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /** Hide FAB on scroll down, show on scroll up — small modern touch. */
    private fun setupFabScrollBehavior() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var dy = 0
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                this.dy += dy
                if (dy > 4 && binding.fabAdd.isExtended) {
                    binding.fabAdd.shrink()
                } else if (dy < -4 && !binding.fabAdd.isExtended) {
                    binding.fabAdd.extend()
                }
            }
        })
    }

    private fun updateToolbarForSelection() {
        if (binding.searchLayout.visibility == View.VISIBLE) toggleSearch()
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_selection)
        binding.btnMenu.setImageResource(R.drawable.ic_close)
        binding.btnMenu.setOnClickListener {
            adapter.clearSelection()
            resetToolbar()
        }
        binding.btnSearch.visibility = View.GONE
    }

    private fun updateSelectionTitle(count: Int) {
        binding.tvTypewriter.animateText("$count selected")
    }

    private fun resetToolbar() {
        binding.tvTypewriter.animateText("Clawless Explorer")
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.btnMenu.setImageResource(R.drawable.ic_menu)
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnSearch.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_delete -> { deleteSelectedFiles(); true }
                R.id.action_share -> { shareSelectedFiles(); true }
                R.id.action_refresh -> { loadFiles(currentPath); true }
                R.id.action_show_hidden -> {
                    it.isChecked = !it.isChecked
                    showHiddenFiles = it.isChecked
                    loadFiles(currentPath)
                    true
                }
                R.id.action_go_to_root -> { navigateTo(File("/")); true }
                R.id.sort_name -> { sortType = SortType.NAME; loadFiles(currentPath); true }
                R.id.sort_date -> { sortType = SortType.DATE; loadFiles(currentPath); true }
                R.id.sort_size -> { sortType = SortType.SIZE; loadFiles(currentPath); true }
                R.id.action_select_all -> { adapter.selectAll(); true }
                else -> false
            }
        }

        binding.fabAdd.setOnClickListener { showCreateOptions() }
    }

    private fun toggleSearch() {
        if (binding.searchLayout.visibility == View.VISIBLE) {
            binding.searchLayout.visibility = View.GONE
            binding.searchEditText.text?.clear()
        } else {
            binding.searchLayout.visibility = View.VISIBLE
            binding.searchLayout.alpha = 0f
            binding.searchLayout.translationY = -8f
            binding.searchLayout.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
            binding.searchEditText.requestFocus()
        }
    }

    private fun deleteSelectedFiles() {
        val selected = adapter.getSelectedFiles()
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${selected.size} items?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                selected.forEach { it.deleteRecursively() }
                loadFiles(currentPath)
                adapter.clearSelection()
                resetToolbar()
                updateStorageInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareSelectedFiles() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return

        val uris = ArrayList<Uri>()
        selected.forEach {
            if (!it.isDirectory) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.provider", it))
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No files to share (directories cannot be shared)", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = if (uris.size == 1) getMimeType(selected.first { !it.isDirectory }) else "*/*"
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Files"))
    }

    private fun setupSearch() {
        binding.searchLayout.setEndIconOnClickListener { startSpeechToText() }
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search for files...")
        }
        try {
            startActivityForResult(intent, 101)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            binding.searchEditText.setText(result?.get(0))
        }
    }

    private fun showCreateOptions() {
        val options = arrayOf("New Folder", "New File")
        MaterialAlertDialogBuilder(this)
            .setTitle("Create New")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createNewFolder()
                    1 -> Toast.makeText(this, "File creation coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun createNewFolder() {
        val input = TextInputEditText(this)
        input.setHint("Folder Name")
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("New Folder")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    val newFolder = File(currentPath, name)
                    if (newFolder.mkdir()) {
                        loadFiles(currentPath)
                    } else {
                        Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(file: File) {
        val input = TextInputEditText(this)
        input.setText(file.name)
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty() && newName != file.name) {
                    val destination = File(file.parentFile, newName)
                    if (file.renameTo(destination)) {
                        loadFiles(currentPath)
                    } else {
                        Toast.makeText(this, "Failed to rename", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileActions(file: File) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_file_actions, null)

        view.findViewById<TextView>(R.id.actionTitle).text = file.name
        view.findViewById<TextView>(R.id.actionSubtitle).text = if (file.isDirectory) {
            val count = try { file.list()?.size ?: 0 } catch (e: Exception) { 0 }
            "$count items · Folder"
        } else {
            val sizeStr = Formatter.formatShortFileSize(this, file.length())
            "$sizeStr · File"
        }

        view.findViewById<View>(R.id.btnOpen).setOnClickListener {
            openFile(file)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnRename).setOnClickListener {
            showRenameDialog(file)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnDelete).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete File?")
                .setMessage("Are you sure you want to delete ${file.name}?")
                .setPositiveButton("Delete") { _, _ ->
                    if (file.delete()) {
                        loadFiles(currentPath)
                        updateStorageInfo()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            dialog.dismiss()
        }

        val btnLock = view.findViewById<View>(R.id.btnLock)
        if (file.isDirectory) {
            btnLock.visibility = View.VISIBLE
            btnLock.setOnClickListener {
                showLockFolderDialog(file)
                dialog.dismiss()
            }
        } else {
            btnLock.visibility = View.GONE
        }

        val btnShare = view.findViewById<View>(R.id.btnShare)
        if (!file.isDirectory) {
            btnShare.visibility = View.VISIBLE
            btnShare.setOnClickListener {
                shareSingleFile(file)
                dialog.dismiss()
            }
        } else {
            btnShare.visibility = View.GONE
        }

        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun shareSingleFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun openFile(file: File) {
        val extension = file.extension.lowercase()
        if (extension in listOf("txt", "log", "conf", "xml", "json", "sh", "prop", "md", "kt", "java", "py", "yml", "yaml", "html", "css", "js")) {
            showTextFileViewer(file)
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                intent.setDataAndType(uri, getMimeType(file))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTextFileViewer(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = try {
                if (file.canRead()) {
                    file.readText()
                } else {
                    readTextAsRoot(file)
                }
            } catch (e: Exception) {
                "Error reading file: ${e.message}"
            }

            withContext(Dispatchers.Main) {
                val view = layoutInflater.inflate(R.layout.dialog_text_viewer, null)
                view.findViewById<TextView>(R.id.textContent).text = content

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(file.name)
                    .setView(view)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun readTextAsRoot(file: File): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '${file.absolutePath}'"))
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Failed to read as root: ${e.message}"
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/*"
            "mp3" -> "audio/*"
            else -> "*/*"
        }
    }

    private fun showLockFolderDialog(folder: File) {
        val input = TextInputEditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setHint("Set Password")
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Lock Folder: ${folder.name}")
            .setMessage("Enter a password to protect this folder.")
            .setView(container)
            .setPositiveButton("Lock") { _, _ ->
                val password = input.text.toString()
                if (password.isNotEmpty()) {
                    lockFolder(folder, password)
                } else {
                    Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun lockFolder(folder: File, password: String) {
        val prefs = getSharedPreferences("folder_locks", MODE_PRIVATE)
        prefs.edit().putString(folder.absolutePath, password).apply()

        val lockedFile = File(folder.parentFile, folder.name + ".locked")
        if (folder.renameTo(lockedFile)) {
            loadFiles(currentPath)
            Toast.makeText(this, "Folder locked", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to lock folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unlockFolder(folder: File) {
        val input = TextInputEditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setHint("Password")
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock Folder")
            .setView(container)
            .setPositiveButton("Unlock") { _, _ ->
                val password = input.text.toString()
                val prefs = getSharedPreferences("folder_locks", MODE_PRIVATE)
                val savedPassword = prefs.getString(folder.absolutePath.removeSuffix(".locked"), "")

                if (password == savedPassword) {
                    val unlockedFile = File(folder.absolutePath.removeSuffix(".locked"))
                    if (folder.renameTo(unlockedFile)) {
                        prefs.edit().remove(unlockedFile.absolutePath).apply()
                        loadFiles(currentPath)
                        Toast.makeText(this, "Folder unlocked", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateTo(directory: File) {
        if (directory.name.endsWith(".locked")) {
            unlockFolder(directory)
            return
        }
        currentPath = directory
        loadFiles(directory)
        updateBreadcrumbs(directory)
    }

    private fun updateBreadcrumbs(directory: File) {
        binding.breadcrumbContainer.removeAllViews()
        val rootPath = Environment.getExternalStorageDirectory().absolutePath

        if (directory.absolutePath.startsWith(rootPath)) {
            val relativePath = directory.absolutePath.removePrefix(rootPath)
            val parts = relativePath.split("/").filter { it.isNotEmpty() }
            addBreadcrumb("Storage", Environment.getExternalStorageDirectory(), isRoot = true)
            var acc = rootPath
            parts.forEach { part ->
                acc += "/$part"
                addBreadcrumb(part, File(acc), isRoot = false)
            }
        } else {
            val parts = directory.absolutePath.split("/").filter { it.isNotEmpty() }
            addBreadcrumb("Root", File("/"), isRoot = true)
            var acc = ""
            parts.forEach { part ->
                acc += "/$part"
                addBreadcrumb(part, File(acc), isRoot = false)
            }
        }
    }

    private fun addBreadcrumb(text: String, file: File, isRoot: Boolean) {
        val tv = TextView(this).apply {
            this.text = if (isRoot) text else "› $text"
            setPadding(if (isRoot) 14 else 8, 8, 14, 8)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_badge_generic)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.md_surface_variant)
            )
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            textSize = 13f
            letterSpacing = 0.01f
            setOnClickListener { navigateTo(file) }
        }
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 8, 0)
        binding.breadcrumbContainer.addView(tv, params)
    }

    private fun updateStorageInfo() {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalSize = totalBlocks * blockSize
        val availableSize = availableBlocks * blockSize
        val usedSize = totalSize - availableSize

        val totalStr = Formatter.formatShortFileSize(this, totalSize)
        val availableStr = Formatter.formatShortFileSize(this, availableSize)

        binding.storageText.text = "$availableStr free of $totalStr"
        val progress = ((usedSize.toDouble() / totalSize.toDouble()) * 100).toInt().coerceIn(0, 100)
        binding.storageProgress.setProgressCompat(progress, true)

        calculateCategorySizes(path)
    }

    private fun calculateCategorySizes(root: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            var imageSize = 0L
            var videoSize = 0L
            var audioSize = 0L

            root.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile) {
                    when (file.extension.lowercase()) {
                        "jpg", "jpeg", "png", "webp", "gif" -> imageSize += file.length()
                        "mp4", "mkv", "avi", "mov", "webm" -> videoSize += file.length()
                        "mp3", "wav", "flac", "aac", "ogg" -> audioSize += file.length()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                binding.imageSizeText.text = Formatter.formatShortFileSize(this@MainActivity, imageSize)
                binding.videoSizeText.text = Formatter.formatShortFileSize(this@MainActivity, videoSize)
                binding.audioSizeText.text = Formatter.formatShortFileSize(this@MainActivity, audioSize)
            }
        }
    }

    private fun checkPermissionsAndLoadFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                loadFiles(currentPath)
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            } else {
                loadFiles(currentPath)
            }
        }
    }

    private fun loadFiles(directory: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            var files = directory.listFiles()?.toList() ?: emptyList()

            if (files.isEmpty() && directory.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                files = listFilesAsRoot(directory)
            }

            if (!showHiddenFiles) {
                files = files.filter { !it.name.startsWith(".") }
            }

            val finalFiles = files.sortedWith { f1, f2 ->
                if (f1.isDirectory && !f2.isDirectory) -1
                else if (!f1.isDirectory && f2.isDirectory) 1
                else when (sortType) {
                    SortType.NAME -> f1.name.lowercase().compareTo(f2.name.lowercase())
                    SortType.DATE -> f2.lastModified().compareTo(f1.lastModified())
                    SortType.SIZE -> f2.length().compareTo(f1.length())
                }
            }

            withContext(Dispatchers.Main) {
                adapter.updateFiles(finalFiles)
                binding.emptyState.visibility = if (finalFiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun listFilesAsRoot(directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -aF ${directory.absolutePath}"))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotEmpty() && line != "./" && line != "../") {
                        val name = line.trimEnd('/', '*', '@', '=', '|')
                        files.add(File(directory, name))
                    }
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return files
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                loadFiles(currentPath)
                updateStorageInfo()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                loadFiles(currentPath)
                updateStorageInfo()
            }
        }
    }
}
