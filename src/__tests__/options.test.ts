import {
  downloadArgs,
  toNativeDownloadOptions,
  toNativeInfoOptions,
  toNativeUpdateConfig,
} from '../options';

describe('downloadArgs', () => {
  it('is empty by default', () => {
    expect(downloadArgs({})).toEqual([]);
  });

  it('maps subtitles: true to English with auto-caption fallback', () => {
    expect(downloadArgs({ subtitles: true })).toEqual([
      '--write-subs',
      '--write-auto-subs',
      '--sub-langs',
      'en',
    ]);
  });

  it('maps subtitle options', () => {
    expect(
      downloadArgs({ subtitles: { languages: ['en', 'es'], auto: false, format: 'vtt' } })
    ).toEqual(['--write-subs', '--sub-langs', 'en,es', '--sub-format', 'vtt']);
  });

  it('maps playlist range, rate limit and thumbnail, with extraArgs last', () => {
    expect(
      downloadArgs({
        playlistItems: '1:5',
        rateLimit: '2M',
        thumbnail: true,
        extraArgs: ['-r', '1M'],
      })
    ).toEqual(['-I', '1:5', '-r', '2M', '--write-thumbnail', '-r', '1M']);
  });
});

describe('toNativeDownloadOptions', () => {
  it('defaults to a single video with media', () => {
    expect(toNativeDownloadOptions({})).toMatchObject({
      noPlaylist: true,
      skipMedia: false,
      simulate: false,
      audioOnly: false,
    });
  });

  it('treats playlistItems as a playlist download', () => {
    expect(toNativeDownloadOptions({ playlistItems: '1:3' }).noPlaylist).toBe(false);
  });

  it('maps media: false and simulate', () => {
    expect(toNativeDownloadOptions({ media: false }, { simulate: true })).toMatchObject({
      skipMedia: true,
      simulate: true,
    });
  });
});

describe('toNativeInfoOptions', () => {
  it('resolves only the video by default', () => {
    expect(toNativeInfoOptions({})).toEqual({
      flatPlaylist: false,
      noPlaylist: true,
      cookiesFile: undefined,
      networkRetries: undefined,
      extraArgs: [],
    });
  });
});

describe('toNativeUpdateConfig', () => {
  it('accepts a bare mode and fills the defaults', () => {
    expect(toNativeUpdateConfig('auto')).toEqual({ mode: 'auto', checkIntervalHours: 24 });
    expect(toNativeUpdateConfig({ mode: 'bundled' })).toEqual({
      mode: 'bundled',
      checkIntervalHours: 24,
    });
  });

  it('passes a custom feed and interval through', () => {
    expect(
      toNativeUpdateConfig({
        mode: 'manual',
        checkIntervalHours: 6,
        feed: { url: 'https://example.com/feed.json', publicKey: '3059' },
      })
    ).toEqual({
      mode: 'manual',
      checkIntervalHours: 6,
      feedUrl: 'https://example.com/feed.json',
      feedPublicKey: '3059',
    });
  });

  it('rejects unknown modes, short intervals and unsafe feeds', () => {
    expect(() => toNativeUpdateConfig('always' as any)).toThrow(TypeError);
    expect(() => toNativeUpdateConfig({ mode: 'auto', checkIntervalHours: 0.5 })).toThrow(
      TypeError
    );
    expect(() => toNativeUpdateConfig({ mode: 'auto', checkIntervalHours: NaN })).toThrow(
      TypeError
    );
    expect(() =>
      toNativeUpdateConfig({ mode: 'auto', feed: { url: 'http://example.com/f', publicKey: 'ab' } })
    ).toThrow(TypeError);
    expect(() =>
      toNativeUpdateConfig({ mode: 'auto', feed: { url: 'https://example.com/f', publicKey: '' } })
    ).toThrow(TypeError);
  });
});
