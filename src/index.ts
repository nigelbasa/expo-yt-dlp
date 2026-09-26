import { Platform } from 'react-native';

import {
  DownloadOptions,
  DownloadTask,
  ExecResult,
  InfoOptions,
  RuntimeInfo,
  VideoInfo,
} from './ExpoYtDlp.types';
import ExpoYtDlpModule from './ExpoYtDlpModule';

export * from './ExpoYtDlp.types';

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

/**
 * Whether the bundled runtime can run on this device: false on iOS/web, on 32-bit devices,
 * or when the app was built without the config plugin.
 */
export function isSupported(): boolean {
  return ExpoYtDlpModule?.isSupported() ?? false;
}

/**
 * Extracts the Python runtime on first use (a few seconds, once per app version) and
 * returns version info. Optional: every other call does this lazily.
 */
export function prepare(): Promise<RuntimeInfo> {
  return nativeModule().prepare();
}

/** Resolves a URL to yt-dlp's info dict without downloading anything. */
export async function getInfo(url: string, options: InfoOptions = {}): Promise<VideoInfo> {
  const mod = nativeModule();
  const { signal, ...nativeOptions } = options;
  const id = jobId('info');
  const onAbort = () => mod.cancel(id);
  // React Native's AbortSignal polyfill has no throwIfAborted().
  if (signal?.aborted) {
    throw Object.assign(new Error('The operation was cancelled'), { code: 'ERR_YTDLP_CANCELLED' });
  }
  signal?.addEventListener('abort', onAbort);
  try {
    return JSON.parse(await mod.getInfo(id, url, nativeOptions)) as VideoInfo;
  } finally {
    signal?.removeEventListener('abort', onAbort);
  }
}

/**
 * Downloads a URL. Without a `format`, picks the best H.264 video + AAC audio (capped at
 * `maxHeight`) and merges them into one MP4.
 */
export function download(url: string, options: DownloadOptions = {}): DownloadTask {
  const mod = nativeModule();
  const { onProgress, ...nativeOptions } = options;
  const id = jobId('dl');
  const subscription = onProgress
    ? mod.addListener('onProgress', (event) => {
        if (event.id === id) onProgress(event);
      })
    : null;
  const promise = mod.download(id, url, nativeOptions).finally(() => subscription?.remove());
  return { id, promise, cancel: () => mod.cancel(id) };
}

/** Runs yt-dlp with raw arguments (the runtime's JS-runtime/ffmpeg/cache flags are prepended). */
export function exec(args: string[]): Promise<ExecResult> {
  return nativeModule().exec(jobId('exec'), args);
}
