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
4. Tap **Start Extraction** — decrypted/copied files are written to the
   output folder, preserving the original subfolder structure.

Extraction runs multiple files concurrently (bounded by a semaphore) using
buffered streaming I/O, so it stays fast without loading large video files
fully into memory.

## Project structure
```
app/src/main/java/com/personal/rpgmextractor/
  core/RpgMakerDecryptor.kt   – XOR decrypt + extension mapping
  core/GameScanner.kt         – walks the folder, finds assets + key
  core/ExtractionEngine.kt    – concurrent decrypt/copy pipeline
  viewmodel/ExtractorViewModel.kt
  ui/ExtractorScreen.kt       – Jetpack Compose Material 3 UI
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
