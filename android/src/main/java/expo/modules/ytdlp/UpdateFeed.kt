package expo.modules.ytdlp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One downloadable yt-dlp bundle (a site-packages.zip), as listed in the signed feed. */
internal class BundleEntry(
  val runtimeApi: Int,
  val bundleId: String,
  val ytDlp: String,
  val extractors: List<String>,
  val url: String,
  val size: Long,
  val sha256: String,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("runtimeApi", runtimeApi)
    .put("bundleId", bundleId)
    .put("ytDlp", ytDlp)
    .put("extractors", JSONArray(extractors))
    .put("url", url)
    .put("size", size)
    .put("sha256", sha256)

  companion object {
    private val BUNDLE_ID = Regex("^[0-9a-f]{16}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    const val MAX_SIZE = 64L * 1024 * 1024

    /** @throws JSONException if a field is missing or unsafe to use (the id becomes a directory name). */
    fun fromJson(json: JSONObject): BundleEntry {
      val entry = BundleEntry(
        runtimeApi = json.getInt("runtimeApi"),
        bundleId = json.getString("bundleId"),
        ytDlp = json.getString("ytDlp"),
        extractors = json.optJSONArray("extractors").toStringList(),
        url = json.getString("url"),
        size = json.getLong("size"),
        sha256 = json.getString("sha256"),
      )
      if (!BUNDLE_ID.matches(entry.bundleId)) throw JSONException("bad bundleId")
      if (!SHA256.matches(entry.sha256)) throw JSONException("bad sha256")
      if (!entry.url.startsWith("https://")) throw JSONException("bundle url must be https")
      if (entry.size !in 1..MAX_SIZE) throw JSONException("bad bundle size")
      return entry
    }
  }
}

internal class Feed(
  val sequence: Long,
  /** Epoch milliseconds after which this feed must no longer be accepted. */
  val expiresAt: Long,
  val bundles: List<BundleEntry>,
  val revoked: Set<String>,
)

/** New routine signing keys, vouched for by a backup key. Higher epochs replace lower ones. */
internal class KeyRotation(val epoch: Int, val keys: List<String>)

/**
 * Verifying and reading the update feed. No Android or network dependencies, so the same
 * logic can be exercised on a plain JVM.
 *
 * Signatures are ECDSA P-256 over SHA-256 (works on every supported API level without a
 * dependency; Ed25519 needs API 33). Keys are hex X.509 SubjectPublicKeyInfo, signatures hex DER.
 */
internal object UpdateFeed {
  private const val SCHEMA = 1

  /**
   * Checks the feed's signature file against the trusted keys.
   *
   * The signature file's first line signs the feed. An optional second line,
   * `<epoch> <key>,<key> <signature>`, is a rotation signed by a backup key: from then on only
   * the keys it names are trusted for routine signing, so a leaked routine key can be retired
   * without an app update. [knownRotation] is the newest rotation this install has accepted.
   *
   * @return the rotation to remember if the file carried a newer one than [knownRotation]
   * @throws UpdateException when nothing trusted signed the feed
   */
  fun verify(
    feed: ByteArray,
    signatureFile: String,
    pinnedKeys: List<String>,
    backupKeys: List<String>,
    knownRotation: KeyRotation?,
  ): KeyRotation? {
    val lines = signatureFile.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) throw signatureError("The update feed has no signature.")

    var newRotation: KeyRotation? = null
    if (lines.size > 1) {
      val parts = lines[1].split(' ')
      val epoch = parts.getOrNull(0)?.toIntOrNull()
      if (parts.size != 3 || epoch == null) throw signatureError("The update feed's key rotation is malformed.")
      if (epoch > (knownRotation?.epoch ?: 0)) {
        val message = "${parts[0]} ${parts[1]}".toByteArray(Charsets.US_ASCII)
        if (!isSignedBy(message, parts[2], backupKeys)) {
          throw signatureError("The update feed's key rotation is not signed by a trusted backup key.")
        }
        newRotation = KeyRotation(epoch, parts[1].split(',').filter { it.isNotEmpty() })
      }
    }

    val trusted = (newRotation ?: knownRotation)?.keys ?: pinnedKeys
    if (!isSignedBy(feed, lines[0], trusted)) {
      throw signatureError("The update feed's signature does not match a trusted key.")
    }
    return newRotation
  }

  /** Call only after [verify]. @throws UpdateException if the document is not a usable feed. */
  fun parse(feed: ByteArray): Feed {
    try {
      val json = JSONObject(String(feed, Charsets.UTF_8))
      if (json.getInt("schema") != SCHEMA) {
        throw signatureError("The update feed uses a newer format than this app understands.")
      }
      val bundles = json.getJSONArray("bundles")
      return Feed(
        sequence = json.getLong("sequence"),
        expiresAt = parseTime(json.getString("expires")),
        bundles = List(bundles.length()) { BundleEntry.fromJson(bundles.getJSONObject(it)) },
        revoked = json.optJSONArray("revoked").toStringList().toSet(),
      )
    } catch (e: JSONException) {
      throw signatureError("The update feed is not valid: ${e.message}")
    } catch (e: ParseException) {
      throw signatureError("The update feed has an unreadable expiry date.")
    }
  }

  /** Compares dotted numeric versions such as 2026.8.19 and 2026.11.3 (also 4-part nightlies). */
  fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.toLongOrNull() ?: 0 }
    val y = b.split('.').map { it.toLongOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
      val c = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0)
      if (c != 0) return c
    }
    return 0
  }

  private fun isSignedBy(data: ByteArray, signatureHex: String, keysHex: List<String>): Boolean {
    val signature = decodeHex(signatureHex) ?: return false
    return keysHex.any { keyHex ->
      val key = decodeHex(keyHex) ?: return@any false
      try {
        Signature.getInstance("SHA256withECDSA").run {
          initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)))
          update(data)
          verify(signature)
        }
      } catch (e: GeneralSecurityException) {
        false
      }
    }
  }

  private fun decodeHex(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val bytes = ByteArray(hex.length / 2)
    for (i in bytes.indices) {
      val hi = Character.digit(hex[2 * i], 16)
      val lo = Character.digit(hex[2 * i + 1], 16)
      if (hi < 0 || lo < 0) return null
      bytes[i] = ((hi shl 4) or lo).toByte()
    }
    return bytes
  }

  private fun parseTime(value: String): Long {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    format.isLenient = false
    return format.parse(value)?.time ?: throw ParseException(value, 0)
  }

  private fun signatureError(message: String) = UpdateException(UpdateException.SIGNATURE, message)
}

internal fun JSONArray?.toStringList(): List<String> =
  if (this == null) emptyList() else List(length()) { getString(it) }
