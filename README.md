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
| **Sites in this build** | YouTube, Facebook, Instagram, TikTok, Twitter/X, Reddit and Vimeo (see [Limitations](#limitations) for the last two), plus yt-dlp's generic extractor (pages with plain `<video>` tags) |
| **Size** | About 11 MB added to a single-ABI APK; about 28 MB on the device once extracted |
| **Requires** | A development build (`npx expo run:android` or EAS Build). It does not work in Expo Go. |

- [Install](#install)
- [Quick start](#quick-start)
- [API](#api)
- [Limitations](#limitations)
- [Recipes](docs/recipes.md): sizes, audio, subtitles, playlists, batches, cancelling, gallery, cookies
- [Contributing](CONTRIBUTING.md): how it works, building, updating yt-dlp, releasing

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
| `getStream(urlOrInfo, options?)` | `Promise<StreamSource>` | A direct link to play the video (or its audio) without saving it. |

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

### Streaming without saving

`getStream` returns a direct media link to hand to a player (`expo-video`, a WebView, or
`Linking.openURL`):

```ts
const stream = await YtDlp.getStream(url);                       // one URL, picture + sound, up to 720p
const audio = await YtDlp.getStream(url, { audioOnly: true });   // e.g. for a music player
player.replace({ uri: stream.url, headers: stream.headers });
```

| Option | Default | |
| --- | --- | --- |
| `maxHeight` | `720` | Tallest video to pick. |
| `audioOnly` | `false` | Pick an audio stream instead. |
| `allowSeparate` | `false` | Also allow a video-only link plus a separate audio link (`kind: 'separate'`, `audioUrl` set) when that gives a better picture. Only for players that can play two sources together, such as ExoPlayer's `MergingMediaSource`. |

`stream.kind` is `'single'` (a plain file), `'hls'` (an .m3u8 playlist) or `'separate'`.
The links expire after a few hours and are tied to the device's connection, so fetch one
right before playing and don't store it. YouTube's single-file streams stop at 360p; above
that it offers HLS or separate streams. `getStream` also accepts an info dict you already
have, and rejects with `ERR_YTDLP_NO_STREAM` when there is nothing playable.

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

### Keeping yt-dlp up to date

Sites change, and an old yt-dlp eventually stops working. The app can fetch a newer one by
itself, with no app release:

```ts
YtDlp.setUpdates('auto'); // once, at startup; the choice is stored on the device
```

| Mode | |
| --- | --- |
| `'bundled'` (default) | Only the yt-dlp shipped inside the app. No update traffic. |
| `'auto'` | Checks in the background (daily, and after a yt-dlp failure) and switches to a newer version. Never delays or fails a download. |
| `'manual'` | Only when you call `updateYtDlp()`. |

| Function | Returns | |
| --- | --- | --- |
| `setUpdates(mode \| options)` | `UpdateState \| null` | Sets the mode. Options: `{ mode, checkIntervalHours?, feed?: { url, publicKey } }`. |
| `getUpdateState()` | `UpdateState \| null` | Mode, yt-dlp in use, `updateAvailable`, `lastCheck`, `lastError`. |
| `checkForUpdate()` | `Promise<UpdateCheck>` | `status`: `'up-to-date'`, `'available'` or `'app-update-required'`. Downloads nothing. |
| `updateYtDlp({ onProgress? })` | `Promise<UpdateResult>` | Downloads, verifies and switches. `{ updated, ytDlp, previous }`. |
| `resetToBundled()` | `Promise<UpdateState>` | Back to the yt-dlp shipped in the app. |
| `addUpdateListener(fn)` | `{ remove() }` | Events: `checking`, `downloading`, `updated`, `up-to-date`, `app-update-required`, `failed` (with `error`). |

```ts
const sub = YtDlp.addUpdateListener((event) => {
  if (event.type === 'updated') console.log(`yt-dlp is now ${event.ytDlp}`);
  if (event.type === 'failed') console.warn(event.error.message); // a sentence you can show
});
```

- Only yt-dlp updates this way. Python, QuickJS and ffmpeg are part of the APK; when a new
  yt-dlp needs a newer version of this package, the status is `'app-update-required'`.
- Every update is signed and checked against keys built into the package, and has to start
  on the device before it replaces the old one. If anything fails, the yt-dlp in use stays.
- A failed call (`ERR_YTDLP`) has `updateAvailable: true` when `updateYtDlp()` might fix it.
- Update errors have codes starting `ERR_YTDLP_UPDATE_` (`NETWORK`, `SIGNATURE`, `CORRUPT`,
  `BROKEN`, `BUSY`, `DISABLED`, `CONFIG`).
- Google Play restricts downloading executable code; interpreted Python is a grey area
  there, which is one reason updates are off by default.

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

## Limitations

- **Android only for now.** iOS needs an in-process interpreter, because iOS apps can't
  spawn processes.
- **64-bit devices only.** python.org doesn't build 32-bit Android.
- **Downloads run while your app's process is alive.** Use a foreground service in your app
  for long background downloads.
- **Without a bundled ffmpeg there's no transcoding.** That means no MP3 conversion and no SRT
  from VTT, and merging needs an H.264 + AAC pair (the best-quality VP9/AV1 streams are
  skipped).
- **Reddit and Vimeo are restricted.** Reddit only answers a logged-in session, so pass a
  `cookiesFile`. Vimeo refuses clients that don't look like a browser at the TLS level, which
  needs a library this build doesn't include, so most Vimeo links fail even with cookies.
- **Bot checks and rate limits.** YouTube may ask to "confirm you're not a bot", or rate-limit
  subtitle downloads. Those errors aren't retried; cookies from a logged-in session help.
- **Sites change and yt-dlp follows quickly.** Turn on [updates](#keeping-yt-dlp-up-to-date),
  or ship app releases with a current version of this package.
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
