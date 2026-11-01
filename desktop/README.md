# Astrum Files for Windows

Desktop version of Astrum Files, built with Compose for Desktop (Kotlin). It has the same design as the Android app
and shares its tested core: file operations, search, the organizer, archives, duplicate detection and vault encryption.

## Features

- **Files**
  - Sidebar with quick access, favorites and drives (with free space).
  - Sortable table or grid view, breadcrumbs, back/forward/up, and search with operators (`ext:` `type:` `size>`
    `after:` `re:`).
  - Copy, cut and paste with a progress bar, cancellation and conflict handling (keep both / skip / replace).
  - Delete to the Recycle Bin, rename, new file/folder, duplicate, batch rename (patterns and regex), compress
    (ZIP with optional AES-256) and extract (ZIP/7z/TAR/GZ/BZ2).
  - "Sort into folders" with preview and undo, compare files or folders, properties with MD5/SHA-1/SHA-256/SHA-512
    and checksum verification, and merge PDFs.
  - Right-click menus and shortcuts: Ctrl+C/X/V, Ctrl+A, Del, F2, F5, Enter, Backspace.
- **Viewers**
  - Images: zoom, rotate, slideshow, compress/convert/resize copies.
  - Text/code editor: highlighting, line numbers, find and replace, JSON/XML format, Markdown preview, atomic save.
  - PDF: render, zoom, rotate, text search, extract pages, split, rotated copy.
  - Archive browser: integrity test and selective extraction.
  - Audio and video open in your default Windows player.
- **Cleaner**: duplicates (SHA-256), similar photos (perceptual hash), blurry photos, large and old files, empty
  folders, old downloads and installers, already-extracted archives. Everything is reviewed before deletion, and
  deleted items go to the Recycle Bin.
- **Analytics**
  - Drive usage, a category donut and a folder treemap; clicking an item opens the matching files.
  - Top extensions, largest files and duplicate waste.
  - A used-space trend built from real snapshots.
- **Vault**: AES-256-GCM (same format as the phone), password plus recovery code, decoy vault, auto-lock (after 5 min
  idle, or 1 min minimized), encrypted backup, and "Encrypt into Vault" from the Files right-click menu.
- **Astrum Transfer**: PC ↔ phone via QR code. Token-protected sessions expire after 15 minutes, and each incoming
  file must be approved. Received files go to `Downloads\Astrum Received`.
- **Smart Organizer**: rules with templates, preview, run history and undo.

## Install

Download the `AstrumFiles-Windows` artifact from the latest **Build Astrum Files** GitHub Actions run:

- `Astrum Files-1.0.0.msi` or `.exe`: installer with its own Java runtime, so no separate Java is needed.
- `Astrum Files-windows-x64-1.0.0.jar`: portable version. Run it with `java -jar` (Java 17+).

The first time you start a Transfer session, Windows Firewall asks to allow Astrum Files. Allow it on **private
networks**.

## Build locally

```bash
./gradlew :desktop:run                      # start the app
./gradlew :desktop:test                     # tests
./gradlew :desktop:packageMsi               # on Windows: installer in desktop/build/compose/binaries
./gradlew :desktop:packageUberJarForCurrentOS
```

## Differences from the phone app (by design)

- **Vault key protection:** there is no Android Keystore or fingerprint unlock. The vault key is protected by your
  password (PBKDF2, 310k iterations) and the recovery code. Use a strong password.
- **Deletion:** files go to the Windows Recycle Bin instead of an app trash.
- **Not included:** SMB, SFTP, WebDAV and FTP clients (Windows Explorer already handles network shares), widgets,
  and an unused-apps tool.
- **Media playback** uses the system player.
