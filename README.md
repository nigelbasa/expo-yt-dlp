# expo-yt-dlp

[yt-dlp](https://github.com/yt-dlp/yt-dlp) for Expo / React Native apps. It ships its own
stripped-down CPython runtime, so there is no server and no Python install on the device.

- **Platforms:** Android (arm64-v8a, x86_64). iOS is planned (embedded interpreter + WebKit JS solver).
- **Sites in this build:** YouTube, Facebook, Instagram, plus yt-dlp's generic extractor.
- **Size:** about 11 MB added to a single-ABI APK (Python 3.14 + OpenSSL + yt-dlp +
  QuickJS). About 28 MB on disk once installed and extracted.

## Usage

```sh
npx expo install expo-yt-dlp
```

Add the config plugin to `app.json`. It is required, because the bundled binaries must be
extracted to disk to run:

```json
{
  "expo": {
    "plugins": [["expo-yt-dlp", { "abiFilters": ["arm64-v8a", "x86_64"] }]]
  }
}
```

The module contains native code, so it needs a development build (`npx expo run:android`
or EAS Build). It does not work in Expo Go.

```ts
import * as YtDlp from 'expo-yt-dlp';

if (YtDlp.isSupported()) {
  const info = await YtDlp.getInfo('https://www.youtube.com/watch?v=jNQXAC9IVRw');
  console.log(info.title, info.formats?.length);

  const task = YtDlp.download(info.webpage_url, {
    maxHeight: 720,
    onProgress: (p) => console.log(p.status, p.formatId, p.percent),
  });
  const { files } = await task.promise; // absolute paths; task.cancel() to abort
}
```

| Function | Description |
| --- | --- |
| `isSupported()` | `false` on iOS/web, 32-bit devices, or builds without the plugin |
| `prepare()` | Extracts the runtime (once per app version, a few seconds) and returns versions. Optional. |
| `getInfo(url, options?)` | yt-dlp's info dict (`--dump-single-json`). Accepts `signal` for cancellation. |
| `download(url, options?)` | Returns `{ id, promise, cancel }`. Default output: `<filesDir>/downloads`. |
| `exec(args)` | Runs yt-dlp with raw arguments and returns exit code, stdout and stderr. |

Instagram and Facebook often require a logged-in session. Pass a Netscape-format cookies
file with `cookiesFile`.

## How it works (Android)

```
JS (src/index.ts)
 └─ Expo module (Kotlin) ── spawns ──> libytdlp_python.so -m expo_yt_dlp_runner ...   (a real child process)
                                        ├─ libpython3.14.so + stripped stdlib   (python.org Android build)
                                        ├─ yt-dlp (API), JSON events on stdout  (python/expo_yt_dlp_runner.py)
                                        ├─ spawns libqjs.so                     (QuickJS-ng: YouTube JS challenges)
                                        └─ spawns libffmpeg.so                  (optional: merging)
```

- **Python.** python.org publishes official Android builds of CPython, but only as an
  embeddable `libpython`. `native/python-launcher` is a 10-line `main()` linked against it,
  which gives yt-dlp a normal `python` executable. yt-dlp then runs unmodified in a child
  process, and cancelling a download simply kills that process.
- **Why the files are named `lib*.so`.** Android only allows executing files from
  `nativeLibraryDir`, and only extracts `lib*.so` files there (with legacy packaging, which
  the config plugin enables). The launcher, QuickJS, ffmpeg and the stdlib C extensions
  (`libpymod_<name>.so`, symlinked into `lib-dynload` at runtime) are all packaged this way.
- **Pure-Python files.** The stdlib and yt-dlp are precompiled to sourceless `.pyc` files,
  zipped into `assets/expo-yt-dlp/`, and extracted atomically into `noBackupFilesDir` on
  first use.
- **Trimmed extractors.** yt-dlp's extractor registry is rewritten to the configured sites,
  and every extractor module those sites don't reference is removed (929 of 970).
- **`getInfo` and downloads go through a small driver.** `python/expo_yt_dlp_runner.py`
  accepts yt-dlp CLI options and runs yt-dlp through its Python API. It adds retries for
  transient network errors and prints JSON progress and result events. `exec` calls the
  plain yt-dlp CLI.
- **Merging.** The best qualities on YouTube, Facebook and Instagram are separate video
  and audio streams. If ffmpeg is bundled, yt-dlp merges them itself. Without ffmpeg, the
  driver picks formats per video:
  - If the video has an H.264 video stream and an AAC audio stream, it downloads both.
    Kotlin then merges them with Android's `MediaMuxer` (stream copy, no re-encode). This
    covers YouTube.
  - Otherwise it downloads the best single file that has video. On Facebook that's the
    combined `hd`/`sd` file, because the separate video is VP9 and `MediaMuxer` can't pair
    VP9 with AAC.

## Building the runtime (maintainers)

The generated binaries are not committed. Build them before running the example or publishing:

```sh
npm run build:runtime                      # python scripts/build_android_runtime.py
npm run smoke:host                         # run the bundled yt-dlp on this machine's Python
```

Requirements:
- **Python 3.14.** The `.pyc` files must match the Android interpreter.
- **Android SDK** with NDK r27+ and CMake (set `ANDROID_HOME` and/or `ANDROID_NDK_HOME`).

This works on Windows, macOS and Linux.

**ffmpeg is optional.** `scripts/build_ffmpeg_android.sh` (Linux/macOS, needs `make`)
builds a mux-only ffmpeg and ffprobe into `.cache/ffmpeg/<abi>/`. The next
`build:runtime` run bundles them. `.github/workflows/android-runtime.yml` runs the whole
pipeline on CI.

**Changing things:**
- **Sites:** edit `EXTRACTORS` in `scripts/build_android_runtime.py`.
- **yt-dlp, Python or QuickJS versions:** bump the pinned versions and SHA-256 hashes at
  the top of the same file. The script refuses a `yt-dlp-ejs` version that doesn't match
  yt-dlp's own pin.
- **After any change:** rerun `host_smoke_test.py` against a real URL. It fails if yt-dlp
  imported a stdlib module that the strip step removed, for example:

  ```sh
  python scripts/host_smoke_test.py -- -J --js-runtimes quickjs:/path/to/qjs "https://youtu.be/jNQXAC9IVRw"
  # the download driver, as the app runs it without ffmpeg:
  python scripts/host_smoke_test.py --runner -- --native-merge --max-res 720 -- --ffmpeg-location none -P out URL
  ```

- **Retry logic:** `python scripts/test_runner_retry.py` runs the bundled driver against a
  local server that drops connections and returns 403/503 responses.
- **On a device or emulator:** `scripts/device_smoke_test.py` pushes the runtime over
  `adb` and runs yt-dlp with the app's environment, without building an app.

## Limitations

- **32-bit ARM devices are not supported.** python.org only builds 64-bit Android.
- **Downloads only run while the app process is alive.** A foreground service is not included.
- **Cancelling stops yt-dlp but not its short-lived QuickJS/ffmpeg children.** They exit
  on their own. Partial files are deleted.
- **Network retries cover transient errors only.** Page and API requests are retried after
  dropped connections, timeouts and 5xx responses (`networkRetries`, default 3, with
  backoff), and yt-dlp retries the media download itself. Bot checks, 4xx responses and
  certificate errors fail immediately.
- **yt-dlp needs updates often** when sites change, and each update means a new app build.
- **App stores:** Google Play and the App Store reject apps that download from YouTube and
  similar sites without the rights holder's authorization. Plan distribution accordingly.

## Licenses of bundled components

The license texts ship in the APK: native components under
`assets/expo-yt-dlp/licenses/`, Python packages inside `site-packages.zip`. List them
(plus OpenSSL, which ships without a license file) in your app's third-party notices.

- yt-dlp: Unlicense
- CPython: PSF-2.0
- OpenSSL: Apache-2.0
- QuickJS-ng: MIT
- certifi: MPL-2.0
- yt-dlp-ejs: Unlicense AND MIT AND ISC (bundles meriyah and astring)
- ffmpeg (if bundled): LGPL-2.1+. The build is configured without any GPL components.
