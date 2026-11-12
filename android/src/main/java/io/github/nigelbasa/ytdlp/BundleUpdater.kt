package io.github.nigelbasa.ytdlp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Finds, verifies and installs newer yt-dlp bundles.
 *
 * A bundle is only used after all of: the feed's signature matches a pinned key, the feed is
 * neither expired nor older than one already seen, the zip matches the size and sha256 in
 * that feed, and the extracted bundle starts and reports the expected yt-dlp version.
 * Until then, and whenever anything fails, the previous bundle stays in use.
 */
internal class BundleUpdater(
  private val runtime: YtDlpRuntime,
  /** Receives update events for JS: type plus ytDlp / fraction / error where they apply. */
  private val emit: (Map<String, Any?>) -> Unit,
) {
  private val store get() = runtime.store
  private val busy = AtomicBoolean(false)

  @Volatile
  private var lastFailureCheck = 0L

  private class FeedSource(val url: String, val keys: List<String>, val backupKeys: List<String>, val custom: Boolean)

  private class Check(val status: String, val latest: BundleEntry?)

  // -------------------------------------------------------------------------
  // Configuration and state
  // -------------------------------------------------------------------------

  fun configure(config: UpdateConfig) {
    if (config.mode !in MODES) {
      throw UpdateException(UpdateException.CONFIG, "Update mode must be one of $MODES, not \"${config.mode}\".")
    }
    if (config.checkIntervalHours.isNaN() || config.checkIntervalHours < 1) {
      throw UpdateException(UpdateException.CONFIG, "checkIntervalHours must be at least 1.")
    }
    val url = config.feedUrl
    val key = config.feedPublicKey
    if ((url == null) != (key == null)) {
      throw UpdateException(UpdateException.CONFIG, "A custom update feed needs both a url and a publicKey.")
    }
    if (url != null && !url.startsWith("https://")) {
      throw UpdateException(UpdateException.CONFIG, "The update feed url must start with https://.")
    }
    store.edit {
      // Another feed is another trust root: nothing learned from the old one carries over,
      // including the bundle it supplied.
      if (optString("feedUrl") != (url ?: "") || optString("feedKey") != (key ?: "")) {
        listOf("active", "sequence", "rotation", "latest", "lastCheck", "lastError").forEach { remove(it) }
      }
      put("mode", config.mode)
      put("checkIntervalHours", config.checkIntervalHours)
      put("feedUrl", url ?: "")
      put("feedKey", key ?: "")
    }
  }

  private fun feedSource(): FeedSource {
    val url = store.read { optString("feedUrl") }
    if (url.isNotEmpty()) {
      return FeedSource(url, listOf(store.read { optString("feedKey") }), emptyList(), custom = true)
    }
    val official = runtime.manifest.optJSONObject("updateFeed") ?: JSONObject()
    return FeedSource(
      official.optString("url"),
      official.optJSONArray("keys").toStringList(),
      official.optJSONArray("backupKeys").toStringList(),
      custom = false,
    )
  }

  private fun current(): Map<String, Any?> {
    val downloaded = store.activeBundle()?.first
    return mapOf(
      "ytDlp" to (downloaded?.ytDlp ?: runtime.bundledYtDlp),
      "source" to if (downloaded != null) "downloaded" else "bundled",
      "bundleId" to (downloaded?.bundleId ?: runtime.manifest.optString("siteBundleId")),
    )
  }

  fun state(): Map<String, Any?> {
    val source = feedSource()
    val current = current()
    val latest = store.read { optJSONObject("latest") }
    val error = store.read { optJSONObject("lastError") }
    return mapOf(
      "mode" to store.mode,
      "checkIntervalHours" to store.read { optDouble("checkIntervalHours", 24.0) },
      "feed" to mapOf("url" to source.url, "custom" to source.custom),
      "current" to current,
      "latest" to latest?.let { mapOf("ytDlp" to it.optString("ytDlp"), "size" to it.optLong("size"), "bundleId" to it.optString("bundleId")) },
      "updateAvailable" to (latest != null && latest.optString("status") == STATUS_AVAILABLE &&
        latest.optString("bundleId") != current["bundleId"]),
      "lastCheck" to store.read { if (has("lastCheck")) optLong("lastCheck").toDouble() else null },
      "lastError" to error?.let { mapOf("code" to it.optString("code"), "message" to it.optString("message")) },
    )
  }

  // -------------------------------------------------------------------------
  // Entry points
  // -------------------------------------------------------------------------

  /** Asks the feed whether a newer bundle exists for this app. Installs nothing. */
  fun check(): Map<String, Any?> = exclusively { checkResult(fetchCheck()) }

  /** Checks, and if a newer compatible bundle exists, downloads, verifies and switches to it. */
  fun update(): Map<String, Any?> {
    if (store.mode == UpdateStore.MODE_BUNDLED) {
      throw UpdateException(
        UpdateException.DISABLED,
        "yt-dlp updates are turned off. Call setUpdates('auto') or setUpdates('manual') first.",
      )
    }
    return exclusively { install(fetchCheck()) }
  }

  /** Back to the yt-dlp in the APK. The downloaded files are removed at the next launch. */
  fun reset(): Map<String, Any?> = exclusively(report = false) {
    store.edit { remove("active") }
    state()
  }

  /**
   * In auto mode, starts a background check when one is due: on a schedule, or ([afterFailure])
   * right after a yt-dlp call failed, which is when a fix is most likely to exist. Never
   * throws and never delays the caller; the outcome goes to the event listener and state().
   */
  fun autoCheck(afterFailure: Boolean = false) {
    if (store.mode != UpdateStore.MODE_AUTO || busy.get()) return
    val now = System.currentTimeMillis()
    val due = if (afterFailure) {
      now - lastFailureCheck >= FAILURE_CHECK_INTERVAL_MS
    } else {
      val interval = (store.read { optDouble("checkIntervalHours", 24.0) } * 3_600_000).toLong()
      now - store.read { optLong("lastCheck", 0) } >= interval
    }
    if (!due) return
    if (afterFailure) lastFailureCheck = now
    thread(name = "yt-dlp-update", isDaemon = true) {
      try {
        exclusively { install(fetchCheck()) }
      } catch (e: Exception) {
        // Already recorded and emitted by exclusively(); a background check must not crash the app.
      }
    }
  }

  /**
   * A downloaded bundle failed to start during a real job. It is dropped for good and the
   * yt-dlp in the APK takes over from the next process on.
   */
  fun reportBroken(bundleId: String, detail: String) {
    store.markBad(bundleId)
    fail(UpdateException(UpdateException.BROKEN, "The downloaded yt-dlp stopped working and was removed: $detail"))
  }

  // -------------------------------------------------------------------------
  // Steps
  // -------------------------------------------------------------------------

  private fun <T> exclusively(report: Boolean = true, block: () -> T): T {
    if (!busy.compareAndSet(false, true)) {
      throw UpdateException(UpdateException.BUSY, "A yt-dlp update is already in progress.")
    }
    try {
      return block()
    } catch (e: UpdateException) {
      if (report) fail(e)
      throw e
    } catch (e: IOException) {
      val error = UpdateException(UpdateException.NETWORK, "The yt-dlp update could not be downloaded: ${e.message}")
      if (report) fail(error)
      throw error
    } finally {
      busy.set(false)
    }
  }

  private fun fail(error: UpdateException) {
    store.edit { put("lastError", JSONObject().put("code", error.code).put("message", error.message)) }
    emit(mapOf("type" to "failed", "error" to mapOf("code" to error.code, "message" to error.message)))
  }

  private fun fetchCheck(): Check {
    emit(mapOf("type" to "checking"))
    val source = feedSource()
    if (source.url.isEmpty() || (source.keys.isEmpty() && source.backupKeys.isEmpty())) {
      throw UpdateException(UpdateException.SIGNATURE, "No yt-dlp update feed with a signing key is configured.")
    }
    store.edit { put("lastCheck", System.currentTimeMillis()) }

    val feedBytes = fetch(source.url, MAX_FEED_BYTES)
    val signature = String(fetch(source.url + ".sig", MAX_FEED_BYTES), Charsets.UTF_8)
    val known = store.read {
      optJSONObject("rotation")?.let { KeyRotation(it.optInt("epoch"), it.optJSONArray("keys").toStringList()) }
    }
    val rotation = UpdateFeed.verify(feedBytes, signature, source.keys, source.backupKeys, known)
    val feed = UpdateFeed.parse(feedBytes)
    if (feed.sequence < store.read { optLong("sequence", 0) }) {
      throw UpdateException(UpdateException.SIGNATURE, "The yt-dlp update feed is older than one this app has already seen.")
    }
    if (feed.expiresAt < System.currentTimeMillis()) {
      throw UpdateException(UpdateException.SIGNATURE, "The yt-dlp update feed has expired.")
    }

    store.downloaded?.let { if (it.bundleId in feed.revoked) store.markBad(it.bundleId) }
    val installed = store.activeBundle()?.first?.ytDlp ?: runtime.bundledYtDlp
    val bad = store.badBundles
    val newer = feed.bundles.filter {
      it.bundleId !in bad && it.bundleId !in feed.revoked && UpdateFeed.compareVersions(it.ytDlp, installed) > 0
    }
    val compatible = newer.firstOrNull { it.runtimeApi == runtime.runtimeApi }
    val check = when {
      compatible != null -> Check(STATUS_AVAILABLE, compatible)
      newer.any { it.runtimeApi > runtime.runtimeApi } -> Check(STATUS_APP_UPDATE, newer.maxByOrNull { it.runtimeApi })
      else -> Check(STATUS_UP_TO_DATE, null)
    }

    store.edit {
      put("sequence", feed.sequence)
      if (rotation != null) put("rotation", JSONObject().put("epoch", rotation.epoch).put("keys", JSONArray(rotation.keys)))
      remove("lastError")
      if (check.latest != null) put("latest", check.latest.toJson().put("status", check.status)) else remove("latest")
    }
    return check
  }

  private fun checkResult(check: Check): Map<String, Any?> {
    if (check.status != STATUS_AVAILABLE) emit(mapOf("type" to check.status, "ytDlp" to check.latest?.ytDlp))
    return mapOf(
      "status" to check.status,
      "current" to current(),
      "latest" to check.latest?.let { mapOf("ytDlp" to it.ytDlp, "size" to it.size, "bundleId" to it.bundleId) },
    )
  }

  private fun install(check: Check): Map<String, Any?> {
    val previous = current()["ytDlp"]
    val entry = check.latest
    if (check.status != STATUS_AVAILABLE || entry == null) {
      return checkResult(check) + mapOf("updated" to false, "ytDlp" to previous, "previous" to previous)
    }

    // The same bundle may still be on disk (after resetToBundled or a mode change).
    if (!store.isComplete(entry.bundleId)) {
      store.otaRoot.mkdirs()
      val tmp = File(store.otaRoot, ".tmp-${entry.bundleId}-${System.nanoTime()}")
      try {
        tmp.mkdirs()
        val zip = File(tmp, "bundle.zip")
        download(entry, zip)
        val site = File(tmp, UpdateStore.SITE_PACKAGES)
        try {
          zip.inputStream().use { runtime.unzip(it, site, MAX_EXTRACTED_BYTES) }
        } catch (e: IOException) {
          throw UpdateException(UpdateException.CORRUPT, "The downloaded yt-dlp ${entry.ytDlp} could not be unpacked: ${e.message}")
        }
        zip.delete()

        val reported = runtime.selfCheck(site)
        if (reported != entry.ytDlp) {
          store.markBad(entry.bundleId)
          throw UpdateException(
            UpdateException.BROKEN,
            "yt-dlp ${entry.ytDlp} was downloaded but did not start correctly on this device, so the previous version is still in use.",
          )
        }
        File(tmp, UpdateStore.COMPLETE_MARKER).createNewFile()
        val dir = store.bundleDir(entry.bundleId)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("could not move the bundle into ${dir.path}")
      } finally {
        tmp.deleteRecursively()
      }
    }

    // New yt-dlp processes pick this up; running ones finish on the bundle they started with,
    // whose files stay until the next launch.
    store.edit { put("active", entry.toJson()) }
    emit(mapOf("type" to "updated", "ytDlp" to entry.ytDlp))
    return mapOf(
      "status" to STATUS_UP_TO_DATE,
      "current" to current(),
      "latest" to null,
      "updated" to true,
      "ytDlp" to entry.ytDlp,
      "previous" to previous,
    )
  }

  /** Streams the zip to [dest], stopping at the signed size and checking the signed sha256. */
  private fun download(entry: BundleEntry, dest: File) {
    val digest = MessageDigest.getInstance("SHA-256")
    var received = 0L
    var lastReported = -1
    emit(mapOf("type" to "downloading", "ytDlp" to entry.ytDlp, "fraction" to 0.0))
    val connection = open(entry.url)
    try {
      connection.inputStream.use { input ->
        dest.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            received += n
            if (received > entry.size) break
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            val percent = (received * 100 / entry.size).toInt()
            if (percent >= lastReported + 5) {
              lastReported = percent
              emit(mapOf("type" to "downloading", "ytDlp" to entry.ytDlp, "fraction" to percent / 100.0))
            }
          }
        }
      }
    } finally {
      connection.disconnect()
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    if (received != entry.size || sha256 != entry.sha256) {
      throw UpdateException(
        UpdateException.CORRUPT,
        "The downloaded yt-dlp ${entry.ytDlp} does not match the signed update feed and was discarded.",
      )
    }
  }

  private fun fetch(url: String, maxBytes: Int): ByteArray {
    val connection = open(url)
    try {
      connection.inputStream.use { input ->
        val bytes = input.readAtMost(maxBytes + 1)
        if (bytes.size > maxBytes) throw IOException("$url is larger than $maxBytes bytes")
        return bytes
      }
    } finally {
      connection.disconnect()
    }
  }

  /** HTTPS only, including after redirects (HttpURLConnection never follows https -> http). */
  private fun open(url: String): HttpURLConnection {
    if (!url.startsWith("https://")) throw IOException("$url is not an https URL")
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "expo-yt-dlp")
    connection.setRequestProperty("Cache-Control", "no-cache")
    val status = connection.responseCode
    if (connection.url.protocol != "https" || status != HttpURLConnection.HTTP_OK) {
      connection.disconnect()
      throw IOException("$url answered HTTP $status")
    }
    return connection
  }

  private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
      val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
      if (n < 0) break
      out.write(buffer, 0, n)
    }
    return out.toByteArray()
  }

  companion object {
    private val MODES = listOf(UpdateStore.MODE_BUNDLED, UpdateStore.MODE_AUTO, UpdateStore.MODE_MANUAL)
    private const val STATUS_AVAILABLE = "available"
    private const val STATUS_UP_TO_DATE = "up-to-date"
    private const val STATUS_APP_UPDATE = "app-update-required"
    private const val MAX_FEED_BYTES = 256 * 1024
    private const val MAX_EXTRACTED_BYTES = 256L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val FAILURE_CHECK_INTERVAL_MS = 3_600_000L
  }
}
