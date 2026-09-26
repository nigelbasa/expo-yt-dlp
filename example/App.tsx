import * as YtDlp from 'expo-yt-dlp';
import { useRef, useState } from 'react';
import { Button, SafeAreaView, ScrollView, Text, TextInput, View } from 'react-native';

const SAMPLE_URL = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';

export default function App() {
  const [url, setUrl] = useState(SAMPLE_URL);
  const [log, setLog] = useState<string[]>([]);
  const [progress, setProgress] = useState<YtDlp.DownloadProgress | null>(null);
  const task = useRef<YtDlp.DownloadTask | null>(null);

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
      append(`${label} ok in ${((Date.now() - started) / 1000).toFixed(1)}s: ${JSON.stringify(result)}`);
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
          <Button
            title="Get info"
            onPress={() =>
              run('getInfo', async () => {
                const info = await YtDlp.getInfo(url);
                return { title: info.title, extractor: info.extractor, formats: info.formats?.length };
              })
            }
          />
          <View style={styles.row}>
            <Button
              title="Download 720p"
              onPress={() => {
                task.current = YtDlp.download(url, { maxHeight: 720, onProgress });
                run('download', () => task.current!.promise);
              }}
            />
            <Button title="Audio" onPress={() => run('audio', () => YtDlp.download(url, { audioOnly: true }).promise)} />
            <Button title="Cancel" onPress={() => task.current?.cancel()} />
          </View>
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

const styles = {
  header: { fontSize: 30, margin: 20 },
  groupHeader: { fontSize: 20, marginBottom: 12 },
  group: { margin: 12, backgroundColor: '#fff', borderRadius: 10, padding: 16, gap: 8 },
  container: { flex: 1, backgroundColor: '#eee' },
  input: { borderWidth: 1, borderColor: '#ccc', borderRadius: 6, padding: 8 },
  row: { flexDirection: 'row' as const, justifyContent: 'space-between' as const },
  log: { fontFamily: 'monospace', fontSize: 11 },
};
