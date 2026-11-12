#!/usr/bin/env bash
# Everything CI runs on the booted emulator (see the emulator job in .github/workflows/ci.yml):
# the example app's self-test, then the Kotlin library's instrumented tests. Both use the clip
# server in emulator_selftest.py, since YouTube blocks CI machines.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 scripts/emulator_selftest.py example/android/app/build/outputs/apk/release/app-release.apk

python3 scripts/emulator_selftest.py --serve > clip-server.log 2>&1 &
server=$!
trap 'kill $server' EXIT
sleep 3
gradle -p kotlin connectedDebugAndroidTest --no-daemon --stacktrace
