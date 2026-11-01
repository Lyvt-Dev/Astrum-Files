# Feature checklist

**Legend**
- 🧪 Implemented and covered by JVM unit tests (pure logic; Android framework paths not exercised)
- ✅ Implemented and wired into the UI. It compiles and passes lint, but has **not yet been verified on a device or emulator**.
- 🟡 Partial (the limitation is described)
- ⬜ Not started
- 🚫 Unsupported by design or by platform

**Verification status (0.2.0):** `assembleDebug` ✔, signed `assembleRelease` (R8) ✔, `testDebugUnitTest` ✔ (38 tests), `lintDebug` ✔ (0 errors).
The user has run 0.1.x on a physical phone. 0.2.0 has **not** been run on a device yet, because the build host has no KVM.

## Milestone 1: Foundation
| Feature | Status | Notes |
|---|---|---|
| Kotlin + Compose + Material 3 project, Gradle KTS, version catalog | ✅ | AGP 8.11, Kotlin 2.2, compile/target SDK 36, min SDK 29 |
| Hilt DI, Room, DataStore, coroutines/StateFlow MVVM | ✅ | |
| Custom design system (tokens, cards, donut, usage bar, empty/loading states) | ✅ | Light (ice blue) and dark (graphite) palettes, 6 accents, Material You |
| Five-section navigation (Home, Files, Cleaner, Analytics, Vault) | ✅ | Bottom bar on phones, navigation rail at ≥600dp |
| Edge-to-edge, predictive back (`enableOnBackInvokedCallback`) | ✅ | |
| Permission onboarding (all-files access, or limited media mode) | ✅ | |
| Astrum branding (logo icon, splash screen, purple/gold palette) | ✅ | |
| Custom font (Manrope / Plus Jakarta Sans) | ⬜ | Uses the system sans-serif with a tuned type scale. The font file is not bundled yet. |
| EN + DE localisation | 🟡 | Navigation and core strings are in `values/` and `values-de/`. Many dialog and feature strings are still hard-coded English. |

## Milestone 2: Core file manager
| Feature | Status | Notes |
|---|---|---|
| Browse, list/grid, thumbnails, hidden files toggle | ✅ | |
| Create file/folder, rename (incl. extension), duplicate | 🧪 | Name validation, no overwrite on rename |
| Copy/move with progress, cancellation, conflict policy | 🧪 | Atomic `.lyvtpart` writes, copy-into-self guard, free-space check |
| Delete → trash, restore, permanent delete | ✅ | `TrashManager` (same-volume rename). Restore picks a unique name if the original path is taken. |
| Share, open with external apps (FileProvider) | ✅ | |
| Multi-select, select all, invert | ✅ | |
| Recursive search with operators (`ext:` `type:` `size>` `after:` `before:` `re:` quotes) | 🧪 | Streams results, cancellable |
| Sort (name natural / date / size / type), folders-first, ascending/descending | 🧪 | |
| Favorites, pin folders with color label | ✅ | |
| Properties: MIME, size, dates, permissions, SHA-256/MD5, checksum compare, EXIF / media metadata | ✅ | |
| Folder size calculation | ✅ | |
| Breadcrumbs, back/forward history, tabs | ✅ | |
| Split view on tablets | ✅ | Two independent panes sharing the clipboard |
| In-app clipboard + previous-clipboard restore | 🟡 | Keeps the last 10 entries. The UI only restores the previous one. |
| Batch rename (pattern, counter, padding, date, regex, preview) | 🧪 | |
| File and directory comparison | ✅ | |
| Operation history | ✅ | Room log + screen |
| SD card / multiple volumes | ✅ | `StorageManager` volumes with real `StatFs`. Removed cards disappear, and missing folders show an error state. |
| Drag and drop | ⬜ | |
| Background (WorkManager) transfers that survive process death | ⬜ | Transfers currently run in `viewModelScope` |
| Storage Access Framework tree browsing (no all-files access) | ⬜ | |
| Search history and saved searches, `tag:` search | ✅ | |

## Milestone 3: Feature parity
| Feature | Status | Notes |
|---|---|---|
| Home: storage cards per volume, animated donut, categories (MediaStore), quick access, recent, frequent, favorites, cleanup tips | ✅ | |
| Dashboard customisation (order, visibility, compact mode) | ✅ | |
| Duplicate finder (size → 16 KiB hash → full SHA-256), keeps one copy | 🧪 | |
| Similar photos (dHash, adjustable threshold) | ✅ | Heuristic, labelled as such. Capped at 4000 images per scan. |
| Blurry photos (Laplacian variance, adjustable) | ✅ | Heuristic, labelled as such |
| Large files (threshold), old files (months, modification-based), empty folders | ✅ | |
| Download cleaner (APKs, partial downloads, old downloads), screenshots by day | ✅ | |
| Unused apps (UsageStats, explains missing permission) | ✅ | Launcher apps only |
| Cleaner safety: review list, savings estimate, confirmation, trash, protected folders, exclusions, cancel | ✅ | |
| Redundant archive finder | ✅ | Archive with an extracted sibling folder |
| Scan history | ⬜ | |
| Analytics: device vs inspectable storage, category donut, treemap, extension bars, largest files, duplicate waste, trend | ✅ | Trend uses snapshots taken at most every 6 hours when Home opens |
| Folder drill-down from charts | ✅ | Opens the folder in Files |
| Vault: PIN, biometrics, AES-256-GCM, Keystore binding, recovery code, encrypted thumbs/index, folders, search, auto-lock, export, backup/restore | 🧪 / ✅ | Crypto core is unit-tested. Keystore and biometric paths still need device testing. |
| Vault viewers | 🟡 | Decrypts to app-private cache, then uses the built-in image/video/PDF/text viewers |
| Archive: create ZIP (AES-256, level), extract ZIP/7z/TAR(.gz/.bz2/.xz)/GZ/BZ2, browse, selected extract, test | 🧪 | |
| ZIP Slip / bomb / overwrite protection | 🧪 | |
| Creating 7z/TAR | ⬜ | |
| RAR | 🚫 | Proprietary format, and there is no maintained, permissively licensed extractor |
| Trash: restore, original path, date, sort, multi-select, retention, size limit, stats | ✅ | |
| Android MediaStore trash integration | ⬜ | |
| Image compress / convert (JPEG, PNG, WebP, resize) | ✅ | Always a new file |
| Image viewer: zoom, double tap, swipe gallery, rotate, EXIF, slideshow, share, crop (square), save edited copy, wallpaper | ✅ | Free-form crop is not implemented |
| Video/music player (Media3): background service, notification and lock screen, speed, sleep timer, queue, shuffle/repeat, PiP, gestures, resume, track selection | ✅ | Codec support is whatever the device provides. No bundled decoders. |
| Album artwork / music library browsing | 🟡 | Shown by Media3's PlayerView when embedded. There is no dedicated library screen. |
| Playlists persisted | ⬜ | Queue = files in the same folder |
| PDF: page render, zoom, rotate, bookmarks, info, print, share | ✅ | |
| PDF merge / split / extract / rotate copy / metadata / text search | ✅ | PdfBox-Android. Search finds pages but does not highlight. No text selection. |
| Text/code editor: highlighting, line numbers, find and replace, safe save | ✅ | Up to 2 MB editable, 8 MB viewable |
| Office document preview (docx/xlsx) | ⬜ | Opens in external apps |

## Milestone 4: Advanced
| Feature | Status | Notes |
|---|---|---|
| SFTP / SMB / WebDAV / FTP clients, saved connections, Keystore-encrypted credentials | ✅ | Browse, upload (SAF picker), download to Download/Astrum, rename, delete, mkdir, progress, cancel, connection test |
| Background (WorkManager) network transfers | ⬜ | Transfers run while the screen is open |
| Astrum Transfer: QR pairing, token auth, expiry, approval, multi-file, history, resumable downloads, NSD discovery | ✅ | Plain HTTP (see SECURITY.md). Folder transfer means selecting the files. |
| Encrypted transfer channel (TLS) | ⬜ | |
| Smart Organizer: rules, templates, dry-run preview, approval, undo, daily automatic runs | 🧪 | |
| "Sort into folders" in Files (by type, by extension, by month, or chosen types such as ZIPs into one folder), with preview and undo | ✅ | Built on the organizer engine |
| Animated brand intro after the system splash | ✅ | 1.4 s; shortened when reduce motion is on |
| Collections, tags, notes (Room), export/import, move tracking, missing-file relink | ✅ | |
| Storage widget, quick-access widget | ✅ | |
| Recent-files / favorite-folders widgets | ⬜ | |
| Developer inspector, JSON/XML format, Markdown preview, encoding detection | 🧪 / ✅ | |
| Decoy vault | ✅ | |
| Mini-player for background audio | ✅ | |
| Storage timeline (used/free/files/category growth) | ✅ | Real snapshots only |
| QR-code sharing of single files | 🟡 | Via Astrum Transfer session |

## Milestone 5: Quality
| Feature | Status |
|---|---|
| JVM unit tests: file ops, copy/move/conflicts, search, natural sort, batch rename, duplicates, scanner, vault crypto, archive security | 🧪 38 tests (adds organizer, encoding, PDF page spec) |
| Compose UI tests, instrumentation tests | ⬜ |
| Settings: theme, Material You, accent, font size, density, grid, reduced motion, sorting, start page, hidden files, thumbnails, trash retention/limit, vault lock, exclusions, protected dirs, settings backup/restore | ✅ |
| Accessibility audit (TalkBack labels, contrast) | 🟡 Content descriptions on key controls; no full audit yet |
| Benchmarks / profiling | ⬜ No numbers are claimed |

## Windows desktop app (`desktop/`)
| Feature | Status | Notes |
|---|---|---|
| Shared core (file ops, search, batch rename, organizer, archives, duplicates, vault cipher) | 🧪 | Same code as Android, compiled for desktop |
| Explorer: sidebar/drives, table/grid, sort, search, copy/move/paste + conflicts + progress/cancel, Recycle Bin delete, rename, batch rename, ZIP/extract, sort into folders + undo, compare, properties + hashes, PDF merge, shortcuts, context menus | ✅ | Launched and screenshotted on Linux/Xvfb. Not yet run on real Windows. |
| Viewers: image (zoom/rotate/slideshow/convert), text/code editor, PDF (search/extract/split/rotate), archives | ✅ | Audio/video open in the system player |
| Cleaner (8 tools) with progress and safe deletion | ✅ | |
| Analytics (drives, donut, treemap, extensions, largest, duplicate waste, trend) | ✅ | |
| Vault (password + recovery code, decoy, auto-lock, backup) | 🧪 | Round-trip, wrong-password, decoy-isolation and recovery tests |
| Astrum Transfer PC ↔ phone (QR, token, expiry, approval, history) | ✅ | Plain HTTP on the LAN, as on Android |
| Smart Organizer (rules, templates, preview, undo) | ✅ | |
| Windows MSI/EXE installer | 🟡 | Built by GitHub Actions on windows-latest. It cannot be built on this Linux host. |
| Drag & drop from Windows Explorer | ⬜ | |
| Network clients (SMB/SFTP/WebDAV/FTP) on desktop | ⬜ | |

## Quick Share (phone 0.2.6 / PC 1.1.0)
| Feature | Status | Notes |
|---|---|---|
| Nearby Astrum devices (auto-discovery) | ✅ | UDP broadcast beacon on port 47823 while a session runs; beacon has no token. Connecting requires "Allow" on the other device, then the normal token session is used. Same Wi-Fi required. |
| Phone: Quick Share button | ✅ | Opens the system Quick Share (Samsung / Google) share target directly, falls back to the share sheet. |
| Phone: Bluetooth button | ✅ | Opens the system Bluetooth share target (OBEX) — works with Windows "Receive files via Bluetooth". |
| PC: Bluetooth send/receive | ✅ (Windows) | Opens the built-in Windows Bluetooth file transfer wizard (fsquirt). Not tested on real hardware. |
| PC: Samsung/Google Quick Share protocol | ❌ | Proprietary protocol; use Google's "Quick Share for Windows" app or Astrum's nearby connect instead. |
