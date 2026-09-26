#!/usr/bin/env python3
"""Run the generated Android runtime on a connected device/emulator, without building an app.

Pushes jniLibs/<abi> and the extracted asset zips to /data/local/tmp/expo-yt-dlp, recreates
the lib-dynload symlinks, and runs yt-dlp with the same environment and fixed flags that
YtDlpRuntime.kt uses. Needs `adb` (from the Android SDK platform-tools) and one device.

Usage (everything after -- is passed to yt-dlp):
  python scripts/device_smoke_test.py -- --version
  python scripts/device_smoke_test.py -- -J "https://www.youtube.com/watch?v=jNQXAC9IVRw"
"""
from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android' / 'src' / 'main' / 'assets' / 'expo-yt-dlp'
JNI_LIBS = ROOT / 'android' / 'src' / 'main' / 'jniLibs'
DEVICE_DIR = '/data/local/tmp/expo-yt-dlp'


def adb_path() -> str:
    exe = 'adb.exe' if sys.platform == 'win32' else 'adb'
    for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(var) and (Path(os.environ[var]) / 'platform-tools' / exe).exists():
            return str(Path(os.environ[var]) / 'platform-tools' / exe)
    if sys.platform == 'win32' and (p := Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools' / exe).exists():
        return str(p)
    return shutil.which('adb') or sys.exit('adb not found; set ANDROID_HOME')


ADB = adb_path()


def adb(*args: str, capture: bool = False) -> str:
    result = subprocess.run([ADB, *args], check=True, text=True, capture_output=capture)
    return result.stdout.strip() if capture else ''


def main() -> None:
    argv = sys.argv[1:]
    if argv[:1] == ['--']:
        argv = argv[1:]
    if not argv:
        raise SystemExit(__doc__)

    manifest = json.loads((ASSETS / 'manifest.json').read_text())
    py_minor = '.'.join(manifest['python'].split('.')[:2])
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist', capture=True).split(',')
    abi = next((a for a in abis if (JNI_LIBS / a).is_dir()), None) or sys.exit(f'no runtime for device ABIs {abis}')
    print(f'[device] ABI {abi}, bundle {manifest["bundleId"]}', file=sys.stderr)

    stamp = f'{DEVICE_DIR}/.bundle-{manifest["bundleId"]}-{abi}'
    if adb('shell', f'[ -e {stamp} ] && echo yes || true', capture=True) != 'yes':
        with tempfile.TemporaryDirectory() as tmp:
            staging = Path(tmp) / 'expo-yt-dlp'
            zipfile.ZipFile(ASSETS / 'python-stdlib.zip').extractall(staging / 'python' / 'lib' / f'python{py_minor}')
            zipfile.ZipFile(ASSETS / 'site-packages.zip').extractall(staging / 'site-packages')
            shutil.copytree(JNI_LIBS / abi, staging / 'lib')
            adb('shell', f'rm -rf {DEVICE_DIR}')
            adb('push', str(staging), '/data/local/tmp/')
        dynload = f'{DEVICE_DIR}/python/lib/python{py_minor}/lib-dynload'
        suffix = manifest['abis'][abi]['extSuffix']  # same naming as YtDlpRuntime.linkExtensionModules
        adb('shell', f'mkdir -p {dynload} && cd {DEVICE_DIR}/lib && chmod 755 *.so && '
                     f'for f in libpymod_*.so; do m=${{f#libpymod_}}; ln -sf {DEVICE_DIR}/lib/$f {dynload}/${{m%.so}}{suffix}; done && '
                     f'mkdir -p {DEVICE_DIR}/tmp {DEVICE_DIR}/home && touch {stamp}')

    d = DEVICE_DIR
    env = (f'PYTHONHOME={d}/python PYTHONPATH={d}/site-packages LD_LIBRARY_PATH={d}/lib '
           f'PYTHONUNBUFFERED=1 PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONNOUSERSITE=1 '
           f'TMPDIR={d}/tmp HOME={d}/home SSL_CERT_FILE={d}/site-packages/certifi/cacert.pem LANG=C.UTF-8')
    fixed = ['--ignore-config', '--color', 'never', '--cache-dir', f'{d}/tmp/cache',
             '--no-js-runtimes', '--js-runtimes', f'quickjs:{d}/lib/libqjs.so']
    if manifest['abis'].get(abi, {}).get('ffmpeg'):
        fixed += ['--ffmpeg-location', f'{d}/lib/libffmpeg.so']
    command = f'cd {d}/tmp && {env} {d}/lib/libytdlp_python.so -m yt_dlp {shlex.join(fixed + argv)}'
    sys.exit(subprocess.run([ADB, 'shell', command]).returncode)


if __name__ == '__main__':
    main()
