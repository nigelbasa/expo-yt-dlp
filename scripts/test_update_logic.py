#!/usr/bin/env python3
"""Runs the app-side update logic (UpdateFeed.kt, UpdateStore.kt) on the host JVM against
feeds signed by scripts/publish_bundle.py, so both ends of the signature format are tested
together.

  python scripts/test_update_logic.py

Needs `kotlinc` and `java` on PATH. org.json (which Android provides on a device) is
downloaded once to .cache/downloads and checked against a pinned hash.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLISH = ROOT / 'scripts' / 'publish_bundle.py'
SOURCES = ROOT / 'android' / 'src' / 'main' / 'java' / 'expo' / 'modules' / 'ytdlp'
ORG_JSON = ('https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar',
            '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796')


def org_json() -> Path:
    url, sha256 = ORG_JSON
    dest = ROOT / '.cache' / 'downloads' / url.rsplit('/', 1)[1]
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f'downloading {url}')
        with urllib.request.urlopen(url) as resp:
            dest.write_bytes(resp.read())
    actual = hashlib.sha256(dest.read_bytes()).hexdigest()
    if actual != sha256:
        dest.unlink()
        raise SystemExit(f'sha256 mismatch for {url}: {actual}')
    return dest


def publish(*args: str) -> None:
    subprocess.run([sys.executable, str(PUBLISH), *args], check=True, capture_output=True)


def main() -> None:
    if not shutil.which('kotlinc'):
        raise SystemExit('kotlinc not found on PATH')
    jar = org_json()
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        cfg, feed = d / 'cfg.json', d / 'feed.json'
        cfg.write_text(json.dumps({'url': '', 'keys': [], 'backupKeys': []}))
        publish('keygen', '--role', 'ci', '--out', str(d / 'ci.pem'), '--config', str(cfg))
        publish('keygen', '--role', 'ci', '--out', str(d / 'ci2.pem'), '--config', str(cfg))
        publish('keygen', '--role', 'backup', '--out', str(d / 'backup.pem'), '--config', str(cfg))
        keys = json.loads(cfg.read_text())
        (d / 'ci.hex').write_text(keys['keys'][0])
        (d / 'ci2.hex').write_text(keys['keys'][1])
        (d / 'backup.hex').write_text(keys['backupKeys'][0])

        (d / 'bundle.json').write_text(json.dumps({
            'runtimeApi': 1, 'bundleId': '0123456789abcdef', 'ytDlp': '2026.11.3',
            'extractors': ['YoutubeIE'], 'size': 1234, 'sha256': 'ab' * 32}))
        publish('add', '--feed', str(feed), '--bundle', str(d / 'bundle.json'),
                '--url', 'https://example.com/site-packages.zip')
        publish('sign', '--feed', str(feed), '--key', str(d / 'ci.pem'))

        # The same feed, re-signed by a new routine key that the backup key vouches for.
        shutil.copy(feed, d / 'r.json')
        publish('rotate', '--feed', str(d / 'r.json'), '--keys', keys['keys'][1], '--epoch', '1',
                '--key', str(d / 'backup.pem'))
        publish('sign', '--feed', str(d / 'r.json'), '--key', str(d / 'ci2.pem'))
        shutil.copy(d / 'r.json.sig', d / 'rotated.sig')

        shutil.copy(feed, d / 'expired.json')
        publish('refresh', '--feed', str(d / 'expired.json'), '--expires-days=-1')

        classes = d / 'classes'
        compile_ = subprocess.run(
            ['kotlinc', '-nowarn', '-cp', str(jar), '-d', str(classes),
             str(SOURCES / 'UpdateFeed.kt'), str(SOURCES / 'UpdateStore.kt'), str(SOURCES / 'Records.kt'),
             *map(str, sorted((ROOT / 'scripts' / 'kotlin').glob('*.kt')))],
            capture_output=True, text=True)
        if compile_.returncode != 0:
            raise SystemExit(compile_.stdout + compile_.stderr)
        stdlib = Path(shutil.which('kotlinc')).resolve().parent.parent / 'lib' / 'kotlin-stdlib.jar'
        run = subprocess.run(
            ['java', '-cp', f'{classes}:{jar}:{stdlib}', 'expo.modules.ytdlp.UpdateLogicTestKt', str(d)])
        sys.exit(run.returncode)


if __name__ == '__main__':
    main()
