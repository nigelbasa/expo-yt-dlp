import { downloadArgs, toNativeDownloadOptions, toNativeInfoOptions } from '../options';

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
    expect(downloadArgs({ subtitles: { languages: ['en', 'es'], auto: false, format: 'vtt' } })).toEqual(
      ['--write-subs', '--sub-langs', 'en,es', '--sub-format', 'vtt']
    );
  });

  it('maps playlist range, rate limit and thumbnail, with extraArgs last', () => {
    expect(
      downloadArgs({ playlistItems: '1:5', rateLimit: '2M', thumbnail: true, extraArgs: ['-r', '1M'] })
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
