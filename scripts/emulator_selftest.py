#!/usr/bin/env python3
"""Runs the example app's self-test on a booted emulator, without YouTube.

Serves a short clip from this machine (the emulator reaches it at 10.0.2.2), installs the APK,
opens expoytdlpexample://selftest?base=... and waits for the app's "SELFTEST {...}" logcat line.
Checks that need a real site are skipped by the app; everything else must pass.

  python scripts/emulator_selftest.py path/to/app.apk [--timeout 900]
  python scripts/emulator_selftest.py --serve   # only the server (for the Kotlin library's tests)

Needs adb on PATH and ffmpeg (to make the clip). Writes the full logcat to emulator-logcat.txt.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PACKAGE = 'expo.modules.ytdlp.example'
PORT = 8765
SLOW_SIZE = 50_000_000  # announced size of /slow.mp4; it trickles out far slower than that


def make_clip(path: Path) -> bytes:
    subprocess.run(
        ['ffmpeg', '-loglevel', 'error', '-y',
         '-f', 'lavfi', '-i', 'testsrc=size=320x240:rate=25:duration=2',
         '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
         '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', str(path)],
        check=True)
    return path.read_bytes()


class Handler(BaseHTTPRequestHandler):
    clip = b''

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head: bool = False):
        path = self.path.split('?')[0]
        if path not in ('/clip.mp4', '/slow.mp4'):  # anything else is a 404, which tests rely on
            self.send_error(404)
            return
        slow = path == '/slow.mp4'
        self.send_response(200)
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('Content-Length', str(SLOW_SIZE if slow else len(self.clip)))
        self.end_headers()
        if head:
            return
        if not slow:
            self.wfile.write(self.clip)
            return
        for _ in range(600):  # ~16 KB/s for up to 10 minutes; the app cancels long before
            self.wfile.write(b'\0' * 1600)
            self.wfile.flush()
            time.sleep(0.1)

    def log_message(self, fmt, *args):
        print(f'[server] {self.command} {self.path} {args[1] if len(args) > 1 else ""}', flush=True)


def adb(*args: str, check: bool = True) -> str:
    return subprocess.run(['adb', *args], check=check, text=True, capture_output=True,
                          encoding='utf-8', errors='replace').stdout


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', nargs='?')
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--serve', action='store_true', help='only run the server, until killed')
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        Handler.clip = make_clip(Path(tmp) / 'clip.mp4')
    server = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    server.handle_error = lambda *a: None  # cancelled downloads hang up mid-response
    if args.serve:
        print(f'serving on port {PORT}', flush=True)
        server.serve_forever()
        return
    if not args.apk:
        parser.error('give the APK to test, or --serve')
    if not shutil.which('adb'):
        sys.exit('adb not found on PATH')
    threading.Thread(target=server.serve_forever, daemon=True).start()

    print(adb('install', '-r', '-g', args.apk).strip())
    adb('logcat', '-c')
    link = f'expoytdlpexample://selftest?base=http://10.0.2.2:{PORT}'
    print(adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', link, PACKAGE).strip())

    deadline = time.monotonic() + args.timeout
    seen: set[str] = set()
    result = None
    log = ''
    while time.monotonic() < deadline and result is None:
        time.sleep(5)
        log = adb('logcat', '-d', '-v', 'brief', check=False)
        for line in log.splitlines():
            if '[expo-yt-dlp example]' in line and line not in seen:
                seen.add(line)
                print(line.split('[expo-yt-dlp example]', 1)[1].strip(), flush=True)
            if m := re.search(r'SELFTEST (\{.*\})', line):
                result = json.loads(m.group(1))
        if result is None and 'FATAL EXCEPTION' in log:
            break

    Path('emulator-logcat.txt').write_text(log, encoding='utf-8')
    server.shutdown()
    if result is None:
        crash = re.findall(r'.*(?:FATAL EXCEPTION|AndroidRuntime|ReactNativeJS.*Error).*', log)
        print('\n'.join(crash[-40:]))
        sys.exit('The self-test did not finish (see emulator-logcat.txt)')
    print(f'\n{result["pass"]} passed, {result["fail"]} failed, {result["skip"]} skipped')
    if result['fail'] or not result['pass']:
        sys.exit(1)


if __name__ == '__main__':
    main()
