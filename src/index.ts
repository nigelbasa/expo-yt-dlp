import { Platform } from 'react-native';

import {
  BatchInput,
  BatchItemResult,
  BatchOptions,
  BatchTask,
  DownloadEstimate,
  DownloadOptions,
  DownloadTask,
  ExecResult,
  FormatSummary,
  InfoOptions,
  Playlist,
  PlaylistEntry,
  RuntimeInfo,
  SubtitleOptions,
  SubtitleTrack,
  VideoInfo,
} from './ExpoYtDlp.types';
import ExpoYtDlpModule from './ExpoYtDlpModule';
import { listSubtitleTracks, summarizeFormats, toPlaylist } from './helpers';
import { toNativeDownloadOptions, toNativeInfoOptions } from './options';

export * from './ExpoYtDlp.types';
export { listSubtitleTracks, summarizeFormats } from './helpers';

let nextId = 0;
function jobId(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${(nextId++).toString(36)}`;
}

function nativeModule() {
  if (!ExpoYtDlpModule) {
    throw new Error(`expo-yt-dlp is not available on ${Platform.OS} yet (Android only).`);
  }
  return ExpoYtDlpModule;
}

function cancelledError(): Error & { code: string } {
  return Object.assign(new Error('The operation was cancelled'), { code: 'ERR_YTDLP_CANCELLED' });
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

/**
 * Whether the bundled runtime can run on this device: false on iOS/web, on 32-bit devices,
 * or when the app was built without the config plugin.
 */
export function isSupported(): boolean {
  return ExpoYtDlpModule?.isSupported() ?? false;
}

/**
 * Extracts the Python runtime on first use (a second or two, once per app version) and
 * returns version info. Optional: every other call does this lazily.
 */
export function prepare(): Promise<RuntimeInfo> {
  return nativeModule().prepare();
}

// ---------------------------------------------------------------------------
// Looking things up (nothing is downloaded)
// ---------------------------------------------------------------------------

/** yt-dlp's full info dict for a URL: title, duration, formats, subtitles, thumbnails... */
export async function getInfo(url: string, options: InfoOptions = {}): Promise<VideoInfo> {
  const mod = nativeModule();
  const { signal } = options;
  const id = jobId('info');
  const onAbort = () => mod.cancel(id);
  // React Native's AbortSignal polyfill has no throwIfAborted().
  if (signal?.aborted) throw cancelledError();
  signal?.addEventListener('abort', onAbort);
  try {
    return JSON.parse(await mod.getInfo(id, url, toNativeInfoOptions(options))) as VideoInfo;
  } finally {
    signal?.removeEventListener('abort', onAbort);
  }
}

/** Every available format with resolution, codecs and size, best first. */
export async function getFormats(
  urlOrInfo: string | VideoInfo,
  options: InfoOptions = {}
): Promise<FormatSummary[]> {
  const info = typeof urlOrInfo === 'string' ? await getInfo(urlOrInfo, options) : urlOrInfo;
  return summarizeFormats(info);
}

/** Uploaded subtitles and auto-generated captions available for a video. */
export async function getSubtitles(
  urlOrInfo: string | VideoInfo,
  options: InfoOptions = {}
): Promise<SubtitleTrack[]> {
  const info = typeof urlOrInfo === 'string' ? await getInfo(urlOrInfo, options) : urlOrInfo;
  return listSubtitleTracks(info);
}

/**
 * What `download(url, options)` would fetch — the exact formats, file names and sizes —
 * without downloading. Use it to check sizes before starting.
 */
export async function estimateDownload(
  url: string,
  options: Omit<DownloadOptions, 'onProgress'> = {}
): Promise<DownloadEstimate> {
  const result = await nativeModule().download(
    jobId('plan'),
    url,
    toNativeDownloadOptions(options, { simulate: true })
  );
  const sizes = result.items.map((item) => item.size);
  return {
    items: result.items,
    totalSize: sizes.some((s) => s == null)
      ? null
      : sizes.reduce<number>((a, s) => a + (s ?? 0), 0),
    sizeIsEstimate: result.items.some((item) => item.sizeIsEstimate),
  };
}

/** The entries of a playlist, channel or search URL, without resolving each video. */
export async function getPlaylist(
  url: string,
  options: InfoOptions & { limit?: number } = {}
): Promise<Playlist> {
  const { limit, ...rest } = options;
  const extraArgs = [...(limit ? ['-I', `1:${limit}`] : []), ...(rest.extraArgs ?? [])];
  const info = await getInfo(url, { ...rest, playlist: true, flatPlaylist: true, extraArgs });
  return toPlaylist(info);
}

/** YouTube search. */
export async function search(
  query: string,
  options: Omit<InfoOptions, 'playlist' | 'flatPlaylist'> & { limit?: number } = {}
): Promise<PlaylistEntry[]> {
  const { limit = 10, ...rest } = options;
  return (await getPlaylist(`ytsearch${limit}:${query}`, rest)).entries;
}

// ---------------------------------------------------------------------------
// Downloading
// ---------------------------------------------------------------------------

/**
 * Downloads a URL. By default: the best H.264 video + AAC audio, merged into one MP4
 * (capped at `maxHeight`). See `DownloadOptions` for audio, subtitles, playlists and more.
 */
export function download(url: string, options: DownloadOptions = {}): DownloadTask {
  const mod = nativeModule();
  const id = jobId('dl');
  const { onProgress } = options;
  const subscription = onProgress
    ? mod.addListener('onProgress', (event) => {
        if (event.id === id) onProgress(event);
      })
    : null;
  const promise = mod
    .download(id, url, toNativeDownloadOptions(options))
    .finally(() => subscription?.remove());
  return { id, promise, cancel: () => mod.cancel(id) };
}

/** Downloads only subtitle files (no video). */
export function downloadSubtitles(
  url: string,
  options: SubtitleOptions & Omit<DownloadOptions, 'subtitles' | 'media' | 'thumbnail'> = {}
): DownloadTask {
  const { languages, auto, format, ...rest } = options;
  return download(url, { ...rest, subtitles: { languages, auto, format }, media: false });
}

/** Downloads only the thumbnail image. */
export function downloadThumbnail(
  url: string,
  options: Omit<DownloadOptions, 'thumbnail' | 'media'> = {}
): DownloadTask {
  return download(url, { ...options, thumbnail: true, media: false });
}

/**
 * Downloads several URLs, `concurrency` at a time. One failure doesn't stop the others:
 * the promise resolves with a result per input, in input order.
 */
export function downloadBatch(inputs: BatchInput[], options: BatchOptions = {}): BatchTask {
  nativeModule();
  const { concurrency = 2, onProgress, onItemDone, ...shared } = options;
  const jobs = inputs.map((input) =>
    typeof input === 'string'
      ? { url: input, options: shared }
      : { url: input.url, options: { ...shared, ...input.options } }
  );
  const results: (BatchItemResult | undefined)[] = new Array(jobs.length);
  const running = new Set<DownloadTask>();
  let next = 0;
  let cancelled = false;

  const worker = async () => {
    while (!cancelled && next < jobs.length) {
      const index = next++;
      const { url, options: itemOptions } = jobs[index];
      let result: BatchItemResult;
      try {
        const task = download(url, {
          ...itemOptions,
          onProgress: onProgress && ((event) => onProgress(event, index)),
        });
        running.add(task);
        try {
          result = { url, ok: true, result: await task.promise };
        } finally {
          running.delete(task);
        }
      } catch (error: any) {
        result = { url, ok: false, error };
      }
      results[index] = result;
      onItemDone?.(result, index);
    }
  };

  const workers = Array.from({ length: Math.max(1, Math.min(concurrency, jobs.length)) }, worker);
  const promise = Promise.all(workers).then(() =>
    jobs.map(
      (job, i): BatchItemResult =>
        results[i] ?? { url: job.url, ok: false, error: cancelledError() }
    )
  );
  return {
    promise,
    cancel: () => {
      cancelled = true;
      running.forEach((task) => task.cancel());
    },
  };
}

// ---------------------------------------------------------------------------
// Anything else
// ---------------------------------------------------------------------------

/**
 * Runs yt-dlp with raw CLI arguments, e.g. `exec(['--list-subs', url])`. The runtime's own
 * flags (JS runtime, ffmpeg, cache dir, --ignore-config) are added first.
 */
export function exec(args: string[]): Promise<ExecResult> {
  return nativeModule().exec(jobId('exec'), args);
}
