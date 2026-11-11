package expo.modules.ytdlp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * What survives between app launches for yt-dlp updates: the chosen mode and feed, the
 * downloaded bundle in use, and what is needed to refuse replayed or known-bad updates.
 *
 * Layout under the runtime root:
 *   state.json                       this store
 *   ota/<bundleId>/site-packages/    a downloaded bundle
 *   ota/<bundleId>/.complete         written last; a directory without it is ignored
 */
internal class UpdateStore(private val root: File) {
  private val file = File(root, STATE_FILE)
  val otaRoot = File(root, OTA_DIR)

  private val state: JSONObject = try {
    JSONObject(file.readText())
  } catch (e: IOException) {
    JSONObject()
  } catch (e: JSONException) {
    JSONObject() // unreadable state only costs a re-download
  }

  @Synchronized
  fun <T> read(block: JSONObject.() -> T): T = state.block()

  /** Applies [block] and writes the state through a temp file, so a crash can't truncate it. */
  @Synchronized
  fun edit(block: JSONObject.() -> Unit) {
    state.block()
    root.mkdirs()
    val tmp = File(root, "$STATE_FILE.tmp")
    tmp.writeText(state.toString())
    if (!tmp.renameTo(file)) throw IOException("Could not write ${file.path}")
  }

  val mode: String
    get() = read { optString("mode", MODE_BUNDLED) }

  /** The downloaded bundle recorded as current, whether or not the mode lets it be used. */
  val downloaded: BundleEntry?
    get() = read {
      try {
        optJSONObject("active")?.let { BundleEntry.fromJson(it) }
      } catch (e: JSONException) {
        null
      }
    }

  fun bundleDir(bundleId: String) = File(otaRoot, bundleId)

  fun isComplete(bundleId: String) = File(bundleDir(bundleId), COMPLETE_MARKER).exists()

  /** The downloaded bundle new yt-dlp processes should use, or null for the one in the APK. */
  fun activeBundle(): Pair<BundleEntry, File>? {
    if (mode == MODE_BUNDLED) return null
    val entry = downloaded ?: return null
    if (!isComplete(entry.bundleId)) return null
    return entry to File(bundleDir(entry.bundleId), SITE_PACKAGES)
  }

  val badBundles: Set<String>
    get() = read { optJSONArray("bad").toStringList().toSet() }

  /** Stops using a bundle and never installs it again. Its files go at the next launch. */
  fun markBad(bundleId: String) = edit {
    put("bad", JSONArray(optJSONArray("bad").toStringList().toSet() + bundleId))
    if (optJSONObject("active")?.optString("bundleId") == bundleId) remove("active")
  }

  /**
   * Run once per process, before anything uses a bundle: drops a downloaded bundle the
   * updated app can no longer use or no longer needs, then deletes every bundle directory
   * that is not current (old versions, interrupted downloads).
   */
  fun reconcile(runtimeApi: Int, bundledYtDlp: String) {
    val entry = downloaded
    if (entry != null && (
        entry.runtimeApi != runtimeApi ||
          UpdateFeed.compareVersions(bundledYtDlp, entry.ytDlp) >= 0 ||
          !isComplete(entry.bundleId)
        )
    ) {
      edit { remove("active") }
    }
    val keep = downloaded?.bundleId
    otaRoot.listFiles()?.filter { it.name != keep }?.forEach { it.deleteRecursively() }
  }

  companion object {
    const val STATE_FILE = "state.json"
    const val OTA_DIR = "ota"
    const val SITE_PACKAGES = "site-packages"
    const val COMPLETE_MARKER = ".complete"
    const val MODE_BUNDLED = "bundled"
    const val MODE_AUTO = "auto"
    const val MODE_MANUAL = "manual"
  }
}
