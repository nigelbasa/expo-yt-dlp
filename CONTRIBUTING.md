# Contributing to expo-yt-dlp

How the package works inside, and how to build, test, update and release it. For using the
package in an app, see the [README](README.md).

## How it works

```
JS (src/index.ts) ─ options → yt-dlp arguments
 └─ Expo module (Kotlin) ── spawns ──> libytdlp_python.so -m expo_yt_dlp_runner …   (child process)
                                        ├─ libpython3.14.so + stripped stdlib   (python.org Android build)
                                        ├─ yt-dlp via its Python API            (python/expo_yt_dlp_runner.py)
                                        ├─ spawns libqjs.so                     (QuickJS-ng: YouTube JS challenges)
                                        └─ spawns libffmpeg.so                  (optional)
```

- **Python.** python.org's official Android build of CPython ships only as an embeddable
  `libpython`. [`native/python-launcher`](native/python-launcher/main.c) is a 10-line `main()`
  linked against it, which gives yt-dlp a normal `python` to run under.
- **Processes.** Every call is a separate child process, so cancelling means killing that
  process.
- **Why the binaries are named `lib*.so`.** Android only allows executing files from
  `nativeLibraryDir`, and only extracts `lib*.so` files there. The launcher, QuickJS, ffmpeg
  and the stdlib C extensions (`libpymod_*.so`, symlinked into `lib-dynload`) are all
  packaged that way.
- **Pure-Python code.** The stdlib and yt-dlp are precompiled to `.pyc`, zipped into the APK's
  assets, and unpacked into app storage on first use.
- **The driver.** [`expo_yt_dlp_runner.py`](python/expo_yt_dlp_runner.py) wraps yt-dlp's API.
  It handles format selection for merging, JSON progress events, per-video results with sizes
  and side files, and retries for transient network errors.
- **Merging.** The best qualities are separate video and audio streams. Without ffmpeg, the
  driver picks an H.264 + AAC pair and Kotlin merges it with Android's `MediaMuxer` (a stream
  copy, not a re-encode). If a video has no such pair, which is common on Facebook, the best
  single file is used instead.

## Maintaining the package

Everything the runtime is built from, including which sites are kept, is pinned in
[`runtime-versions.json`](runtime-versions.json). Built binaries are not committed; they're
produced by `npm run build:runtime` and published inside the npm tarball.

**Requirements:** Python 3.14 (the bundle is precompiled for it), Node 20+, and the Android SDK
with NDK r27+ and CMake. Set `ANDROID_HOME` / `ANDROID_NDK_HOME` if they aren't in the default
locations.

### Updating yt-dlp (and the rest)

```sh
npm run deps:check                          # pinned vs. latest upstream
npm run deps:update                         # newest stable yt-dlp + the yt-dlp-ejs it pins + certifi
npm run deps:update -- --channel nightly    # yt-dlp nightly build
npm run deps:update -- --ytdlp 2026.8.19    # a specific version (also how to roll back)
npm run deps:update -- --all                # also Python 3.14.x, QuickJS-ng, ffmpeg
```

`deps:update` rewrites the pins with verified SHA-256 hashes and rebuilds the runtime,
re-trimming yt-dlp to the configured extractors. It then runs the offline tests. After it
succeeds:
1. Run `npm run test:online` to try the three sites for real.
2. Run `npm run build:runtime` for a full native build.
3. Try the example app on a device.
4. Bump the version and tag it (see Releasing).

The **Update bundled yt-dlp** workflow does the first part weekly and opens a PR when there's
a new version.

### Over-the-air yt-dlp bundles

Apps that call `setUpdates('auto' | 'manual')` read a signed feed and download the stripped
yt-dlp (`site-packages.zip`, about 1.5 MB) it points to. The **Publish yt-dlp bundle**
workflow maintains both: every day it checks for a new stable yt-dlp, builds the bundle,
runs the offline and live-site tests, and only if they all pass publishes the zip as a
GitHub release and commits a newly signed `feed.json` to the `feed` branch. If a test fails
it publishes nothing and opens an issue.

```sh
python scripts/build_android_runtime.py --bundle-only     # dist/bundle/site-packages.zip + bundle.json
python scripts/publish_bundle.py add --feed feed.json --bundle dist/bundle/bundle.json --url <https url of the zip>
python scripts/publish_bundle.py sign --feed feed.json    # private key from --key or $EYD_SIGNING_KEY
python scripts/publish_bundle.py verify --feed feed.json
```

`publish_bundle.py --help` lists the rest: `refresh` (renew the expiry), `revoke` (make apps
stop using a bundle), `rotate` (replace a leaked signing key using the backup key).

- **`runtimeApi`** in `runtime-versions.json` is what a bundle needs from the app: the Python
  minor version, the protocol between the Kotlin module and `expo_yt_dlp_runner.py`, and the
  QuickJS that yt-dlp-ejs needs. An app only accepts bundles built for its own number.
  **Bump it whenever one of those changes**, or older apps will download a bundle they
  can't run (the on-device self-check would reject it, but they'd retry every day).
- **Keys.** `update-feed.json` holds the feed URL and the public keys compiled into the
  package. `python scripts/publish_bundle.py keygen --role ci --out ci.pem` creates the key
  the workflow signs with (store it as the `EYD_SIGNING_KEY` secret of the `bundle-signing`
  environment, then delete the file); `--role backup` creates one to keep offline, used only
  to replace the first if it leaks. Private keys never go in the repository.
- **Your own feed.** Fork the scripts, set your `extractors`, run `keygen`, host `feed.json`,
  `feed.json.sig` and the zip anywhere on HTTPS, and pass `feed: { url, publicKey }` to
  `setUpdates()`.

### Changing which sites are included

Edit `"extractors"` in `runtime-versions.json`. The names are yt-dlp's extractor module names
(`yt_dlp/extractor/<name>.py`), e.g. `"tiktok"`, `"vimeo"`, `"twitter"`, `"reddit"`. The generic
extractor is always kept. Then run `npm run build:runtime && npm run test:runtime`; the build
fails if a name doesn't exist. Each site adds a little to the bundle, and removing the list
entirely isn't supported (all ~1,800 extractors would add several MB).

### Building and testing

| Command | What it does |
| --- | --- |
| `npm run build:runtime` | Builds `android/src/main/jniLibs` and `assets/expo-yt-dlp`. `--python-only` skips the NDK builds. |
| `npm test` | Unit tests for the TypeScript layer (jest). |
| `npm run test:updates` | Offline tests of yt-dlp updates: the feed tooling (signing, key rotation) and the app's own verification and state logic, compiled with `kotlinc` and run on the JVM against really-signed feeds. |
| `npm run test:runtime` | Offline checks of the built bundle: the trimmed yt-dlp imports and runs on the stripped stdlib, and network retries behave (local test server). |
| `npm run test:online` | Resolves, plans and downloads from the real sites using the bundled runtime. Can fail for reasons outside this repo (bot checks, rate limits). |
| `python scripts/device_smoke_test.py -- -J <url>` | Pushes the runtime to a connected device or emulator over `adb` and runs yt-dlp there, without building an app. |
| `cd example && npx expo run:android` | The example app, which exercises every function. |

The optional ffmpeg (`scripts/build_ffmpeg_android.sh`, Linux/macOS) builds a mux-only
ffmpeg that the next `build:runtime` bundles. Without it, merging uses `MediaMuxer`.

### CI

| Workflow | When | What |
| --- | --- | --- |
| **CI** (`ci.yml`) | Every push to `main`, every PR | Lint, types, unit tests; full runtime build on Linux with offline tests; checks the npm tarball's contents; on PRs, also a Gradle build of the example app. |
| **Update bundled yt-dlp** (`update-deps.yml`) | Mondays, or manually (stable/nightly) | `deps:update` plus tests, then a PR with the new pins. Needs *Settings → Actions → General → Allow GitHub Actions to create and approve pull requests*. |
| **Publish yt-dlp bundle** (`publish-bundle.yml`) | Daily, or manually | Builds and tests the stripped yt-dlp for the newest stable release, then publishes it and a signed feed for apps to update from. The signing key lives in the `bundle-signing` environment. |
| **Release** (`release.yml`) | Pushing a `v*` tag | Builds the runtime, runs the tests, publishes to npm with trusted publishing (no token stored), creates a GitHub release listing the bundled versions. |

### Releasing

```sh
npm version patch          # or minor/major
git push --follow-tags     # the Release workflow builds and publishes
```

Publishing uses [npm trusted publishing](https://docs.npmjs.com/trusted-publishers): npm
trusts `release.yml` in this repository, so no npm token is stored in GitHub. npm only lets
you set that up for a package that already exists, so the very first version is published
by hand, once:

1. Turn on two-factor authentication for your npm account.
2. Build and publish from a clean checkout:
   ```sh
   npm login
   npm ci
   npm run build:runtime      # both ABIs
   npm run build
   npm pack --dry-run         # check the file list and size
   npm publish --access public
   ```
3. On npmjs.com, open the package's **Settings → Trusted publishing**, choose GitHub Actions
   and enter owner `nigelbasa`, repository `expo-yt-dlp`, workflow `release.yml`, and leave
   the environment empty.
4. Optionally, under **Publishing access**, require 2FA and disallow tokens, so only the
   workflow can publish.

After that, every tag is published by the workflow, with a
[provenance statement](https://docs.npmjs.com/generating-provenance-statements) that links
the version on npm to the commit and workflow run that built it.
