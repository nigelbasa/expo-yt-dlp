import type {
  FormatSummary,
  Playlist,
  PlaylistEntry,
  StreamOptions,
  StreamSource,
  SubtitleTrack,
  VideoFormat,
  VideoInfo,
} from './ExpoYtDlp.types';

/** yt-dlp uses 'none' for "no such stream" and null/undefined for "unknown". */
function presence(codec: string | null | undefined): boolean | null {
  if (codec === 'none') return false;
  return codec ? true : null;
}

function isStoryboard(format: VideoFormat): boolean {
  return format.ext === 'mhtml' || format.protocol === 'mhtml';
}

/**
 * Normalizes `info.formats` (best first) with sizes: the site's exact size when given,
 * otherwise yt-dlp's estimate, otherwise bitrate × duration.
 */
export function summarizeFormats(info: VideoInfo): FormatSummary[] {
  const duration = info.duration ?? null;
  return (info.formats ?? [])
    .filter((f) => !isStoryboard(f))
    .map((f): FormatSummary => {
      const hasVideo = presence(f.vcodec);
      const exact = f.filesize ?? null;
      const approx =
        f.filesize_approx ??
        (f.tbr && duration ? Math.round(((f.tbr * 1000) / 8) * duration) : null);
      return {
        formatId: f.format_id,
        ext: f.ext,
        hasVideo,
        hasAudio: presence(f.acodec),
        width: f.width ?? null,
        height: f.height ?? null,
        resolution:
          f.width && f.height ? `${f.width}x${f.height}` : hasVideo === false ? 'audio only' : null,
        fps: f.fps ?? null,
        vcodec: f.vcodec && f.vcodec !== 'none' ? f.vcodec : null,
        acodec: f.acodec && f.acodec !== 'none' ? f.acodec : null,
        bitrate: f.tbr ?? null,
        size: exact ?? approx,
        sizeIsEstimate: exact == null,
        note: f.format_note ?? null,
        protocol: f.protocol ?? null,
      };
    })
    .reverse();
}

/** Uploaded subtitles first, then auto-generated captions. */
export function listSubtitleTracks(info: VideoInfo): SubtitleTrack[] {
  const tracks = (source: VideoInfo['subtitles'], automatic: boolean) =>
    Object.entries(source ?? {}).map(([language, formats]) => ({
      language,
      name: formats.find((f) => f.name)?.name ?? null,
      automatic,
      formats,
    }));
  return [...tracks(info.subtitles, false), ...tracks(info.automatic_captions, true)];
}

function bestThumbnail(info: VideoInfo): string | null {
  const thumbs = info.thumbnails ?? [];
  return thumbs.length ? thumbs[thumbs.length - 1].url : (info.thumbnail ?? null);
}

export function toPlaylist(info: VideoInfo): Playlist {
  const entries = info._type === 'playlist' || info.entries ? (info.entries ?? []) : [info];
  return {
    id: info.id,
    title: info.title ?? null,
    uploader: info.uploader ?? info.channel ?? null,
    entries: entries.filter(Boolean).map((e): PlaylistEntry => ({
      id: e.id,
      url: e.webpage_url ?? e.url ?? '',
      title: e.title ?? null,
      duration: e.duration ?? null,
      uploader: e.uploader ?? e.channel ?? null,
      thumbnail: bestThumbnail(e),
    })),
  };
}

/** Formats a player can open from one URL: plain HTTP(S) files and HLS playlists. */
function isPlayable(format: VideoFormat): boolean {
  const protocol = format.protocol ?? 'https';
  return !!format.url && !isStoryboard(format) && /^(https?|m3u8(_native)?)$/.test(protocol);
}

const isHls = (format: VideoFormat) => (format.protocol ?? '').startsWith('m3u8');
const isH264 = (format: VideoFormat) => /^avc/.test(format.vcodec ?? '');
const isAac = (format: VideoFormat) => /^mp4a/.test(format.acodec ?? '');

/** The candidate that scores highest; later formats win ties (yt-dlp lists best last). */
function best(formats: VideoFormat[], score: (f: VideoFormat) => number[]): VideoFormat | null {
  let winner: VideoFormat | null = null;
  let top: number[] = [];
  for (const format of formats) {
    const s = score(format);
    const diff = s.map((v, i) => v - (top[i] ?? -Infinity)).find((d) => d !== 0) ?? 0;
    if (!winner || diff >= 0) [winner, top] = [format, s];
  }
  return winner;
}

/**
 * Picks the direct link to hand to a media player from a video's info dict.
 *
 * Without `allowSeparate` the result is always one URL carrying picture and sound: the
 * tallest one within `maxHeight`, preferring a plain file over HLS and H.264/AAC for
 * compatibility. Returns null when the video has no such stream.
 */
export function pickStream(info: VideoInfo, options: StreamOptions = {}): StreamSource | null {
  const { maxHeight = 720, audioOnly = false, allowSeparate = false } = options;
  const formats = (info.formats ?? []).filter(isPlayable);
  const fits = (f: VideoFormat) => (f.height ?? 0) <= maxHeight;
  const hasVideo = (f: VideoFormat) => presence(f.vcodec) !== false;
  const hasAudio = (f: VideoFormat) => presence(f.acodec) !== false;

  const source = (video: VideoFormat, audio: VideoFormat | null): StreamSource => ({
    kind: audio ? 'separate' : isHls(video) ? 'hls' : 'single',
    url: video.url!,
    audioUrl: audio?.url ?? null,
    headers: (video.http_headers as Record<string, string> | undefined) ?? {},
    formatId: audio ? `${video.format_id}+${audio.format_id}` : video.format_id,
    ext: video.ext,
    hasVideo: !audioOnly,
    height: audioOnly ? null : (video.height ?? null),
    title: info.title,
    duration: info.duration ?? null,
  });

  const audio = best(
    formats.filter((f) => presence(f.vcodec) === false && hasAudio(f)),
    (f) => [isHls(f) ? 0 : 1, isAac(f) ? 1 : 0, f.tbr ?? 0]
  );
  if (audioOnly) return audio ? source(audio, null) : null;

  const combined = best(
    formats.filter((f) => hasVideo(f) && hasAudio(f) && fits(f)),
    (f) => [f.height ?? 0, isHls(f) ? 0 : 1, isH264(f) ? 1 : 0, f.tbr ?? 0]
  );
  if (allowSeparate && audio) {
    const video = best(
      formats.filter((f) => hasVideo(f) && presence(f.acodec) === false && fits(f) && !isHls(f)),
      (f) => [isH264(f) ? 1 : 0, f.height ?? 0, f.tbr ?? 0]
    );
    if (video && (video.height ?? 0) > (combined?.height ?? 0)) return source(video, audio);
  }
  return combined ? source(combined, null) : null;
}
