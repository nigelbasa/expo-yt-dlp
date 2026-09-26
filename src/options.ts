import type { DownloadOptions, InfoOptions } from './ExpoYtDlp.types';
import type { NativeDownloadOptions, NativeInfoOptions } from './ExpoYtDlpModule';

/** yt-dlp CLI arguments for the options that have no native field. */
export function downloadArgs(options: DownloadOptions): string[] {
  const args: string[] = [];
  if (options.playlistItems) args.push('-I', options.playlistItems);
  if (options.rateLimit) args.push('-r', options.rateLimit);
  if (options.subtitles) {
    const subs = options.subtitles === true ? {} : options.subtitles;
    args.push('--write-subs');
    // yt-dlp prefers uploaded subtitles over auto-captions when both exist for a language.
    if (subs.auto ?? true) args.push('--write-auto-subs');
    args.push('--sub-langs', (subs.languages ?? ['en']).join(','));
    if (subs.format) args.push('--sub-format', subs.format);
  }
  if (options.thumbnail) args.push('--write-thumbnail');
  // User-supplied arguments last, so they can override the generated ones.
  return [...args, ...(options.extraArgs ?? [])];
}

export function toNativeDownloadOptions(
  options: DownloadOptions,
  mode: { simulate?: boolean } = {}
): NativeDownloadOptions {
  return {
    outputDir: options.outputDir,
    format: options.format,
    maxHeight: options.maxHeight,
    audioOnly: options.audioOnly ?? false,
    noPlaylist: !(options.playlist || options.playlistItems),
    cookiesFile: options.cookiesFile,
    networkRetries: options.networkRetries,
    extraArgs: downloadArgs(options),
    simulate: mode.simulate ?? false,
    skipMedia: options.media === false,
  };
}

export function toNativeInfoOptions(options: InfoOptions): NativeInfoOptions {
  return {
    flatPlaylist: options.flatPlaylist ?? false,
    noPlaylist: !options.playlist,
    cookiesFile: options.cookiesFile,
    networkRetries: options.networkRetries,
    extraArgs: options.extraArgs ?? [],
  };
}
