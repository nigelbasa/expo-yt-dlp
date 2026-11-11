# Over-the-air yt-dlp bundles: design

Date: 2026-11-11. Status: approved and implemented, with the changes listed under
"As built" at the end.

## Goal

When yt-dlp stops working against a site, an installed app gets a fixed yt-dlp without an
app release. The app developer turns this on with one line, can turn it off again in code,
and can point it at their own feed.

## What can and cannot update

| Part | Where it lives | Updates over the air |
|---|---|---|
| Kotlin module, Python interpreter, stdlib, QuickJS, ffmpeg (the **shell**) | APK | No. Android only executes binaries from the APK. |
| `site-packages.zip`: stripped yt-dlp, yt-dlp-ejs, certifi, `expo_yt_dlp_runner.py` (the **bundle**) | APK asset, plus optional downloaded copy | Yes |

The bundle that ships in the APK is always kept and is the fallback.

## Compatibility: `runtimeApi`

A bundle depends on three things in the shell: the Python minor version (bundles are
sourceless `.pyc`), the Kotlin-to-runner protocol, and the QuickJS version yt-dlp-ejs needs.
A raw package-version range cannot be known when a bundle is published, because future
shell versions do not exist yet.

So the shell declares one integer, `runtimeApi`, in `runtime-versions.json`. It is bumped
whenever any of the three change. A bundle is built for exactly one `runtimeApi`, and a
shell only accepts bundles with its own number. The feed also carries a `shells` table
(package version range per `runtimeApi`) so people and tools can read it as version ranges.

## Public API (TypeScript)

All new functions are exported from `expo-yt-dlp` next to the existing ones.

```ts
import * as YtDlp from 'expo-yt-dlp';

// One line, anywhere at startup. Persisted natively, so it can be changed at any time.
YtDlp.setUpdates('auto');      // check in the background, install compatible fixes
YtDlp.setUpdates('bundled');   // default: only ever use the yt-dlp shipped in the app
YtDlp.setUpdates('manual');    // never check on its own; the app calls updateYtDlp()

// Options form: own feed, own cadence.
YtDlp.setUpdates({
  mode: 'auto',
  checkIntervalHours: 24,                          // default 24, minimum 1
  feed: { url: 'https://example.com/feed.json',    // default: the official feed
          publicKey: '<base64 P-256 public key>' },
});

const check = await YtDlp.checkForUpdate();
// { status: 'up-to-date' | 'available' | 'app-update-required',
//   current: { ytDlp: '2026.8.19', source: 'bundled', bundleId },
//   latest?: { ytDlp: '2026.11.3', size: 3_100_000, bundleId } }

const result = await YtDlp.updateYtDlp({ onProgress: (p) => p.fraction });
// { updated: true, ytDlp: '2026.11.3', previous: '2026.8.19' }

await YtDlp.resetToBundled();             // drop any downloaded bundle
YtDlp.getUpdateState();                   // { mode, current, lastCheck, lastError }

const sub = YtDlp.addUpdateListener((e) => {
  // e.type: 'checking' | 'downloading' | 'updated' | 'up-to-date'
  //       | 'app-update-required' | 'failed'
  // e.error?: { code, message }   e.ytDlp?: string   e.fraction?: number
});
sub.remove();
```

`prepare()` and `RuntimeInfo` gain `source: 'bundled' | 'downloaded'`, `bundleId` and
`runtimeApi`.

### Behaviour

- **`bundled`** (default): no network access for updates. A previously downloaded bundle is
  left on disk but not used; `resetToBundled()` deletes it.
- **`auto`**: a check runs in the background on the first yt-dlp call of a process if the
  last check is older than `checkIntervalHours`. It also runs once (at most hourly) right
  after a call fails with an extractor error, since that is the moment an update is most
  likely to help. Auto checks never delay or fail a user call; outcomes arrive on the
  listener and in `getUpdateState()`.
- **`manual`**: only `checkForUpdate()` / `updateYtDlp()` touch the network.
- A new bundle is used by the next yt-dlp process. Running jobs finish on the old one.
- `app-update-required` means a newer yt-dlp exists but only for a newer `runtimeApi`.

### Errors

`checkForUpdate()` and `updateYtDlp()` reject with coded errors; auto mode reports the same
codes in `failed` events. Every message is a full sentence an app can show as-is.

| Code | Meaning |
|---|---|
| `ERR_YTDLP_UPDATE_NETWORK` | Feed or bundle could not be fetched |
| `ERR_YTDLP_UPDATE_SIGNATURE` | Feed signature invalid, feed expired, or sequence went backwards |
| `ERR_YTDLP_UPDATE_CORRUPT` | Bundle size or sha256 does not match the signed feed |
| `ERR_YTDLP_UPDATE_BROKEN` | Bundle verified but failed its self-check; the previous bundle stays active |
| `ERR_YTDLP_UPDATE_BUSY` | Another update is already in progress |
| `ERR_YTDLP_UPDATE_DISABLED` | `updateYtDlp()` called while mode is `bundled` |

An existing `ERR_YTDLP` extractor failure gains `updateAvailable?: boolean` when the last
check found a newer compatible bundle.

## Feed format

Two files, fetched over HTTPS:

- `feed.json`: the document below.
- `feed.json.sig`: base64 ECDSA P-256 / SHA-256 signature over the exact bytes of `feed.json`.

```json
{
  "schema": 1,
  "sequence": 42,
  "published": "2026-11-11T06:10:00Z",
  "expires": "2026-12-11T06:10:00Z",
  "shells": { "1": ">=0.2.0" },
  "bundles": [
    {
      "runtimeApi": 1,
      "bundleId": "3f9a1c0e5b7d2a44",
      "ytDlp": "2026.11.3",
      "extractors": ["youtube", "facebook", "instagram"],
      "url": "https://github.com/nigelbasa/expo-yt-dlp/releases/download/bundle-2026.11.3-api1/site-packages.zip",
      "size": 3100000,
      "sha256": "…"
    }
  ],
  "revoked": ["<bundleId>"]
}
```

One entry per `runtimeApi`: the newest bundle that passed the pipeline. `revoked` lists
bundles that must stop being used; a client running one falls back to the APK bundle (or
installs the listed replacement).

Official location: `feed.json` and `feed.json.sig` on a `feed` branch of this repository,
read through `raw.githubusercontent.com`. A branch gives atomic updates and an audit
history. Bundle zips are assets of immutable GitHub releases.

## Security

Threats: a tampered feed or zip in transit or at the host, replay of an old (broken or
vulnerable) feed, a compromised CI signing key, a hostile zip.

1. **Signature.** The client verifies `feed.json.sig` against public keys pinned in the
   package before parsing the feed. ECDSA P-256 is used rather than Ed25519 because
   Android's built-in Ed25519 verification needs API 33 and the package supports API 24;
   P-256 needs no added dependency.
2. **Two pinned keys.** The CI key signs routine feeds. A backup key, kept offline, is also
   pinned so a compromised CI key can be replaced by publishing a feed signed with the
   backup key that names a new CI key (`"keys"` field, honoured only under a backup-key
   signature). Without this, rotation would need an app release from every consumer.
3. **Integrity.** The zip's size and sha256 come from the signed feed. Download is capped at
   the declared size and hashed while streaming.
4. **No rollback, no freeze.** The client stores the highest `sequence` it accepted and
   rejects lower ones. A feed past `expires` is rejected (the active bundle stays in use).
   CI re-signs the feed weekly even when nothing changed.
5. **Safe extraction.** HTTPS only, redirects only to HTTPS. Existing zip-slip check stays;
   entries must be regular files or directories; total uncompressed size is capped.
6. **Self-check before activation.** The new bundle is extracted to a temp directory and
   run as `python -m yt_dlp --version` plus an import of the runner; the version must match
   the feed. Only then is it renamed into place and recorded as active.
7. **Fallback.** If the active downloaded bundle fails to start (import error, crash before
   the runner's handshake), it is marked bad, never fetched again, and the APK bundle takes
   over for that same call.
8. **App updates.** On start, a downloaded bundle is discarded if its `runtimeApi` no longer
   matches the shell or the APK now ships the same or a newer yt-dlp.
9. **Custom feeds.** An overridden feed must supply its own public key; the official keys
   are then not trusted for that feed, and vice versa.
10. **Build provenance.** Each bundle release carries a GitHub build attestation. The zip is
    built deterministically (fixed timestamps and ordering) so anyone can rebuild from the
    pins and compare the sha256.
11. **Upstream check.** The yt-dlp wheel hash from PyPI is cross-checked against the
    `SHA2-256SUMS` of the matching yt-dlp GitHub release before a build is accepted.

Google Play restricts downloading executable code from outside Play. Interpreted Python is
a grey area; the README will say so, and `bundled` stays the default.

## Android implementation

Kept small: three focused files, no new dependencies (`HttpURLConnection`, `java.security`,
`org.json`).

- `BundleStore.kt`: owns the on-disk layout and decides which bundle is active.

  ```
  noBackupFilesDir/expo-yt-dlp/
    stdlib/<apkBundleId>/          python stdlib from the APK
    bundled/<apkBundleId>/         site-packages from the APK
    ota/<bundleId>/                downloaded site-packages
    state.json                     mode, feed override, active ota id, sequence, lastCheck,
                                   lastError, bad bundle ids
  ```

  `state.json` is written by temp-file-and-rename. Cleanup removes anything not referenced.
- `FeedClient.kt`: fetch, verify signature, parse, pick the entry for this `runtimeApi`.
  Pure logic with the HTTP call injected, so it is unit-testable on the JVM.
- `BundleUpdater.kt`: download, hash, extract, self-check, activate; one update at a time;
  emits update events.
- `YtDlpRuntime.kt`: `ensureInstalled()` asks `BundleStore` for the site-packages path
  instead of assuming the APK one. Stdlib and site-packages are extracted separately so a
  bundle swap does not re-extract the stdlib.
- `ExpoYtDlpModule.kt`: new functions and an `onUpdate` event; triggers the auto check.

## Build and release pipeline

`scripts/build_android_runtime.py` gains `--bundle-only`, producing a deterministic
`site-packages.zip` plus its feed entry for a given yt-dlp version.

New `scripts/publish_bundle.py` signs the feed and is used by a new workflow,
`.github/workflows/publish-bundle.yml`:

1. **Watch** (daily, and on demand): compare PyPI's stable yt-dlp with the feed.
2. **Build** the bundle for each `runtimeApi` still supported.
3. **Gate**, all required:
   - offline tests (trimmed import check, runner retry tests);
   - live-site test for each bundled extractor;
   - emulator test: install the example app built from the released shell, side-load the
     new bundle through the real update path, run an info lookup and a short download.
4. **Publish**: create the immutable bundle release with attestation, then commit the new
   signed `feed.json` to the `feed` branch with `sequence + 1`.
5. **On gate failure**: publish nothing and open an issue with the logs.

The signing key is a secret on a GitHub environment restricted to `main` and to this
workflow. The existing `update-deps.yml` PR flow stays, so the next npm release also ships
the new yt-dlp in the APK.

Developers running their own feed use the same two scripts with their own key and their own
`extractors` list.

## Testing

- JVM unit tests for `FeedClient` (bad signature, expired, sequence rollback, wrong
  `runtimeApi`, revoked) and `BundleStore` (selection, app-update discard, bad-bundle list).
- Jest tests for the TS wrappers and option validation.
- Python tests for deterministic zip output and feed signing round-trip.
- Emulator test described above, also run in CI on pull requests that touch the updater.

## Out of scope

- Updating Python, QuickJS or ffmpeg over the air.
- Delta/patch downloads (the bundle is a few MB).
- Per-app extractor sets on the official feed.
- iOS.

## Open choices

- Default mode is `bundled` (no surprise network use). Could be `auto` instead.
- Official feed checks daily for upstream releases; could be weekly like today.

## As built

Differences from the design above, decided during implementation:

- **Hex, not base64.** Public keys and signatures are hex. `java.util.Base64` needs API 26
  and the package supports API 24; hex needs no decoder.
- **Key rotation travels in the signature file,** not in a `"keys"` field of the feed. Line 2
  of `feed.json.sig` is `<epoch> <key>,<key> <backup signature>`. A field inside the feed
  would have to be read before the feed is verified, and an install that missed the one
  rotation feed could never verify later feeds. Each install remembers the highest epoch.
- **On-disk layout.** The APK's stdlib and site-packages stay where they were
  (`<root>/<bundleId>/`), so existing installs don't re-extract. Downloads go to
  `<root>/ota/<bundleId>/site-packages/` and state to `<root>/state.json`.
- **Three Kotlin files, named differently:** `UpdateFeed.kt` (verify and parse),
  `UpdateStore.kt` (state and layout), `BundleUpdater.kt` (network, install, auto checks).
- **Extra error code** `ERR_YTDLP_UPDATE_CONFIG` for invalid `setUpdates()` input.
- **`setUpdates()` replaces the whole configuration.** Switching to a different feed drops
  the bundle and replay state from the previous feed.
- **Broken-bundle detection** uses Python import failures in stderr (there is no runner
  handshake), then retries the same call on the APK bundle.
- **Upstream hash cross-check (security item 11) dropped.** yt-dlp's `SHA2-256SUMS` does not
  list the PyPI wheel, so there is nothing to compare against.
- **The publish workflow is two jobs.** Build and tests run without the signing key; the
  signing job only handles the files they produced and never runs yt-dlp code.
- **JVM tests run through `kotlinc`,** not Gradle: `scripts/test_update_logic.py` compiles
  `UpdateFeed.kt` and `UpdateStore.kt` and runs them against feeds signed by the real tool.
- **Not built yet:** the emulator test of the real update path. Download, self-check and
  activation in `BundleUpdater.kt` are compiled but have not run on a device.
