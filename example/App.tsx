import * as YtDlp from 'expo-yt-dlp';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Linking,
  Pressable,
  SafeAreaView,
  ScrollView,
  Switch,
  Text,
  TextInput,
  View,
  useColorScheme,
  useWindowDimensions,
} from 'react-native';

const SAMPLE_URL = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';
const BATCH_URLS = [
  'https://www.youtube.com/watch?v=jNQXAC9IVRw',
  'https://www.facebook.com/cnn/videos/10155529876156509/',
  'https://www.instagram.com/reel/Chunk8-jurw/',
];

const PALETTES = {
  light: {
    bg: '#F4F5FB',
    surface: '#FFFFFF',
    surface2: '#E9EBF7',
    text: '#14162B',
    muted: '#5B5F7A',
    brand: '#4F46E5',
    onBrand: '#FFFFFF',
    tint: 'rgba(79,70,229,0.12)',
    good: '#15803D',
    bad: '#B91C1C',
  },
  dark: {
    bg: '#12142B',
    surface: '#1C1F3F',
    surface2: '#2A2E57',
    text: '#F1F2FB',
    muted: '#B3B7D6',
    brand: '#6366F1',
    onBrand: '#FFFFFF',
    tint: 'rgba(129,140,248,0.18)',
    good: '#4ADE80',
    bad: '#F87171',
  },
};
type Palette = (typeof PALETTES)['light'];

type CheckResult = { name: string; status: 'running' | 'pass' | 'fail' | 'skip'; detail: string };

const mb = (bytes: number | null | undefined) =>
  bytes == null ? '?' : `${(bytes / 1e6).toFixed(1)} MB`;
const ago = (time: number | null | undefined) => {
  if (!time) return 'Never';
  const minutes = Math.round((Date.now() - time) / 60000);
  return minutes < 1
    ? 'Just now'
    : minutes < 60
      ? `${minutes} min ago`
      : `${Math.round(minutes / 60)} h ago`;
};

export default function App() {
  const c = PALETTES[useColorScheme() === 'dark' ? 'dark' : 'light'];
  const { width } = useWindowDimensions();
  const wide = width >= 840;

  const [url, setUrl] = useState(SAMPLE_URL);
  const [save, setSave] = useState(true);
  const [runtime, setRuntime] = useState<YtDlp.RuntimeInfo | null>(null);
  const [updates, setUpdates] = useState<YtDlp.UpdateState | null>(null);
  const [updateFraction, setUpdateFraction] = useState<number | null>(null);
  const [progress, setProgress] = useState<YtDlp.DownloadProgress | null>(null);
  const [checks, setChecks] = useState<CheckResult[]>([]);
  const [busy, setBusy] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const task = useRef<{ cancel(): unknown } | null>(null);

  const append = useCallback((line: string) => {
    console.log(`[expo-yt-dlp example] ${line}`); // also to logcat (tag ReactNativeJS)
    setLog((lines) => [...lines.slice(-80), line]);
  }, []);

  /** Re-reads what yt-dlp is in use; called after anything that could change it. */
  const refresh = useCallback(async () => {
    setUpdates(YtDlp.getUpdateState());
    if (YtDlp.isSupported()) setRuntime(await YtDlp.prepare());
  }, []);

  useEffect(() => {
    refresh().catch((e) => append(`prepare failed: ${e.message}`));
    // Background update activity (auto mode) arrives here too.
    const sub = YtDlp.addUpdateListener((e) => {
      if (e.type === 'downloading') {
        setUpdateFraction(e.fraction ?? 0);
        return;
      }
      setUpdateFraction(null);
      append(
        `update: ${e.type} ${e.ytDlp ?? ''} ${e.error ? `[${e.error.code}] ${e.error.message}` : ''}`
      );
      refresh().catch(() => {});
    });
    return () => sub.remove();
  }, [append, refresh]);

  const run = async (label: string, fn: () => Promise<unknown> | unknown) => {
    const started = Date.now();
    append(`${label}...`);
    try {
      const result = await fn();
      const text = typeof result === 'string' ? result : JSON.stringify(result);
      append(`${label} ok in ${((Date.now() - started) / 1000).toFixed(1)}s: ${text}`);
    } catch (e: any) {
      const hint = e.updateAvailable ? ' (a newer yt-dlp is available)' : '';
      append(`${label} failed [${e.code ?? 'error'}]: ${e.message}${hint}`);
    }
  };

  const onProgress = (p: YtDlp.DownloadProgress) => {
    setProgress(p);
    if (p.status !== 'downloading') append(`progress: ${p.status} ${p.formatId ?? ''}`);
  };

  const startDownload = (label: string, options: YtDlp.DownloadOptions) => {
    const t = YtDlp.download(url, { ...options, onProgress });
    task.current = t;
    return run(label, async () => (await t.promise).files);
  };

  /** Save on: download to storage. Save off: get a direct link and hand it to a player. */
  const getMedia = (audioOnly: boolean) => {
    if (save) return startDownload(audioOnly ? 'audio' : 'download', { audioOnly, maxHeight: 720 });
    return run(audioOnly ? 'stream audio' : 'stream', async () => {
      const stream = await YtDlp.getStream(url, { audioOnly });
      await Linking.openURL(stream.url);
      return `${stream.kind} ${stream.formatId} ${stream.height ?? 'audio'} ${stream.url}`;
    });
  };

  const updateAction = (label: string, fn: () => Promise<unknown> | unknown) =>
    run(label, fn).then(refresh);

  // One tap: exercises every function of the package and lists what passed.
  const runAllChecks = async () => {
    setBusy(true);
    setChecks([]);
    const step = async (name: string, fn: () => Promise<string>) => {
      setChecks((list) => [...list, { name, status: 'running', detail: '' }]);
      let result: CheckResult;
      try {
        result = { name, status: 'pass', detail: await fn() };
      } catch (e: any) {
        const skip =
          e.code === 'ERR_YTDLP_UPDATE_NETWORK' || e.code === 'ERR_YTDLP_UPDATE_SIGNATURE';
        result = {
          name,
          status: skip ? 'skip' : 'fail',
          detail: `[${e.code ?? 'error'}] ${e.message}`,
        };
      }
      append(`check ${result.status}: ${name} ${result.detail}`);
      setChecks((list) => [...list.slice(0, -1), result]);
    };
    const expect = (ok: unknown, message: string) => {
      if (!ok) throw new Error(message);
    };

    let info: YtDlp.VideoInfo | null = null;
    let version = '';
    await step('Runtime starts', async () => {
      expect(YtDlp.isSupported(), 'isSupported() is false');
      const r = await YtDlp.prepare();
      version = r.ytDlp;
      return `yt-dlp ${r.ytDlp} (${r.source}), Python ${r.python}, ${r.abi}`;
    });
    await step('Raw command', async () => {
      const r = await YtDlp.exec(['--version']);
      expect(r.exitCode === 0 && r.stdout.trim() === version, `got "${r.stdout.trim()}"`);
      return r.stdout.trim();
    });
    await step('Video info', async () => {
      info = await YtDlp.getInfo(SAMPLE_URL);
      expect(info.formats?.length, 'no formats');
      return `"${info.title}", ${info.formats!.length} formats`;
    });
    await step('Formats and subtitles', async () => {
      const formats = await YtDlp.getFormats(info ?? SAMPLE_URL);
      const subs = await YtDlp.getSubtitles(info ?? SAMPLE_URL);
      expect(formats.length, 'no formats');
      return `${formats.length} formats, best ${formats[0].resolution}, ${subs.length} subtitle tracks`;
    });
    await step('Stream link', async () => {
      const video = await YtDlp.getStream(info ?? SAMPLE_URL);
      const audio = await YtDlp.getStream(info ?? SAMPLE_URL, { audioOnly: true });
      expect(video.url.startsWith('http') && audio.url.startsWith('http'), 'no link');
      return `video ${video.kind} ${video.height}p, audio ${audio.ext}`;
    });
    await step('Size estimate', async () => {
      const e = await YtDlp.estimateDownload(SAMPLE_URL, { maxHeight: 360 });
      expect(e.items.length, 'no items');
      return `${mb(e.totalSize)}${e.sizeIsEstimate ? ' (estimated)' : ''}`;
    });
    await step('Search', async () => {
      const results = await YtDlp.search('blender open movie', { limit: 2 });
      expect(results.length === 2, `${results.length} results`);
      return results.map((r) => r.title).join(' | ');
    });
    await step('Download video', async () => {
      const r = await YtDlp.download(SAMPLE_URL, { maxHeight: 360, onProgress }).promise;
      expect(r.files.length === 1, `${r.files.length} files`);
      return r.files[0];
    });
    await step('Download audio', async () => {
      const r = await YtDlp.download(SAMPLE_URL, { audioOnly: true, onProgress }).promise;
      expect(r.files.length === 1, `${r.files.length} files`);
      return r.files[0];
    });
    await step('Subtitles and thumbnail', async () => {
      const subs = await YtDlp.downloadSubtitles(SAMPLE_URL, { languages: ['en'] }).promise;
      const thumb = await YtDlp.downloadThumbnail(SAMPLE_URL).promise;
      const count = (r: typeof subs, key: 'subtitles' | 'thumbnails') =>
        r.items.reduce((n, item: any) => n + (item[key]?.length ?? 0), 0);
      expect(count(subs, 'subtitles') && count(thumb, 'thumbnails'), 'nothing written');
      return `${count(subs, 'subtitles')} subtitle file, ${count(thumb, 'thumbnails')} thumbnail`;
    });
    await step('Cancel', async () => {
      const t = YtDlp.download(SAMPLE_URL, { maxHeight: 720 });
      setTimeout(() => t.cancel(), 1500);
      const code = await t.promise.then(
        () => 'finished',
        (e) => e.code
      );
      expect(code === 'ERR_YTDLP_CANCELLED', `ended with ${code}`);
      return 'Stopped, partial files removed';
    });
    await step('Update state', async () => {
      const s = YtDlp.getUpdateState();
      expect(s, 'no update state');
      return `mode ${s!.mode}, using ${s!.current.ytDlp} (${s!.current.source})`;
    });
    await step('Update check', async () => {
      const check = await YtDlp.checkForUpdate();
      return check.latest ? `${check.status}: ${check.latest.ytDlp}` : check.status;
    });
    setProgress(null);
    setBusy(false);
    refresh().catch(() => {});
  };

  const current = updates?.current;
  const passed = checks.filter((r) => r.status === 'pass').length;

  const summary = (
    <>
      <View style={[styles.hero, { backgroundColor: c.brand }]}>
        <Text style={[styles.label, { color: c.onBrand, opacity: 0.85 }]}>yt-dlp in use</Text>
        <Text style={[styles.display, { color: c.onBrand }]}>
          {current?.ytDlp ?? runtime?.ytDlp ?? 'Not available'}
        </Text>
        <Text style={{ color: c.onBrand, opacity: 0.85 }}>
          {runtime
            ? `Python ${runtime.python} · ${runtime.abi} · ${runtime.hasFfmpeg ? 'ffmpeg' : 'MediaMuxer'}`
            : YtDlp.isSupported()
              ? 'Starting'
              : 'This device is not supported'}
        </Text>
        <View style={styles.pill}>
          <Text style={[styles.label, { color: c.onBrand }]}>
            {current?.source === 'downloaded' ? 'Downloaded update' : 'Shipped with app'}
          </Text>
        </View>
      </View>

      <Card c={c} title="Updates">
        <View style={[styles.segment, { backgroundColor: c.surface2 }]}>
          {(['bundled', 'auto', 'manual'] as const).map((mode) => {
            const on = updates?.mode === mode;
            return (
              <Pressable
                key={mode}
                accessibilityRole="button"
                accessibilityState={{ selected: on }}
                onPress={() => updateAction(`set ${mode}`, () => YtDlp.setUpdates(mode)?.mode)}
                style={[styles.segmentItem, on && { backgroundColor: c.brand }]}>
                <Text style={[styles.label, { color: on ? c.onBrand : c.text }]}>
                  {mode[0].toUpperCase() + mode.slice(1)}
                </Text>
              </Pressable>
            );
          })}
        </View>
        <Stat
          c={c}
          name="Newer version"
          value={
            updates?.latest ? `${updates.latest.ytDlp} · ${mb(updates.latest.size)}` : 'None found'
          }
        />
        <Stat c={c} name="Last check" value={ago(updates?.lastCheck)} />
        {updates?.lastError && (
          <Text selectable style={{ color: c.bad }}>
            {updates.lastError.message}
          </Text>
        )}
        {updateFraction != null && <Bar c={c} fraction={updateFraction} />}
        <View style={styles.row}>
          <Btn c={c} title="Check" onPress={() => updateAction('check', YtDlp.checkForUpdate)} />
          <Btn
            c={c}
            primary
            title={updates?.latest ? `Update to ${updates.latest.ytDlp}` : 'Update'}
            onPress={() => updateAction('update', () => YtDlp.updateYtDlp())}
          />
          <Btn
            c={c}
            title="Reset"
            onPress={() =>
              updateAction('reset', async () => (await YtDlp.resetToBundled()).current)
            }
          />
        </View>
      </Card>

      <Card c={c} title="Self-test">
        <Btn
          c={c}
          primary
          disabled={busy}
          title={busy ? `Running ${checks.length} of 13` : 'Run all checks'}
          onPress={runAllChecks}
        />
        {checks.length > 0 && !busy && (
          <Text style={[styles.title, { color: passed === checks.length ? c.good : c.text }]}>
            {passed} of {checks.length} passed
          </Text>
        )}
        {checks.map((r) => (
          <View key={r.name} style={styles.check}>
            <Text
              style={[
                styles.label,
                {
                  color: { pass: c.good, fail: c.bad, skip: c.muted, running: c.brand }[r.status],
                  width: 44,
                },
              ]}>
              {{ pass: 'Pass', fail: 'Fail', skip: 'Skip', running: '...' }[r.status]}
            </Text>
            <View style={{ flex: 1 }}>
              <Text style={{ color: c.text }}>{r.name}</Text>
              {r.detail !== '' && (
                <Text selectable numberOfLines={3} style={[styles.small, { color: c.muted }]}>
                  {r.detail}
                </Text>
              )}
            </View>
          </View>
        ))}
      </Card>
    </>
  );

  const main = (
    <>
      <Card c={c} title="Video">
        <TextInput
          value={url}
          onChangeText={setUrl}
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel="Video address"
          style={[styles.input, { backgroundColor: c.surface2, color: c.text }]}
        />
        <View style={[styles.row, { alignItems: 'center', justifyContent: 'space-between' }]}>
          <Text style={{ color: c.text }}>Save to storage</Text>
          <Switch value={save} onValueChange={setSave} accessibilityLabel="Save to storage" />
        </View>
        <View style={styles.row}>
          <Btn c={c} primary title={save ? 'Download' : 'Play'} onPress={() => getMedia(false)} />
          <Btn
            c={c}
            title={save ? 'Download audio' : 'Play audio'}
            onPress={() => getMedia(true)}
          />
          <Btn c={c} title="Cancel" onPress={() => task.current?.cancel()} />
        </View>
        {progress && (
          <>
            <Bar c={c} fraction={(progress.percent ?? 0) / 100} />
            <Text style={[styles.small, { color: c.muted }]}>
              {progress.status}
              {progress.percent != null ? ` · ${progress.percent.toFixed(0)}%` : ''}
              {progress.speed ? ` · ${(progress.speed / 1e6).toFixed(2)} MB/s` : ''}
              {progress.formatId ? ` · format ${progress.formatId}` : ''}
            </Text>
          </>
        )}
      </Card>

      <Card c={c} title="Look up">
        <View style={styles.row}>
          <Btn
            c={c}
            title="Info"
            onPress={() =>
              run('info', async () => {
                const info = await YtDlp.getInfo(url);
                return `"${info.title}" ${info.duration}s, ${info.formats?.length} formats`;
              })
            }
          />
          <Btn
            c={c}
            title="Formats"
            onPress={() =>
              run('formats', async () =>
                (await YtDlp.getFormats(url))
                  .slice(0, 6)
                  .map((f) => `${f.formatId} ${f.resolution} ${mb(f.size)}`)
              )
            }
          />
          <Btn
            c={c}
            title="Subtitles"
            onPress={() =>
              run('subtitles', async () =>
                (await YtDlp.getSubtitles(url))
                  .slice(0, 8)
                  .map((s) => s.language + (s.automatic ? ' (auto)' : ''))
              )
            }
          />
          <Btn
            c={c}
            title="Size"
            onPress={() =>
              run('size', async () => {
                const e = await YtDlp.estimateDownload(url, { maxHeight: 720 });
                return `${mb(e.totalSize)}${e.sizeIsEstimate ? ' (estimated)' : ''}`;
              })
            }
          />
          <Btn
            c={c}
            title="Link"
            onPress={() => run('link', async () => (await YtDlp.getStream(url)).url)}
          />
          <Btn
            c={c}
            title="Search"
            onPress={() =>
              run('search', async () =>
                (await YtDlp.search('blender open movie', { limit: 3 })).map((e) => e.title)
              )
            }
          />
        </View>
      </Card>

      <Card c={c} title="More downloads">
        <View style={styles.row}>
          <Btn
            c={c}
            title="Subtitles"
            onPress={() => {
              const t = YtDlp.downloadSubtitles(url, { languages: ['en'] });
              task.current = t;
              run('subtitle files', async () => (await t.promise).items);
            }}
          />
          <Btn
            c={c}
            title="Thumbnail"
            onPress={() => {
              const t = YtDlp.downloadThumbnail(url);
              task.current = t;
              run('thumbnail', async () => (await t.promise).items);
            }}
          />
          <Btn
            c={c}
            title="Batch of 3"
            onPress={() => {
              const batch = YtDlp.downloadBatch(BATCH_URLS, {
                concurrency: 2,
                maxHeight: 480,
                onItemDone: (r, i) =>
                  append(`batch item ${i}: ${r.ok ? 'ok' : `failed: ${r.error.message}`}`),
              });
              task.current = batch;
              run('batch', async () => (await batch.promise).map((r) => r.ok));
            }}
          />
          <Btn
            c={c}
            title="List formats (raw)"
            onPress={() => run('exec -F', async () => (await YtDlp.exec(['-F', url])).stdout)}
          />
        </View>
      </Card>

      <Card c={c} title="Log">
        <Btn c={c} title="Clear" onPress={() => setLog([])} />
        {log.map((line, i) => (
          <Text key={i} selectable style={[styles.log, { color: c.text }]}>
            {line}
          </Text>
        ))}
      </Card>
    </>
  );

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: c.bg }}>
      <ScrollView contentContainerStyle={{ padding: wide ? 24 : 16, alignItems: 'center' }}>
        {wide ? (
          <View style={{ flexDirection: 'row', gap: 24, width: '100%', maxWidth: 1200 }}>
            <View style={{ width: 380, gap: 12 }}>{summary}</View>
            <View style={{ flex: 1, gap: 12 }}>{main}</View>
          </View>
        ) : (
          <View style={{ width: '100%', maxWidth: 640, gap: 12 }}>
            {summary}
            {main}
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function Card(props: { c: Palette; title: string; children: React.ReactNode }) {
  return (
    <View style={{ gap: 8 }}>
      <Text style={[styles.label, { color: props.c.muted, marginLeft: 4 }]}>{props.title}</Text>
      <View style={[styles.card, { backgroundColor: props.c.surface }]}>{props.children}</View>
    </View>
  );
}

function Btn(props: {
  c: Palette;
  title: string;
  onPress: () => void;
  primary?: boolean;
  disabled?: boolean;
}) {
  const { c, primary, disabled } = props;
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      onPress={props.onPress}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: primary ? c.brand : c.tint, opacity: disabled ? 0.5 : 1 },
        pressed && { transform: [{ scale: 0.97 }] },
      ]}>
      <Text style={[styles.label, { color: primary ? c.onBrand : c.text }]}>{props.title}</Text>
    </Pressable>
  );
}

function Stat(props: { c: Palette; name: string; value: string }) {
  return (
    <View style={[styles.row, { justifyContent: 'space-between' }]}>
      <Text style={{ color: props.c.muted }}>{props.name}</Text>
      <Text style={[styles.label, { color: props.c.text }]}>{props.value}</Text>
    </View>
  );
}

function Bar(props: { c: Palette; fraction: number }) {
  const percent = `${Math.round(Math.min(1, Math.max(0, props.fraction)) * 100)}%` as const;
  return (
    <View style={[styles.track, { backgroundColor: props.c.surface2 }]}>
      <View style={[styles.track, { width: percent, backgroundColor: props.c.brand }]} />
    </View>
  );
}

const styles = {
  hero: { borderRadius: 28, padding: 24, gap: 4 },
  display: {
    fontSize: 44,
    fontWeight: '800' as const,
    letterSpacing: -1,
    fontVariant: ['tabular-nums' as const],
  },
  pill: {
    alignSelf: 'flex-start' as const,
    marginTop: 12,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 999,
    backgroundColor: 'rgba(255,255,255,0.2)',
  },
  card: { borderRadius: 20, padding: 16, gap: 12 },
  title: { fontSize: 18, fontWeight: '700' as const },
  label: { fontSize: 14, fontWeight: '600' as const },
  small: { fontSize: 12 },
  row: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: 8 },
  segment: { flexDirection: 'row' as const, borderRadius: 999, padding: 4 },
  segmentItem: {
    flex: 1,
    minHeight: 48,
    borderRadius: 999,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
  },
  button: {
    minHeight: 48,
    paddingHorizontal: 16,
    borderRadius: 999,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
  },
  input: { minHeight: 48, borderRadius: 12, paddingHorizontal: 12 },
  track: { height: 8, borderRadius: 999 },
  check: { flexDirection: 'row' as const, gap: 8 },
  log: { fontFamily: 'monospace', fontSize: 11 },
};
