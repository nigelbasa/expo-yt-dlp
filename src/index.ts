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
  StreamOptions,
  StreamSource,
  SubtitleOptions,
  SubtitleTrack,
  UpdateCheck,
  UpdateEvent,
  UpdateMode,
  UpdateOptions,
  UpdateResult,
  UpdateState,
  VideoInfo,
} from './ExpoYtDlp.types';
import ExpoYtDlpModule from './ExpoYtDlpModule';
import { listSubtitleTracks, pickStream, summarizeFormats, toPlaylist } from './helpers';
import { toNativeDownloadOptions, toNativeInfoOptions, toNativeUpdateConfig } from './options';

export * from './ExpoYtDlp.types';
export { listSubtitleTracks, pickStream, summarizeFormats } from './helpers';

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

/**
 * When yt-dlp itself fails (`ERR_YTDLP`), marks the error with `updateAvailable`: whether
 * the last update check found a newer yt-dlp that `updateYtDlp()` could install.
 */
function withUpdateHint<T>(promise: Promise<T>): Promise<T> {
  return promise.catch((error: any) => {
    if (error?.code === 'ERR_YTDLP') {
      try {
        error.updateAvailable = ExpoYtDlpModule?.getUpdateState().updateAvailable ?? false;
      } catch {
        // The original error matters more than the hint.
      }
    }
    throw error;
  });
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
    const json = await withUpdateHint(mod.getInfo(id, url, toNativeInfoOptions(options)));
    return JSON.parse(json) as VideoInfo;
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
 * A direct link to play a video (or its audio) without saving it: hand `url` to a media
 * player. By default it is always a single URL with picture and sound, at most 720p; see
 * `StreamOptions`. The link expires after a few hours, so fetch it right before playing.
 */
export async function getStream(
  urlOrInfo: string | VideoInfo,
  options: StreamOptions = {}
): Promise<StreamSource> {
  const { maxHeight, audioOnly, allowSeparate, ...infoOptions } = options;
  const info = typeof urlOrInfo === 'string' ? await getInfo(urlOrInfo, infoOptions) : urlOrInfo;
  const stream = pickStream(info, { maxHeight, audioOnly, allowSeparate });
  if (!stream) {
    throw Object.assign(
      new Error(`No playable ${audioOnly ? 'audio' : 'video'} stream was found.`),
      {
        code: 'ERR_YTDLP_NO_STREAM',
      }
    );
  }
  return stream;
}

/**
 * What `download(url, options)` would fetch — the exact formats, file names and sizes —
 * without downloading. Use it to check sizes before starting.
 */
export async function estimateDownload(
  url: string,
  options: Omit<DownloadOptions, 'onProgress'> = {}
): Promise<DownloadEstimate> {
  const result = await withUpdateHint(
    nativeModule().download(
      jobId('plan'),
      url,
      toNativeDownloadOptions(options, { simulate: true })
    )
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
        if (event.id !== id) return;
        // yt-dlp's last event for a file often has no total, so the percentage would be missing.
        onProgress(
          event.status === 'finished' && event.percent == null ? { ...event, percent: 100 } : event
        );
      })
    : null;
  const promise = withUpdateHint(mod.download(id, url, toNativeDownloadOptions(options))).finally(
    () => subscription?.remove()
  );
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
// Keeping yt-dlp up to date
// ---------------------------------------------------------------------------

/**
 * Chooses where yt-dlp comes from. Call it once at startup; the choice is stored on the
 * device and can be changed at any time.
 *
 *   setUpdates('auto')      // fetch newer, verified yt-dlp builds in the background
 *   setUpdates('bundled')   // default: only the yt-dlp shipped inside the app
 *   setUpdates('manual')    // only when the app calls updateYtDlp()
 *   setUpdates({ mode: 'auto', checkIntervalHours: 12, feed: { url, publicKey } })
 *
 * Auto mode never delays or fails a download: it checks at most once per interval, and once
 * more after a yt-dlp failure. Follow it with `addUpdateListener()` or `getUpdateState()`.
 * Returns null where the module is not available (iOS, web), so it is safe to call anywhere.
 */
export function setUpdates(options: UpdateMode | UpdateOptions): UpdateState | null {
  const config = toNativeUpdateConfig(options);
  return ExpoYtDlpModule?.setUpdates(config) ?? null;
}

/** The current mode, the yt-dlp in use, and the outcome of the last check. */
export function getUpdateState(): UpdateState | null {
  return ExpoYtDlpModule?.getUpdateState() ?? null;
}

/** Asks whether a newer yt-dlp exists for this app. Downloads nothing; works in any mode. */
export function checkForUpdate(): Promise<UpdateCheck> {
  return nativeModule().checkForUpdate();
}

/**
 * Checks, and if a newer compatible yt-dlp exists, downloads, verifies and switches to it.
 * Downloads already running finish on the old version. Rejects with an `ERR_YTDLP_UPDATE_*`
 * code whose message can be shown as it is; the yt-dlp in use is never left broken.
 */
export function updateYtDlp(
  options: { onProgress?: (fraction: number) => void } = {}
): Promise<UpdateResult> {
  const mod = nativeModule();
  const { onProgress } = options;
  const subscription = onProgress
    ? mod.addListener('onUpdate', (event) => {
        if (event.type === 'downloading') onProgress(event.fraction ?? 0);
      })
    : null;
  return mod.updateYtDlp().finally(() => subscription?.remove());
}

/** Goes back to the yt-dlp shipped inside the app and forgets the downloaded one. */
export function resetToBundled(): Promise<UpdateState> {
  return nativeModule().resetToBundled();
}

/**
 * Follows update activity, including auto mode's background checks: `checking`,
 * `downloading`, `updated`, `up-to-date`, `app-update-required` and `failed` (with `error`).
 */
export function addUpdateListener(listener: (event: UpdateEvent) => void): { remove(): void } {
  return ExpoYtDlpModule?.addListener('onUpdate', listener) ?? { remove() {} };
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
