export type RuntimeInfo = {
  python: string;
  ytDlp: string;
  /** QuickJS-ng version used to solve YouTube's JS challenges, or null if not bundled. */
  quickjs: string | null;
  /** When false, video+audio are merged with Android's MediaMuxer (H.264/AAC only). */
  hasFfmpeg: boolean;
  abi: string;
  /** Extractor classes compiled into this build. */
  extractors: string[];
};

export type VideoFormat = {
  format_id: string;
  ext: string;
  url?: string;
  protocol?: string;
  vcodec?: string | null;
  acodec?: string | null;
  width?: number | null;
  height?: number | null;
  fps?: number | null;
  tbr?: number | null;
  filesize?: number | null;
  filesize_approx?: number | null;
  format_note?: string | null;
  [key: string]: unknown;
};

/** yt-dlp's info dict (`--dump-single-json`). Only the common fields are typed. */
export type VideoInfo = {
  _type?: 'video' | 'playlist' | 'multi_video' | 'url' | 'url_transparent';
  id: string;
  title: string;
  extractor: string;
  extractor_key: string;
  webpage_url: string;
  duration?: number | null;
  thumbnail?: string | null;
  uploader?: string | null;
  description?: string | null;
  formats?: VideoFormat[];
  entries?: VideoInfo[];
  [key: string]: unknown;
};

export type InfoOptions = {
  /** For playlists, list entries without resolving each one. */
  flatPlaylist?: boolean;
  /** Treat URLs that point at both a video and a playlist as just the video. Default true. */
  noPlaylist?: boolean;
  /** Netscape-format cookies file (Instagram and Facebook often need a logged-in session). */
  cookiesFile?: string;
  /** Raw yt-dlp arguments, inserted before the URL. */
  extraArgs?: string[];
  /** Retries after a dropped connection, timeout or 5xx while fetching pages. Default 3. */
  networkRetries?: number;
  signal?: AbortSignal;
};

export type DownloadOptions = {
  /** Directory to save into. Defaults to `<filesDir>/downloads`. */
  outputDir?: string;
  /** yt-dlp format selector. Overrides maxHeight/audioOnly and disables the built-in merge logic. */
  format?: string;
  /**
   * Caps the short side (720 = 720p for landscape and portrait). Best effort: files whose
   * size the site doesn't report, such as Facebook's combined hd/sd files, can exceed it.
   */
  maxHeight?: number;
  audioOnly?: boolean;
  /** Default true. */
  noPlaylist?: boolean;
  cookiesFile?: string;
  extraArgs?: string[];
  /**
   * Retries after a dropped connection, timeout or 5xx while fetching pages (yt-dlp already
   * retries the media download itself). Bot checks, 4xx and certificate errors are not
   * retried. Default 3.
   */
  networkRetries?: number;
  onProgress?: (event: DownloadProgress) => void;
};

export type DownloadProgress = {
  /** The job id returned by `download()`. */
  id: string;
  videoId: string | null;
  /** Video and audio are separate downloads; each reports its own progress. */
  formatId: string | null;
  status: 'downloading' | 'finished' | 'error' | 'merging' | 'retrying';
  downloadedBytes?: number | null;
  totalBytes?: number | null;
  percent?: number | null;
  /** Bytes per second. */
  speed?: number | null;
  /** Seconds. */
  eta?: number | null;
  fragmentIndex?: number | null;
  fragmentCount?: number | null;
  /** status "retrying": which retry this is, out of how many, and the wait before it (s). */
  attempt?: number | null;
  retries?: number | null;
  retryDelay?: number | null;
};

export type DownloadResult = {
  id: string;
  /** Absolute paths of the final files. */
  files: string[];
};

export type DownloadTask = {
  id: string;
  promise: Promise<DownloadResult>;
  /** Returns false if the job already finished. The promise rejects with ERR_YTDLP_CANCELLED. */
  cancel(): boolean;
};

export type ExecResult = {
  exitCode: number;
  stdout: string;
  stderr: string;
};

export type ExpoYtDlpModuleEvents = {
  onProgress: (event: DownloadProgress) => void;
};
