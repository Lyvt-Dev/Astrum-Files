# Astrum Files

Astrum Files is a privacy-first Android file manager built with Kotlin and Jetpack Compose. It covers the usual
file-management features plus a storage cleaner, storage analytics, an encrypted vault, an archive manager, a trash,
and built-in media and document viewers.

> Status: **0.2.0**. The core app compiles, unit tests pass, and a debug APK is produced.
> It has **not yet been run on a device or emulator** in CI (the build machine has no hardware virtualisation).
> See [`FEATURES.md`](FEATURES.md) for an honest per-feature status.

## Highlights

- **Files**: list and grid views, tabs, split view on tablets (≥600dp), breadcrumbs, back/forward history,
  multi-select, copy/move with conflict resolution (keep both / skip / replace), trash-backed delete, rename,
  extension change, duplicate, batch rename (patterns, counters, regex), recursive search with operators,
  natural sort, properties with SHA-256/MD5 checksums and EXIF/media metadata, folder size, file/folder compare.
- **Cleaner**: exact duplicates (size → partial hash → full SHA-256), similar photos (dHash), possibly blurry photos
  (Laplacian variance), large files, old files, empty folders, download cleanup, screenshots by day, unused apps
  (with Usage Access). Everything is reviewed before deletion, and duplicate groups always keep at least one copy.
- **Analytics**: device storage compared with the portion the app can actually inspect, category donut, folder treemap,
  extension bars, largest files, duplicate waste, and a used-storage trend built from local snapshots. Tapping a chart
  item opens the matching files.
- **Vault**: AES-256-GCM chunked streaming encryption, a PBKDF2-wrapped master key bound to the Android Keystore,
  biometric unlock, a one-time recovery code, encrypted thumbnails and index, folders, search, auto-lock, and
  encrypted backup/restore.
- **Archives**: create ZIP (optionally AES-256), extract ZIP/7z/TAR/TAR.GZ/TAR.BZ2/TAR.XZ/GZ/BZ2, browse and
  extract selected entries, integrity test, ZIP-Slip and decompression-bomb protection.
- **Smart Organizer**: offline rules (extension, regex, size, age, folder) with `{ext} {category} {yyyy} {MM} {lang}`
  destinations, dry-run preview, approval, undo, and optional daily runs through WorkManager.
- **Network**: SFTP (pinned host keys), SMB/NAS, WebDAV (HTTPS, validated certificates), and FTP (labelled insecure).
  Browse, upload, download, rename, delete, progress and cancel. Passwords are encrypted with the Android Keystore.
- **Astrum Transfer**: phone↔PC and phone↔phone via QR code. Uses a token-protected local HTTP session that expires,
  approves every incoming file on the phone, supports resumable downloads (HTTP Range), and keeps a history.
- **Collections, tags and notes** stored in Room, which follow files when they are moved inside the app.
- **Developer tools**: file inspector (MD5/SHA-1/SHA-256/SHA-512, encoding, line endings, MIME, POSIX permissions,
  APK metadata), JSON/XML formatter, Markdown preview, and highlighting for about 20 languages.
- **PDF tools**: merge, split, extract pages, rotate copy, metadata, and text search (PdfBox-Android).
- **Storage timeline** built from real snapshots (WorkManager, every ~12 h). **Widgets**: storage and quick access.
- **Vault 2.0**: optional independent decoy vault unlocked by a different PIN.
- **Viewers**: image viewer (zoom, swipe, rotate, slideshow, EXIF, edited copies, wallpaper), Media3 audio/video
  player (background playback service, notification and lock-screen controls, speed, sleep timer, queue, PiP,
  gestures, resume position), PDF viewer (system renderer, zoom, rotate, bookmarks, print, share), and a text/code
  editor (syntax highlighting, line numbers, find and replace, safe save).

## Build

Requirements: JDK 17+ (tested with JDK 21), Android SDK platform 36 and build-tools 36.

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or set ANDROID_HOME
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds: see [`docs/RELEASE.md`](docs/RELEASE.md).

## Architecture

There is a single Gradle module, `app`, organised into package-level layers. It was deliberately kept as one
module for now to keep build times and Hilt wiring simple, and the packages are separated so they can be moved into
Gradle modules later.

```
com.lyvt.files
├── core
│   ├── common      formatting, natural ordering
│   ├── storage     FileOperations (pure JVM), FileRepository, TrashManager, search, batch rename, volumes, MediaStore stats
│   ├── scan        StorageScanner, DuplicateFinder, perceptual hash / sharpness, ScanCache
│   ├── archive     ArchiveManager (zip4j + Apache Commons Compress)
│   ├── security    VaultCipher (pure JVM crypto), VaultRepository (Keystore, biometrics, index)
│   ├── media       PlaybackService (Media3 MediaSessionService)
│   ├── database    Room entities and DAOs
│   └── settings    DataStore-backed settings, JSON backup
├── di              Hilt modules
├── ui              theme, design-system components, navigation
└── feature         home, explorer, cleaner, analytics, vault, trash, settings, viewer
```

MVVM with `StateFlow`, Hilt DI, Room, DataStore, coroutines with structured cancellation, and Coil for thumbnails.

## Permissions

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (optional) | A general-purpose file manager needs to browse every folder. The user grants it in system settings. Without it the app offers a reduced media-only mode. |
| `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE` (≤ API 32) | Media-only mode and Android 10 support. |
| `PACKAGE_USAGE_STATS` (optional) | Only for the "unused apps" tool. The user must grant it manually. |
| `USE_BIOMETRIC` | Vault biometric unlock. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK` | Background audio playback. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Remote file systems and Astrum Transfer (user-initiated only). |
| `SET_WALLPAPER` | "Set as wallpaper" in the image viewer. |

`INTERNET` is used **only** for features you start yourself (remote servers, Astrum Transfer). There is no analytics, no ads and no background network traffic.

**Play Store note:** Google Play allows "All files access" only for apps whose core purpose needs it, such as file
managers, and requires a declaration form. If that is not accepted, the fallback is a Storage Access Framework and
MediaStore build (not implemented yet, see FEATURES.md).

## Documentation

- [`FEATURES.md`](FEATURES.md): feature checklist (implemented, tested, partial, not started)
- [`docs/SECURITY.md`](docs/SECURITY.md): threat model and vault design
- [`docs/RELEASE.md`](docs/RELEASE.md): signing and release steps

## License and identity

This is an original implementation. It contains no code, assets or branding from any other app. The icon is an
original vector drawing.
