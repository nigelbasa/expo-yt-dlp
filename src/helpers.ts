import type {
  FormatSummary,
  Playlist,
  PlaylistEntry,
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
