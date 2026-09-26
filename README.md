# expo-yt-dlp

[yt-dlp](https://github.com/yt-dlp/yt-dlp) inside your Expo / React Native app. Get video
info, check sizes, download video, audio, subtitles and thumbnails, whole playlists or batches
of links, all on the device, with no server.

The package bundles its own stripped-down Python 3.14 runtime and a copy of yt-dlp cut down to
the sites you need, and wraps them in a typed JS API. Anything the wrapper doesn't cover is one
`exec([...])` away, because it's the real yt-dlp underneath.

| | |
| --- | --- |
| **Platforms** | Android (arm64-v8a, x86_64). iOS is planned. |
| **Sites in this build** | YouTube, Facebook, Instagram, plus yt-dlp's generic extractor (pages with plain `<video>` tags) |
| **Size** | About 11 MB added to a single-ABI APK; about 28 MB on the device once extracted |
| **Requires** | A development build (`npx expo run:android` or EAS Build). It does not work in Expo Go. |

- [Install](#install)
- [Quick start](#quick-start)
- [API](#api)
- [Recipes](#recipes)
- [How it works](#how-it-works)
- [Maintaining the package](#maintaining-the-package)
- [Limitations](#limitations)

## Install

```sh
npx expo install expo-yt-dlp
```

Add the config plugin to `app.json`. It is **required**: the bundled binaries have to be
extracted to disk to run, and the plugin turns that on.

```json
{
  "expo": {
    "plugins": [["expo-yt-dlp", { "abiFilters": ["arm64-v8a", "x86_64"] }]]
  }
}
```

`abiFilters` is optional. The runtime only exists for 64-bit ABIs, so limiting the build to
them also drops 32-bit copies of your other native libraries. Then rebuild the native app with
`npx expo prebuild` and `npx expo run:android`, or with EAS Build.

## Quick start

```ts
import * as YtDlp from 'expo-yt-dlp';

async function quickStart() {
  if (!YtDlp.isSupported()) return; // iOS/web, 32-bit phones, or the plugin is missing

  const url = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';

  const info = await YtDlp.getInfo(url);
  console.log(info.title, info.duration);

  const task = YtDlp.download(url, {
    maxHeight: 720,
    onProgress: (p) => console.log(p.status, p.percent?.toFixed(0), '%'),
  });
  const { files } = await task.promise; // absolute paths, e.g. [".../files/downloads/Me at the zoo [jNQXAC9IVRw].mp4"]
  console.log(files);
}
```

The first call takes a second or two longer while the runtime is unpacked, which happens once
per app version. To do that at startup instead, call `YtDlp.prepare()`.

## API

Every function takes a URL from any supported site. All options are optional.

### Setup

| Function | Returns | |
| --- | --- | --- |
| `isSupported()` | `boolean` | Whether the runtime can run on this device and build. Synchronous. |
| `prepare()` | `Promise<RuntimeInfo>` | Unpacks the runtime ahead of time. Returns the bundled versions of Python, yt-dlp and QuickJS, whether ffmpeg is bundled, the ABI, and the extractor list. |

### Looking things up (nothing is downloaded)

| Function | Returns | |
| --- | --- | --- |
| `getInfo(url, options?)` | `Promise<VideoInfo>` | yt-dlp's full info dict: title, duration, uploader, formats, subtitles, thumbnails… |
| `getFormats(urlOrInfo, options?)` | `Promise<FormatSummary[]>` | Every format, best first: resolution, codecs, bitrate, size. |
| `estimateDownload(url, options?)` | `Promise<DownloadEstimate>` | Exactly what `download` would fetch with the same options, including files and sizes, without downloading. |
| `getSubtitles(urlOrInfo, options?)` | `Promise<SubtitleTrack[]>` | Uploaded subtitles, then auto-generated captions. |
| `getPlaylist(url, { limit? })` | `Promise<Playlist>` | Entries of a playlist or channel, without resolving each video. |
| `search(query, { limit? })` | `Promise<PlaylistEntry[]>` | YouTube search results (default 10). |

`getFormats` and `getSubtitles` also accept an info dict you already fetched, which saves a
network round trip:

```ts
import * as YtDlp from 'expo-yt-dlp';

async function inspect(url: string) {
  const info = await YtDlp.getInfo(url);
  const formats = await YtDlp.getFormats(info);
  const subs = await YtDlp.getSubtitles(info);
  console.log(formats[0].resolution, formats[0].size, subs.map((s) => s.language));
}
```

Options for lookups (`InfoOptions`):

| Option | Default | |
| --- | --- | --- |
| `playlist` | `false` | For `watch?v=…&list=…` URLs, resolve the playlist instead of just the video. |
| `flatPlaylist` | `false` | List playlist entries without resolving each one (fast). |
| `cookiesFile` | | Path to a Netscape-format cookies file, for content that needs a login. |
| `networkRetries` | `3` | Retries after dropped connections, timeouts and 5xx responses. |
| `extraArgs` | | Any extra yt-dlp CLI arguments. |
| `signal` | | An `AbortSignal` that cancels the lookup. |

### Downloading

| Function | Returns | |
| --- | --- | --- |
| `download(url, options?)` | `DownloadTask` | Downloads media (plus subtitles/thumbnails if asked). |
| `downloadSubtitles(url, options?)` | `DownloadTask` | Only subtitle files. |
| `downloadThumbnail(url, options?)` | `DownloadTask` | Only the thumbnail image. |
| `downloadBatch(urls, options?)` | `BatchTask` | Several URLs, `concurrency` at a time; per-item results. |

A `DownloadTask` has an `id`, a `promise` that resolves to a `DownloadResult`, and `cancel()`.
A `DownloadResult` has `files` (all media paths) and `items` (one per video: id, title, files
with sizes and codecs, subtitle files, thumbnail files).

By default `download` gets the best H.264 video and AAC audio and merges them into one MP4.
These are the codecs every phone plays. Options (`DownloadOptions`):

| Option | Default | |
| --- | --- | --- |
| `outputDir` | `<filesDir>/downloads` | Where files go (`file://` URIs are accepted). |
| `maxHeight` | | Caps the resolution (short side, so `720` also works for portrait videos). |
| `audioOnly` | `false` | Best audio only, AAC `.m4a` where available. |
| `format` | | A yt-dlp format selector, e.g. `'18'` or `'bv*[height<=480]'`. Overrides the defaults above. |
| `playlist` | `false` | Download the whole playlist when the URL is (or includes) one. |
| `playlistItems` | | Which items: `'1:5'`, `'1,3,7'`, `'-3:'` (last three). Implies `playlist`. |
| `subtitles` | `false` | `true` for English (falling back to auto-captions), or `{ languages, auto, format }`. |
| `thumbnail` | `false` | Also save the thumbnail image. |
| `media` | `true` | `false` writes only subtitles/thumbnails. |
| `rateLimit` | | Max speed, e.g. `'500K'`, `'2M'`. |
| `cookiesFile`, `networkRetries`, `extraArgs` | | As for lookups. |
| `onProgress` | | Called with `DownloadProgress` events. |

**Progress events** (`DownloadProgress`) have a `status`:
- `downloading`: includes `percent`, `downloadedBytes`, `totalBytes`, `speed` (bytes/s) and `eta` (s). Video and audio are separate streams, so each reports its own progress, identified by `formatId`.
- `finished`: one stream is done.
- `merging`: video and audio are being combined.
- `retrying`: includes `attempt`, `retries` and `retryDelay`.

**Errors.** Rejections carry a `code`:

| Code | Meaning |
| --- | --- |
| `ERR_YTDLP` | yt-dlp failed. The message is yt-dlp's `ERROR:` line (unavailable video, login required, bot check…). |
| `ERR_YTDLP_CANCELLED` | You called `cancel()`, or an `AbortSignal` fired. Partial files are deleted. |
| `ERR_YTDLP_UNSUPPORTED` | This device or build can't run the runtime. The message says why (for example, a missing config plugin). |

### Running any yt-dlp command

`exec(args)` runs yt-dlp with raw CLI arguments and returns `{ exitCode, stdout, stderr }`.
The runtime adds its own flags first (JS runtime, cache dir, `--ignore-config`), so write
commands exactly as you would in a terminal.

```ts
import * as YtDlp from 'expo-yt-dlp';

async function rawCommands(url: string) {
  // yt-dlp's own format table
  console.log((await YtDlp.exec(['-F', url])).stdout);

  // Custom fields, one line per video
  const r = await YtDlp.exec(['--print', '%(title)s | %(duration_string)s | %(view_count)s', url]);
  console.log(r.stdout);

  // Channel's latest uploads as JSON lines
  await YtDlp.exec(['--flat-playlist', '-I', '1:20', '-j', 'https://www.youtube.com/@blender/videos']);
}
```

See yt-dlp's [options](https://github.com/yt-dlp/yt-dlp#usage-and-options) and
[output template](https://github.com/yt-dlp/yt-dlp#output-template) docs. `extraArgs` on the
other functions accepts the same flags.

## Recipes

These examples use `import * as YtDlp from 'expo-yt-dlp'`.

### Check the size before downloading

```ts
import * as YtDlp from 'expo-yt-dlp';

async function downloadIfSmall(url: string, limitBytes = 50_000_000) {
  const plan = await YtDlp.estimateDownload(url, { maxHeight: 720 });
  // totalSize is null when the site doesn't report sizes (e.g. some Facebook videos)
  if (plan.totalSize != null && plan.totalSize > limitBytes) {
    throw new Error(`Too big: ${(plan.totalSize / 1e6).toFixed(0)} MB`);
  }
  return YtDlp.download(url, { maxHeight: 720 }).promise;
}
```

To let users pick a quality, list the formats and pass the chosen id as `format`:

```ts
import * as YtDlp from 'expo-yt-dlp';

async function pickQuality(url: string) {
  const formats = await YtDlp.getFormats(url);
  const choices = formats.filter((f) => f.hasVideo && f.vcodec?.startsWith('avc1'));
  // e.g. [{ formatId: '137', resolution: '1920x1080', size: 48_000_000, ... }, ...]
  const chosen = choices[0];
  // Video-only formats have no sound: combine with the best audio using yt-dlp syntax.
  return YtDlp.download(url, { format: `${chosen.formatId}+bestaudio[ext=m4a]` }).promise;
}
```

Without a bundled ffmpeg, a `+` combination is merged on the device when Android's
`MediaMuxer` supports the pair: H.264 + AAC into `.mp4`, or VP8/VP9 + Opus/Vorbis into
`.webm`. Other pairs, such as VP9 + AAC, come back as two files: the video and the audio.

### Audio only

```ts
import * as YtDlp from 'expo-yt-dlp';

async function audio(url: string) {
  const { files } = await YtDlp.download(url, { audioOnly: true }).promise; // .m4a (AAC)
  return files[0];
}
```

### Subtitles

```ts
import * as YtDlp from 'expo-yt-dlp';

async function subtitles(url: string) {
  // What's available? Uploaded tracks first, then auto-generated ones.
  const tracks = await YtDlp.getSubtitles(url);
  console.log(tracks.filter((t) => !t.automatic).map((t) => t.language));

  // Only the subtitle files:
  const subs = await YtDlp.downloadSubtitles(url, { languages: ['en', 'es'], format: 'vtt' }).promise;
  console.log(subs.items[0].subtitles); // [{ language: 'en', ext: 'vtt', path: '...' }]

  // Or together with the video:
  await YtDlp.download(url, { subtitles: { languages: ['en'] }, thumbnail: true }).promise;
}
```

### Playlists, channels and search

```ts
import * as YtDlp from 'expo-yt-dlp';

async function playlists() {
  const list = await YtDlp.getPlaylist('https://www.youtube.com/playlist?list=PL…', { limit: 50 });
  console.log(list.title, list.entries.length);

  // Download the first five items as one job…
  await YtDlp.download('https://www.youtube.com/playlist?list=PL…', { playlistItems: '1:5' }).promise;

  // …or search and pick
  const results = await YtDlp.search('blender open movie', { limit: 5 });
  await YtDlp.download(results[0].url).promise;
}
```

YouTube **Mixes** (the auto-generated "radio" playlists) work through the watch URL that
YouTube gives you, `watch?v=ID&list=RD…`. They can run to dozens of songs, so always pass a
`limit` or `playlistItems`. Not every video has a Mix, and in that case you get the single
video back. A bare `playlist?list=RD…` URL doesn't work, because YouTube refuses to show
Mixes that way.

```ts
import * as YtDlp from 'expo-yt-dlp';

async function mix(videoId: string) {
  const url = `https://www.youtube.com/watch?v=${videoId}&list=RD${videoId}`;
  const radio = await YtDlp.getPlaylist(url, { limit: 25 });
  console.log(radio.title, radio.entries.map((e) => e.title));

  // Plain download(url) fetches only the video; opt into the playlist to take songs from it
  await YtDlp.download(url, { audioOnly: true, playlistItems: '1:10' }).promise;
}
```

### Batches

```ts
import * as YtDlp from 'expo-yt-dlp';

async function batch(urls: string[]) {
  const job = YtDlp.downloadBatch(urls, {
    concurrency: 2, // each download is its own process; 2-3 is plenty on a phone
    maxHeight: 720,
    onProgress: (p, index) => console.log(`#${index}`, p.status, p.percent),
    onItemDone: (r, index) => console.log(`#${index}`, r.ok ? 'done' : r.error.message),
  });
  // job.cancel() stops everything
  const results = await job.promise; // never rejects; one entry per URL, in order
  return results.filter((r) => r.ok);
}
```

Items can override the shared options:
`downloadBatch([url1, { url: url2, options: { audioOnly: true } }])`.

### Cancelling

```ts
import * as YtDlp from 'expo-yt-dlp';

async function cancelling(url: string) {
  const task = YtDlp.download(url);
  setTimeout(() => task.cancel(), 5000);
  try {
    await task.promise;
  } catch (e: any) {
    if (e.code === 'ERR_YTDLP_CANCELLED') console.log('cancelled, partial files removed');
  }

  const controller = new AbortController();
  const info = YtDlp.getInfo(url, { signal: controller.signal });
  controller.abort();
  await info.catch(() => {});
}
```

### Save to the gallery

Files land in app-private storage. To publish them, move them with
[`expo-media-library`](https://docs.expo.dev/versions/latest/sdk/media-library/) or
`expo-file-system`:

```ts
import * as MediaLibrary from 'expo-media-library';
import * as YtDlp from 'expo-yt-dlp';

async function saveToGallery(url: string) {
  const { files } = await YtDlp.download(url, { maxHeight: 1080 }).promise;
  await MediaLibrary.requestPermissionsAsync();
  await MediaLibrary.saveToLibraryAsync(`file://${files[0]}`);
}
```

### Logged-in content (Instagram, Facebook)

Many Instagram and some Facebook posts require a session. Export cookies in Netscape format
(for example with a "cookies.txt" browser extension), ship or download the file to the device,
and pass `cookiesFile`:

```ts
import * as YtDlp from 'expo-yt-dlp';

async function withCookies(url: string, cookiesPath: string) {
  return YtDlp.download(url, { cookiesFile: cookiesPath }).promise;
}
```

## How it works

```
JS (src/index.ts) ─ options → yt-dlp arguments
 └─ Expo module (Kotlin) ── spawns ──> libytdlp_python.so -m expo_yt_dlp_runner …   (child process)
                                        ├─ libpython3.14.so + stripped stdlib   (python.org Android build)
                                        ├─ yt-dlp via its Python API            (python/expo_yt_dlp_runner.py)
                                        ├─ spawns libqjs.so                     (QuickJS-ng: YouTube JS challenges)
                                        └─ spawns libffmpeg.so                  (optional)
```

- **Python.** python.org's official Android build of CPython ships only as an embeddable
  `libpython`. [`native/python-launcher`](native/python-launcher/main.c) is a 10-line `main()`
  linked against it, which gives yt-dlp a normal `python` to run under.
- **Processes.** Every call is a separate child process, so cancelling means killing that
  process.
- **Why the binaries are named `lib*.so`.** Android only allows executing files from
  `nativeLibraryDir`, and only extracts `lib*.so` files there. The launcher, QuickJS, ffmpeg
  and the stdlib C extensions (`libpymod_*.so`, symlinked into `lib-dynload`) are all
  packaged that way.
- **Pure-Python code.** The stdlib and yt-dlp are precompiled to `.pyc`, zipped into the APK's
  assets, and unpacked into app storage on first use.
- **The driver.** [`expo_yt_dlp_runner.py`](python/expo_yt_dlp_runner.py) wraps yt-dlp's API.
  It handles format selection for merging, JSON progress events, per-video results with sizes
  and side files, and retries for transient network errors.
- **Merging.** The best qualities are separate video and audio streams. Without ffmpeg, the
  driver picks an H.264 + AAC pair and Kotlin merges it with Android's `MediaMuxer` (a stream
  copy, not a re-encode). If a video has no such pair, which is common on Facebook, the best
  single file is used instead.

## Maintaining the package

Everything the runtime is built from, including which sites are kept, is pinned in
[`runtime-versions.json`](runtime-versions.json). Built binaries are not committed; they're
produced by `npm run build:runtime` and published inside the npm tarball.

**Requirements:** Python 3.14 (the bundle is precompiled for it), Node 20+, and the Android SDK
with NDK r27+ and CMake. Set `ANDROID_HOME` / `ANDROID_NDK_HOME` if they aren't in the default
locations.

### Updating yt-dlp (and the rest)

```sh
npm run deps:check                          # pinned vs. latest upstream
npm run deps:update                         # newest stable yt-dlp + the yt-dlp-ejs it pins + certifi
npm run deps:update -- --channel nightly    # yt-dlp nightly build
npm run deps:update -- --ytdlp 2026.8.19    # a specific version (also how to roll back)
npm run deps:update -- --all                # also Python 3.14.x, QuickJS-ng, ffmpeg
```

`deps:update` rewrites the pins with verified SHA-256 hashes and rebuilds the runtime,
re-trimming yt-dlp to the configured extractors. It then runs the offline tests. After it
succeeds:
1. Run `npm run test:online` to try the three sites for real.
2. Run `npm run build:runtime` for a full native build.
3. Try the example app on a device.
4. Bump the version and tag it (see Releasing).

The **Update bundled yt-dlp** workflow does the first part weekly and opens a PR when there's
a new version.

### Changing which sites are included

Edit `"extractors"` in `runtime-versions.json`. The names are yt-dlp's extractor module names
(`yt_dlp/extractor/<name>.py`), e.g. `"tiktok"`, `"vimeo"`, `"twitter"`, `"reddit"`. The generic
extractor is always kept. Then run `npm run build:runtime && npm run test:runtime`; the build
fails if a name doesn't exist. Each site adds a little to the bundle, and removing the list
entirely isn't supported (all ~1,800 extractors would add several MB).

### Building and testing

| Command | What it does |
| --- | --- |
| `npm run build:runtime` | Builds `android/src/main/jniLibs` and `assets/expo-yt-dlp`. `--python-only` skips the NDK builds. |
| `npm test` | Unit tests for the TypeScript layer (jest). |
| `npm run test:runtime` | Offline checks of the built bundle: the trimmed yt-dlp imports and runs on the stripped stdlib, and network retries behave (local test server). |
| `npm run test:online` | Resolves, plans and downloads from the real sites using the bundled runtime. Can fail for reasons outside this repo (bot checks, rate limits). |
| `python scripts/device_smoke_test.py -- -J <url>` | Pushes the runtime to a connected device or emulator over `adb` and runs yt-dlp there, without building an app. |
| `cd example && npx expo run:android` | The example app, which exercises every function. |

The optional ffmpeg (`scripts/build_ffmpeg_android.sh`, Linux/macOS) builds a mux-only
ffmpeg that the next `build:runtime` bundles. Without it, merging uses `MediaMuxer`.

### CI

| Workflow | When | What |
| --- | --- | --- |
| **CI** (`ci.yml`) | Every push to `main`, every PR | Lint, types, unit tests; full runtime build on Linux with offline tests; checks the npm tarball's contents; on PRs, also a Gradle build of the example app. |
| **Update bundled yt-dlp** (`update-deps.yml`) | Mondays, or manually (stable/nightly) | `deps:update` plus tests, then a PR with the new pins. Needs *Settings → Actions → General → Allow GitHub Actions to create and approve pull requests*. |
| **Release** (`release.yml`) | Pushing a `v*` tag | Builds the runtime, runs the tests, publishes to npm with trusted publishing (no token stored), creates a GitHub release listing the bundled versions. |

### Releasing

```sh
npm version patch          # or minor/major
git push --follow-tags     # the Release workflow builds and publishes
```

Publishing uses [npm trusted publishing](https://docs.npmjs.com/trusted-publishers): npm
trusts `release.yml` in this repository, so no npm token is stored in GitHub. npm only lets
you set that up for a package that already exists, so the very first version is published
by hand, once:

1. Turn on two-factor authentication for your npm account.
2. Build and publish from a clean checkout:
   ```sh
   npm login
   npm ci
   npm run build:runtime      # both ABIs
   npm run build
   npm pack --dry-run         # check the file list and size
   npm publish --access public
   ```
3. On npmjs.com, open the package's **Settings → Trusted publishing**, choose GitHub Actions
   and enter owner `nigelbasa`, repository `expo-yt-dlp`, workflow `release.yml`, and leave
   the environment empty.
4. Optionally, under **Publishing access**, require 2FA and disallow tokens, so only the
   workflow can publish.

After that, every tag is published by the workflow. Provenance statements need a public
source repository; add `--provenance` to the publish step in `release.yml` once the repo is
public.

## Limitations

- **Android only for now.** iOS needs an in-process interpreter, because iOS apps can't
  spawn processes.
- **64-bit devices only.** python.org doesn't build 32-bit Android.
- **Downloads run while your app's process is alive.** Use a foreground service in your app
  for long background downloads.
- **Without a bundled ffmpeg there's no transcoding.** That means no MP3 conversion and no SRT
  from VTT, and merging needs an H.264 + AAC pair (the best-quality VP9/AV1 streams are
  skipped).
- **Bot checks and rate limits.** YouTube may ask to "confirm you're not a bot", or rate-limit
  subtitle downloads. Those errors aren't retried; cookies from a logged-in session help.
- **Sites change and yt-dlp follows quickly.** Keep the bundled version current with
  `deps:update`.
- **App stores.** Google Play and the App Store reject apps that download from YouTube and
  similar sites without the rights holder's authorization.

## Licenses

MIT for this package. Bundled components:
- yt-dlp: Unlicense
- CPython: PSF-2.0
- OpenSSL: Apache-2.0
- libffi: MIT; xz (liblzma): public domain; bzip2: BSD-style (all linked into CPython)
- QuickJS-ng: MIT
- certifi: MPL-2.0
- yt-dlp-ejs: Unlicense, MIT and ISC
- ffmpeg, if bundled: LGPL-2.1+

The license texts ship in the APK (`assets/expo-yt-dlp/licenses`, and inside
`site-packages.zip`). List them in your app's third-party notices.
