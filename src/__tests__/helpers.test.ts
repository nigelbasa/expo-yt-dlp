import type { VideoInfo } from '../ExpoYtDlp.types';
import { listSubtitleTracks, summarizeFormats, toPlaylist } from '../helpers';

const base = { id: 'x', title: 'T', extractor: 'youtube', extractor_key: 'Youtube', webpage_url: 'u' };

describe('summarizeFormats', () => {
  const info: VideoInfo = {
    ...base,
    duration: 100,
    formats: [
      { format_id: 'sb0', ext: 'mhtml', protocol: 'mhtml', vcodec: 'none', acodec: 'none' },
      { format_id: '140', ext: 'm4a', vcodec: 'none', acodec: 'mp4a.40.2', tbr: 128, filesize: 1_600_000 },
      { format_id: '137', ext: 'mp4', vcodec: 'avc1.640028', acodec: 'none', width: 1920, height: 1080, tbr: 4000 },
      { format_id: 'hd', ext: 'mp4', vcodec: null, acodec: null, filesize_approx: 9_000_000 },
    ],
  };
  const formats = summarizeFormats(info);

  it('drops storyboards and returns best first', () => {
    expect(formats.map((f) => f.formatId)).toEqual(['hd', '137', '140']);
  });

  it('uses exact size, then approximate, then bitrate × duration', () => {
    const byId = Object.fromEntries(formats.map((f) => [f.formatId, f]));
    expect(byId['140']).toMatchObject({ size: 1_600_000, sizeIsEstimate: false, resolution: 'audio only' });
    expect(byId['hd']).toMatchObject({ size: 9_000_000, sizeIsEstimate: true, hasVideo: null, hasAudio: null });
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
      { language: 'de', name: 'German', automatic: false, formats: [{ ext: 'vtt', url: 'a', name: 'German' }] },
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
        { ...base, id: 'a', title: 'A', webpage_url: undefined as any, url: 'https://y/a', duration: 5, channel: 'C' },
      ],
    });
    expect(playlist).toEqual({
      id: 'x',
      title: 'Mix',
      uploader: null,
      entries: [{ id: 'a', url: 'https://y/a', title: 'A', duration: 5, uploader: 'C', thumbnail: null }],
    });
  });

  it('wraps a single video as a one-entry playlist', () => {
    expect(toPlaylist({ ...base }).entries).toHaveLength(1);
  });
});
