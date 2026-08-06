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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.transition.Fade
import android.transition.TransitionManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var bookmarkManager: BookmarkManager
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private var showHiddenFiles: Boolean = true
    private var fileServer: FileServer? = null
    private var sortType: SortType = SortType.NAME
    private var isRootMode: Boolean = false
    private var isGridView = false

    enum class SortType { NAME, DATE, SIZE }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply persisted theme BEFORE super.onCreate so the inflate picks it up.
        settings = SettingsManager(this)
        bookmarkManager = BookmarkManager(this)
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
        isRootMode = settings.rootMode

        // Restore state after rotation
        savedInstanceState?.let {
            currentPath = File(it.getString("current_path") ?: currentPath.absolutePath)
            showHiddenFiles = it.getBoolean("show_hidden", showHiddenFiles)
            isRootMode = it.getBoolean("root_mode", isRootMode)
            sortType = try { SortType.valueOf(it.getString("sort_type") ?: "NAME") } catch (_: Exception) { SortType.NAME }
        }

        setupEdgeToEdge()
        setupToolbar()
        setupStorageCard()
        setupHeaderButtons()
        setupHiddenFilesToggle()
        setupDrawer()
        setupRecyclerView()
        setupListeners()
        setupSearch()
        setupFilterChips()
        setupSwipeRefresh()
        setupSelectionBar()
        setupBackPress()
        setupFabScrollBehavior()
        setupAnimatedBackground()
        updateStorageInfo()

        // Check MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Storage Access")
                    .setMessage("This app needs access to all files to function as a file manager. Grant permission?")
                    .setPositiveButton("Grant") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                    .setNegativeButton("Continue anyway", null)
                    .show()
            }
        }

        checkPermissionsAndLoadFiles()
        if (settings.serverEnabled) startFileServer()

        binding.tvTypewriter.setCharacterDelay(100)
        binding.tvTypewriter.animateText("Clawless Explorer")

        // Show root/non-root dialog on first launch
        if (!settings.hasShownRootDialog) {
            showRootModeDialog()
        }

        // Update root badge in nav header
        updateRootBadge()

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_path", currentPath.absolutePath)
        outState.putBoolean("show_hidden", showHiddenFiles)
        outState.putBoolean("root_mode", isRootMode)
        outState.putString("sort_type", sortType.name)
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

    /** Toggle button for showing/hiding hidden (dot) files. */
    private fun setupHiddenFilesToggle() {
        updateHiddenToggleIcon()
        binding.btnHiddenToggle.setOnClickListener {
            showHiddenFiles = !showHiddenFiles
            settings.showHiddenByDefault = showHiddenFiles
            updateHiddenToggleIcon()
            loadFiles(currentPath)
            // Animate the toggle
            it.animate()
                .scaleX(1.2f).scaleY(1.2f)
                .setDuration(100L)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                }
                .start()
        }
    }

    private fun updateHiddenToggleIcon() {
        binding.btnHiddenToggle.setImageResource(
            if (showHiddenFiles) R.drawable.ic_visibility else R.drawable.ic_visibility_off
        )
        binding.btnHiddenToggle.alpha = if (showHiddenFiles) 1f else 0.5f
    }

    /** Configure the animated particle/wave/aurora background. */
    private fun setupAnimatedBackground() {
        if (!settings.animationsEnabled) {
            binding.particleField.visibility = View.GONE
            binding.morphingWave.visibility = View.GONE
            binding.auroraGradient.visibility = View.GONE
        }
    }

    /** Show the first-launch root mode selection dialog. */
    private fun showRootModeDialog() {
        settings.hasShownRootDialog = true
        val rootAvailable = settings.isRootAvailable()

        val message = if (rootAvailable) {
            "This device has root (superuser) access available.\n\n" +
            "• Root mode: Access all files and directories, including system partitions\n" +
            "• Non-root mode: Access only files in standard storage locations\n\n" +
            "You can change this later in Settings."
        } else {
            "Root (superuser) access is not available on this device.\n\n" +
            "The app will run in standard (non-root) mode. You can only access files in standard storage locations.\n\n" +
            "If you root your device later, you can enable root mode in Settings."
        }

        if (rootAvailable) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Choose Access Mode")
                .setMessage(message)
                .setPositiveButton("Root Mode") { _, _ ->
                    settings.rootMode = true
                    isRootMode = true
                    updateRootBadge()
                    loadFiles(currentPath)
                    Toast.makeText(this, "Root mode enabled — full filesystem access", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Non-Root") { _, _ ->
                    settings.rootMode = false
                    isRootMode = false
                    updateRootBadge()
                    Toast.makeText(this, "Running in standard mode", Toast.LENGTH_SHORT).show()
                }
                .setCancelable(false)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Access Mode")
                .setMessage(message)
                .setPositiveButton("Continue") { _, _ ->
                    settings.rootMode = false
                    isRootMode = false
                }
                .setCancelable(false)
                .show()
        }
    }

    /** Show or hide the ROOT badge in the nav drawer header. */
    private fun updateRootBadge() {
        val headerView = binding.navigationView.getHeaderView(0)
        val rootBadge = headerView.findViewById<LinearLayout>(R.id.rootBadge)
        rootBadge?.visibility = if (isRootMode) View.VISIBLE else View.GONE
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
                R.id.nav_bookmarks -> {
                    startActivity(BookmarksActivity.intent(this))
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
                    if (externalFilesDirs.size > 1 && externalFilesDirs[1] != null) {
                        val sdCardPath = externalFilesDirs[1]!!.absolutePath.split("/Android")[0]
                        navigateTo(File(sdCardPath))
                    } else {
                        Toast.makeText(this, "SD Card not found", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.nav_downloads -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                R.id.nav_dcim -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
                // New Quick Access folders
                R.id.nav_documents -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                R.id.nav_music -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
                R.id.nav_pictures -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
                R.id.nav_movies -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
                // Storage & Tools shortcuts
                R.id.nav_storage_analyzer -> startActivity(Intent(this, StorageAnalyzerActivity::class.java))
                R.id.nav_batch_rename -> startActivity(Intent(this, BatchRenameActivity::class.java))
                R.id.nav_hash -> startActivity(Intent(this, HashActivity::class.java))
                R.id.nav_json -> startActivity(Intent(this, JsonFormatterActivity::class.java))
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
        // Just the number for the badge
        binding.selectionBarCount.text = count.toString()
        // Animate badge scale
        binding.selectionBarCount.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(80L)
            .withEndAction {
                binding.selectionBarCount.animate().scaleX(1f).scaleY(1f).setDuration(80L).start()
            }
            .start()
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

        // Grid/List view toggle
        binding.btnViewToggle.setOnClickListener {
            isGridView = !isGridView
            binding.recyclerView.layoutManager = if (isGridView) {
                androidx.recyclerview.widget.GridLayoutManager(this, 3)
            } else {
                androidx.recyclerview.widget.LinearLayoutManager(this)
            }
            binding.recyclerView.adapter = adapter
            loadFiles(currentPath)
        }
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
        binding.selectionCopy.setOnClickListener {
            FileClipboard.copy(adapter.getSelectedFiles().toList(), currentPath)
            adapter.clearSelection()
            binding.selectionBar.visibility = View.GONE
            com.google.android.material.snackbar.Snackbar.make(binding.root, "${FileClipboard.files.size} file(s) copied", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .setAction("Paste") { pasteFiles() }
                .show()
        }
        binding.selectionMove.setOnClickListener {
            FileClipboard.move(adapter.getSelectedFiles().toList(), currentPath)
            adapter.clearSelection()
            binding.selectionBar.visibility = View.GONE
            com.google.android.material.snackbar.Snackbar.make(binding.root, "${FileClipboard.files.size} file(s) cut", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .setAction("Paste") { pasteFiles() }
                .show()
        }
        binding.selectionSelectAll.setOnClickListener {
            adapter.selectAll()
        }
        binding.selectionMore.setOnClickListener {
            val selected = adapter.getSelectedFiles()
            if (selected.size == 1) {
                showFileProperties(selected.first())
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("${selected.size} items selected")
                    .setItems(arrayOf("Move Selected", "Compress Selected", "Properties")) { _, which ->
                        when (which) {
                            0 -> showCopyMoveDialog(selected.first(), isMove = true)
                            1 -> startActivity(ZipToolsActivity.intent(this, "compress", selected.first().absolutePath))
                            2 -> showFileProperties(selected.first())
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

    private fun pasteFiles() {
        if (!FileClipboard.hasContent) return
        val (success, failures) = FileClipboard.paste(currentPath)
        if (success.isNotEmpty()) {
            loadFiles(currentPath)
            com.google.android.material.snackbar.Snackbar.make(binding.root, "${success.size} file(s) pasted", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
        if (failures.isNotEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, failures.joinToString("\n"), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
        }
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
            binding.searchEditText.setText(result?.firstOrNull())
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
            updateHiddenToggleIcon()
            loadFiles(currentPath)
        }

        // Animations switch
        val switchAnimations = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAnimations)
        switchAnimations.isChecked = settings.animationsEnabled
        switchAnimations.setOnCheckedChangeListener { _, isChecked ->
            settings.animationsEnabled = isChecked
            binding.particleField.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.morphingWave.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.auroraGradient.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Root mode switch
        val switchRoot = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchRoot)
        switchRoot.isChecked = settings.rootMode
        switchRoot.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !settings.isRootAvailable()) {
                // Root not available — revert
                switchRoot.isChecked = false
                Toast.makeText(this, "Root (su) not available on this device", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            settings.rootMode = isChecked
            isRootMode = isChecked
            updateRootBadge()
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
        // Speed-dial style options with more actions
        val options = arrayOf(
            "📁  New Folder",
            "📄  New File",
            "📝  New Text Note",
            "🔍  Search in Files",
            "📊  Analyze Storage"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Quick Actions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createNewFolder()
                    1 -> createNewFile()
                    2 -> createNewTextNote()
                    3 -> showSearchInFilesDialog()
                    4 -> startActivity(Intent(this, StorageAnalyzerActivity::class.java))
                }
            }
            .show()
    }

    /** Create a quick text note with timestamp. */
    private fun createNewTextNote() {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "Note $timestamp.txt"
        val newFile = File(currentPath, fileName)
        try {
            newFile.writeText("# Note — $timestamp\n\n")
            loadFiles(currentPath)
            val snack = com.google.android.material.snackbar.Snackbar.make(
                binding.root, "Created $fileName", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            )
            snack.anchorView = binding.fabAdd
            snack.setAction("Open") { openFile(newFile) }
            snack.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create note", Toast.LENGTH_SHORT).show()
        }
    }

    /** Search for text content within files (grep-like). */
    private fun showSearchInFilesDialog() {
        val input = TextInputEditText(this)
        input.hint = "Search text in files..."
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 16, 48, 16)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Search in Files")
            .setView(container)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString()
                if (query.isNotEmpty()) {
                    searchInFiles(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Grep-like search: find files containing the query text. */
    private fun searchInFiles(query: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<File>()
            val maxFileSize = 512 * 1024L // Skip files larger than 512KB
            currentPath.walkTopDown().take(500).forEach { file ->
                if (!file.isDirectory && file.length() <= maxFileSize && file.canRead()) {
                    try {
                        file.bufferedReader().use { reader ->
                            var found = false
                            while (!found) {
                                val line = reader.readLine() ?: break
                                if (line.contains(query, ignoreCase = true)) {
                                    found = true
                                    results.add(file)
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root, "No files containing \"$query\"", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).apply { anchorView = binding.fabAdd }.show()
                } else {
                    // Show results as filter in adapter
                    adapter.updateFiles(results)
                    binding.emptyState.visibility = View.GONE
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root, "Found in ${results.size} file(s)", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).apply {
                        anchorView = binding.fabAdd
                        setAction("Clear") { loadFiles(currentPath) }
                    }.show()
                }
            }
        }
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

    /** Show detailed file properties with checksum. */
    private fun showFileProperties(file: File) {
        val name = file.name
        val path = file.absolutePath
        val size = if (file.isDirectory) {
            val count = try { file.list()?.size ?: 0 } catch (_: Exception) { 0 }
            "$count items"
        } else {
            Formatter.formatFileSize(this, file.length())
        }
        val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(file.lastModified()))
        val permissions = (if (file.canRead()) "r" else "-") +
                (if (file.canWrite()) "w" else "-") +
                (if (file.canExecute()) "x" else "-")
        val isHidden = file.name.startsWith(".")
        val type = if (file.isDirectory) "Directory" else {
            val ext = file.extension.uppercase().ifEmpty { "File" }
            "$ext File"
        }

        // Build the info text
        val info = buildString {
            appendLine("📄 Name: $name")
            appendLine("📂 Path: $path")
            appendLine("📊 Size: $size")
            appendLine("🕐 Modified: $modified")
            appendLine("🔒 Permissions: $permissions")
            appendLine("📁 Type: $type")
            appendLine("👁️ Hidden: ${if (isHidden) "Yes" else "No"}")
            if (!file.isDirectory) {
                appendLine()
                appendLine("⏳ Computing checksums...")
            }
        }

        val tv = TextView(this).apply {
            text = info
            textSize = 13f
            setPadding(48, 24, 48, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Properties")
            .setView(tv)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy Path") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", file.absolutePath))
                Toast.makeText(this@MainActivity, "Path copied", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.show()

        // Compute checksums asynchronously
        if (!file.isDirectory) {
            lifecycleScope.launch(Dispatchers.IO) {
                val md5 = computeHash(file, "MD5")
                val sha256 = computeHash(file, "SHA-256")
                withContext(Dispatchers.Main) {
                    val updated = buildString {
                        appendLine("📄 Name: $name")
                        appendLine("📂 Path: $path")
                        appendLine("📊 Size: $size")
                        appendLine("🕐 Modified: $modified")
                        appendLine("🔒 Permissions: $permissions")
                        appendLine("📁 Type: $type")
                        appendLine("👁️ Hidden: ${if (isHidden) "Yes" else "No"}")
                        appendLine()
                        appendLine("🔑 MD5: $md5")
                        appendLine("🔑 SHA-256: $sha256")
                    }
                    tv.text = updated
                }
            }
        }
    }

    /** Compute a hash for a file. */
    private fun computeHash(file: File, algorithm: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance(algorithm)
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** Delete with trash/undo support. */
    private fun deleteWithUndo(file: File) {
        val trashDir = File(cacheDir, ".trash")
        trashDir.mkdirs()

        // Move to trash instead of permanent delete
        val trashFile = File(trashDir, file.name + "_" + System.currentTimeMillis())
        val movedToTrash = file.renameTo(trashFile)

        if (movedToTrash) {
            loadFiles(currentPath)
            updateStorageInfo()
            val snack = com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                "Moved ${file.name} to trash",
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            )
            snack.anchorView = binding.fabAdd
            snack.setAction("Undo") {
                // Restore from trash
                if (trashFile.exists()) {
                    trashFile.renameTo(file)
                    loadFiles(currentPath)
                    updateStorageInfo()
                }
            }
            snack.addCallback(object : com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback<com.google.android.material.snackbar.Snackbar>() {
                override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) {
                        // Snackbar dismissed without undo — permanently delete
                        trashFile.deleteRecursively()
                    }
                }
            })
            snack.show()
        } else {
            // Fallback: direct delete with confirmation
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete File?")
                .setMessage("Cannot move to trash. Permanently delete ${file.name}?")
                .setPositiveButton("Delete") { _, _ ->
                    if (file.deleteRecursively()) {
                        loadFiles(currentPath)
                        updateStorageInfo()
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root, "Deleted ${file.name}", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).apply { anchorView = binding.fabAdd }.show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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

        val btnBookmark = view.findViewById<View>(R.id.btnBookmark)
        val bookmarkIcon = btnBookmark.findViewById<ImageView>(R.id.bookmarkActionIcon)
        val bookmarkLabel = btnBookmark.findViewById<TextView>(R.id.bookmarkActionLabel)
        val isBookmarked = bookmarkManager.isBookmarked(file.absolutePath)
        bookmarkIcon.setImageResource(if (isBookmarked) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        bookmarkIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isBookmarked) R.color.md_primary else R.color.file_generic)
        )
        bookmarkLabel.text = if (isBookmarked) "Remove Bookmark" else "Bookmark"
        bookmarkLabel.setTextColor(if (isBookmarked) ContextCompat.getColor(this, R.color.md_primary) else ContextCompat.getColor(this, R.color.md_on_surface))
        btnBookmark.setOnClickListener {
            if (bookmarkManager.isBookmarked(file.absolutePath)) {
                bookmarkManager.removeBookmark(file.absolutePath)
                Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
            } else {
                bookmarkManager.addBookmark(file.absolutePath)
                Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnRename).setOnClickListener {
            showRenameDialog(file)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnDelete).setOnClickListener {
            deleteWithUndo(file)
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

        // Run in Terminal — show for script and binary files
        val btnRunInTerminal = view.findViewById<View>(R.id.btnRunInTerminal)
        val scriptExtensions = setOf("sh", "bash", "zsh", "py", "pl", "rb")
        val isRunable = !file.isDirectory && (
            file.extension.lowercase() in scriptExtensions ||
            file.canExecute() ||
            file.extension.lowercase().isEmpty()
        )
        if (isRunable) {
            btnRunInTerminal.visibility = View.VISIBLE
            btnRunInTerminal.setOnClickListener {
                startActivity(TerminalActivity.intent(this, file.absolutePath))
                dialog.dismiss()
            }
        } else {
            btnRunInTerminal.visibility = View.GONE
        }

        // Compress — show for any file/folder
        val btnCompress = view.findViewById<View>(R.id.btnCompress)
        btnCompress.setOnClickListener {
            startActivity(ZipToolsActivity.intent(this, "compress", file.absolutePath))
            dialog.dismiss()
        }

        // Extract — show only for .zip files
        val btnExtract = view.findViewById<View>(R.id.btnExtract)
        if (!file.isDirectory && file.extension.lowercase() in listOf("zip")) {
            btnExtract.visibility = View.VISIBLE
            btnExtract.setOnClickListener {
                startActivity(ZipToolsActivity.intent(this, "extract", file.absolutePath))
                dialog.dismiss()
            }
        } else {
            btnExtract.visibility = View.GONE
        }

        // Open With — show for all non-directory files
        val btnOpenWith = view.findViewById<View>(R.id.btnOpenWith)
        if (!file.isDirectory) {
            btnOpenWith.visibility = View.VISIBLE
            btnOpenWith.setOnClickListener {
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, getMimeType(file))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open ${file.name} with…"))
                } catch (e: Exception) {
                    Toast.makeText(this, "No apps available to open this file", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        } else {
            btnOpenWith.visibility = View.GONE
        }

        // Duplicate — show for all files (not directories)
        val btnDuplicate = view.findViewById<View>(R.id.btnDuplicate)
        if (!file.isDirectory) {
            btnDuplicate.visibility = View.VISIBLE
            btnDuplicate.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val parent = file.parentFile ?: return@withContext Result.failure(Exception("No parent directory"))
                            val nameWithoutExt = file.nameWithoutExtension
                            val ext = file.extension.ifEmpty { null }
                            var targetName = if (ext != null) "$nameWithoutExt (copy).$ext" else "${file.name} (copy)"
                            var target = File(parent, targetName)
                            var copyNum = 2
                            while (target.exists()) {
                                targetName = if (ext != null) "$nameWithoutExt (copy $copyNum).$ext" else "${file.name} (copy $copyNum)"
                                target = File(parent, targetName)
                                copyNum++
                            }
                            val success = file.copyTo(target).exists()
                            if (success) Result.success(target) else Result.failure(Exception("Copy failed"))
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        result.onSuccess { newFile ->
                            val snack = com.google.android.material.snackbar.Snackbar.make(
                                binding.root,
                                "Created ${newFile.name}",
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            )
                            snack.anchorView = binding.fabAdd
                            snack.setAction("Undo") {
                                newFile.delete()
                                loadFiles(currentPath)
                            }
                            snack.show()
                            loadFiles(currentPath)
                        }.onFailure {
                            com.google.android.material.snackbar.Snackbar.make(
                                binding.root,
                                "Failed to duplicate: ${it.message}",
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            ).apply {
                                anchorView = binding.fabAdd
                                show()
                            }
                        }
                    }
                }
                dialog.dismiss()
            }
        } else {
            btnDuplicate.visibility = View.GONE
        }

        // Compress & Share — show for all non-directory files
        val btnCompressShare = view.findViewById<View>(R.id.btnCompressShare)
        if (!file.isDirectory) {
            btnCompressShare.visibility = View.VISIBLE
            btnCompressShare.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val cacheDir = File(cacheDir, "shared_zips")
                        cacheDir.mkdirs()
                        val zipFile = File(cacheDir, "${file.nameWithoutExtension}.zip")
                        if (zipFile.exists()) zipFile.delete()

                        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                            val entry = java.util.zip.ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }

                        withContext(Dispatchers.Main) {
                            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", zipFile)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Share ${zipFile.name}"))
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to compress: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Toast.makeText(this, "Preparing zip…", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        } else {
            btnCompressShare.visibility = View.GONE
        }

        // Create Shortcut — API 26+ only
        val btnCreateShortcut = view.findViewById<View>(R.id.btnCreateShortcut)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (shortcutManager.isRequestPinShortcutSupported) {
                btnCreateShortcut.visibility = View.VISIBLE
                btnCreateShortcut.setOnClickListener {
                    try {
                        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                        val shortcutIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, getMimeType(file))
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val shortcut = android.content.pm.ShortcutInfo.Builder(this, "file_${file.absolutePath.hashCode()}")
                            .setShortLabel(file.name)
                            .setLongLabel(file.absolutePath)
                            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_file_generic))
                            .setIntent(shortcutIntent)
                            .build()
                        shortcutManager.requestPinShortcut(shortcut, null)
                        Toast.makeText(this, "Shortcut for ${file.name} added to home screen", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to create shortcut: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            } else {
                btnCreateShortcut.visibility = View.GONE
            }
        } else {
            btnCreateShortcut.visibility = View.GONE
        }

        // File Info / Properties — always visible
        view.findViewById<View>(R.id.btnFileInfo).setOnClickListener {
            showFileProperties(file)
            dialog.dismiss()
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
        if (bookmarkManager.isBookmarked(file.absolutePath)) {
            bookmarkManager.updateLastAccessed(file.absolutePath)
        }
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

            // PDF → built-in PDF viewer
            ext == "pdf" -> {
                startActivity(PdfViewerActivity.intent(this, file.absolutePath))
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
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat", file.absolutePath))
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

        if (folder.parentFile != null) {
            val lockedFile = File(folder.parentFile!!, folder.name + ".locked")
            if (folder.renameTo(lockedFile)) {
                loadFiles(currentPath)
                Toast.makeText(this, "Folder locked", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to lock folder", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Cannot lock root directory", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileInfoDialog(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fileSize = if (file.isDirectory) {
                var total = 0L
                var count = 0
                try {
                    file.walkTopDown().forEach { f ->
                        if (f.isFile) {
                            total += f.length()
                            count++
                        }
                    }
                } catch (_: Exception) {}
                "$count files, ${Formatter.formatShortFileSize(this@MainActivity, total)} total"
            } else {
                val bytes = file.length()
                val kb = bytes / 1024.0
                val mb = kb / 1024.0
                val gb = mb / 1024.0
                "${Formatter.formatShortFileSize(this@MainActivity, bytes)} (${"%.2f".format(kb)} KB / ${"%.2f".format(mb)} MB / ${"%.2f".format(gb)} GB)"
            }

            val createdDate = try {
                val attrs = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(attrs.creationTime().toMillis()))
            } catch (_: Exception) { "Unknown" }

            val modifiedDate = try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
            } catch (_: Exception) { "Unknown" }

            val lastAccessedDate = try {
                val attrs = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(attrs.lastAccessTime().toMillis()))
            } catch (_: Exception) { "Unknown" }

            val permissions = try {
                val r = if (file.canRead()) "r" else "-"
                val w = if (file.canWrite()) "w" else "-"
                val x = if (file.canExecute()) "x" else "-"
                "$r$w$x"
            } catch (_: Exception) { "???" }

            val mimeType = getMimeType(file)
            val nameWithoutExt = file.nameWithoutExtension
            val ext = file.extension.ifEmpty { "(none)" }

            val info = buildString {
                appendLine("Full Path: ${file.absolutePath}")
                appendLine("File Name: ${file.name}")
                appendLine("Name (no ext): $nameWithoutExt")
                appendLine("Extension: $ext")
                appendLine("Type: ${if (file.isDirectory) "Directory" else "File"}")
                appendLine("Size: $fileSize")
                appendLine("Created: $createdDate")
                appendLine("Modified: $modifiedDate")
                appendLine("Last Accessed: $lastAccessedDate")
                appendLine("Permissions: $permissions")
                appendLine("MIME Type: $mimeType")
            }

            withContext(Dispatchers.Main) {
                val scrollView = android.widget.ScrollView(this@MainActivity)
                val textView = TextView(this@MainActivity).apply {
                    text = info.trimEnd()
                    setPadding(48, 24, 48, 8)
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
                }
                scrollView.addView(textView)

                val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("File Info — ${file.name}")
                    .setView(scrollView)

                if (!file.isDirectory) {
                    dialog.setPositiveButton("Calculate MD5") { d, _ ->
                        d.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val md = java.security.MessageDigest.getInstance("MD5")
                                file.inputStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        md.update(buffer, 0, read)
                                    }
                                }
                                val hash = md.digest().joinToString("") { "%02x".format(it) }
                                withContext(Dispatchers.Main) {
                                    val hashText = TextView(this@MainActivity).apply {
                                        text = "MD5: $hash"
                                        setPadding(48, 24, 48, 24)
                                        textSize = 13f
                                        typeface = android.graphics.Typeface.MONOSPACE
                                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
                                    }
                                    MaterialAlertDialogBuilder(this@MainActivity)
                                        .setTitle("MD5 Hash")
                                        .setView(hashText)
                                        .setPositiveButton("Copy") { _, _ ->
                                            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("md5", hash))
                                            Toast.makeText(this@MainActivity, "MD5 copied to clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                        .setNegativeButton("Close", null)
                                        .show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Failed to calculate MD5: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                dialog.setNegativeButton("Close", null).show()
            }
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
        // Animate out current list before navigating
        if (settings.animationsEnabled) {
            binding.recyclerView.animate()
                .alpha(0f)
                .translationX(-24f)
                .setDuration(120L)
                .withEndAction {
                    currentPath = directory
                    loadFiles(directory)
                    updateBreadcrumbs(directory)
                    settings.addRecent(directory.absolutePath)
                    binding.recyclerView.alpha = 0f
                    binding.recyclerView.translationX = 24f
                    binding.recyclerView.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(180L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                .start()
        } else {
            currentPath = directory
            loadFiles(directory)
            updateBreadcrumbs(directory)
            settings.addRecent(directory.absolutePath)
            // Update custom breadcrumb view
            binding.breadcrumb.setPath(directory) { file ->
                navigateTo(file)
            }
        }
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
        // Update breadcrumb navigation
        binding.breadcrumb.setPath(currentPath) { file ->
            navigateTo(file)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var files = directory.listFiles()?.toList() ?: emptyList()

            // If root mode is enabled and normal listing fails, try root listing
            if (files.isEmpty() && (isRootMode || directory.absolutePath != Environment.getExternalStorageDirectory().absolutePath)) {
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
                // Animate the list transition
                if (settings.animationsEnabled) {
                    TransitionManager.beginDelayedTransition(binding.recyclerView, Fade().apply {
                        duration = 200L
                    })
                }
                adapter.updateFiles(finalFiles)
                binding.emptyState.visibility = if (finalFiles.isEmpty()) View.VISIBLE else View.GONE
                // Wire up empty state create button
                if (finalFiles.isEmpty()) {
                    try {
                        binding.emptyState.findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyStateCreateBtn)?.setOnClickListener {
                            showCreateOptions()
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun listFilesAsRoot(directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls", "-aF", directory.absolutePath))
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
        // Show/hide paste option based on clipboard
        if (FileClipboard.hasContent) {
            // Clipboard has content — paste action available via snackbar after copy/move
        }
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
