#!/usr/bin/env python3
"""Check for and apply upstream updates to the bundled runtime (runtime-versions.json).

  npm run deps:check                      # what's pinned vs what's available upstream
  npm run deps:update                     # newest stable yt-dlp (+ the yt-dlp-ejs it pins) and certifi
  npm run deps:update -- --channel nightly
  npm run deps:update -- --ytdlp 2026.8.19   # a specific yt-dlp version
  npm run deps:update -- --all            # also Python (same 3.x line), QuickJS-ng and ffmpeg

`update` rewrites runtime-versions.json with the new versions and their sha256, then rebuilds
the runtime (which re-trims the extractors listed in runtime-versions.json) and runs the
offline tests. Pass --no-build to only edit the pins. Exit code 0 = up to date or updated
and tests passed; 1 = something failed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PINS_FILE = ROOT / 'runtime-versions.json'
DOWNLOADS = ROOT / '.cache' / 'downloads'
TRIPLETS = {'arm64-v8a': 'aarch64-linux-android', 'x86_64': 'x86_64-linux-android'}


def get(url: str, *, head: bool = False):
    headers = {'User-Agent': 'expo-yt-dlp update_deps'}
    if 'api.github.com' in url and os.environ.get('GITHUB_TOKEN'):
        headers['Authorization'] = f'Bearer {os.environ["GITHUB_TOKEN"]}'
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers, method='HEAD' if head else 'GET'))


def get_json(url: str):
    with get(url) as resp:
        return json.load(resp)


def exists(url: str) -> bool:
    try:
        with get(url, head=True):
            return True
    except urllib.error.HTTPError:
        return False


def version_key(v: str) -> tuple:
    return tuple(int(x) for x in re.findall(r'\d+', v))


def download_sha256(url: str, name: str) -> str:
    """Downloads into .cache/downloads (where the build scripts look) and hashes it."""
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    dest = DOWNLOADS / name
    print(f'  downloading {url}')
    h = hashlib.sha256()
    with get(url) as resp, dest.open('wb') as out:
        for chunk in iter(lambda: resp.read(1 << 20), b''):
            h.update(chunk)
            out.write(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Upstream lookups
# ---------------------------------------------------------------------------

def pypi_wheel(name: str, version: str) -> dict:
    meta = get_json(f'https://pypi.org/pypi/{name}/{version}/json')
    wheel = next((u for u in meta['urls'] if u['filename'].endswith('py3-none-any.whl')), None)
    if not wheel:
        raise SystemExit(f'{name} {version} has no py3-none-any wheel on PyPI')
    return {'version': meta['info']['version'], 'sha256': wheel['digests']['sha256'],
            'requires_dist': meta['info'].get('requires_dist') or []}


def latest_ytdlp(channel: str) -> str:
    meta = get_json('https://pypi.org/pypi/yt-dlp/json')
    if channel == 'stable':
        return meta['info']['version']
    # Nightlies are PyPI pre-releases (e.g. 2026.9.16.232951.dev0); newest by upload time.
    uploads = [(files[0]['upload_time_iso_8601'], v) for v, files in meta['releases'].items()
               if files and any(f['filename'].endswith('py3-none-any.whl') for f in files)]
    return max(uploads)[1]


def ejs_pin(ytdlp: dict) -> str | None:
    for req in ytdlp['requires_dist']:
        m = re.match(r'yt-dlp-ejs\s*==\s*([\w.]+)', req)
        if m:
            return m.group(1)
    return None


def latest_python_patch(current: str) -> str:
    """Newest 3.x.y (same 3.x as `current`) that has python.org Android builds."""
    minor = '.'.join(current.split('.')[:2])
    with get('https://www.python.org/ftp/python/') as resp:
        listing = resp.read().decode()
    candidates = sorted({v for v in re.findall(rf'href="({re.escape(minor)}\.\d+)/"', listing)},
                        key=version_key, reverse=True)
    for v in candidates:
        if version_key(v) <= version_key(current):
            break
        if exists(f'https://www.python.org/ftp/python/{v}/python-{v}-aarch64-linux-android.tar.gz'):
            return v
    return current


def latest_quickjs() -> str:
    return get_json('https://api.github.com/repos/quickjs-ng/quickjs/releases/latest')['tag_name'].lstrip('v')


def latest_ffmpeg(current: str) -> str:
    """Newest release in the same major line (a new major may change configure flags)."""
    major = current.split('.')[0]
    with get('https://ffmpeg.org/releases/') as resp:
        listing = resp.read().decode()
    versions = set(re.findall(rf'ffmpeg-({major}\.\d+(?:\.\d+)?)\.tar\.xz"', listing))
    return max(versions, key=version_key) if versions else current


# ---------------------------------------------------------------------------

def check(pins: dict, channel: str) -> list[tuple[str, str, str]]:
    rows = []
    target = latest_ytdlp(channel)
    rows.append((f'yt-dlp ({channel})', pins['wheels']['yt-dlp']['version'], target))
    rows.append(('yt-dlp-ejs (pinned by yt-dlp)', pins['wheels']['yt-dlp-ejs']['version'],
                 ejs_pin(pypi_wheel('yt-dlp', target)) or '?'))
    rows.append(('certifi', pins['wheels']['certifi']['version'],
                 get_json('https://pypi.org/pypi/certifi/json')['info']['version']))
    rows.append(('python (Android)', pins['python']['version'], latest_python_patch(pins['python']['version'])))
    rows.append(('quickjs-ng', pins['quickjs-ng']['version'], latest_quickjs()))
    rows.append(('ffmpeg', pins['ffmpeg']['version'], latest_ffmpeg(pins['ffmpeg']['version'])))
    return rows


def print_rows(rows):
    width = max(len(r[0]) for r in rows)
    for name, pinned, latest in rows:
        flag = '' if pinned == latest else '   <- update available'
        print(f'  {name:<{width}}  {pinned:<22} {latest}{flag}')


def update(pins: dict, args) -> list[str]:
    changed = []
    wheels = pins['wheels']

    target = args.ytdlp or latest_ytdlp(args.channel)
    ytdlp = pypi_wheel('yt-dlp', target)
    if ytdlp['version'] != wheels['yt-dlp']['version']:
        changed.append(f"yt-dlp {wheels['yt-dlp']['version']} -> {ytdlp['version']}")
        wheels['yt-dlp'] = {'version': ytdlp['version'], 'sha256': ytdlp['sha256']}
    ejs_version = ejs_pin(ytdlp)
    if ejs_version and ejs_version != wheels['yt-dlp-ejs']['version']:
        ejs = pypi_wheel('yt-dlp-ejs', ejs_version)
        changed.append(f"yt-dlp-ejs {wheels['yt-dlp-ejs']['version']} -> {ejs['version']}")
        wheels['yt-dlp-ejs'] = {'version': ejs['version'], 'sha256': ejs['sha256']}

    certifi = pypi_wheel('certifi', get_json('https://pypi.org/pypi/certifi/json')['info']['version'])
    if certifi['version'] != wheels['certifi']['version']:
        changed.append(f"certifi {wheels['certifi']['version']} -> {certifi['version']}")
        wheels['certifi'] = {'version': certifi['version'], 'sha256': certifi['sha256']}

    if args.all:
        py = latest_python_patch(pins['python']['version'])
        if py != pins['python']['version']:
            shas = {abi: download_sha256(
                f'https://www.python.org/ftp/python/{py}/python-{py}-{triplet}.tar.gz',
                f'python-{py}-{triplet}.tar.gz') for abi, triplet in TRIPLETS.items()}
            changed.append(f"python {pins['python']['version']} -> {py}")
            pins['python'] = {'version': py, 'sha256': shas}

        qjs = latest_quickjs()
        if qjs != pins['quickjs-ng']['version']:
            sha = download_sha256(f'https://github.com/quickjs-ng/quickjs/archive/refs/tags/v{qjs}.tar.gz',
                                  f'quickjs-ng-{qjs}.tar.gz')
            changed.append(f"quickjs-ng {pins['quickjs-ng']['version']} -> {qjs}")
            pins['quickjs-ng'] = {'version': qjs, 'sha256': sha}

        ff = latest_ffmpeg(pins['ffmpeg']['version'])
        if ff != pins['ffmpeg']['version']:
            sha = download_sha256(f'https://ffmpeg.org/releases/ffmpeg-{ff}.tar.xz', f'ffmpeg-{ff}.tar.xz')
            changed.append(f"ffmpeg {pins['ffmpeg']['version']} -> {ff}")
            pins['ffmpeg'] = {'version': ff, 'sha256': sha}
    return changed


def run(*cmd: str) -> None:
    print(f'\n$ {" ".join(cmd)}', flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['check', 'update'])
    parser.add_argument('--channel', choices=['stable', 'nightly'], default='stable')
    parser.add_argument('--ytdlp', metavar='VERSION', help='pin this yt-dlp version instead of the newest')
    parser.add_argument('--all', action='store_true', help='also update Python, QuickJS-ng and ffmpeg')
    parser.add_argument('--no-build', action='store_true', help='only edit runtime-versions.json')
    parser.add_argument('--full', action='store_true',
                        help='rebuild the native binaries too (default: only when native pins changed)')
    args = parser.parse_args()

    pins = json.loads(PINS_FILE.read_text(encoding='utf-8'))
    if args.command == 'check':
        print(f'Pinned in {PINS_FILE.name} vs upstream:')
        print_rows(check(pins, args.channel))
        return

    changed = update(pins, args)
    if not changed:
        print('Already up to date.')
        return
    PINS_FILE.write_text(json.dumps(pins, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Updated runtime-versions.json:\n  ' + '\n  '.join(changed))
    if args.no_build:
        return

    native = args.full or any(c.split()[0] in ('python', 'quickjs-ng', 'ffmpeg') for c in changed)
    try:
        run(sys.executable, 'scripts/build_android_runtime.py', *([] if native else ['--python-only']))
        run(sys.executable, 'scripts/host_smoke_test.py', '--', '--list-extractors')
        run(sys.executable, 'scripts/test_runner_retry.py')
    except subprocess.CalledProcessError:
        print('\nBuild or tests failed with the new versions. runtime-versions.json keeps the new pins;'
              ' `git checkout runtime-versions.json` to go back.')
        sys.exit(1)
    if not native:
        print('\nNote: only the Python bundle was rebuilt; jniLibs from before still apply. '
              'Run `npm run build:runtime` for a full build before publishing.')
    print('\nDone. Try a real download too:  npm run test:online')


if __name__ == '__main__':
    main()
