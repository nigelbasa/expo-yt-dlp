#!/usr/bin/env python3
"""Run the *bundled* (trimmed, precompiled) yt-dlp on the host Python 3.14 and check that
every stdlib module it imported is still present in the stripped Android stdlib.

This catches the two ways a bump can silently break the phone build: the extractor trim
removing something a kept extractor needs, and the stdlib strip removing something yt-dlp
imports. It can't exercise the Android binaries themselves - use an emulator for that.

Usage (everything after -- is passed to yt-dlp):
  python scripts/host_smoke_test.py -- --list-extractors
  python scripts/host_smoke_test.py -- -J --js-runtimes quickjs:/path/to/qjs "https://youtu.be/jNQXAC9IVRw"

  # The download driver the Android module runs (its own flags, then -- and yt-dlp options):
  python scripts/host_smoke_test.py --runner -- --native-merge --max-res 720 -- -P out URL
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android' / 'src' / 'main' / 'assets' / 'expo-yt-dlp'
JNI_LIBS = ROOT / 'android' / 'src' / 'main' / 'jniLibs'
CACHE = ROOT / '.cache'

# Host-only modules that yt-dlp imports on Windows/macOS but never on Android.
HOST_ONLY = {'nt', 'winreg', '_winapi', 'msvcrt', '_overlapped', '_wmi', 'winsound', '_msi',
             '_scproxy', '_osx_support'}
FROZEN = {'_frozen_importlib', '_frozen_importlib_external', 'zipimport'}
# Stripped on purpose; every importer wraps them in try/except ImportError
# (yt_dlp.dependencies, uuid, shutil, zipfile, concurrent.futures).
OPTIONAL = {'sqlite3', 'sqlite3.dbapi2', '_sqlite3', '_uuid', '_interpreters',
            'compression.zstd', 'compression.zstd._zstdfile', '_zstd'}

# Records every module yt-dlp imported under its real name (skipping aliases such as
# os.path -> ntpath, whose spec name differs from the sys.modules key).
RUNNER = r'''
import atexit, json, runpy, sys
out = sys.argv.pop(1)
module = sys.argv.pop(1)
def dump():
    mods = [name for name, m in list(sys.modules.items())
            if getattr(getattr(m, "__spec__", None), "name", None) == name]
    open(out, "w").write(json.dumps(sorted(mods)))
atexit.register(dump)
sys.argv[0] = module
runpy.run_module(module, run_name="__main__", alter_sys=True)
'''


def zip_modules(path: Path) -> set[str]:
    mods = set()
    for name in zipfile.ZipFile(path).namelist():
        if not name.endswith('.pyc'):
            continue
        parts = name[:-4].split('/')
        if parts[-1] == '__init__':
            parts.pop()
        mods.add('.'.join(parts))
    return mods


def android_builtin_modules() -> set[str]:
    """Modules compiled into libpython, read from the release's config.c."""
    configs = list((CACHE / 'python-android').glob('*/prefix/lib/python3.*/config-*/config.c'))
    if not configs:
        raise SystemExit('run scripts/build_android_runtime.py first')
    return set(re.findall(r'\{"([\w.]+)",', configs[0].read_text()))


def main() -> None:
    argv = sys.argv[1:]
    module = 'yt_dlp'
    if argv[:1] == ['--runner']:
        module, argv = 'expo_yt_dlp_runner', argv[1:]
    if argv[:1] == ['--']:
        argv = argv[1:]
    if not argv:
        raise SystemExit(__doc__)

    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp) / 'site-packages'
        zipfile.ZipFile(ASSETS / 'site-packages.zip').extractall(site)
        mods_file = Path(tmp) / 'modules.json'
        env = {**os.environ, 'PYTHONPATH': str(site), 'PYTHONUTF8': '1'}
        # -S: no host site-packages, so optional deps installed on this machine (requests,
        # brotli, websockets...) can't mask what the Android bundle lacks.
        proc = subprocess.run([sys.executable, '-S', '-c', RUNNER, str(mods_file), module, *argv], env=env)
        imported = json.loads(mods_file.read_text()) if mods_file.exists() else []

    available = zip_modules(ASSETS / 'python-stdlib.zip') | android_builtin_modules() | FROZEN
    for abi_dir in JNI_LIBS.glob('*'):
        available |= {p.name[len('libpymod_'):-3] for p in abi_dir.glob('libpymod_*.so')}

    missing = sorted(
        m for m in imported
        if m.split('.')[0] in sys.stdlib_module_names
        and m.split('.')[0] not in HOST_ONLY
        and not m.startswith('encodings.')  # host console codecs (cp1252, mbcs...)
        and m not in available and m not in OPTIONAL)
    print(f'\n[smoke] yt-dlp exit code {proc.returncode}; {len(imported)} modules imported', file=sys.stderr)
    if missing:
        print(f'[smoke] MISSING from the Android stdlib bundle: {missing}', file=sys.stderr)
        sys.exit(1)
    print('[smoke] all imported stdlib modules are present in the Android bundle', file=sys.stderr)
    sys.exit(proc.returncode)


if __name__ == '__main__':
    main()
