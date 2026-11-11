# expo-yt-dlp recipes

These examples use `import * as YtDlp from 'expo-yt-dlp'`.

## Check the size before downloading

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

## Audio only

```ts
import * as YtDlp from 'expo-yt-dlp';

async function audio(url: string) {
  const { files } = await YtDlp.download(url, { audioOnly: true }).promise; // .m4a (AAC)
  return files[0];
}
```

## Subtitles

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

## Playlists, channels and search

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

## Batches

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

## Cancelling

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

## Save to the gallery

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

## Logged-in content (Instagram, Facebook)

Many Instagram and some Facebook posts require a session. Export cookies in Netscape format
(for example with a "cookies.txt" browser extension), ship or download the file to the device,
and pass `cookiesFile`:

```ts
import * as YtDlp from 'expo-yt-dlp';

async function withCookies(url: string, cookiesPath: string) {
  return YtDlp.download(url, { cookiesFile: cookiesPath }).promise;
}
```
