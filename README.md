# Clawless Explorer

A premium, feature-packed file manager for Android — fast, beautiful, and built
for power users. Browse, organize, preview, and manipulate files with a modern
Material 3 interface that stays out of your way.

![Platform](https://img.shields.io/badge/platform-Android-green)
![Min SDK](https://img.shields.io/badge/minSdk-24-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## Features

### File management
- Full filesystem browsing with breadcrumbs and hidden-file toggle
- List view **and** media grid view (image thumbnails in both; video frames in grid)
- Multi-select with share / copy / move / delete action bar
- Cut, copy, paste via `FileClipboard` (fails on conflict with the reason shown; no overwrite prompt yet)
- Batch rename with live preview and invalid-regex guard
- Bookmarks and recent files

### Built-in viewers & editors
- **Text editor** — language label, undo/redo, find & single replace-next, unsaved-changes guard (files over 2 MB open read-only to avoid truncation)
- **Code viewer** — highlighted, monospace, line numbers
- **Image viewer** — pinch-zoom, single image; folder swipe lives in the gallery screen
- **Video player** — 10s skip buttons, tap-to-hide controls
- **PDF viewer** — paged rendering with page navigation and pinch/double-tap zoom
- **HTML viewer** — local preview with same-folder asset resolution (JavaScript on)
- **APK inspector** — permissions, version/SDK info, dex listing, signer info
- **Shell scripts** — tapping `.sh` asks Run / Edit / Cancel with an as-root option; `#!` lines respected

### Power tools (Utils Hub)
- Terminal (with optional root mode)
- ZIP compress / extract (.zip only; unsafe entry paths are skipped and reported)
- Storage analyzer with type breakdown (percentages are of scanned files; the home card shows share of total storage)
- Hash calculator (MD5 / SHA-1 / SHA-256 / SHA-512)
- JSON formatter, Regex tester, Text diff
- Base64 / URL / HTML encode-decode
- Color picker, first-IPv4 network info
- **Wi-Fi file server** — access your phone's files from a browser (token shown in Settings)

## Screenshots

See `preview.html` at the repo root for the design system and visual reference,
and `DESIGN.md` for tokens, palettes, and layout conventions.

## Build

Requirements: JDK 17, Android SDK 34.

```bash
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # release APK
```

Or open in Android Studio and press Run.

## Download

Grab the latest APK from [Releases](https://github.com/lee-muriithi-kingori/clawless-explorer/releases).

## License

[MIT](LICENSE)
