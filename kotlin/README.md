# ytdlp-android

yt-dlp for native Android apps written in Kotlin. It's the same runtime as the
[expo-yt-dlp](../README.md) npm package, with the same engine and signed yt-dlp updates,
behind a Kotlin API with suspend functions.

| | |
| --- | --- |
| **Package** | `io.github.nigelbasa:ytdlp-android` on Maven Central |
| **Devices** | Android 7.0+ (API 24), arm64-v8a and x86_64 |
| **Sites in this build** | YouTube, Facebook, Instagram, TikTok and Twitter/X, plus yt-dlp's generic extractor |
| **Size** | About 11 MB added to a single-ABI APK; about 28 MB on the device once extracted |

## Install

```kotlin
// app/build.gradle.kts
android {
  // The library runs its Python and QuickJS binaries from the app's native library folder,
  // which Android only fills with legacy packaging.
  packaging {
    jniLibs { useLegacyPackaging = true }
  }
  // Optional: only the ABIs the runtime exists for.
  defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }
}

dependencies {
  implementation("io.github.nigelbasa:ytdlp-android:0.4.0")
}
```

## Use

```kotlin
val ytDlp = YtDlp(context)

lifecycleScope.launch {
  // Video info: yt-dlp's full JSON.
  val info = ytDlp.getInfo("https://www.youtube.com/watch?v=jNQXAC9IVRw")
  println(info.getString("title"))

  // Download: best H.264 video + AAC audio, merged into one MP4, capped at 720p.
  val result = ytDlp.download(url, DownloadOptions(maxHeight = 720)) { progress ->
    println("${progress.status} ${progress.percent}%") // called on a background thread
  }
  println(result.files) // List<File>, in the app's files/downloads by default

  // Audio only.
  ytDlp.download(url, DownloadOptions(audioOnly = true))

  // Anything else yt-dlp can do.
  val search = ytDlp.exec("--flat-playlist", "-J", "ytsearch5:blender open movie")
}
```

Cancelling the coroutine stops yt-dlp and deletes partial files:

```kotlin
val job = lifecycleScope.launch { ytDlp.download(url) }
job.cancel()
```

Failures throw `YtDlpException`. Its `code` is stable (`ERR_YTDLP` when yt-dlp failed on the
URL, `ERR_YTDLP_UNSUPPORTED` on a device without binaries, `ERR_YTDLP_UPDATE_*` for updates),
and its message is a full sentence you can show as it is.

### The engine

The first call starts one Python process that stays up between calls, which saves several
seconds per call. It stops after 5 idle minutes. Call `ytDlp.warmUp()` when a screen opens to
have it ready before the user taps anything; `warmUp()` returns `EngineState` with a `reason`
when it can't start. `engineEnabled = false` gives every call its own process.

### Keeping yt-dlp up to date

Sites change, and yt-dlp releases fixes every few weeks. The library can fetch newer yt-dlp
versions without an app update. They're signed, and only used once the signature, the hash
and a test run on the device all check out:

```kotlin
ytDlp.setUpdates(UpdateConfig(mode = "auto")) // check in the background; stored
ytDlp.addUpdateListener { event -> println("${event.type} ${event.ytDlp}") }

// Or by hand, with mode = "manual":
val check = ytDlp.checkForUpdate() // status "available", "up-to-date" or "app-update-required"
val result = ytDlp.update()        // downloads, verifies and switches
ytDlp.resetToBundled()             // back to the yt-dlp shipped in the app
```

## Using it alongside the npm package

Both packages contain the same classes, so an app uses one or the other, not both.

## Licenses

MIT for this library. The bundled runtime includes Python (PSF), yt-dlp (Unlicense), QuickJS-ng
(MIT) and certifi (MPL 2.0); see the [main README](../README.md#licenses).

## Signing

Releases are signed with the key in [signing-key.asc](signing-key.asc), fingerprint
`841A 1E10 350C A25A D872 EBAB 614A 97D4 3CBA B9E1`.
