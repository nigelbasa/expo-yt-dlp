#!/usr/bin/env python3
"""Runs the app-side engine (YtDlpEngine.kt: one Python process kept up between calls) on the
host JVM against the real runner from the built bundle and a local web server.

  python scripts/test_engine.py

Needs `kotlinc` and `java` on PATH, host Python 3.14, and a built runtime
(scripts/build_android_runtime.py).
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_runner_retry import SITE_ZIP, Handler  # noqa: E402
from test_update_logic import ROOT, SOURCES, org_json  # noqa: E402


class SlowHandler(Handler):
    def do_GET(self):
        if self.path.startswith('/slow'):
            time.sleep(3)
        super().do_GET()


def main() -> None:
    if not shutil.which('kotlinc'):
        raise SystemExit('kotlinc not found on PATH')
    jar = org_json()
    Handler.faults = {'/missing-403.html': [403, 99]}
    server = ThreadingHTTPServer(('127.0.0.1', 0), SlowHandler)
    server.handle_error = lambda *args: None  # a cancelled call hangs up mid-response
    threading.Thread(target=server.serve_forever, daemon=True).start()

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        site, out, classes = d / 'site-packages', d / 'out', d / 'classes'
        zipfile.ZipFile(SITE_ZIP).extractall(site)
        out.mkdir()
        compile_ = subprocess.run(
            ['kotlinc', '-nowarn', '-cp', str(jar), '-d', str(classes),
             str(SOURCES / 'YtDlpEngine.kt'), str(SOURCES / 'YtDlpProcess.kt'), str(SOURCES / 'Records.kt'),
             *map(str, sorted((ROOT / 'scripts' / 'kotlin').glob('*Stubs.kt'))),
             str(ROOT / 'scripts' / 'kotlin' / 'engine' / 'EngineTest.kt')],
            capture_output=True, text=True)
        if compile_.returncode != 0:
            raise SystemExit(compile_.stdout + compile_.stderr)
        stdlib = Path(shutil.which('kotlinc')).resolve().parent.parent / 'lib' / 'kotlin-stdlib.jar'
        run = subprocess.run(
            ['java', '-cp', f'{classes}:{jar}:{stdlib}', 'expo.modules.ytdlp.EngineTestKt',
             sys.executable, str(site), f'http://127.0.0.1:{server.server_port}', str(out)])
    server.shutdown()
    sys.exit(run.returncode)


if __name__ == '__main__':
    main()
