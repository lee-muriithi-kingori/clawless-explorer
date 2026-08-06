# Clawless Explorer Work Log

## 2025-07-10 — Agent B: New Feature Files Created

### Kotlin Files (5 new)
1. **BreadcrumbView.kt** — Custom HorizontalScrollView-based breadcrumb path navigation widget
   - Clickable chips with "›" separators, Material3 color styling
   - Collapses middle segments with "…" when path depth > 6
   - Auto-scrolls to end on path change

2. **FileClipboard.kt** — Singleton clipboard object for cut/copy/paste file operations
   - Supports COPY and MOVE (cut) modes
   - `paste()` returns success/failure lists with conflict detection
   - Fallback copy+delete for cross-filesystem moves

3. **TextEditorActivity.kt** — Full text editor with save, undo/redo, find/replace
   - Loads files up to 2MB, monospace font, syntax language detection
   - Undo/redo with action merging (500ms window)
   - Find/replace bar toggled from menu, save FAB
   - Status bar: language, line count, char count, modified indicator

4. **GalleryActivity.kt** — Image gallery with ViewPager2 swipe navigation
   - Filters directory for image files (jpg, png, webp, gif, bmp, svg, avif)
   - Coil image loading with crossfade, position counter overlay
   - Starts at user-selected image

5. **AppManagerActivity.kt** — Installed apps list with APK extraction
   - Lists user-installed apps sorted alphabetically with icon, name, package, size
   - Extract APK to Downloads/ExtractedAPKs with share intent
   - Empty state handling

### Layout XML Files (5 new)
1. **activity_text_editor.xml** — CoordinatorLayout with toolbar, search/replace bar, NestedScrollView+EditText, status bar, save FAB
2. **activity_gallery.xml** — FrameLayout with ViewPager2 and counter TextView
3. **activity_app_manager.xml** — CoordinatorLayout with toolbar, RecyclerView, empty state
4. **item_app.xml** — App list item with icon, name/package/size text, extract button

## 2025-07-10 — Agent A: Bug Fixes (11 files)

### 1. StorageAnalyzerActivity.kt (P0 CRASH)
- **Inverted boolean**: `if (isScanning.compareAndSet(false, true)) return` → `if (!isScanning.compareAndSet(false, true)) return` — the original logic was backwards, causing the scan to never start
- **CoroutineScope leak**: Replaced `CoroutineScope(Dispatchers.IO).launch` with `lifecycleScope.launch(Dispatchers.IO)` — old code created an unmanaged scope that leaked after Activity destruction
- **Recursive scan depth**: Added `depth` parameter to `scanDirectory()` with `if (depth > 20) return` guard to prevent stack overflow on deeply nested directory structures

### 2. FileAdapter.kt (P0 CRASH)
- **IndexOutOfBoundsException**: `notifyItemChanged(filteredFiles.indexOf(file))` could pass -1 when file not in filteredFiles. Fixed with bounds check: `val idx = filteredFiles.indexOf(file); if (idx >= 0) notifyItemChanged(idx)`
- **DiffUtil optimization**: Modified `updateFiles()` to use `DiffUtil.calculateDiff(FileDiffCallback(...))` with `diff.dispatchUpdatesTo(this)` instead of `notifyDataSetChanged()` for efficient, animated list updates

### 3. SettingsManager.kt (P0)
- **Unclosed process streams**: `isRootAvailable()` now properly closes `process.inputStream`, `process.errorStream`, and `process.outputStream` after `waitFor()` to prevent file descriptor leaks

### 4. PdfViewerActivity.kt (P0)
- **ParcelFileDescriptor leak**: Added `private var pfd: ParcelFileDescriptor? = null` member. The FD is now stored as a member and opened in `openDocument()`, then closed in `onDestroy()` alongside the document. This keeps the FD open while PdfiumAndroid needs it but ensures cleanup.

### 5. PdfRendererView.kt (P0 + P1, 5 fixes)
- **Bitmap OOM**: Capped bitmap dimensions with `coerceAtMost(2048)` on both width and height before `Bitmap.createBitmap`
- **Bitmap not recycled**: Added `pageBitmap?.recycle()` before reassigning `pageBitmap = bitmap`
- **Stale view reference**: Added `if (!isAttachedToWindow) return@post` guard inside `post { }` block
- **Raw Thread cleanup**: Added `renderThread` member, set it inside loadPage, interrupt in `onDetachedFromWindow()`
- **ValueAnimator leak**: Added `zoomAnimator` member, cancel previous animator in `animateZoomTo()` before starting new one; cancel in `onDetachedFromWindow()`
- **onDetachedFromWindow**: New override that interrupts render thread, cancels zoom animator, and recycles bitmap

### 6. PinchZoomImageView.kt (P1)
- **ValueAnimator not cancelled**: Added `scaleAnimator` member, cancel previous in `animateScaleTo()` before creating new one

### 7. ImageViewerActivity.kt (P1)
- **Handler callbacks not removed**: Added `onDestroy()` that calls `binding.topOverlay.removeCallbacks(hideOverlayRunnable)` and `binding.bottomOverlay.removeCallbacks(hideOverlayRunnable)` to prevent callbacks firing after Activity death

### 8. MediaViewerActivity.kt (P1)
- **MediaPlayer release safety**: Wrapped `mediaPlayer?.release()` in try-catch for `IllegalStateException` — MediaPlayer can throw if in wrong state during release

### 9. CodeViewerActivity.kt (P1)
- **Unclosed RandomAccessFile**: Replaced manual `val raf = ...; raf.close()` with `RandomAccessFile(file, "r").use { raf -> ... }` for automatic closing even on exceptions

### 10. TerminalSession.kt (P1)
- **Unclosed output stream**: Added `process.outputStream.close()` after `ProcessBuilder.start()` to prevent pipe deadlock when the shell doesn't read stdin
- **Volatile cwd**: Added `@Volatile` to `cwd` declaration — the field is read from IO coroutines and written from Main, needs visibility guarantee

### 11. HtmlViewerActivity.kt (P5)
- **WebView security**: Added `allowFileAccessFromFileURLs = false` and `allowUniversalAccessFromFileURLs = false` to WebView settings to prevent cross-origin file access from JavaScript

## 2025-07-10 — Agent C: Bug Fixes + Features (MainActivity, Layout, FileServer, Manifest)

### 1. MainActivity.kt — searchInFiles memory fix (P0)
- **Replaced `file.readText()`** with line-by-line `file.bufferedReader().use { reader → }` loop that breaks on first match. Prevents loading entire file contents into memory for grep-like search.

### 2. MainActivity.kt — rotation state loss fix (P1)
- **Added `onSaveInstanceState()`** override saving `current_path`, `show_hidden`, `root_mode`, `sort_type` to Bundle
- **Added state restoration** in `onCreate()` after settings init: reads from `savedInstanceState` to restore currentPath, showHiddenFiles, isRootMode, sortType after config change

### 3. MainActivity.kt — MANAGE_EXTERNAL_STORAGE permission dialog (P1)
- **Added runtime permission check** in `onCreate()` before `checkPermissionsAndLoadFiles()`: on Android 11+ (Build.VERSION_CODES.R), if `!Environment.isExternalStorageManager()`, shows a MaterialAlertDialog asking the user to grant all-files access or continue anyway

### 4. activity_main.xml — BreadcrumbView added
- **Added `<com.example.clawlessexplorer.BreadcrumbView>`** element with id `breadcrumb` between AppBarLayout and SwipeRefreshLayout, with `@color/md_surface_variant` background and 2dp elevation

### 5. MainActivity.kt — BreadcrumbView wired
- **In `loadFiles()`**: Added `binding.breadcrumb.setPath(currentPath) { navigateTo(it) }` at method start
- **In `navigateTo()`**: Added `binding.breadcrumb.setPath(directory) { navigateTo(it) }` in the non-animated code path

### 6. activity_main.xml + MainActivity.kt — Grid/List view toggle
- **Added `btnViewToggle` ImageButton** in toolbar between hidden toggle and settings button, using `@android:drawable/ic_menu_view`
- **Added `isGridView` member variable** in MainActivity
- **Added toggle listener** in `setupListeners()`: toggles between `GridLayoutManager(3)` and `LinearLayoutManager`, re-applies adapter, and reloads files

### 7. activity_main.xml + MainActivity.kt — Clipboard (Cut/Copy/Paste)
- **Added `selectionMove` ImageButton** in selection bar XML with `ic_content_cut` icon
- **Replaced `selectionCopy` handler**: now calls `FileClipboard.copy()` with selected files, shows Snackbar with "Paste" action
- **Added `selectionMove` handler**: calls `FileClipboard.move()`, shows Snackbar with "Paste" action
- **Added `pasteFiles()` method**: calls `FileClipboard.paste(currentPath)`, shows success/failure Snackbars, reloads file list on success
- **Added clipboard check** in `onResume()`: checks `FileClipboard.hasContent` for future paste FAB/toolbar action

### 8. FileServer.kt — path traversal fix (P0 SECURITY)
- **`/api/files` endpoint**: Replaced `File(rootDir, path.trimStart('/'))` with `resolveSafe(path)`, returning HTTP 403 "Path traversal blocked" if null
- **`/api/tree` endpoint**: Same fix — uses `resolveSafe(path)` instead of direct File constructor
- Both endpoints now go through the existing `resolveSafe()` method that validates canonical paths stay within rootDir

### 9. AndroidManifest.xml — new activities registered
- **Added 3 activities**: `TextEditorActivity`, `GalleryActivity`, `AppManagerActivity` — all `exported="false"` with `Theme.ClawlessExplorer`
