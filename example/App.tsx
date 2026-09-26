import * as YtDlp from 'expo-yt-dlp';
import { useRef, useState } from 'react';
import { Button, SafeAreaView, ScrollView, Text, TextInput, View } from 'react-native';

const SAMPLE_URL = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';
const BATCH_URLS = [
  'https://www.youtube.com/watch?v=jNQXAC9IVRw',
  'https://www.facebook.com/cnn/videos/10155529876156509/',
  'https://www.instagram.com/reel/Chunk8-jurw/',
];

const mb = (bytes: number | null) => (bytes == null ? '?' : `${(bytes / 1e6).toFixed(1)} MB`);

export default function App() {
  const [url, setUrl] = useState(SAMPLE_URL);
  const [log, setLog] = useState<string[]>([]);
  const [progress, setProgress] = useState<YtDlp.DownloadProgress | null>(null);
  const task = useRef<{ cancel(): unknown } | null>(null);

  const append = (line: string) => {
    console.log(`[expo-yt-dlp example] ${line}`); // also to logcat (tag ReactNativeJS)
    setLog((lines) => [...lines.slice(-50), line]);
  };
  const onProgress = (p: YtDlp.DownloadProgress) => {
    setProgress(p);
    if (p.status !== 'downloading') append(`progress: ${p.status} ${p.formatId ?? ''}`);
  };
  const run = async (label: string, fn: () => Promise<unknown>) => {
    const started = Date.now();
    append(`${label}...`);
    try {
      const result = await fn();
      append(
        `${label} ok in ${((Date.now() - started) / 1000).toFixed(1)}s: ${JSON.stringify(result)}`
      );
    } catch (e: any) {
      append(`${label} failed [${e.code ?? 'error'}]: ${e.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView style={styles.container}>
        <Text style={styles.header}>expo-yt-dlp</Text>
        <Group name="Runtime">
          <Text>isSupported: {String(YtDlp.isSupported())}</Text>
          <Button title="Prepare" onPress={() => run('prepare', YtDlp.prepare)} />
        </Group>

        <Group name="URL">
          <TextInput style={styles.input} value={url} onChangeText={setUrl} autoCapitalize="none" />
          <Row>
            <Button
              title="Get info"
              onPress={() =>
                run('getInfo', async () => {
                  const info = await YtDlp.getInfo(url);
                  return {
                    title: info.title,
                    extractor: info.extractor,
                    formats: info.formats?.length,
                  };
                })
              }
            />
            <Button
              title="Formats"
              onPress={() =>
                run('formats', async () =>
                  (await YtDlp.getFormats(url))
                    .slice(0, 4)
                    .map((f) => `${f.formatId} ${f.resolution ?? '?'} ${f.ext} ${mb(f.size)}`)
                )
              }
            />
            <Button
              title="Estimate"
              onPress={() =>
                run('estimate', async () => {
                  const plan = await YtDlp.estimateDownload(url, { maxHeight: 720 });
                  return {
                    total: mb(plan.totalSize),
                    files: plan.items[0]?.files.map((f) => f.formatId),
                  };
                })
              }
            />
          </Row>
          <Row>
            <Button
              title="Download 720p"
              onPress={() => {
                const t = YtDlp.download(url, { maxHeight: 720, onProgress });
                task.current = t;
                run('download', () => t.promise.then((r) => r.files));
              }}
            />
            <Button
              title="Audio"
              onPress={() =>
                run('audio', () =>
                  YtDlp.download(url, { audioOnly: true }).promise.then((r) => r.files)
                )
              }
            />
            <Button title="Cancel" onPress={() => task.current?.cancel()} />
          </Row>
          <Row>
            <Button
              title="Subtitles"
              onPress={() =>
                run('subtitles', async () => {
                  const result = await YtDlp.downloadSubtitles(url, { languages: ['en'] }).promise;
                  return result.items.flatMap((i) =>
                    i.subtitles.map((s) => `${s.language}.${s.ext}`)
                  );
                })
              }
            />
            <Button
              title="Thumbnail"
              onPress={() =>
                run(
                  'thumbnail',
                  async () => (await YtDlp.downloadThumbnail(url).promise).items[0]?.thumbnails
                )
              }
            />
            <Button
              title="Search"
              onPress={() =>
                run('search', async () =>
                  (await YtDlp.search('blender open movie', { limit: 3 })).map((e) => e.title)
                )
              }
            />
            <Button
              title="Batch x3"
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
          </Row>
          {progress && (
            <Text>
              {progress.status} {progress.formatId ?? ''} {progress.percent?.toFixed(1) ?? '?'}%{' '}
              {progress.speed ? `${(progress.speed / 1e6).toFixed(2)} MB/s` : ''}
            </Text>
          )}
        </Group>

        <Group name="Log">
          {log.map((line, i) => (
            <Text key={i} selectable style={styles.log}>
              {line}
            </Text>
          ))}
        </Group>
      </ScrollView>
    </SafeAreaView>
  );
}

function Group(props: { name: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupHeader}>{props.name}</Text>
      {props.children}
    </View>
  );
}

function Row(props: { children: React.ReactNode }) {
  return <View style={styles.row}>{props.children}</View>;
}

const styles = {
  header: { fontSize: 30, margin: 20 },
  groupHeader: { fontSize: 20, marginBottom: 12 },
  group: { margin: 12, backgroundColor: '#fff', borderRadius: 10, padding: 16, gap: 8 },
  container: { flex: 1, backgroundColor: '#eee' },
  input: { borderWidth: 1, borderColor: '#ccc', borderRadius: 6, padding: 8 },
  row: { flexDirection: 'row' as const, justifyContent: 'space-between' as const },
  log: { fontFamily: 'monospace', fontSize: 11 },
};
