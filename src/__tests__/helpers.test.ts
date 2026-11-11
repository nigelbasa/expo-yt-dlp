import type { VideoInfo } from '../ExpoYtDlp.types';
import { listSubtitleTracks, pickStream, summarizeFormats, toPlaylist } from '../helpers';

const base = {
  id: 'x',
  title: 'T',
  extractor: 'youtube',
  extractor_key: 'Youtube',
  webpage_url: 'u',
};

describe('summarizeFormats', () => {
  const info: VideoInfo = {
    ...base,
    duration: 100,
    formats: [
      { format_id: 'sb0', ext: 'mhtml', protocol: 'mhtml', vcodec: 'none', acodec: 'none' },
      {
        format_id: '140',
        ext: 'm4a',
        vcodec: 'none',
        acodec: 'mp4a.40.2',
        tbr: 128,
        filesize: 1_600_000,
      },
      {
        format_id: '137',
        ext: 'mp4',
        vcodec: 'avc1.640028',
        acodec: 'none',
        width: 1920,
        height: 1080,
        tbr: 4000,
      },
      { format_id: 'hd', ext: 'mp4', vcodec: null, acodec: null, filesize_approx: 9_000_000 },
    ],
  };
  const formats = summarizeFormats(info);

  it('drops storyboards and returns best first', () => {
    expect(formats.map((f) => f.formatId)).toEqual(['hd', '137', '140']);
  });

  it('uses exact size, then approximate, then bitrate × duration', () => {
    const byId = Object.fromEntries(formats.map((f) => [f.formatId, f]));
    expect(byId['140']).toMatchObject({
      size: 1_600_000,
      sizeIsEstimate: false,
      resolution: 'audio only',
    });
    expect(byId['hd']).toMatchObject({
      size: 9_000_000,
      sizeIsEstimate: true,
      hasVideo: null,
      hasAudio: null,
    });
    expect(byId['137']).toMatchObject({
      size: 50_000_000,
      sizeIsEstimate: true,
      resolution: '1920x1080',
      hasVideo: true,
      hasAudio: false,
      acodec: null,
    });
  });
});

describe('listSubtitleTracks', () => {
  it('lists uploaded subtitles before auto-captions', () => {
    const tracks = listSubtitleTracks({
      ...base,
      subtitles: { de: [{ ext: 'vtt', url: 'a', name: 'German' }] },
      automatic_captions: { en: [{ ext: 'vtt', url: 'b' }] },
    });
    expect(tracks).toEqual([
      {
        language: 'de',
        name: 'German',
        automatic: false,
        formats: [{ ext: 'vtt', url: 'a', name: 'German' }],
      },
      { language: 'en', name: null, automatic: true, formats: [{ ext: 'vtt', url: 'b' }] },
    ]);
  });
});

describe('toPlaylist', () => {
  it('maps flat playlist entries', () => {
    const playlist = toPlaylist({
      ...base,
      _type: 'playlist',
      title: 'Mix',
      entries: [
        {
          ...base,
          id: 'a',
          title: 'A',
          webpage_url: undefined as any,
          url: 'https://y/a',
          duration: 5,
          channel: 'C',
        },
      ],
    });
    expect(playlist).toEqual({
      id: 'x',
      title: 'Mix',
      uploader: null,
      entries: [
        { id: 'a', url: 'https://y/a', title: 'A', duration: 5, uploader: 'C', thumbnail: null },
      ],
    });
  });

  it('wraps a single video as a one-entry playlist', () => {
    expect(toPlaylist({ ...base }).entries).toHaveLength(1);
  });
});

describe('pickStream', () => {
  const info: VideoInfo = {
    ...base,
    duration: 60,
    formats: [
      {
        format_id: 'sb0',
        ext: 'mhtml',
        protocol: 'mhtml',
        url: 's',
        vcodec: 'none',
        acodec: 'none',
      },
      {
        format_id: '251',
        ext: 'webm',
        protocol: 'https',
        url: 'a-opus',
        vcodec: 'none',
        acodec: 'opus',
        tbr: 130,
      },
      {
        format_id: '140',
        ext: 'm4a',
        protocol: 'https',
        url: 'a-aac',
        vcodec: 'none',
        acodec: 'mp4a.40.2',
        tbr: 128,
      },
      {
        format_id: '18',
        ext: 'mp4',
        protocol: 'https',
        url: 'v360',
        vcodec: 'avc1.42001E',
        acodec: 'mp4a.40.2',
        height: 360,
        http_headers: { 'User-Agent': 'UA' },
      },
      {
        format_id: '95',
        ext: 'mp4',
        protocol: 'm3u8_native',
        url: 'hls720',
        vcodec: 'avc1.64001F',
        acodec: 'mp4a.40.2',
        height: 720,
      },
      {
        format_id: '136',
        ext: 'mp4',
        protocol: 'https',
        url: 'v720',
        vcodec: 'avc1.64001f',
        acodec: 'none',
        height: 720,
      },
      {
        format_id: '248',
        ext: 'webm',
        protocol: 'https',
        url: 'v1080-vp9',
        vcodec: 'vp9',
        acodec: 'none',
        height: 1080,
      },
      {
        format_id: '137',
        ext: 'mp4',
        protocol: 'http_dash_segments',
        url: 'dash',
        vcodec: 'avc1.640028',
        acodec: 'none',
        height: 1080,
      },
    ],
  };

  it('picks the tallest single stream within maxHeight, HLS included', () => {
    expect(pickStream(info)).toMatchObject({
      kind: 'hls',
      url: 'hls720',
      audioUrl: null,
      height: 720,
    });
  });

  it('respects maxHeight and carries the headers', () => {
    expect(pickStream(info, { maxHeight: 480 })).toMatchObject({
      kind: 'single',
      url: 'v360',
      formatId: '18',
      headers: { 'User-Agent': 'UA' },
      title: 'T',
      duration: 60,
    });
  });

  it('prefers a plain file over HLS at the same height', () => {
    const both: VideoInfo = {
      ...base,
      formats: [
        {
          format_id: 'file',
          ext: 'mp4',
          protocol: 'https',
          url: 'file',
          vcodec: 'avc1',
          acodec: 'mp4a',
          height: 480,
        },
        {
          format_id: 'hls',
          ext: 'mp4',
          protocol: 'm3u8_native',
          url: 'hls',
          vcodec: 'avc1',
          acodec: 'mp4a',
          height: 480,
        },
      ],
    };
    expect(pickStream(both)?.url).toBe('file');
  });

  it('picks AAC audio for audioOnly', () => {
    expect(pickStream(info, { audioOnly: true })).toMatchObject({
      kind: 'single',
      url: 'a-aac',
      hasVideo: false,
      height: null,
    });
  });

  it('returns separate H.264 video + audio only when allowed and better', () => {
    expect(pickStream(info, { maxHeight: 1080, allowSeparate: true })).toMatchObject({
      kind: 'hls', // the 720p single stream ties the best H.264 video-only stream
      url: 'hls720',
    });
    const noHls = { ...info, formats: info.formats!.filter((f) => f.format_id !== '95') };
    expect(pickStream(noHls, { allowSeparate: true })).toMatchObject({
      kind: 'separate',
      url: 'v720',
      audioUrl: 'a-aac',
      formatId: '136+140',
    });
    expect(pickStream(noHls)).toMatchObject({ kind: 'single', url: 'v360' });
  });

  it('never returns storyboards or segmented DASH, and null when nothing fits', () => {
    expect(pickStream({ ...base, formats: [info.formats![0], info.formats![7]] })).toBeNull();
    expect(pickStream(base)).toBeNull();
  });
});
