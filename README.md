# Filo

A private file manager for Android that looks and feels like Files by Google, without anything from Google inside.

- **No internet permission.** Filo can't connect to anything, so your files never leave your phone.
- **No Google Play Services, Firebase, analytics or trackers.** Works great on GrapheneOS.
- Built with Kotlin, Jetpack Compose and Material 3 (Material You colors from your wallpaper on Android 12+).
- Runs on Android 8.0 and newer; made mainly for GrapheneOS on a Pixel 8 Pro.

<p>
  <img src="docs/screenshots/browse.png" width="200" alt="Browse tab">
  <img src="docs/screenshots/storage.png" width="200" alt="Storage tab">
  <img src="docs/screenshots/review.png" width="200" alt="Review tab">
  <img src="docs/screenshots/apps.png" width="200" alt="Apps">
  <img src="docs/screenshots/browse-dark.png" width="200" alt="Browse in dark theme">
</p>

## Features

**Four tabs**: tap them, or swipe along the bottom bar (left for the tab on the right, right for the tab on the left)

| Review | Browse | Storage | Trash |
|---|---|---|---|
| What takes space, as plain facts: large files, duplicates, old downloads, screenshots, installer files, empty folders. You choose what goes; nothing is deleted automatically. | Search, recent files, internal storage at the top with a usage bar, categories with their sizes (Downloads, Images, Videos, Audio, Documents, Apps) and collections (Favorites, Hidden folder). | Opens internal storage directly with a tappable path bar. | Deleted files, kept 30 days. Restore or delete one or many. |

The ☰ menu holds Favorites, Hidden folder, Settings and About, and has room for future features.

**Apps**
- Installed apps with icon, size and version; sort by name, size or last update
- Open, App info, Uninstall (Android asks to confirm), and Save APK to Download as a backup
- A second tab lists the `.apk` installer files in your storage
- Settings: show system apps, and optionally full sizes (app + data + cache). Full sizes need Android's Usage access, which you switch on yourself in the Android page Filo opens. Filo never asks with a pop-up.

**Files**
- List or grid view, sort by name, date, size or type
- Long-press to select many; copy, move (with a Paste bar), rename, share, open with, file info
- New folder, zip and unzip (protected against malicious archives)
- Favorites, search across all storage, pinch-to-zoom image viewer
- Undo right after moving something to Trash
- Hidden files (names starting with a dot, like `.thumbnails`) stay hidden unless you switch them on in Settings
- Long-press the app icon for shortcuts to Internal storage and Downloads
- SD cards are shown when present

**Hidden folder**
- 6-digit PIN, plus optional fingerprint unlock
- Files are encrypted with AES-256-GCM and stored in Filo's private app storage, where no other app, gallery or file manager can see them. Even file names are encrypted.
- The folder key is sealed with your PIN *and* a hardware key from the phone's security chip (StrongBox on Pixel), so a copied vault can't be brute-forced elsewhere.
- 5 wrong PINs, then waits of 30 s, 1 min, 2 min… up to 1 hour
- Locks as soon as you go back out of it, and after 5 seconds in another app or with the screen off (adjustable in Settings: immediately, 5, 15, 30 seconds or 1 minute)
- Screenshots and the recent-apps preview are blocked while it's open
- ⚠️ **If you forget the PIN or uninstall Filo, the hidden files are gone for good.** Nobody can recover them. Move files out before uninstalling.
- Pictures are shown decrypted in memory only. Other file types (videos, PDFs) are decrypted to Filo's private cache to open in another app, and those copies are deleted the next time the hidden folder is unlocked or locked.

## Install

1. Download `Filo-1.2.0.apk` from the [Releases](../../releases) page on your phone.
2. Open it. If asked, allow your browser or file app to install unknown apps.
3. Open Filo and tap **Allow access**, then turn on **Allow access to manage all files**. (A full file manager needs this; Filo has no internet access.)

To tap `.apk` files inside Filo and install them, Android will ask once to let Filo install apps.

## Build from source

Requirements: JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest   # 59 tests: file operations, trash, zip, Review, search, encryption, PIN lockout, auto-lock, UI
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Signed release build (the signing key is never part of the repository):

```bash
export FILO_KEYSTORE=/path/to/filo-release.jks
export FILO_KEYSTORE_PASSWORD=...
export FILO_KEY_ALIAS=filo
./gradlew assembleRelease
```

The UI tests save a screenshot of every screen to `app/build/screenshots/`.

## License

Filo is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3. See [LICENSE](LICENSE).

The bundled font, [Plus Jakarta Sans](https://github.com/tokotype/PlusJakartaSans) by Tokotype, is under the SIL Open Font License 1.1 ([licenses/PlusJakartaSans-OFL.txt](licenses/PlusJakartaSans-OFL.txt)).

## Credits

Made by [RealKHFan](https://github.com/RealKHFan) with help from [Claude](https://claude.ai) by Anthropic.
