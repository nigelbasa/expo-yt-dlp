#!/usr/bin/env python3
"""Offline tests for scripts/publish_bundle.py: feed edits, signing, verification, key rotation.

  python scripts/test_publish_bundle.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / 'publish_bundle.py'


def run(*args: str, ok: bool = True) -> str:
    proc = subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)
    if (proc.returncode == 0) != ok:
        raise AssertionError(f'{" ".join(args)} -> exit {proc.returncode}\n{proc.stdout}{proc.stderr}')
    return proc.stdout + proc.stderr


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        cfg, feed = d / 'cfg.json', d / 'feed.json'
        cfg.write_text(json.dumps({'url': 'https://example.com/feed.json', 'keys': [], 'backupKeys': []}))
        run('keygen', '--role', 'ci', '--out', str(d / 'ci.pem'), '--config', str(cfg))
        run('keygen', '--role', 'backup', '--out', str(d / 'backup.pem'), '--config', str(cfg))
        run('keygen', '--role', 'ci', '--out', str(d / 'ci2.pem'), '--config', str(cfg))
        run('keygen', '--role', 'ci', '--out', str(d / 'ci.pem'), '--config', str(cfg), ok=False)  # no overwrite
        keys = json.loads(cfg.read_text())
        ci, ci2 = keys['keys']

        def bundle(api: int, version: str, bundle_id: str) -> str:
            path = d / f'bundle-{bundle_id}.json'
            path.write_text(json.dumps({
                'runtimeApi': api, 'bundleId': bundle_id, 'ytDlp': version,
                'extractors': ['YoutubeIE'], 'size': 10, 'sha256': '00' * 32}))
            return str(path)

        def verify(key: str, ok: bool = True) -> str:
            # Backup keys come from the repo's update-feed.json, which has none in this test,
            # so only the routine-key path is checked through the CLI.
            return run('verify', '--feed', str(feed), '--public-key', key, ok=ok)

        run('add', '--feed', str(feed), '--bundle', bundle(1, '2026.11.3', 'aaaa'),
            '--url', 'https://example.com/a.zip', '--shell-version', '0.2.0')
        assert not (d / 'feed.json.sig').exists()
        run('sign', '--feed', str(feed), '--key', str(d / 'ci.pem'))
        verify(ci)
        verify(ci2, ok=False)

        # Any change to the bytes invalidates the signature.
        original = feed.read_text()
        feed.write_text(original.replace('2026.11.3', '2026.11.4'))
        verify(ci, ok=False)
        feed.write_text(original)
        verify(ci)

        # A newer bundle for the same runtimeApi replaces the old one; editing drops the signature.
        run('add', '--feed', str(feed), '--bundle', bundle(1, '2026.12.1', 'bbbb'),
            '--url', 'https://example.com/b.zip')
        assert not (d / 'feed.json.sig').exists()
        run('add', '--feed', str(feed), '--bundle', bundle(2, '2026.12.1', 'cccc'),
            '--url', 'https://example.com/c.zip', '--shell-version', '0.5.0')
        run('add', '--feed', str(feed), '--bundle', bundle(2, '2026.12.1', 'dddd'),
            '--url', 'http://example.com/d.zip', ok=False)  # https only
        doc = json.loads(feed.read_text())
        assert doc['sequence'] == 3, doc
        assert [b['bundleId'] for b in doc['bundles']] == ['bbbb', 'cccc'], doc
        assert doc['shells'] == {'1': '>=0.2.0 <0.5.0', '2': '>=0.5.0'}, doc
        assert doc['expires'] > doc['published']

        # What a scheduled run should do with a freshly built bundle.
        def plan(*args: str) -> str:
            return run('plan', '--feed', str(feed), *args).strip()
        assert plan('--bundle', bundle(1, '2026.12.1', 'eeee')) == 'none'      # same version listed
        assert plan('--bundle', bundle(1, '2026.12.10', 'eeee')) == 'publish'  # newer (numeric compare)
        assert plan('--bundle', bundle(3, '2026.1.1', 'eeee')) == 'publish'    # runtimeApi not listed yet
        assert plan('--bundle', bundle(1, '2026.12.1', 'eeee'), '--refresh-before-days', '40') == 'refresh'
        assert run('plan', '--feed', str(d / 'new.json'), '--bundle', bundle(1, '2026.1.1', 'ffff')).strip() == 'publish'

        run('revoke', '--feed', str(feed), 'bbbb')
        doc = json.loads(feed.read_text())
        assert doc['revoked'] == ['bbbb'] and [b['bundleId'] for b in doc['bundles']] == ['cccc']
        run('add', '--feed', str(feed), '--bundle', bundle(1, '2026.12.1', 'bbbb'),
            '--url', 'https://example.com/b.zip', ok=False)  # revoked ids can't come back
        run('refresh', '--feed', str(feed))
        assert json.loads(feed.read_text())['sequence'] == 5

        # The key can also come from the environment (how CI passes it).
        proc = subprocess.run(
            [sys.executable, str(SCRIPT), 'sign', '--feed', str(feed)], capture_output=True, text=True,
            env={'PATH': __import__('os').environ['PATH'], 'EYD_SIGNING_KEY': (d / 'ci.pem').read_text()})
        assert proc.returncode == 0, proc.stderr
        verify(ci)

        # Rotation: a backup-signed line names the new routine keys and travels in the .sig file.
        sys.path.insert(0, str(SCRIPT.parent))
        import publish_bundle
        run('rotate', '--feed', str(feed), '--keys', ci2, '--epoch', '1', '--key', str(d / 'backup.pem'))
        run('sign', '--feed', str(feed), '--key', str(d / 'ci2.pem'))
        assert len((d / 'feed.json.sig').read_text().strip().split('\n')) == 2
        assert publish_bundle.verify(feed, [ci], keys['backupKeys']) == ci2  # pinned key replaced
        run('sign', '--feed', str(feed), '--key', str(d / 'ci.pem'))  # the leaked key
        assert publish_bundle.verify(feed, [ci], keys['backupKeys']) is None
        (d / 'feed.json.keys').write_text((d / 'feed.json.keys').read_text().replace('1 ', '2 ', 1))
        run('sign', '--feed', str(feed), '--key', str(d / 'ci2.pem'))
        try:
            publish_bundle.verify(feed, [ci], keys['backupKeys'])
        except SystemExit:
            pass
        else:
            raise AssertionError('a tampered rotation line was accepted')

    print('publish_bundle: all checks passed')


if __name__ == '__main__':
    main()
