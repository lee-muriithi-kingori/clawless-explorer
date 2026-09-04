# Clawless Explorer — Design Notes (Clean v3)

> v3 cleanup: flat Material 3 surface, no gradient hero, no canvas animations,
> single breadcrumb system. Content-first, Files-by-Google calm.
> Dials: VARIANCE 4 / MOTION 3 / DENSITY 6.

## Brand

## Brand

| Token | Hex | Use |
| --- | --- | --- |
| **Primary (indigo)** | `#5B5BF6` | FAB, primary actions, gradient hero start |
| **Primary container** | `#E8E8FF` | Selected chips, selection row background |
| **Secondary (pink)** | `#FF6B9D` | Gradient hero end, accent highlights |
| **Tertiary (teal)** | `#00C8B4` | Gradient storage end, success states |

Gradient hero: `linear-gradient(135deg, #5B5BF6 0%, #7B5BF6 50%, #FF6B9D 100%)`
at 32dp bottom radius.

## File-type accent palette

| Type | Accent | Soft bg |
| --- | --- | --- |
| Folder | `#F59E0B` amber | `#FEF3C7` |
| Image | `#EC4899` pink | `#FCE7F3` |
| Video | `#8B5CF6` violet | `#EDE9FE` |
| Audio | `#06B6D4` cyan | `#CFFAFE` |
| Document | `#3B82F6` blue | `#DBEAFE` |
| Archive | `#F97316` orange | `#FFEDD5` |
| Code | `#10B981` emerald | `#D1FAE5` |
| APK | `#6366F1` indigo | `#E0E7FF` |
| Locked | `#EF4444` red | `#FEE2E2` |
| Generic | `#64748B` slate | `#F1F5F9` |

Each file row paints the badge background with the soft tint and tints the
icon glyph with the accent. APKs fold into the archive segment of the storage
stacked bar but keep their own filter chip and icon.

## Shape scale

12 / 16 / 20 / 24 / 32 dp, applied via theme shapes:

- `ShapeAppearance.Clawless.Small` — 12dp
- `ShapeAppearance.Clawless.Medium` — 20dp (chip / card)
- `ShapeAppearance.Clawless.Large` — 28dp (bottom sheet, hero)
- `ShapeAppearance.Clawless.XLarge` — 32dp (gradient hero bottom)

## Typography

Sans-serif family throughout, with three weight tiers:

- **Display** (sans-serif-black) — hero titles, large numbers
- **UI** (sans-serif-medium) — labels, button text, app bar titles
- **Body** (sans-serif, monospace for code)

Negative letter-spacing on display + title sizes, positive on all-caps labels.

## Layout (v3 clean)

- `activity_main.xml` — DrawerLayout → CoordinatorLayout
  - `AppBarLayout` flat `?attr/colorSurface`, `liftOnScroll=true` containing:
    - **Toolbar** — static `TextView` title, 4 icons tinted `onSurface`/`onSurfaceVariant`
    - **Storage** — single compact `surfaceVariant` card (no pill, no glass, tap → analyzer)
    - **Search** — TextInputLayout, hidden by default
    - **Filter chips** — Material chip group, single-select
  - `BreadcrumbView` — single path system (legacy `breadcrumbContainer` removed)
  - `SwipeRefreshLayout` → `RecyclerView`
  - `MaterialCardView` — bottom selection action bar (hidden by default)
  - `ExtendedFloatingActionButton` — "New" FAB
- `item_file.xml` — MaterialCardView 16dp radius, 42dp badge + name/meta + more
- `bottom_sheet_file_actions.xml` — vertical list with preview header
- `nav_header.xml` — flat `surfaceVariant`, no gradient, no glass
- `dialog_text_viewer.xml` — monospace selectable text

Removed in v3: `ParticleFieldView`, `AuroraGradientView`, `MorphingWaveView`,
`TypeWriterTextView`, `storagePill`, `breadcrumbContainer`, `serverStatusText`.
List animation is a 140ms fade gated by Settings → UI Animations.

## Script execution (v2.0.2)

- Tapping `.sh` / `.bash` / `.zsh` opens a Run dialog: Run, Edit, Cancel,
  plus a "Run as root (su)" checkbox carried into the terminal session.
- `TerminalActivity.intent(ctx, path, asRoot)` applies the root flag on launch,
  including the SU button state. `chmod +x` fallback kept.
- A `#!` first line beats the extension map, so scripts that name their own
  interpreter run as written. Runs report `[exit: N]`, and the log can be
  saved or shared from the terminal overflow.
- Long-press sheet keeps its "Run in Terminal" row for scripts, binaries,
  and extensionless files.

## APK inspect (Apktool-flavored, no decode weight)

- Apktool (Apache 2.0) decodes and rebuilds; too heavy to ship on-device.
  Borrowed workflow instead: read-only structure peek.
- `ApkViewerActivity` adds a CONTENTS card: total zip entries plus every
  `classes*.dex` with size, read via `ZipFile`. Permissions card unchanged.
- Copy rule: plain text everywhere, no emoji in dialogs, sheets, or logs.

## Components

### Storage card

The glass-style card on top of the gradient hero. The `STORAGE` eyebrow label
on the top-left, free-of-total on the right. Big "Internal Storage" title
below. Then the **stacked bar** — file-type breakdown by absolute size
relative to total storage. The remaining transparent space is the headroom.
Below: three pill stats (Images / Videos / Audio) with their respective
accent dots.

### Filter chips

`ChipGroup` with `singleSelection=true`, `selectionRequired=true`. Seven
chips: All, Images, Videos, Audio, Docs, Archives, APKs. The "All" chip
resets the filter. The icon on each chip is tinted to the file-type accent
for an at-a-glance category map.

### Selection action bar

Slides up from the bottom (200dp translation + alpha) when entering
selection mode. Shows: count label, share, copy, more, delete. Hides the
chip row and the FAB. Resets when count returns to 0.

### Bottom sheet (file actions)

`BottomSheetDialog` with `STATE_EXPANDED`. Rounded top corners (28dp) via
`bg_bottom_sheet`. The preview header at the top shows the same badge
treatment as the row, with name + meta. Below: action rows. The Delete
row is the only one with the error color.

## Animations (v3)

- Toolbar title is static text (instant legibility)
- File rows: 140ms alpha fade on first bind, disabled via Settings
- Search bar slides down + fades in
- Selection bar slides up + fades in, FAB hides/shows
- No background loops, no typewriter, no overshoot

## Things to know when extending

1. **Don't reference `@android:drawable/ic_menu_*` system icons.** Use the
   custom vectors in `res/drawable/ic_*.xml` and `ic_file_*.xml` so the
   color tints match the file-type palette.
2. **All view IDs in layouts are auto-bound** via Android View Binding. Add
   the ID, use it through `binding.X`. The only exception is `tag_animated`
   which is in `res/values/ids.xml`.
3. **The filter chip and file-type logic both flow through `FileAdapter.TypeFilter`.**
   The chip group in MainActivity calls `adapter.setTypeFilter(...)`.
4. **Dark mode is automatic** (`values-night/colors.xml` overrides). All
   semantic colors (`colorOnSurface`, etc.) flow through `?attr/...` so the
   same layout renders in both themes.

## Files added or replaced in the v2 design pass

```
app/src/main/res/color/chip_filter_bg.xml          # chip selected/unselected
app/src/main/res/color/chip_filter_stroke.xml      # chip stroke color
app/src/main/res/drawable/bg_*.xml                 # 13 badge + 4 track drawables
app/src/main/res/drawable/ic_*.xml                 # 33 custom vector icons
app/src/main/res/layout/activity_main.xml          # full redesign
app/src/main/res/layout/item_file.xml              # full redesign
app/src/main/res/layout/bottom_sheet_file_actions.xml  # preview header + modern rows
app/src/main/res/layout/nav_header.xml             # gradient hero header
app/src/main/res/layout/dialog_text_viewer.xml     # monospace selectable
app/src/main/res/values/colors.xml                 # 12KB palette
app/src/main/res/values-night/colors.xml           # dark variant
app/src/main/res/values/themes.xml                 # shape + typography + components
app/src/main/res/values/ids.xml                    # tag_animated
app/src/main/java/.../FileAdapter.kt               # TypeFilter enum, applyFilters
app/src/main/java/.../MainActivity.kt              # new wires + bottom bar
DESIGN.md                                          # this file
preview.html                                       # live visual reference
```
