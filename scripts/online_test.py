#!/usr/bin/env python3
"""Checks the *bundled* yt-dlp against the real sites, on the host Python 3.14.

Run after `npm run deps:update` (or any change to the runner) and before publishing:

  npm run test:online

For each site it resolves the info dict and plans a download (formats + sizes, nothing
written), and for YouTube it also downloads a short video for real. YouTube's JS challenges
are solved with a host QuickJS-ng binary of the pinned version (downloaded to
.cache/host-tools if missing).

These talk to live sites, so they can fail for reasons outside this repo (bot checks, rate
limits, a site change that needs a newer yt-dlp); that is why CI doesn't run them by default.
"""
from __future__ import annotations

import json
import os
import platform
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE_ZIP = ROOT / 'android' / 'src' / 'main' / 'assets' / 'expo-yt-dlp' / 'site-packages.zip'
HOST_TOOLS = ROOT / '.cache' / 'host-tools'

CASES = [
    # (name, url, also do a real download)
    ('youtube', 'https://www.youtube.com/watch?v=jNQXAC9IVRw', True),
    ('facebook', 'https://www.facebook.com/cnn/videos/10155529876156509/', False),
    ('instagram', 'https://www.instagram.com/reel/Chunk8-jurw/', False),
]


def host_qjs() -> Path:
    version = json.loads((ROOT / 'runtime-versions.json').read_text())['quickjs-ng']['version']
    system, machine = platform.system(), platform.machine().lower()
    arch = 'aarch64' if machine in ('arm64', 'aarch64') else 'x86_64'
    asset = {'Windows': 'qjs-windows-x86_64.exe', 'Darwin': f'qjs-darwin-{"arm64" if arch == "aarch64" else "x86_64"}',
             'Linux': f'qjs-linux-{arch}'}[system]
    dest = HOST_TOOLS / f'qjs-{version}{".exe" if system == "Windows" else ""}'
    if not dest.exists():
        HOST_TOOLS.mkdir(parents=True, exist_ok=True)
        url = f'https://github.com/quickjs-ng/quickjs/releases/download/v{version}/{asset}'
        print(f'downloading {url}')
        urllib.request.urlretrieve(url, dest)
        dest.chmod(dest.stat().st_mode | stat.S_IEXEC)
    return dest


def main() -> None:
    qjs = host_qjs()
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp) / 'site-packages'
        zipfile.ZipFile(SITE_ZIP).extractall(site)
        out = Path(tmp) / 'out'
        env = {**os.environ, 'PYTHONPATH': str(site), 'PYTHONUTF8': '1'}
        # Same fixed options as the app; no ffmpeg, like a build without it.
        base = ['--ignore-config', '--no-js-runtimes', '--js-runtimes', f'quickjs:{qjs}',
                '--ffmpeg-location', 'none', '-P', str(out), '--no-playlist']

        def runner(runner_args, ytdlp_args, url):
            return subprocess.run(
                [sys.executable, '-S', '-m', 'expo_yt_dlp_runner', *runner_args, '--', *base, *ytdlp_args, '--', url],
                env=env, capture_output=True, text=True, encoding='utf-8', timeout=300)

        def result(proc):
            line = next((l for l in proc.stdout.splitlines() if l.startswith('__EYD__') and '"result"' in l), None)
            return json.loads(line[len('__EYD__'):]) if line else None

        def report(name, ok, detail):
            print(f'{"PASS" if ok else "FAIL"}  {name:<22} {detail}')
            if not ok:
                failures.append(name)

        for name, url, real in CASES:
            r = runner(['--info'], [], url)
            info = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else None
            report(f'{name} info', bool(info and info.get('formats')),
                   f'"{info["title"][:40]}", {len(info["formats"])} formats' if info else r.stderr.strip()[-200:])

            template = ['-o', '%(title).50B [%(id)s].f%(format_id)s.%(ext)s']
            r = runner(['--native-merge', '--max-res', '720'], ['--simulate', *template], url)
            res = result(r)
            item = (res or {}).get('items', [{}])[0] if res else None
            report(f'{name} plan', r.returncode == 0 and bool(item and item.get('files')),
                   f'{[f["formatId"] for f in item["files"]]}, {item["size"]} bytes' if item and item.get('files')
                   else r.stderr.strip()[-200:])

            if real:
                # Same templates and side-file options as the module's native-merge download.
                name_t = '%(title).50B [%(id)s]'
                side = ['-o', f'{name_t}.f%(format_id)s.%(ext)s', '-o', f'subtitle:{name_t}.%(ext)s',
                        '-o', f'thumbnail:{name_t}.%(ext)s', '--write-subs', '--write-auto-subs',
                        '--sub-langs', 'en', '--write-thumbnail']
                r = runner(['--native-merge', '--max-res', '720'], side, url)
                res = result(r)
                items = (res or {}).get('items', [])
                files = [f['path'] for it in items for f in it['files']]
                subs = [s['path'] for it in items for s in it['subtitles']]
                thumbs = [t for it in items for t in it['thumbnails']]
                on_disk_subs = list(out.glob('*.vtt'))
                ok = (r.returncode == 0 and files and all(Path(p).exists() for p in files + subs + thumbs)
                      and len(subs) == 1 and len(thumbs) == 1 and len(on_disk_subs) == 1)
                report(f'{name} download', bool(ok),
                       f'{len(files)} media files, subtitles {[Path(p).name for p in subs]}, '
                       f'{len(thumbs)} thumbnail' if ok else
                       f'exit {r.returncode}, subs={subs}, thumbs={thumbs}, vtt on disk={on_disk_subs} '
                       f'{r.stderr.strip()[-200:]}')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
