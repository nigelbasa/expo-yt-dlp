// ---------------------------------------------------------------------------
// Runtime
// ---------------------------------------------------------------------------

export type RuntimeInfo = {
  python: string;
  ytDlp: string;
  /** QuickJS-ng version used to solve YouTube's JS challenges, or null if not bundled. */
  quickjs: string | null;
  /** When false, video+audio are merged with Android's MediaMuxer (H.264/AAC only). */
  hasFfmpeg: boolean;
  abi: string;
  /** Extractor classes in the yt-dlp bundle in use. */
  extractors: string[];
  /** Whether yt-dlp is the copy shipped in the app or a newer one fetched by an update. */
  source: BundleSource;
  /** Identifies the exact yt-dlp bundle in use. */
  bundleId: string;
  /** What this build of the package accepts from updates; see the README on updates. */
  runtimeApi: number;
};

// ---------------------------------------------------------------------------
// yt-dlp updates
// ---------------------------------------------------------------------------

/**
 * - `bundled`: only ever use the yt-dlp shipped inside the app (the default).
 * - `auto`: look for a newer compatible yt-dlp in the background and switch to it.
 * - `manual`: never check on its own; the app calls `checkForUpdate()` / `updateYtDlp()`.
 */
export type UpdateMode = 'bundled' | 'auto' | 'manual';

export type UpdateOptions = {
  mode: UpdateMode;
  /** Auto mode: how often to look for a new yt-dlp. Default 24, minimum 1. */
  checkIntervalHours?: number;
  /**
   * Use your own feed instead of the official one. `publicKey` is the hex public key printed
   * by `scripts/publish_bundle.py keygen`; only feeds signed with it are accepted.
   */
  feed?: { url: string; publicKey: string };
};

export type BundleSource = 'bundled' | 'downloaded';

export type InstalledYtDlp = { ytDlp: string; source: BundleSource; bundleId: string };

export type AvailableYtDlp = { ytDlp: string; size: number; bundleId: string };

export type UpdateErrorCode =
  | 'ERR_YTDLP_UPDATE_NETWORK'
  | 'ERR_YTDLP_UPDATE_SIGNATURE'
  | 'ERR_YTDLP_UPDATE_CORRUPT'
  | 'ERR_YTDLP_UPDATE_BROKEN'
  | 'ERR_YTDLP_UPDATE_BUSY'
  | 'ERR_YTDLP_UPDATE_DISABLED'
  | 'ERR_YTDLP_UPDATE_CONFIG';

/** `message` is a full sentence that can be shown to the user as it is. */
export type UpdateError = { code: UpdateErrorCode; message: string };

export type UpdateCheck = {
  /**
   * `app-update-required`: a newer yt-dlp exists, but only for a newer version of this
   * package, so the app itself has to be updated to get it.
   */
  status: 'up-to-date' | 'available' | 'app-update-required';
  current: InstalledYtDlp;
  latest: AvailableYtDlp | null;
};

export type UpdateResult = UpdateCheck & {
  /** Whether a new yt-dlp was installed by this call. */
  updated: boolean;
  /** The yt-dlp version in use after the call. */
  ytDlp: string;
  /** The yt-dlp version in use before the call. */
  previous: string;
};

export type UpdateState = {
  mode: UpdateMode;
  checkIntervalHours: number;
  feed: { url: string; custom: boolean };
  current: InstalledYtDlp;
  /** From the last successful check: the newer yt-dlp it found, if any. */
  latest: AvailableYtDlp | null;
  /** True when `updateYtDlp()` would install something. */
  updateAvailable: boolean;
  /** Time of the last check, in milliseconds since the epoch. */
  lastCheck: number | null;
  /** Why the last check or update failed; cleared by the next successful check. */
  lastError: UpdateError | null;
};

export type UpdateEvent = {
  type: 'checking' | 'downloading' | 'updated' | 'up-to-date' | 'app-update-required' | 'failed';
  /** The yt-dlp version the event is about. */
  ytDlp?: string;
  /** `downloading`: 0 to 1. */
  fraction?: number;
  /** `failed` only. */
  error?: UpdateError;
};

// ---------------------------------------------------------------------------
// yt-dlp's info dict (only the commonly used fields are typed)
// ---------------------------------------------------------------------------

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
  /** Total bitrate, kbit/s. */
  tbr?: number | null;
  filesize?: number | null;
  filesize_approx?: number | null;
  format_note?: string | null;
  [key: string]: unknown;
};

export type SubtitleFormat = { ext: string; url: string; name?: string };

/** yt-dlp's info dict (`yt-dlp -J`). */
export type VideoInfo = {
  _type?: 'video' | 'playlist' | 'multi_video' | 'url' | 'url_transparent';
  id: string;
  title: string;
  extractor: string;
  extractor_key: string;
  webpage_url: string;
  url?: string;
  duration?: number | null;
  thumbnail?: string | null;
  thumbnails?: { url: string; width?: number; height?: number }[];
  uploader?: string | null;
  channel?: string | null;
  description?: string | null;
  upload_date?: string | null;
  view_count?: number | null;
  formats?: VideoFormat[];
  subtitles?: Record<string, SubtitleFormat[]>;
  automatic_captions?: Record<string, SubtitleFormat[]>;
  entries?: VideoInfo[];
  [key: string]: unknown;
};

// ---------------------------------------------------------------------------
// Options
// ---------------------------------------------------------------------------

type CommonOptions = {
  /** Netscape-format cookies file. Instagram and Facebook often need a logged-in session. */
  cookiesFile?: string;
  /**
   * Retries after a dropped connection, timeout or 5xx while fetching pages (yt-dlp already
   * retries the media download itself). Bot checks, 4xx and certificate errors are not
   * retried. Default 3.
   */
  networkRetries?: number;
  /** Raw yt-dlp CLI arguments, added after the ones generated from these options. */
  extraArgs?: string[];
};

export type InfoOptions = CommonOptions & {
  /**
   * When a URL points at both a video and a playlist (watch?v=…&list=…), resolve the
   * playlist instead of just the video. Default false.
   */
  playlist?: boolean;
  /** For playlists, list the entries without resolving each video (much faster). */
  flatPlaylist?: boolean;
  signal?: AbortSignal;
};

export type StreamOptions = Omit<InfoOptions, 'playlist' | 'flatPlaylist'> & {
  /** Highest video height to pick. Default 720. */
  maxHeight?: number;
  /** Pick an audio stream instead of video. */
  audioOnly?: boolean;
  /**
   * Allow a video-only stream with a separate audio stream when that gives a better picture
   * than any single stream (YouTube's single streams stop at 360p). Only players that can
   * play two sources together can use these, e.g. ExoPlayer's MergingMediaSource.
   * Default false: always one URL.
   */
  allowSeparate?: boolean;
};

/** A direct media link for a player. These links expire (hours) and belong to this device's connection. */
export type StreamSource = {
  /**
   * - `single`: one file with everything; any player can open `url`.
   * - `hls`: `url` is an HLS playlist (.m3u8).
   * - `separate`: `url` has the picture only and `audioUrl` the sound; play them together.
   */
  kind: 'single' | 'hls' | 'separate';
  url: string;
  audioUrl: string | null;
  /** Send these with the request if the player lets you (some sites require them). */
  headers: Record<string, string>;
  formatId: string;
  ext: string;
  hasVideo: boolean;
  height: number | null;
  title: string;
  duration: number | null;
};

export type SubtitleOptions = {
  /** Language codes, e.g. ['en', 'es'], or ['all']. Default ['en']. */
  languages?: string[];
  /** Fall back to auto-generated captions when there are no uploaded ones. Default true. */
  auto?: boolean;
  /** Preferred format, e.g. 'vtt', 'srt', 'ttml' (as offered by the site). Default: best. */
  format?: string;
};

export type DownloadOptions = CommonOptions & {
  /** Directory to save into. Defaults to `<filesDir>/downloads`. */
  outputDir?: string;
  /**
   * Caps the short side (720 = 720p for landscape and portrait). Best effort: files whose
   * size the site doesn't report, such as Facebook's combined hd/sd files, can exceed it.
   */
  maxHeight?: number;
  /** Best audio only (AAC in .m4a where available). */
  audioOnly?: boolean;
  /**
   * A yt-dlp format selector (see `getFormats`). Overrides maxHeight/audioOnly and the
   * built-in selection; without ffmpeg, selectors that merge (`a+b`) fall back to one file.
   */
  format?: string;
  /** Download the whole playlist when the URL points at one. Default false. */
  playlist?: boolean;
  /** Which playlist items, e.g. '1:5', '1,3,7', '-3:' (last three). Implies playlist. */
  playlistItems?: string;
  /** Also write subtitle files next to the media (`true` = English, with auto-captions). */
  subtitles?: boolean | SubtitleOptions;
  /** Also write the thumbnail image next to the media. */
  thumbnail?: boolean;
  /** Set to false to write only subtitles/thumbnails, without the video itself. */
  media?: boolean;
  /** Max download speed, e.g. '500K' or '2M' (bytes per second). */
  rateLimit?: string;
  onProgress?: (event: DownloadProgress) => void;
};

export type BatchOptions = Omit<DownloadOptions, 'onProgress'> & {
  /** How many downloads run at once. Each one is a separate Python process. Default 2. */
  concurrency?: number;
  /** Progress for any item; `index` is the item's position in the input list. */
  onProgress?: (event: DownloadProgress, index: number) => void;
  /** Called as each item finishes, successfully or not. */
  onItemDone?: (result: BatchItemResult, index: number) => void;
};

export type BatchInput = string | { url: string; options?: Omit<DownloadOptions, 'onProgress'> };

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

export type DownloadProgress = {
  /** The job id of the download (`DownloadTask.id`). */
  id: string;
  videoId: string | null;
  /** Video and audio can be separate downloads; each reports its own progress. */
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

export type DownloadedFile = {
  /** Absolute path. For estimates, the path it would be written to. */
  path: string;
  formatId: string | null;
  ext: string | null;
  vcodec: string | null;
  acodec: string | null;
  width: number | null;
  height: number | null;
  /** Bytes; null if the site doesn't say. */
  size: number | null;
  sizeIsEstimate: boolean;
};

export type SubtitleFile = { language: string; ext: string | null; path: string | null };

/** One video of a download (a playlist download has several). */
export type DownloadItem = {
  id: string;
  title: string;
  duration: number | null;
  extractor: string | null;
  webpageUrl: string | null;
  files: DownloadedFile[];
  subtitles: SubtitleFile[];
  thumbnails: string[];
  /** Total media bytes of this item. */
  size: number | null;
  sizeIsEstimate: boolean;
};

export type DownloadResult = {
  id: string;
  /** Absolute paths of all media files, across items. */
  files: string[];
  items: DownloadItem[];
};

export type DownloadTask = {
  id: string;
  promise: Promise<DownloadResult>;
  /** Returns false if the job already finished. The promise rejects with ERR_YTDLP_CANCELLED. */
  cancel(): boolean;
};

export type DownloadEstimate = {
  /** What `download` would fetch with the same options; files are not on disk. */
  items: DownloadItem[];
  /** Sum of all items; null if any size is unknown. */
  totalSize: number | null;
  sizeIsEstimate: boolean;
};

export type BatchItemResult =
  | { url: string; ok: true; result: DownloadResult }
  | { url: string; ok: false; error: Error & { code?: string } };

export type BatchTask = {
  /** Resolves when every item has finished; never rejects (failures are per item). */
  promise: Promise<BatchItemResult[]>;
  /** Stops queued items and cancels the running ones. */
  cancel(): void;
};

/** A normalized row of `info.formats`, best first. */
export type FormatSummary = {
  formatId: string;
  ext: string;
  /** null when the site doesn't say (often a combined file). */
  hasVideo: boolean | null;
  hasAudio: boolean | null;
  width: number | null;
  height: number | null;
  /** '1920x1080', 'audio only', or null. */
  resolution: string | null;
  fps: number | null;
  vcodec: string | null;
  acodec: string | null;
  /** Total bitrate, kbit/s. */
  bitrate: number | null;
  /** Bytes, from the site, or estimated from bitrate × duration. */
  size: number | null;
  sizeIsEstimate: boolean;
  note: string | null;
  protocol: string | null;
};

export type SubtitleTrack = {
  language: string;
  name: string | null;
  /** true for auto-generated captions (YouTube's also include machine translations). */
  automatic: boolean;
  formats: SubtitleFormat[];
};

export type PlaylistEntry = {
  id: string;
  url: string;
  title: string | null;
  duration: number | null;
  uploader: string | null;
  thumbnail: string | null;
};

export type Playlist = {
  id: string;
  title: string | null;
  uploader: string | null;
  entries: PlaylistEntry[];
};

export type ExecResult = {
  exitCode: number;
  stdout: string;
  stderr: string;
};

export type ExpoYtDlpModuleEvents = {
  onProgress: (event: DownloadProgress) => void;
  onUpdate: (event: UpdateEvent) => void;
};
