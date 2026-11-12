# Changelog

What changed in each version of `expo-yt-dlp`. Every release also bundles the newest stable
yt-dlp available that day; the exact versions are listed in its
[GitHub release](https://github.com/nigelbasa/expo-yt-dlp/releases).

## 0.4.0

- **A Kotlin library for native Android apps:** `io.github.nigelbasa:ytdlp-android` on Maven
  Central, same version as this package. It has the same runtime, engine and signed updates,
  behind suspend functions; see [kotlin/README.md](kotlin/README.md).
- The Android code is now one core shared by both packages (`io.github.nigelbasa.ytdlp`),
  with the Expo module as a thin layer over it. The JavaScript API and its error codes are
  unchanged.
- CI runs the example app and the Kotlin library on an Android emulator.

No breaking changes. Rebuild the native app after upgrading.

## 0.3.0

- **Faster calls.** One Python process with yt-dlp loaded, the engine, now stays up between
  calls instead of starting for each one. Nothing to change in your code: the first call
  starts it. `warmUp()` starts it ahead of time, `shutDown()` stops it, and
  `setEngine(false)` goes back to one process per call.
- A call made while the engine is busy runs in its own process, as before, so parallel
  downloads still work.

No breaking changes. Rebuild the native app after upgrading. A yt-dlp downloaded as an update
before this release keeps working, in one process per call, until the next yt-dlp update.

## 0.2.0

- **yt-dlp updates without an app release.** `setUpdates('auto')` lets an installed app fetch
  a newer, signed yt-dlp by itself when a site stops working. Also `checkForUpdate()`,
  `updateYtDlp()`, `resetToBundled()`, `getUpdateState()` and `addUpdateListener()`.
  Off by default.
- **Streaming.** `getStream(url)` returns a direct link to play a video or its audio without
  saving it.
- **More sites.** TikTok and Twitter/X join YouTube, Facebook and Instagram.
- `prepare()` now also reports `source`, `bundleId` and `runtimeApi`.
- Download progress always ends at 100%; the last event used to arrive without a percentage.
- A failed call (`ERR_YTDLP`) carries `updateAvailable` when a newer yt-dlp might fix it.

No breaking changes. Rebuild the native app after upgrading.

## 0.1.1

- License texts for OpenSSL, libffi, xz and bzip2 ship in the APK.
- Published with npm provenance.

## 0.1.0

- First release: yt-dlp for Expo on Android with a bundled Python runtime.
