# RPGM Asset Extractor (Android)

A personal-use Android app for extracting art/CG images, audio, and movie
assets from RPG Maker MV/MZ games — including files the engine obfuscates
(`.rpgmvp/.rpgmvo/.rpgmvm`, or MZ's `.png_/.ogg_/.m4a_`).

## ⚠️ Personal use / legal note
RPG Maker MV/MZ "encrypts" assets with a simple, well-documented XOR
obfuscation (not real DRM) — the same scheme implemented by many public
open-source tools. This app only reverses that obfuscation locally on your
device. Only use it on games you own or otherwise have the right to access,
and respect the original creators' copyright/licensing when using or sharing
the extracted assets.

## How it works
1. Pick the game's root folder (Storage Access Framework) — the one
   containing `www/` (MV) or `img/`, `audio/`, `movies` directly (MZ).
2. The app scans for image/audio/video assets (encrypted and plain) and
   looks for the `encryptionKey` inside `System.json`.
3. Pick an output folder.
4. Tap **Start Extraction** — this starts a foreground **background service**
   that decrypts/copies files into the output folder, preserving the
   original subfolder structure. You can leave the app, lock the screen, or
   switch to another app; extraction keeps running.
5. Progress shows both in-app and as a system **notification with a progress
   bar** (`N / total` files, updated live). Tapping the notification reopens
   the app. On Android 13+ you'll be asked once for notification permission
   the first time you extract — if you decline, extraction still runs, it
   just won't show a notification.

Extraction runs multiple files concurrently (bounded by a semaphore) using
buffered streaming I/O, so it stays fast without loading large video files
fully into memory.

### Performance notes
`DocumentFile.findFile()` (used to check "does this output file already
exist?") does a **full directory listing** on every call — calling it once
per file turns extraction into an O(files²) operation inside any folder
with many assets (RPG Maker games routinely have hundreds of files in
`img/pictures`, `img/characters`, etc.), which is what caused very slow
(30-60+ minute) extractions on larger games. The engine now lists each
output directory's contents exactly once and caches the result, so existence
checks are O(1) hash lookups instead of repeated directory scans. Combined
with a larger I/O buffer (512 KB) and higher default concurrency (8 files
at once), a ~1.3 GB game should extract in well under a couple of minutes
rather than tens of minutes.

## App icon
A simple original vector logo (adaptive icon, so no bundled image files):
a folder with an extraction arrow bursting out of it, and three dots for
the three asset types (image/audio/video). Defined as vector drawables at
`app/src/main/res/drawable/ic_launcher_background.xml` and
`ic_launcher_foreground.xml`, wired up via
`res/mipmap-anydpi-v26/ic_launcher.xml`, with a flattened single-layer
fallback at `res/mipmap/ic_launcher.xml` for pre-Android-8 devices.

## Project structure
```
app/src/main/java/com/personal/rpgmextractor/
  core/RpgMakerDecryptor.kt    – XOR decrypt + extension mapping
  core/GameScanner.kt          – walks the folder, finds assets + key
  core/ExtractionEngine.kt     – concurrent decrypt/copy pipeline
  core/ExtractionProgress.kt   – shared progress state (service <-> UI)
  service/ExtractionService.kt – foreground service + notification progress bar
  viewmodel/ExtractorViewModel.kt
  ui/ExtractorScreen.kt        – Jetpack Compose Material 3 UI
  MainActivity.kt
```

## Building locally
Open the project root in Android Studio (Koala+ recommended) and run it, or:
```
./gradlew assembleDebug   # if you generate a wrapper, or:
gradle assembleDebug      # with Gradle 8.7+ and Android SDK/AGP set up
```
The debug APK is written to `app/build/outputs/apk/debug/`.

## Automatic APK builds via GitHub Actions
`.github/workflows/build-apk.yml` builds a debug APK automatically:
- On every push to `main`/`master`
- Manually via the **Run workflow** button (workflow_dispatch)

The workflow installs JDK 17, the Android SDK, and Gradle 8.7, then runs
`gradle assembleDebug`. Download the built APK from the run's **Artifacts**
section (`rpgm-asset-extractor-debug-apk`).

To use it: push this project to a new GitHub repo, then check the **Actions**
tab.

## Notes / limitations
- Supports the RPG Maker MV/MZ XOR asset scheme specifically — not other
  engines or custom encryption.
- The debug build is unsigned-for-release but installable directly
  (enable "install unknown apps" for your file manager/browser).
- minSdk 24 (Android 7.0+), targetSdk 34.
