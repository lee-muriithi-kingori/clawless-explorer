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
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.clawlessexplorer.databinding.ActivityMainBinding
import com.example.clawlessexplorer.server.FileServer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    private lateinit var settings: SettingsManager
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private var showHiddenFiles: Boolean = true
    private var fileServer: FileServer? = null
    private var sortType: SortType = SortType.NAME

    enum class SortType { NAME, DATE, SIZE }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply persisted theme BEFORE super.onCreate so the inflate picks it up.
        settings = SettingsManager(this)
        settings.applyTheme()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply persisted defaults
        sortType = when (settings.defaultSort) {
            SettingsManager.SortPref.NAME -> SortType.NAME
            SettingsManager.SortPref.DATE -> SortType.DATE
            SettingsManager.SortPref.SIZE -> SortType.SIZE
        }
        showHiddenFiles = settings.showHiddenByDefault

        setupEdgeToEdge()
        setupToolbar()
        setupStorageCard()
        setupHeaderButtons()
        setupDrawer()
        setupRecyclerView()
        setupListeners()
        setupSearch()
        setupFilterChips()
        setupSwipeRefresh()
        setupSelectionBar()
        setupBackPress()
        setupFabScrollBehavior()
        updateStorageInfo()
        checkPermissionsAndLoadFiles()
        if (settings.serverEnabled) startFileServer()

        binding.tvTypewriter.setCharacterDelay(100)
        binding.tvTypewriter.animateText("Clawless Explorer")

        handleIntentExtras(intent)
    }

    private fun startFileServer() {
        fileServer = FileServer(Environment.getExternalStorageDirectory(), this)
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
        binding.btnSettings.setOnClickListener {
            showSettingsSheet()
        }
    }

    private fun setupStorageCard() {
        val collapsed = settings.storageCardCollapsed
        binding.storageCard.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.storagePill.visibility = if (collapsed) View.VISIBLE else View.GONE

        binding.storageCard.setOnClickListener { toggleStorageCard() }
        binding.storagePill.setOnClickListener { toggleStorageCard() }
    }

    private fun toggleStorageCard() {
        val collapse = binding.storageCard.visibility == View.VISIBLE
        val expand = !collapse

        if (collapse) {
            // Collapse: animate card out, pill in
            val cardAnim = binding.storageCard.animate()
                .alpha(0f)
                .scaleY(0.9f)
                .setDuration(180L)
                .withEndAction { binding.storageCard.visibility = View.GONE }
            val pillAnim = binding.storagePill.animate()
                .alpha(1f)
                .scaleY(1f)
                .setDuration(220L)
            binding.storagePill.alpha = 0f
            binding.storagePill.scaleY = 0.9f
            binding.storagePill.visibility = View.VISIBLE
            pillAnim.start()
            cardAnim.start()
            binding.storageChevron.animate().rotation(90f).setDuration(180L).start()
        } else {
            // Expand
            binding.storagePill.animate()
                .alpha(0f)
                .scaleY(0.9f)
                .setDuration(160L)
                .withEndAction { binding.storagePill.visibility = View.GONE }
                .start()
            binding.storageCard.alpha = 0f
            binding.storageCard.scaleY = 0.9f
            binding.storageCard.visibility = View.VISIBLE
            binding.storageCard.animate()
                .alpha(1f)
                .scaleY(1f)
                .setDuration(220L)
                .start()
            binding.storageChevron.animate().rotation(-90f).setDuration(180L).start()
        }
        settings.storageCardCollapsed = expand
    }

    private fun setupDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_recent -> {
                    startActivity(Intent(this, RecentsActivity::class.java))
                }
                R.id.nav_terminal -> {
                    startActivity(Intent(this, TerminalActivity::class.java))
                }
                R.id.nav_tools -> {
                    startActivity(Intent(this, UtilsHubActivity::class.java))
                }
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
        binding.btnMenu.setImageResource(R.drawable.ic_close)
        binding.btnMenu.setOnClickListener {
            adapter.clearSelection()
            resetToolbar()
        }
        binding.btnSearch.visibility = View.GONE
        binding.filterScroll.visibility = View.GONE

        // Show the bottom action bar
        binding.selectionBar.visibility = View.VISIBLE
        binding.selectionBar.translationY = 200f
        binding.selectionBar.alpha = 0f
        binding.selectionBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .start()
        binding.fabAdd.hide()
    }

    private fun updateSelectionTitle(count: Int) {
        binding.tvTypewriter.animateText("$count selected")
        binding.selectionBarCount.text = if (count == 1) "1 item selected" else "$count items selected"
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
        binding.filterScroll.visibility = View.VISIBLE

        // Hide the bottom action bar
        binding.selectionBar.animate()
            .translationY(200f)
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { binding.selectionBar.visibility = View.GONE }
            .start()
        binding.fabAdd.show()
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

    private fun setupFilterChips() {
        binding.filterChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipFilterAll
            val filter = when (checkedId) {
                R.id.chipFilterImage -> FileAdapter.TypeFilter.IMAGE
                R.id.chipFilterVideo -> FileAdapter.TypeFilter.VIDEO
                R.id.chipFilterAudio -> FileAdapter.TypeFilter.AUDIO
                R.id.chipFilterDocs -> FileAdapter.TypeFilter.DOCUMENT
                R.id.chipFilterArchive -> FileAdapter.TypeFilter.ARCHIVE
                R.id.chipFilterApk -> FileAdapter.TypeFilter.APK
                else -> FileAdapter.TypeFilter.ALL
            }
            adapter.setTypeFilter(filter)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.md_primary),
            ContextCompat.getColor(this, R.color.md_secondary),
            ContextCompat.getColor(this, R.color.md_tertiary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.md_surface_container)
        )
        binding.swipeRefresh.setOnRefreshListener {
            loadFiles(currentPath)
            updateStorageInfo()
            binding.swipeRefresh.postDelayed({
                if (binding.swipeRefresh.isRefreshing) binding.swipeRefresh.isRefreshing = false
            }, 800)
        }
    }

    private fun setupSelectionBar() {
        binding.selectionDelete.setOnClickListener { deleteSelectedFiles() }
        binding.selectionShare.setOnClickListener { shareSelectedFiles() }
        binding.selectionCopy.setOnClickListener { copySelectedToClipboard() }
        binding.selectionMore.setOnClickListener {
            val selected = adapter.getSelectedFiles()
            if (selected.size == 1) {
                showFileActions(selected.first())
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("${selected.size} items")
                    .setItems(arrayOf("Move", "Rename", "Properties")) { _, which ->
                        when (which) {
                            0 -> Toast.makeText(this, "Move — coming soon", Toast.LENGTH_SHORT).show()
                            1 -> Toast.makeText(this, "Rename — coming soon", Toast.LENGTH_SHORT).show()
                            2 -> Toast.makeText(this, "Properties — coming soon", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }

    private fun copySelectedToClipboard() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return
        val paths = selected.joinToString("\n") { it.absolutePath }
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("File paths", paths)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied ${selected.size} path(s)", Toast.LENGTH_SHORT).show()
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        val path = intent?.getStringExtra(RecentsActivity.EXTRA_OPEN_PATH) ?: return
        val file = File(path)
        if (file.exists()) {
            if (file.isDirectory) {
                navigateTo(file)
            } else {
                navigateTo(file.parentFile ?: currentPath)
            }
        }
    }

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)

        // Theme radios
        when (settings.themeMode) {
            SettingsManager.ThemeMode.SYSTEM -> view.findViewById<RadioButton>(R.id.themeSystem).isChecked = true
            SettingsManager.ThemeMode.LIGHT -> view.findViewById<RadioButton>(R.id.themeLight).isChecked = true
            SettingsManager.ThemeMode.DARK -> view.findViewById<RadioButton>(R.id.themeDark).isChecked = true
        }
        view.findViewById<RadioGroup>(R.id.themeGroup).setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> SettingsManager.ThemeMode.LIGHT
                R.id.themeDark -> SettingsManager.ThemeMode.DARK
                else -> SettingsManager.ThemeMode.SYSTEM
            }
            settings.themeMode = mode
            settings.applyTheme()
        }

        // Sort radios
        when (settings.defaultSort) {
            SettingsManager.SortPref.NAME -> view.findViewById<RadioButton>(R.id.sortName).isChecked = true
            SettingsManager.SortPref.DATE -> view.findViewById<RadioButton>(R.id.sortDate).isChecked = true
            SettingsManager.SortPref.SIZE -> view.findViewById<RadioButton>(R.id.sortSize).isChecked = true
        }
        view.findViewById<RadioGroup>(R.id.sortGroup).setOnCheckedChangeListener { _, checkedId ->
            val pref = when (checkedId) {
                R.id.sortDate -> SettingsManager.SortPref.DATE
                R.id.sortSize -> SettingsManager.SortPref.SIZE
                else -> SettingsManager.SortPref.NAME
            }
            settings.defaultSort = pref
            sortType = when (pref) {
                SettingsManager.SortPref.NAME -> SortType.NAME
                SettingsManager.SortPref.DATE -> SortType.DATE
                SettingsManager.SortPref.SIZE -> SortType.SIZE
            }
            loadFiles(currentPath)
        }

        // Hidden switch
        val switchHidden = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchHidden)
        switchHidden.isChecked = settings.showHiddenByDefault
        switchHidden.setOnCheckedChangeListener { _, isChecked ->
            settings.showHiddenByDefault = isChecked
            showHiddenFiles = isChecked
            loadFiles(currentPath)
        }

        // Server switch
        val switchServer = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchServer)
        switchServer.isChecked = settings.serverEnabled
        switchServer.setOnCheckedChangeListener { _, isChecked ->
            settings.serverEnabled = isChecked
            if (isChecked) {
                startFileServer()
            } else {
                fileServer?.stop()
                fileServer = null
            }
        }

        // Clear recents
        view.findViewById<View>(R.id.btnClearRecents).setOnClickListener {
            settings.clearRecents()
            Toast.makeText(this, "Recent files cleared", Toast.LENGTH_SHORT).show()
        }

        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun showCreateOptions() {
        val options = arrayOf("New Folder", "New File")
        MaterialAlertDialogBuilder(this)
            .setTitle("Create New")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createNewFolder()
                    1 -> createNewFile()
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

    private fun createNewFile() {
        val input = TextInputEditText(this)
        input.setHint("File name (e.g. notes.txt)")
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("New File")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    val newFile = File(currentPath, name)
                    if (newFile.createNewFile()) {
                        loadFiles(currentPath)
                    } else {
                        Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
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
            val ext = file.extension.uppercase().ifEmpty { "FILE" }
            "$sizeStr · ${ext} file"
        }

        // Populate the preview badge
        val (badgeRes, iconRes, tintRes) = styleForPreview(file)
        view.findViewById<View>(R.id.previewBadge).setBackgroundResource(badgeRes)
        val previewIcon = view.findViewById<ImageView>(R.id.previewIcon)
        previewIcon.setImageResource(iconRes)
        previewIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, tintRes)
        )

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
                    if (file.deleteRecursively()) {
                        val snack = com.google.android.material.snackbar.Snackbar.make(
                            binding.root,
                            "Deleted ${file.name}",
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        )
                        snack.anchorView = binding.fabAdd
                        snack.show()
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

        view.findViewById<View>(R.id.btnCopy).setOnClickListener {
            showCopyMoveDialog(file, isMove = false)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnMove).setOnClickListener {
            showCopyMoveDialog(file, isMove = true)
            dialog.dismiss()
        }

        // "View" only makes sense for HTML files
        val btnView = view.findViewById<View>(R.id.btnView)
        if (!file.isDirectory && file.extension.lowercase() in listOf("html", "htm")) {
            btnView.visibility = View.VISIBLE
            btnView.setOnClickListener {
                startActivity(HtmlViewerActivity.intent(this, file.absolutePath))
                dialog.dismiss()
            }
        } else {
            btnView.visibility = View.GONE
        }

        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun styleForPreview(file: File): Triple<Int, Int, Int> {
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

    private fun shareSingleFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun showCopyMoveDialog(file: File, isMove: Boolean) {
        val action = if (isMove) "Move" else "Copy"
        val input = TextInputEditText(this).apply {
            setText(currentPath.absolutePath)
            setHint("Destination folder")
            setSelection(text?.length ?: 0)
        }
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("$action \"${file.name}\" to…")
            .setView(container)
            .setPositiveButton(action) { _, _ ->
                val dest = File(input.text.toString().trim())
                if (!dest.exists() || !dest.isDirectory) {
                    Toast.makeText(this, "Destination is not a folder", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = File(dest, file.name)
                if (target.exists()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Already exists")
                        .setMessage("${file.name} already exists at the destination. Overwrite?")
                        .setPositiveButton("Overwrite") { _, _ ->
                            performCopyMove(file, dest, isMove, overwrite = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    performCopyMove(file, dest, isMove, overwrite = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performCopyMove(source: File, destDir: File, isMove: Boolean, overwrite: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = withContext(Dispatchers.IO) {
                try {
                    val target = File(destDir, source.name)
                    if (isMove) {
                        if (overwrite && target.exists()) target.delete()
                        if (source.renameTo(target)) Result.success(target)
                        else Result.failure(IllegalStateException("Move failed"))
                    } else {
                        if (overwrite && target.exists()) target.delete()
                        if (copyRecursively(source, target)) Result.success(target)
                        else Result.failure(IllegalStateException("Copy failed"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { target ->
                    val action = if (isMove) "Moved" else "Copied"
                    val snack = com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        "$action ${source.name} → ${destDir.name}",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    )
                    if (isMove) {
                        // Undo: move it back to the original location.
                        snack.setAction("Undo") {
                            val newPath = File(source.parentFile ?: currentPath, target.name)
                            target.renameTo(newPath)
                            loadFiles(currentPath)
                            updateStorageInfo()
                        }
                    }
                    snack.anchorView = binding.fabAdd
                    snack.show()
                    loadFiles(currentPath)
                    updateStorageInfo()
                }.onFailure {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        "Failed: ${it.message}",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).apply {
                        anchorView = binding.fabAdd
                        show()
                    }
                }
            }
        }
    }

    private fun copyRecursively(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            src.listFiles()?.forEach { child ->
                if (!copyRecursively(child, File(dst, child.name))) return false
            }
            return true
        } else {
            return try {
                src.inputStream().use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun openFile(file: File) {
        settings.addRecent(file.absolutePath)
        val ext = file.extension.lowercase()

        when {
            // Images → built-in image viewer
            ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg") -> {
                startActivity(ImageViewerActivity.intent(this, file.absolutePath))
            }

            // Video → built-in media player
            ext in listOf("mp4", "mkv", "avi", "mov", "webm", "flv") -> {
                startActivity(MediaViewerActivity.intent(this, file.absolutePath, "video"))
            }

            // Audio → built-in media player
            ext in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma") -> {
                startActivity(MediaViewerActivity.intent(this, file.absolutePath, "audio"))
            }

            // APK → built-in APK info viewer
            ext == "apk" -> {
                startActivity(ApkViewerActivity.intent(this, file.absolutePath))
            }

            // HTML → built-in HTML viewer
            ext in listOf("html", "htm") -> {
                startActivity(HtmlViewerActivity.intent(this, file.absolutePath))
            }

            // Code / text → built-in code viewer with syntax highlighting
            ext in listOf(
                "txt", "log", "conf", "prop", "md", "csv",
                "kt", "java", "py", "js", "ts", "jsx", "tsx",
                "c", "cpp", "h", "hpp", "cs", "rb", "go", "rs", "swift",
                "sh", "bash", "zsh",
                "css", "scss", "less",
                "json", "xml", "yaml", "yml",
                "html", "htm", "sql", "gradle", "toml", "ini", "cfg"
            ) -> {
                startActivity(CodeViewerActivity.intent(this, file.absolutePath))
            }

            // Everything else → try external app, fall back to code viewer
            else -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                    intent.setDataAndType(uri, getMimeType(file))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } catch (e: Exception) {
                    // No app found — offer to view as text in code viewer
                    startActivity(CodeViewerActivity.intent(this, file.absolutePath))
                }
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
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "wma" -> "audio/x-ms-wma"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "log", "conf", "prop", "md", "csv" -> "text/plain"
            "rtf" -> "application/rtf"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "epub" -> "application/epub+zip"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "bz2" -> "application/x-bzip2"
            "xz" -> "application/x-xz"
            "tgz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "yaml", "yml" -> "text/yaml"
            "kt" -> "text/x-kotlin"
            "java" -> "text/x-java"
            "py" -> "text/x-python"
            "c", "cpp", "h", "hpp" -> "text/x-c"
            "cs" -> "text/x-csharp"
            "rb" -> "text/x-ruby"
            "go" -> "text/x-go"
            "rs" -> "text/x-rust"
            "swift" -> "text/x-swift"
            "sh" -> "application/x-sh"
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
        settings.addRecent(directory.absolutePath)
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
        binding.storagePillLabel.text = "Internal · $availableStr free of $totalStr"

        calculateCategorySizes(path, totalSize)
    }

    private fun calculateCategorySizes(root: File, totalSize: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            var imageSize = 0L
            var videoSize = 0L
            var audioSize = 0L
            var docSize = 0L
            var archiveSize = 0L
            var apkSize = 0L
            var otherSize = 0L

            root.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile) {
                    val ext = file.extension.lowercase()
                    val len = file.length()
                    when {
                        ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> imageSize += len
                        ext in listOf("mp4", "mkv", "avi", "mov", "webm", "flv") -> videoSize += len
                        ext in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma") -> audioSize += len
                        ext in listOf("pdf", "doc", "docx", "txt", "rtf", "odt", "epub", "log", "conf", "prop", "md", "csv", "xls", "xlsx", "ppt", "pptx") -> docSize += len
                        ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz") -> archiveSize += len
                        ext == "apk" -> apkSize += len
                        else -> otherSize += len
                    }
                }
            }

            withContext(Dispatchers.Main) {
                binding.imageSizeText.text = Formatter.formatShortFileSize(this@MainActivity, imageSize)
                binding.videoSizeText.text = Formatter.formatShortFileSize(this@MainActivity, videoSize)
                binding.audioSizeText.text = Formatter.formatShortFileSize(this@MainActivity, audioSize)
                updateStorageBar(totalSize, imageSize, videoSize, audioSize, docSize, archiveSize + apkSize, otherSize)
            }
        }
    }

    private fun updateStorageBar(
        totalSize: Long,
        imageSize: Long,
        videoSize: Long,
        audioSize: Long,
        docSize: Long,
        archiveSize: Long,
        otherSize: Long
    ) {
        // Compute weights as percentages of TOTAL storage (not just used),
        // so the user sees both the breakdown AND the headroom.
        val total = totalSize.coerceAtLeast(1L).toFloat()
        val imgW = imageSize / total * 100f
        val vidW = videoSize / total * 100f
        val audW = audioSize / total * 100f
        val docW = docSize / total * 100f
        val arcW = archiveSize / total * 100f
        val otherW = 100f - (imgW + vidW + audW + docW + arcW).coerceAtMost(100f)

        binding.segImage.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { weight = imgW.coerceAtLeast(0f) }
        binding.segVideo.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { weight = vidW.coerceAtLeast(0f) }
        binding.segAudio.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { weight = audW.coerceAtLeast(0f) }
        binding.segDocs.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { weight = docW.coerceAtLeast(0f) }
        binding.segArchive.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { weight = arcW.coerceAtLeast(0f) }
        binding.segOther.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { weight = otherW.coerceAtLeast(0f) }
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
