#!/usr/bin/env python3
"""Tests the runner's network retries against a local server that drops connections.

Runs the *bundled* runner (android/src/main/assets/expo-yt-dlp/site-packages.zip) on the
host Python 3.14, so run scripts/build_android_runtime.py first.

  python scripts/test_runner_retry.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# EYD_SITE_ZIP: test another build, e.g. dist/bundle/site-packages.zip from --bundle-only.
SITE_ZIP = Path(os.environ.get('EYD_SITE_ZIP')
                or ROOT / 'android' / 'src' / 'main' / 'assets' / 'expo-yt-dlp' / 'site-packages.zip')

PAGE = b'<html><head><title>retry test</title></head><body><video src="/clip.mp4"></video></body></html>'
CLIP = b'\x00' * 4096


class Handler(BaseHTTPRequestHandler):
    # path -> [mode, remaining]; mode "drop" truncates the body, "status" returns an error code
    faults: dict[str, list] = {}
    hits: dict[str, int] = {}

    def do_GET(self):
        path = self.path.split('?')[0]
        Handler.hits[path] = Handler.hits.get(path, 0) + 1
        body = PAGE if path.endswith('.html') else CLIP
        fault = Handler.faults.get(path)
        if fault and fault[1] > 0:
            fault[1] -= 1
            if fault[0] == 'drop':
                self.send_response(200)
                self.send_header('Content-Type', 'text/html')
                self.send_header('Content-Length', str(len(body) + 1000))
                self.end_headers()
                self.wfile.write(body[:20])  # then close: IncompleteRead on the client
                self.close_connection = True
                return
            self.send_error(fault[0])
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/html' if path.endswith('.html') else 'video/mp4')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def run_runner(site: Path, runner_args: list[str], url: str, out: Path,
               ytdlp_args: tuple[str, ...] = ()) -> subprocess.CompletedProcess:
    env = {**os.environ, 'PYTHONPATH': str(site), 'PYTHONUTF8': '1'}
    cmd = [sys.executable, '-S', '-m', 'expo_yt_dlp_runner', *runner_args, '--',
           '--ignore-config', '-P', str(out), *ytdlp_args, '--', url]
    return subprocess.run(cmd, env=env, capture_output=True, text=True, encoding='utf-8', timeout=120)


def main() -> None:
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    failures = []

    def check(name, ok, detail=''):
        print(f'{"PASS" if ok else "FAIL"}  {name}{"  -- " + detail if detail and not ok else ""}')
        if not ok:
            failures.append(name)

    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp) / 'site-packages'
        zipfile.ZipFile(SITE_ZIP).extractall(site)
        out = Path(tmp) / 'out'

        # 1. Two dropped page loads, three retries allowed: info succeeds on the third attempt.
        Handler.faults, Handler.hits = {'/a.html': ['drop', 2]}, {}
        r = run_runner(site, ['--info', '--network-retries', '3'], f'{base}/a.html', out)
        info = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else {}
        check('dropped connections are retried (info)',
              r.returncode == 0 and str(info.get('title')).startswith('retry test') and r.stderr.count('retrying') == 2,
              f'exit={r.returncode} title={info.get("title")!r} type={info.get("_type")} '
              f'retries={r.stderr.count("retrying")}')

        # 2. Download mode: same fault, reports retry events and still returns the file.
        Handler.faults, Handler.hits = {'/b.html': ['drop', 1]}, {}
        r = run_runner(site, ['--network-retries', '3'], f'{base}/b.html', out)
        events = [json.loads(l[len('__EYD__'):]) for l in r.stdout.splitlines() if l.startswith('__EYD__')]
        retries = [e for e in events if e['event'] == 'retry']
        result = next((e for e in events if e['event'] == 'result'), None)
        files = [f['path'] for item in (result or {}).get('items', []) for f in item['files']]
        check('download mode emits retry events and finishes',
              r.returncode == 0 and len(retries) == 1 and files and Path(files[0]).exists(),
              f'exit={r.returncode} events={[e["event"] for e in events]} stderr={r.stderr[-300:]}')

        # 2b. The media download itself fails past yt-dlp's own retries on the first attempt.
        Handler.faults, Handler.hits = {'/clip.mp4': ['drop', 2]}, {}
        r = run_runner(site, ['--network-retries', '3'], f'{base}/b2.html', out, ('--retries', '1'))
        events = [json.loads(l[len('__EYD__'):]) for l in r.stdout.splitlines() if l.startswith('__EYD__')]
        result = next((e for e in events if e['event'] == 'result'), None)
        files = [f['path'] for item in (result or {}).get('items', []) for f in item['files']]
        check('media download failure is retried and the file reported',
              r.returncode == 0 and files and Path(files[0]).exists(),
              f'exit={r.returncode} hits={Handler.hits} events={events} stderr={r.stderr[-400:]}')

        # 3. A 403 is not transient: fails at once, without retries.
        Handler.faults, Handler.hits = {'/c.html': [403, 99]}, {}
        r = run_runner(site, ['--info', '--network-retries', '3'], f'{base}/c.html', out)
        check('HTTP 403 is not retried',
              r.returncode != 0 and 'retrying' not in r.stderr and Handler.hits.get('/c.html') == 1,
              f'exit={r.returncode} hits={Handler.hits} stderr={r.stderr[-300:]}')

        # 4. A 503 is transient.
        Handler.faults, Handler.hits = {'/d.html': [503, 1]}, {}
        r = run_runner(site, ['--info', '--network-retries', '3'], f'{base}/d.html', out)
        check('HTTP 503 is retried', r.returncode == 0 and r.stderr.count('retrying') == 1,
              f'exit={r.returncode} stderr={r.stderr[-300:]}')

        # 5. More failures than retries: gives up after the configured number.
        Handler.faults, Handler.hits = {'/e.html': ['drop', 99]}, {}
        r = run_runner(site, ['--info', '--network-retries', '2'], f'{base}/e.html', out)
        check('gives up after --network-retries',
              r.returncode != 0 and r.stderr.count('retrying') == 2 and Handler.hits.get('/e.html') == 3,
              f'exit={r.returncode} hits={Handler.hits}')

        # 6. --serve: one process, several jobs, each answered like a single call.
        env = {**os.environ, 'PYTHONPATH': str(site), 'PYTHONUTF8': '1'}
        engine = subprocess.Popen([sys.executable, '-S', '-m', 'expo_yt_dlp_runner', '--serve'], env=env,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, encoding='utf-8')

        def next_event():
            for line in engine.stdout:
                if line.startswith('__EYD__'):
                    return json.loads(line[len('__EYD__'):])
            return {}

        def job(job_id, runner_args, url, ytdlp_args=()):
            argv = [*runner_args, '--', '--ignore-config', '-P', str(out), *ytdlp_args, '--', url]
            engine.stdin.write(json.dumps({'id': job_id, 'argv': argv}) + '\n')
            engine.stdin.flush()
            events = []
            while not events or events[-1].get('event') not in ('done', None):
                events.append(next_event())
            return events

        check('engine reports ready', next_event().get('event') == 'ready')

        Handler.faults, Handler.hits = {'/f.html': ['drop', 1]}, {}
        *_, done = job('one', ['--info', '--network-retries', '3'], f'{base}/f.html')
        info = json.loads(done['stdout']) if done.get('code') == 0 else {}
        check('engine: info job returns the info and its retry log',
              done.get('id') == 'one' and str(info.get('title')).startswith('retry test')
              and done.get('stderr', '').count('retrying') == 1, f'done={str(done)[:300]}')

        Handler.faults, Handler.hits = {'/g.html': ['drop', 1]}, {}
        events = job('two', ['--network-retries', '3'], f'{base}/g.html')
        result = next((e for e in events if e['event'] == 'result'), None)
        files = [f['path'] for item in (result or {}).get('items', []) for f in item['files']]
        check('engine: download job emits events, then done',
              events[-1].get('code') == 0 and any(e['event'] == 'retry' for e in events)
              and any(e['event'] == 'progress' for e in events) and files and Path(files[0]).exists(),
              f'events={[e.get("event") for e in events]} done={str(events[-1])[:300]}')

        Handler.faults, Handler.hits = {'/h.html': [403, 99]}, {}
        *_, done = job('three', ['--info', '--network-retries', '3'], f'{base}/h.html')
        check('engine: a failed job reports its error and the engine keeps going',
              done.get('code') == 1 and 'ERROR:' in done.get('stderr', ''),
              f'done={str(done)[:300]}')

        *_, done = job('four', ['--info'], f'{base}/a.html', ('--no-such-option',))
        check('engine: a bad option fails only that job',
              done.get('code') != 0 and 'no such option' in done.get('stderr', ''), f'done={str(done)[:300]}')

        Handler.faults, Handler.hits = {}, {}
        *_, done = job('five', ['--info'], f'{base}/a.html')
        check('engine: still answers after failures', done.get('code') == 0 and done.get('id') == 'five')

        engine.stdin.close()
        check('engine exits when stdin closes', engine.wait(timeout=30) == 0)

    server.shutdown()
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
