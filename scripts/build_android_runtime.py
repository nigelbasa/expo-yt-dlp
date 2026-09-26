#!/usr/bin/env python3
"""Build the Android runtime that expo-yt-dlp ships inside its npm package.

Outputs (all generated, git-ignored, included in the npm tarball):

  android/src/main/jniLibs/<abi>/
      libpython3.14.so, libssl_python.so, libcrypto_python.so   official python.org build
      libpymod_<name>.so     stdlib C extensions (lib-dynload), renamed so Android installs them
      libytdlp_python.so     tiny `python` executable (native/python-launcher)
      libqjs.so              QuickJS-ng, the JS runtime yt-dlp uses for YouTube challenges
      libffmpeg.so, libffprobe.so   only if built with scripts/build_ffmpeg_android.sh

  android/src/main/assets/expo-yt-dlp/
      python-stdlib.zip      stripped stdlib, precompiled to sourceless .pyc
      site-packages.zip      yt-dlp (extractors trimmed), yt-dlp-ejs, certifi and the
                             download driver (python/expo_yt_dlp_runner.py), precompiled
      manifest.json          versions + bundle id the Kotlin side uses to version extraction

Everything is compiled to .pyc, so this must run on the same Python minor version as
the Android runtime (3.14).

Usage:
  python scripts/build_android_runtime.py                 # both ABIs, everything
  python scripts/build_android_runtime.py --abi arm64-v8a
  python scripts/build_android_runtime.py --python-only   # skip the NDK builds
"""
from __future__ import annotations

import argparse
import ast
import compileall
import hashlib
import json
import os
import py_compile
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

# ---------------------------------------------------------------------------
# Pinned inputs live in runtime-versions.json (see scripts/update_deps.py).
# ---------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parent.parent
PINS = json.loads((ROOT / 'runtime-versions.json').read_text(encoding='utf-8'))

PYTHON_VERSION = PINS['python']['version']
PY_MINOR = '.'.join(PYTHON_VERSION.split('.')[:2])
TRIPLETS = {'arm64-v8a': 'aarch64-linux-android', 'x86_64': 'x86_64-linux-android'}
PYTHON_ANDROID = {abi: (TRIPLETS[abi], sha) for abi, sha in PINS['python']['sha256'].items()}
ANDROID_API = 24  # same minimum as the python.org build

# PyPI name -> (version, sha256 of the py3-none-any wheel). yt-dlp-ejs must match the
# `yt-dlp-ejs==` pin in yt-dlp's own metadata (checked below).
WHEELS = {name: (w['version'], w['sha256']) for name, w in PINS['wheels'].items()}

QUICKJS_NG = (PINS['quickjs-ng']['version'], PINS['quickjs-ng']['sha256'])

# yt-dlp extractor modules to keep (yt_dlp/extractor/<name>.py or <name>/ package).
EXTRACTORS = PINS['extractors']
# Always kept: GenericIE is registered unconditionally as the fallback, and it finds plain
# <video>/<audio> tags through the embed extractors in genericembeds (HTML5MediaEmbedIE,
# QuotedHTMLIE), which it only sees if they are registered.
ALWAYS_KEEP = ['generic', 'genericembeds']

# Stdlib paths (relative to lib/python3.14) that yt-dlp never needs on a phone.
STDLIB_REMOVE = [
    'test', 'idlelib', 'tkinter', 'turtledemo', 'turtle.py', 'ensurepip', 'venv',
    'pydoc_data', 'pydoc.py', '_pyrepl', 'curses', 'dbm', 'sqlite3', 'xmlrpc', 'wsgiref',
    'unittest', 'doctest.py', 'pdb.py', 'profile.py', 'cProfile.py', 'pstats.py',
    'timeit.py', 'tabnanny.py', 'trace.py', 'lib2to3', '__phello__', 'site-packages',
    'multiprocessing', 'concurrent/futures/process.py', 'compression/zstd',
    '_pydecimal.py', '_pyio.py',
]
STDLIB_REMOVE_GLOBS = ['config-*', '**/__pycache__']

# lib-dynload C extensions to drop: test/dev modules, sqlite (browser-cookie import only),
# zstd (2 MB, unused), CJK codecs (none of the target sites need them).
LIB_DYNLOAD_REMOVE = re.compile(
    r'^(_test.*|xx.*|_xxtestfuzz|_ctypes_test|_remote_debugging|_lsprof|_interp.*'
    r'|_sqlite3|_zstd|_codecs_(cn|hk|iso2022|jp|kr|tw)|_multibytecodec)$')

# Native libraries from the python.org prefix/lib that go into jniLibs.
PYTHON_SHARED_LIBS = [f'libpython{PY_MINOR}.so', 'libssl_python.so', 'libcrypto_python.so']

CACHE = ROOT / '.cache'
JNI_LIBS = ROOT / 'android' / 'src' / 'main' / 'jniLibs'
ASSETS = ROOT / 'android' / 'src' / 'main' / 'assets' / 'expo-yt-dlp'
LAUNCHER_SRC = ROOT / 'native' / 'python-launcher'
RUNNER = ROOT / 'python' / 'expo_yt_dlp_runner.py'  # download driver the Kotlin side runs

# Paths baked into .pyc files (shown in tracebacks); purely cosmetic.
STDLIB_DDIR = f'/expo-yt-dlp/python/lib/python{PY_MINOR}'
SITE_DDIR = '/expo-yt-dlp/site-packages'


def log(msg: str) -> None:
    print(f'[build-runtime] {msg}', flush=True)


# ---------------------------------------------------------------------------
# Downloads
# ---------------------------------------------------------------------------

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def fetch(url: str, sha256: str, dest: Path) -> Path:
    if dest.exists() and sha256_file(dest) == sha256:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    log(f'downloading {url}')
    tmp = dest.with_suffix(dest.suffix + '.part')
    with urllib.request.urlopen(url) as resp, tmp.open('wb') as out:
        shutil.copyfileobj(resp, out)
    actual = sha256_file(tmp)
    if actual != sha256:
        tmp.unlink()
        raise SystemExit(f'sha256 mismatch for {url}\n  expected {sha256}\n  got      {actual}')
    tmp.replace(dest)
    return dest


def fetch_wheel(name: str, version: str, sha256: str) -> Path:
    cached = CACHE / 'downloads' / f'{name}-{version}.whl'
    if cached.exists() and sha256_file(cached) == sha256:
        return cached
    with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json') as resp:
        meta = json.load(resp)
    for f in meta['urls']:
        if f['packagetype'] == 'bdist_wheel' and f['digests']['sha256'] == sha256:
            return fetch(f['url'], sha256, cached)
    raise SystemExit(f'no wheel for {name}=={version} with sha256 {sha256} on PyPI')


def fetch_python_android(abi: str) -> Path:
    """Download and unpack the python.org Android release; returns its prefix/ dir."""
    triplet, sha256 = PYTHON_ANDROID[abi]
    name = f'python-{PYTHON_VERSION}-{triplet}.tar.gz'
    archive = fetch(f'https://www.python.org/ftp/python/{PYTHON_VERSION}/{name}', sha256,
                    CACHE / 'downloads' / name)
    out = CACHE / 'python-android' / f'{PYTHON_VERSION}-{abi}'
    stamp = out / '.complete'
    if not stamp.exists():
        shutil.rmtree(out, ignore_errors=True)
        with tarfile.open(archive) as tar:
            # Symlinks are skipped: they are only unversioned aliases we don't ship,
            # and creating them fails on Windows without developer mode.
            members = [m for m in tar.getmembers() if not (m.issym() or m.islnk())]
            tar.extractall(out, members=members, filter='data')
        stamp.touch()
    return out / 'prefix'


def fetch_quickjs() -> Path:
    version, sha256 = QUICKJS_NG
    archive = fetch(f'https://github.com/quickjs-ng/quickjs/archive/refs/tags/v{version}.tar.gz',
                    sha256, CACHE / 'downloads' / f'quickjs-ng-{version}.tar.gz')
    out = CACHE / 'src'
    src = out / f'quickjs-{version}'
    if not (src / 'CMakeLists.txt').exists():
        with tarfile.open(archive) as tar:
            tar.extractall(out, filter='data')
    return src


# ---------------------------------------------------------------------------
# Python bundles
# ---------------------------------------------------------------------------

def compile_sourceless(root: Path, ddir: str) -> None:
    """Compile every .py to a .pyc next to it, then delete the sources.

    Hash-based pycs keep the output identical for identical sources (timestamp pycs embed
    the unpack time), so the bundle id only changes when the Python code does.
    """
    if not compileall.compile_dir(str(root), ddir=ddir, legacy=True, force=True, quiet=1, workers=0,
                                  invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH):
        raise SystemExit(f'compileall failed under {root}')
    for py in root.rglob('*.py'):
        if py.with_suffix('.pyc').exists():
            py.unlink()
    for cache_dir in list(root.rglob('__pycache__')):
        shutil.rmtree(cache_dir)


def write_zip(src_dir: Path, dest: Path) -> None:
    """Deterministic zip (sorted entries, fixed timestamps) so bundle ids are stable."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    files = sorted(p for p in src_dir.rglob('*') if p.is_file())
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(src_dir).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, path.read_bytes())


def build_stdlib(prefixes: dict[str, Path], work: Path) -> Path:
    """Pure-Python stdlib shared by all ABIs (C extensions go to jniLibs instead)."""
    first = next(iter(prefixes.values()))
    src = first / 'lib' / f'python{PY_MINOR}'
    dest = work / 'stdlib'
    shutil.rmtree(dest, ignore_errors=True)
    shutil.copytree(src, dest, ignore=shutil.ignore_patterns('lib-dynload'))

    for rel in STDLIB_REMOVE:
        p = dest / rel
        if p.is_dir():
            shutil.rmtree(p)
        elif p.exists():
            p.unlink()
    for pattern in STDLIB_REMOVE_GLOBS:
        for p in list(dest.glob(pattern)):
            shutil.rmtree(p) if p.is_dir() else p.unlink()

    # _sysconfigdata is the only ABI-specific pure-Python module; ship one per ABI.
    for prefix in prefixes.values():
        for f in (prefix / 'lib' / f'python{PY_MINOR}').glob('_sysconfigdata_*.py'):
            shutil.copy2(f, dest / f.name)

    compile_sourceless(dest, STDLIB_DDIR)
    return dest


def extract_wheel(wheel: Path, dest: Path) -> None:
    with zipfile.ZipFile(wheel) as zf:
        for name in zf.namelist():
            top = name.split('/', 1)[0]
            # Keep license files; skip other metadata, the PyInstaller hook and the .data dir
            # (man pages, shell completions).
            is_license = top.endswith('.dist-info') and ('/licenses/' in name or '/LICENSE' in name)
            if not is_license and (top.endswith(('.dist-info', '.data')) or name.startswith('yt_dlp/__pyinstaller/')):
                continue
            zf.extract(name, dest)


def module_index(site: Path, package: str) -> dict[str, tuple[Path, bool]]:
    """Map dotted module name -> (file, is_package) for every .py under a package."""
    index = {}
    for path in (site / package).rglob('*.py'):
        parts = list(path.relative_to(site).with_suffix('').parts)
        is_pkg = parts[-1] == '__init__'
        if is_pkg:
            parts.pop()
        index['.'.join(parts)] = (path, is_pkg)
    return index


def imports_of(name: str, is_pkg: bool, tree: ast.AST):
    """Yield candidate absolute module names imported anywhere in a module (incl. lazy imports)."""
    package = name if is_pkg else name.rpartition('.')[0]
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                yield alias.name
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                base = package
                for _ in range(node.level - 1):
                    base = base.rpartition('.')[0]
                target = f'{base}.{node.module}' if node.module else base
            else:
                target = node.module
            yield target
            for alias in node.names:
                yield f'{target}.{alias.name}'


def trim_extractors(site: Path, keep: list[str]) -> list[str]:
    """Rewrite yt-dlp's extractor registry to `keep` (+ ALWAYS_KEEP), drop unreachable modules.

    Returns the names of the registered extractor classes.
    """
    ext_dir = site / 'yt_dlp' / 'extractor'
    # The lazy registry references every extractor; without it yt-dlp imports _extractors.
    (ext_dir / 'lazy_extractors.py').unlink(missing_ok=True)

    registry = ext_dir / '_extractors.py'
    tree = ast.parse(registry.read_text(encoding='utf-8'))
    keep_roots = set(keep) | set(ALWAYS_KEEP)
    kept = [n for n in tree.body
            if isinstance(n, ast.ImportFrom) and n.level == 1 and n.module.split('.')[0] in keep_roots]
    missing = keep_roots - {n.module.split('.')[0] for n in kept}
    if missing:
        raise SystemExit(f'extractor modules not found in yt-dlp: {sorted(missing)}')
    registry.write_text(
        '# Generated by scripts/build_android_runtime.py - trimmed extractor registry\n'
        '# ruff: noqa: F401\n' + ''.join(ast.unparse(n) + '\n' for n in kept),
        encoding='utf-8')

    # Everything outside yt_dlp.extractor.* is kept; follow imports from there (plus the kept
    # extractors) to find which other extractor modules are still referenced, e.g. helpers
    # that the downloader or YoutubeDL import directly.
    index = module_index(site, 'yt_dlp')
    prefix = 'yt_dlp.extractor.'
    reachable: set[str] = set()
    queue = [m for m in index if not m.startswith(prefix)]
    queue += [m for m in index if m.startswith(prefix) and m[len(prefix):].split('.')[0] in keep_roots]
    queue += [f'{prefix}extractors', f'{prefix}_extractors', f'{prefix}common']
    while queue:
        mod = queue.pop()
        if mod in reachable or mod not in index:
            continue
        reachable.add(mod)
        path, is_pkg = index[mod]
        for target in imports_of(mod, is_pkg, ast.parse(path.read_text(encoding='utf-8'))):
            while target:
                if target in index and target not in reachable:
                    queue.append(target)
                target = target.rpartition('.')[0]

    removed = 0
    for mod, (path, _) in index.items():
        if mod.startswith(prefix) and mod not in reachable:
            path.unlink()
            removed += 1
    for d in sorted((p for p in ext_dir.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        if not any(d.iterdir()):
            d.rmdir()
    log(f'extractors: kept {len([m for m in reachable if m.startswith(prefix)])} modules, removed {removed}')
    return [alias.asname or alias.name for n in kept for alias in n.names]


def build_site_packages(work: Path) -> tuple[Path, list[str]]:
    dest = work / 'site-packages'
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    for name, (version, sha256) in WHEELS.items():
        extract_wheel(fetch_wheel(name, version, sha256), dest)

    # Guard against bumping yt-dlp without bumping its pinned ejs version.
    ytdlp_version, _ = WHEELS['yt-dlp']
    meta = zipfile.ZipFile(fetch_wheel('yt-dlp', *WHEELS['yt-dlp'])).read(
        f'yt_dlp-{ytdlp_version}.dist-info/METADATA').decode()
    pin = re.search(r'^Requires-Dist: yt-dlp-ejs==(\S+);', meta, re.M)
    if pin and pin.group(1) != WHEELS['yt-dlp-ejs'][0]:
        raise SystemExit(f'yt-dlp {ytdlp_version} pins yt-dlp-ejs=={pin.group(1)}, '
                         f'but WHEELS has {WHEELS["yt-dlp-ejs"][0]}')

    extractors = trim_extractors(dest, EXTRACTORS)
    shutil.copy2(RUNNER, dest / RUNNER.name)
    compile_sourceless(dest, SITE_DDIR)
    return dest, extractors


# ---------------------------------------------------------------------------
# Native builds (NDK)
# ---------------------------------------------------------------------------

def version_key(p: Path) -> tuple[int, ...]:
    return tuple(int(x) for x in re.findall(r'\d+', p.name))


def find_sdk() -> Path:
    for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(var):
            return Path(os.environ[var])
    if sys.platform == 'win32':
        return Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk'
    if sys.platform == 'darwin':
        return Path.home() / 'Library' / 'Android' / 'sdk'
    return Path.home() / 'Android' / 'Sdk'


class Toolchain:
    def __init__(self) -> None:
        sdk = find_sdk()
        ndk_env = os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT')
        if ndk_env:
            self.ndk = Path(ndk_env)
        else:
            ndks = [p for p in (sdk / 'ndk').glob('*') if version_key(p) and version_key(p)[0] >= 27]
            if not ndks:
                raise SystemExit(f'No NDK r27+ found under {sdk / "ndk"}; set ANDROID_NDK_HOME')
            self.ndk = max(ndks, key=version_key)

        exe = '.exe' if sys.platform == 'win32' else ''
        cmake_dirs = sorted((sdk / 'cmake').glob('*'), key=version_key, reverse=True)
        cmake_bins = [d / 'bin' for d in cmake_dirs if (d / 'bin' / f'cmake{exe}').exists()]
        self.cmake = str(cmake_bins[0] / f'cmake{exe}') if cmake_bins else shutil.which('cmake')
        self.ninja = str(cmake_bins[0] / f'ninja{exe}') if cmake_bins else shutil.which('ninja')
        if not self.cmake or not self.ninja:
            raise SystemExit('cmake and ninja are required (install "CMake" from the Android SDK Manager)')

        host = {'win32': 'windows-x86_64', 'darwin': 'darwin-x86_64'}.get(sys.platform, 'linux-x86_64')
        self.strip = str(self.ndk / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin' / f'llvm-strip{exe}')
        log(f'NDK {self.ndk.name}, cmake {self.cmake}')

    def cmake_build(self, src: Path, build: Path, abi: str, target: str, defines: dict[str, str]) -> None:
        subprocess.run([
            self.cmake, '-S', str(src), '-B', str(build), '-G', 'Ninja',
            f'-DCMAKE_MAKE_PROGRAM={self.ninja}',
            f'-DCMAKE_TOOLCHAIN_FILE={self.ndk / "build" / "cmake" / "android.toolchain.cmake"}',
            f'-DANDROID_ABI={abi}', f'-DANDROID_PLATFORM=android-{ANDROID_API}',
            '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',  # 16 KB pages; default from NDK r28
            '-DCMAKE_BUILD_TYPE=Release',
            *(f'-D{k}={v}' for k, v in defines.items()),
        ], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([self.cmake, '--build', str(build), '--target', target], check=True,
                       stdout=subprocess.DEVNULL)

    def install_binary(self, src: Path, dest: Path) -> None:
        subprocess.run([self.strip, '--strip-unneeded', '-o', str(dest), str(src)], check=True)


def add_license(component: str, source: Path) -> None:
    """License texts for the native binaries (Python packages keep theirs in site-packages)."""
    dest = ASSETS / 'licenses' / f'{component}.txt'
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, dest)


def build_jnilibs(abi: str, prefix: Path, tc: Toolchain | None, ffmpeg_dir: Path | None) -> dict:
    out = JNI_LIBS / abi
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)

    for name in PYTHON_SHARED_LIBS:
        shutil.copy2(prefix / 'lib' / name, out / name)
    # CPython's own license. OpenSSL (Apache-2.0) ships no license file in the release;
    # apps should list it in their third-party notices.
    add_license('cpython', prefix / 'lib' / f'python{PY_MINOR}' / 'LICENSE.txt')

    ext_modules = []
    ext_suffix = None
    for so in sorted((prefix / 'lib' / f'python{PY_MINOR}' / 'lib-dynload').glob('*.so')):
        mod, ext_suffix = so.name.split('.', 1)[0], '.' + so.name.split('.', 1)[1]
        if LIB_DYNLOAD_REMOVE.match(mod):
            continue
        shutil.copy2(so, out / f'libpymod_{mod}.so')
        ext_modules.append(mod)

    # The runtime symlinks lib-dynload/<mod><extSuffix> -> libpymod_<mod>.so. Android's
    # libpython only accepts the full SOABI suffix (or .abi3.so), not a bare .so.
    info = {'extSuffix': ext_suffix, 'extModules': ext_modules,
            'ffmpeg': False, 'quickjs': False, 'launcher': False}
    if tc is None:
        return info

    build_root = CACHE / 'build' / abi
    tc.cmake_build(LAUNCHER_SRC, build_root / 'launcher', abi, 'ytdlp_python',
                   {'PYTHON_PREFIX': prefix.as_posix(), 'PYTHON_VERSION': PY_MINOR})
    tc.install_binary(build_root / 'launcher' / 'libytdlp_python.so', out / 'libytdlp_python.so')
    info['launcher'] = True

    quickjs_src = fetch_quickjs()
    tc.cmake_build(quickjs_src, build_root / 'quickjs', abi, 'qjs_exe', {'QJS_ENABLE_INSTALL': 'OFF'})
    tc.install_binary(build_root / 'quickjs' / 'qjs', out / 'libqjs.so')
    add_license('quickjs-ng', quickjs_src / 'LICENSE')
    info['quickjs'] = True

    ff = (ffmpeg_dir or CACHE / 'ffmpeg') / abi
    if (ff / 'ffmpeg').exists() and (ff / 'ffprobe').exists():
        tc.install_binary(ff / 'ffmpeg', out / 'libffmpeg.so')
        tc.install_binary(ff / 'ffprobe', out / 'libffprobe.so')
        for src in (CACHE / 'src').glob('ffmpeg-*/COPYING.LGPLv2.1'):
            add_license('ffmpeg', src)
        info['ffmpeg'] = True
    else:
        log(f'{abi}: no ffmpeg in {ff} - merging separate audio/video streams will be unavailable '
            '(run scripts/build_ffmpeg_android.sh on Linux/macOS/CI)')
    return info


# ---------------------------------------------------------------------------

def dir_size(path: Path) -> int:
    return sum(p.stat().st_size for p in path.rglob('*') if p.is_file())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--abi', action='append', choices=sorted(PYTHON_ANDROID),
                        help='ABI to build (repeatable, default: all)')
    parser.add_argument('--python-only', action='store_true',
                        help='only build the Python bundles and copy python.org libs; skip NDK builds')
    parser.add_argument('--ffmpeg-dir', type=Path,
                        help='dir containing <abi>/ffmpeg and <abi>/ffprobe (default: .cache/ffmpeg)')
    args = parser.parse_args()

    if sys.version_info[:2] != tuple(map(int, PY_MINOR.split('.'))):
        raise SystemExit(f'Run this with Python {PY_MINOR} (bytecode must match the Android runtime); '
                         f'this is {sys.version.split()[0]}')

    abis = args.abi or list(PYTHON_ANDROID)
    prefixes = {abi: fetch_python_android(abi) for abi in abis}
    work = CACHE / 'work'

    log('building stdlib bundle')
    stdlib = build_stdlib(prefixes, work)
    log('building site-packages bundle')
    site, extractors = build_site_packages(work)

    shutil.rmtree(ASSETS, ignore_errors=True)
    write_zip(stdlib, ASSETS / 'python-stdlib.zip')
    write_zip(site, ASSETS / 'site-packages.zip')

    tc = None if args.python_only else Toolchain()
    for stale in JNI_LIBS.glob('*'):
        if stale.name not in abis:
            shutil.rmtree(stale)
    native = {}
    for abi in abis:
        log(f'{abi}: native libraries')
        native[abi] = build_jnilibs(abi, prefixes[abi], tc, args.ffmpeg_dir)

    digest = hashlib.sha256()
    for name in ('python-stdlib.zip', 'site-packages.zip'):
        digest.update((ASSETS / name).read_bytes())
    manifest = {
        'bundleId': digest.hexdigest()[:16],
        'python': PYTHON_VERSION,
        'ytDlp': WHEELS['yt-dlp'][0],
        'ytDlpEjs': WHEELS['yt-dlp-ejs'][0],
        'certifi': WHEELS['certifi'][0],
        'quickjsNg': QUICKJS_NG[0],
        'extractors': extractors,
        'abis': {abi: {k: v for k, v in info.items() if k != 'extModules'} for abi, info in native.items()},
    }
    (ASSETS / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')

    log(f'stdlib.zip        {(ASSETS / "python-stdlib.zip").stat().st_size / 1e6:6.1f} MB')
    log(f'site-packages.zip {(ASSETS / "site-packages.zip").stat().st_size / 1e6:6.1f} MB')
    for abi in abis:
        log(f'jniLibs/{abi:<10} {dir_size(JNI_LIBS / abi) / 1e6:6.1f} MB (uncompressed)')
    log(f'bundle {manifest["bundleId"]}: {len(extractors)} extractor classes -> {ASSETS.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
