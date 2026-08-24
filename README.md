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
- List view **and** media grid view (image *and* video thumbnails)
- Multi-select with share / copy / move / delete action bar
- Cut, copy, paste with conflict detection (`FileClipboard`)
- Batch rename with live preview
- Bookmarks and recent files

### Built-in viewers & editors
- **Text editor** — syntax detection, undo/redo, find & replace
- **Code viewer** — highlighted, monospace, line numbers
- **Image viewer** — pinch-zoom, gallery swipe navigation
- **Video player** — gesture controls, 10s skip
- **PDF viewer** — smooth rendering with page navigation
- **HTML viewer** — sandboxed local preview
- **APK inspector** — permissions, activities, extraction

### Power tools (Utils Hub)
- Terminal (with optional root mode)
- ZIP compress / extract
- Storage analyzer with type breakdown
- Hash calculator (MD5 / SHA-1 / SHA-256)
- JSON formatter, Regex tester, Text diff
- Base64 / URL encode-decode
- Color picker, IP info
- **Wi-Fi file server** — access your phone's files from a browser

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
