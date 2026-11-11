#!/usr/bin/env python3
"""Maintain and sign the feed that installed apps read to find newer yt-dlp bundles.

A feed is two files served next to each other over HTTPS:

  feed.json       which bundle to use per runtimeApi (see docs/superpowers/specs)
  feed.json.sig   line 1: hex ECDSA P-256 / SHA-256 signature over the exact bytes of feed.json
                  line 2 (only after a key rotation): "<epoch> <key>,<key> <signature>", a
                  backup-key signature that replaces the routine signing keys pinned in apps

Typical release, after `python scripts/build_android_runtime.py --bundle-only`:

  python scripts/publish_bundle.py add --feed feed.json --bundle dist/bundle/bundle.json \\
      --url https://github.com/<owner>/<repo>/releases/download/<tag>/site-packages.zip
  python scripts/publish_bundle.py sign --feed feed.json          # key from $EYD_SIGNING_KEY
  python scripts/publish_bundle.py verify --feed feed.json

Other commands:

  keygen --role ci|backup --out KEY.pem   new key pair; the public key is added to update-feed.json
  refresh --feed feed.json                no content change, new sequence and expiry
  plan --feed feed.json --bundle B.json   prints what a scheduled run should do: `publish` (the
                                          bundle is newer than the feed's), `refresh` (the feed
                                          expires soon) or `none`
  revoke --feed feed.json BUNDLE_ID       tell apps to stop using a bundle
  rotate --feed feed.json --keys HEX... --key BACKUP.pem
                                          replace the routine signing keys (after a leak). Writes
                                          feed.json.keys, which `sign` then attaches to every feed

Every command that edits the feed raises `sequence` and renews `expires`, so it must be signed
again afterwards. Signing uses the `openssl` command line tool; nothing else is needed.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FEED_CONFIG = ROOT / 'update-feed.json'
SCHEMA = 1
TIME_FORMAT = '%Y-%m-%dT%H:%M:%SZ'
KEY_ENV = 'EYD_SIGNING_KEY'
ENTRY_FIELDS = ('runtimeApi', 'bundleId', 'ytDlp', 'extractors', 'url', 'size', 'sha256')


def openssl(*args: str, stdin: bytes | None = None) -> bytes:
    try:
        proc = subprocess.run(['openssl', *args], input=stdin, capture_output=True)
    except FileNotFoundError:
        raise SystemExit('the `openssl` command line tool is required')
    if proc.returncode != 0:
        raise SystemExit(f'openssl {args[0]} failed: {proc.stderr.decode(errors="replace").strip()}')
    return proc.stdout


def public_key_hex(private_key: Path) -> str:
    """X.509 SubjectPublicKeyInfo (DER) as hex: what java.security's X509EncodedKeySpec reads."""
    return openssl('pkey', '-in', str(private_key), '-pubout', '-outform', 'DER').hex()


# ---------------------------------------------------------------------------
# Feed document
# ---------------------------------------------------------------------------

def load_feed(path: Path) -> dict:
    if not path.exists():
        return {'schema': SCHEMA, 'sequence': 0, 'published': '', 'expires': '',
                'shells': {}, 'bundles': [], 'revoked': []}
    feed = json.loads(path.read_text(encoding='utf-8'))
    if feed.get('schema') != SCHEMA:
        raise SystemExit(f'{path}: unsupported feed schema {feed.get("schema")}')
    return feed


def save_feed(path: Path, feed: dict, expires_days: int) -> None:
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    feed['sequence'] += 1
    feed['published'] = now.strftime(TIME_FORMAT)
    feed['expires'] = (now + dt.timedelta(days=expires_days)).strftime(TIME_FORMAT)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(feed, indent=2) + '\n', encoding='utf-8', newline='\n')
    # A signature made for the previous bytes must not sit next to the new feed.
    sig_path(path).unlink(missing_ok=True)
    print(f'{path}: sequence {feed["sequence"]}, expires {feed["expires"]} (unsigned)')


def sig_path(feed: Path) -> Path:
    return feed.with_name(feed.name + '.sig')


def delegation_path(feed: Path) -> Path:
    return feed.with_name(feed.name + '.keys')


def package_version() -> str:
    return json.loads((ROOT / 'package.json').read_text(encoding='utf-8'))['version']


def add_bundle(feed: dict, entry: dict, shell_version: str) -> None:
    missing = [f for f in ENTRY_FIELDS if f not in entry]
    if missing:
        raise SystemExit(f'bundle entry is missing {missing}')
    if not entry['url'].startswith('https://'):
        raise SystemExit('bundle url must be https://')
    if entry['bundleId'] in feed['revoked']:
        raise SystemExit(f'bundle {entry["bundleId"]} is revoked')
    api = entry['runtimeApi']
    feed['bundles'] = sorted(
        [b for b in feed['bundles'] if b['runtimeApi'] != api] + [{f: entry[f] for f in ENTRY_FIELDS}],
        key=lambda b: b['runtimeApi'])

    # `shells` is for people and tools: which package versions have which runtimeApi. A new
    # runtimeApi starts at the current package version and closes the range before it.
    shells = feed['shells']
    if str(api) not in shells:
        previous = str(api - 1)
        if previous in shells and '<' not in shells[previous]:
            shells[previous] += f' <{shell_version}'
        shells[str(api)] = f'>={shell_version}'


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split('.'))


def plan(feed: dict, bundle: dict, refresh_before_days: int) -> str:
    listed = next((b for b in feed['bundles'] if b['runtimeApi'] == bundle['runtimeApi']), None)
    if bundle['bundleId'] not in feed['revoked'] and (
            listed is None or version_key(bundle['ytDlp']) > version_key(listed['ytDlp'])):
        return 'publish'
    if not feed['expires']:
        return 'none'
    expires = dt.datetime.strptime(feed['expires'], TIME_FORMAT).replace(tzinfo=dt.timezone.utc)
    soon = dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=refresh_before_days)
    return 'refresh' if expires < soon else 'none'


# ---------------------------------------------------------------------------
# Signing
# ---------------------------------------------------------------------------

def sign_bytes(data: bytes, key_path: Path | None) -> tuple[str, str]:
    """Returns (signature hex, public key hex). The key comes from the file or $EYD_SIGNING_KEY."""
    with tempfile.TemporaryDirectory() as tmp:
        if key_path is None:
            pem = os.environ.get(KEY_ENV)
            if not pem:
                raise SystemExit(f'pass --key or set ${KEY_ENV} to the private key (PEM)')
            key_path = Path(tmp) / 'key.pem'
            key_path.touch(mode=0o600)
            key_path.write_text(pem if pem.endswith('\n') else pem + '\n')
        return openssl('dgst', '-sha256', '-sign', str(key_path), stdin=data).hex(), public_key_hex(key_path)


def verify_bytes(data: bytes, signature: str, public_keys: list[str]) -> str | None:
    """Returns the key that made the signature, or None."""
    with tempfile.TemporaryDirectory() as tmp:
        sig_file = Path(tmp) / 'sig'
        sig_file.write_bytes(bytes.fromhex(signature))
        for key in public_keys:
            key_file = Path(tmp) / 'pub.der'
            key_file.write_bytes(bytes.fromhex(key))
            proc = subprocess.run(
                ['openssl', 'dgst', '-sha256', '-verify', str(key_file), '-keyform', 'DER',
                 '-signature', str(sig_file)], input=data, capture_output=True)
            if proc.returncode == 0:
                return key
    return None


def sign(feed_path: Path, key_path: Path | None) -> str:
    signature, public = sign_bytes(feed_path.read_bytes(), key_path)
    lines = [signature]
    if delegation_path(feed_path).exists():
        lines.append(delegation_path(feed_path).read_text(encoding='utf-8').strip())
    sig_path(feed_path).write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    return public


def verify(feed_path: Path, keys: list[str], backup_keys: list[str]) -> str | None:
    """The same check the app does. Returns the key that signed the feed, or None."""
    lines = sig_path(feed_path).read_text(encoding='utf-8').split('\n')
    if len(lines) > 1 and lines[1].strip():
        epoch, delegated, signature = lines[1].split(' ')
        if verify_bytes(f'{epoch} {delegated}'.encode(), signature, backup_keys) is None:
            raise SystemExit(f'{feed_path}: key rotation line is not signed by a backup key')
        keys = delegated.split(',')
    return verify_bytes(feed_path.read_bytes(), lines[0].strip(), keys)


def rotate(feed_path: Path, keys: list[str], epoch: int, key_path: Path | None) -> None:
    for key in keys:
        bytes.fromhex(key)
    message = f'{epoch} {",".join(keys)}'
    signature, _ = sign_bytes(message.encode(), key_path)
    delegation_path(feed_path).write_text(f'{message} {signature}\n', encoding='utf-8', newline='\n')
    print(f'{delegation_path(feed_path)}: epoch {epoch}, {len(keys)} key(s). '
          'Now sign the feed with one of the new keys.')


def keygen(role: str, out: Path, config: Path) -> None:
    if out.exists():
        raise SystemExit(f'{out} already exists; refusing to overwrite a private key')
    out.parent.mkdir(parents=True, exist_ok=True)
    openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', str(out))
    out.chmod(stat.S_IRUSR | stat.S_IWUSR)
    public = public_key_hex(out)
    cfg = json.loads(config.read_text(encoding='utf-8'))
    cfg['keys' if role == 'ci' else 'backupKeys'].append(public)
    config.write_text(json.dumps(cfg, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(f'private key: {out}  (keep it out of git)')
    print(f'public key added to {config.name} as a {role} key:\n  {public}')
    if role == 'ci':
        print(f'store the private key as the {KEY_ENV} secret of the publishing workflow, then delete the file')
    else:
        print('move the private key to offline storage; it is only needed to replace a leaked CI key')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)

    def feed_command(name: str, edits: bool = True) -> argparse.ArgumentParser:
        p = sub.add_parser(name)
        p.add_argument('--feed', type=Path, required=True)
        if edits:
            p.add_argument('--expires-days', type=int, default=30)
        return p

    p = feed_command('add')
    p.add_argument('--bundle', type=Path, required=True, help='bundle.json from --bundle-only')
    p.add_argument('--url', required=True, help='https URL the zip will be served from')
    p.add_argument('--shell-version', default=None,
                   help='package version that introduced this runtimeApi (default: package.json)')
    feed_command('refresh')
    p = feed_command('plan', edits=False)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--refresh-before-days', type=int, default=21)
    p = feed_command('revoke')
    p.add_argument('bundle_id')
    p = feed_command('rotate', edits=False)
    p.add_argument('--keys', nargs='+', required=True, metavar='HEX', help='the new routine public keys')
    p.add_argument('--epoch', type=int, required=True, help='1 for the first rotation, then 2, 3...')
    p.add_argument('--key', type=Path, help=f'backup private key PEM (default: ${KEY_ENV})')
    p = feed_command('sign', edits=False)
    p.add_argument('--key', type=Path, help=f'private key PEM (default: ${KEY_ENV})')
    p = feed_command('verify', edits=False)
    p.add_argument('--public-key', action='append', metavar='HEX',
                   help='routine key to accept (repeatable; default: keys in update-feed.json)')
    p = sub.add_parser('keygen')
    p.add_argument('--role', choices=['ci', 'backup'], required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--config', type=Path, default=FEED_CONFIG)
    args = parser.parse_args()

    if args.command == 'keygen':
        keygen(args.role, args.out, args.config)
    elif args.command == 'sign':
        public = sign(args.feed, args.key)
        print(f'{sig_path(args.feed)}: signed with {public[-16:]}')
    elif args.command == 'plan':
        print(plan(load_feed(args.feed), json.loads(args.bundle.read_text(encoding='utf-8')),
                   args.refresh_before_days))
    elif args.command == 'rotate':
        rotate(args.feed, args.keys, args.epoch, args.key)
    elif args.command == 'verify':
        cfg = json.loads(FEED_CONFIG.read_text(encoding='utf-8'))
        signer = verify(args.feed, args.public_key or cfg['keys'], cfg['backupKeys'])
        if signer is None:
            raise SystemExit(f'{args.feed}: signature does NOT match a trusted key')
        feed = load_feed(args.feed)
        print(f'{args.feed}: valid, sequence {feed["sequence"]}, signed with ...{signer[-16:]}')
    else:
        feed = load_feed(args.feed)
        if args.command == 'add':
            entry = json.loads(args.bundle.read_text(encoding='utf-8')) | {'url': args.url}
            add_bundle(feed, entry, args.shell_version or package_version())
        elif args.command == 'revoke':
            if args.bundle_id not in feed['revoked']:
                feed['revoked'].append(args.bundle_id)
            feed['bundles'] = [b for b in feed['bundles'] if b['bundleId'] != args.bundle_id]
        save_feed(args.feed, feed, args.expires_days)


if __name__ == '__main__':
    sys.exit(main())
