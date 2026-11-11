// JVM tests for UpdateFeed.kt and UpdateStore.kt, run by scripts/test_update_logic.py against
// feeds signed by the real tooling (scripts/publish_bundle.py).
package expo.modules.ytdlp

import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean) {
  println("${if (ok) "PASS" else "FAIL"}  $name")
  if (!ok) failures++
}

private fun rejected(block: () -> Unit): Boolean = try {
  block()
  false
} catch (e: UpdateException) {
  e.code == UpdateException.SIGNATURE
}

fun main(args: Array<String>) {
  val dir = File(args[0])
  fun text(name: String) = File(dir, name).readText().trim()
  fun bytes(name: String) = File(dir, name).readBytes()
  val ci = listOf(text("ci.hex"))
  val ci2 = listOf(text("ci2.hex"))
  val backup = listOf(text("backup.hex"))
  val feed = bytes("feed.json")
  val sig = text("feed.json.sig")

  // --- Signatures ---------------------------------------------------------------------
  check("valid signature is accepted", UpdateFeed.verify(feed, sig, ci, backup, null) == null)
  check("unknown key is rejected", rejected { UpdateFeed.verify(feed, sig, ci2, backup, null) })
  check("no keys is rejected", rejected { UpdateFeed.verify(feed, sig, emptyList(), emptyList(), null) })
  check("changed feed is rejected", rejected {
    UpdateFeed.verify(feed.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }, sig, ci, backup, null)
  })
  check("empty signature is rejected", rejected { UpdateFeed.verify(feed, "\n", ci, backup, null) })
  check("garbage signature is rejected", rejected { UpdateFeed.verify(feed, "zz", ci, backup, null) })

  // --- Key rotation: feed signed by ci2, with a backup-signed line naming ci2 -----------
  val rotatedSig = text("rotated.sig")
  val rotation = UpdateFeed.verify(feed, rotatedSig, ci, backup, null)
  check("rotation is accepted and returned", rotation?.epoch == 1 && rotation.keys == ci2)
  check("known rotation is not returned again", UpdateFeed.verify(feed, rotatedSig, ci, backup, rotation) == null)
  check("rotation needs a pinned backup key", rejected { UpdateFeed.verify(feed, rotatedSig, ci, emptyList(), null) })
  check("retired key is rejected after rotation", rejected { UpdateFeed.verify(feed, sig, ci, backup, rotation) })
  check("retired key can't ride on the rotation line", rejected {
    UpdateFeed.verify(feed, sig.lines()[0] + "\n" + rotatedSig.lines()[1], ci, backup, null)
  })
  check("forged rotation line is rejected", rejected {
    val line = rotatedSig.lines()[1].split(' ')
    UpdateFeed.verify(feed, "${sig.lines()[0]}\n2 ${ci[0]} ${line[2]}", ci, backup, rotation)
  })
  check("older rotation is ignored once a newer one is known", rejected {
    UpdateFeed.verify(feed, rotatedSig, ci, backup, KeyRotation(2, ci))
  })

  // --- Parsing --------------------------------------------------------------------------
  val parsed = UpdateFeed.parse(feed)
  val now = System.currentTimeMillis()
  check("feed fields are read", parsed.sequence == 1L && parsed.bundles.size == 1 && parsed.revoked.isEmpty())
  check("expiry is about 30 days ahead", parsed.expiresAt in (now + 29 * 86_400_000L)..(now + 31 * 86_400_000L))
  val entry = parsed.bundles[0]
  check("bundle fields are read", entry.runtimeApi == 1 && entry.ytDlp == "2026.11.3" &&
    entry.bundleId == "0123456789abcdef" && entry.size == 1234L && entry.extractors == listOf("YoutubeIE"))
  check("expired feed parses with a past expiry", UpdateFeed.parse(bytes("expired.json")).expiresAt < now)

  fun withEntry(change: JSONObject.() -> Unit): ByteArray {
    val json = JSONObject(String(feed))
    json.getJSONArray("bundles").getJSONObject(0).change()
    return json.toString().toByteArray()
  }
  check("path-like bundle id is rejected", rejected { UpdateFeed.parse(withEntry { put("bundleId", "../../../etc/x") }) })
  check("http bundle url is rejected", rejected { UpdateFeed.parse(withEntry { put("url", "http://example.com/a.zip") }) })
  check("oversized bundle is rejected", rejected { UpdateFeed.parse(withEntry { put("size", 1L shl 40) }) })
  check("bad sha256 is rejected", rejected { UpdateFeed.parse(withEntry { put("sha256", "abc") }) })
  check("unknown schema is rejected", rejected { UpdateFeed.parse(String(feed).replace("\"schema\": 1", "\"schema\": 2").toByteArray()) })
  check("non-JSON is rejected", rejected { UpdateFeed.parse("<html>".toByteArray()) })

  // --- Versions -------------------------------------------------------------------------
  check("versions compare numerically", UpdateFeed.compareVersions("2026.11.3", "2026.8.19") > 0 &&
    UpdateFeed.compareVersions("2026.8.19", "2026.8.19") == 0 &&
    UpdateFeed.compareVersions("2026.8.19", "2026.8.19.123") < 0 &&
    UpdateFeed.compareVersions("2026.8.9", "2026.8.19") < 0)

  // --- Store ----------------------------------------------------------------------------
  val root = File(dir, "store")
  var store = UpdateStore(root)
  check("default mode is bundled, nothing active", store.mode == "bundled" && store.activeBundle() == null)
  store.edit { put("mode", "auto").put("active", entry.toJson()) }
  check("a bundle without its files is not used", store.activeBundle() == null)
  File(store.bundleDir(entry.bundleId), "site-packages").mkdirs()
  check("a bundle without the completion marker is not used", store.activeBundle() == null)
  File(store.bundleDir(entry.bundleId), ".complete").createNewFile()
  check("a complete bundle is used", store.activeBundle()?.second == File(root, "ota/0123456789abcdef/site-packages"))
  store.edit { put("mode", "bundled") }
  check("bundled mode ignores the download", store.activeBundle() == null && store.downloaded != null)
  store.edit { put("mode", "manual") }

  store = UpdateStore(root)
  check("state survives a restart", store.mode == "manual" && store.activeBundle()?.first?.ytDlp == "2026.11.3")
  File(store.otaRoot, ".tmp-leftover").mkdirs()
  File(store.otaRoot, "fedcba9876543210").mkdirs()
  store.reconcile(runtimeApi = 1, bundledYtDlp = "2026.8.19")
  check("reconcile keeps a newer compatible bundle", store.activeBundle() != null)
  check("reconcile deletes other bundle directories", store.otaRoot.list()!!.toList() == listOf("0123456789abcdef"))
  store.reconcile(runtimeApi = 2, bundledYtDlp = "2026.8.19")
  check("reconcile drops a bundle for another runtimeApi", store.downloaded == null && store.otaRoot.list()!!.isEmpty())

  store.edit { put("active", entry.toJson()) }
  File(store.bundleDir(entry.bundleId), ".complete").apply { parentFile!!.mkdirs() }.createNewFile()
  store.reconcile(runtimeApi = 1, bundledYtDlp = "2026.11.3")
  check("reconcile drops a bundle the app update caught up with", store.downloaded == null)

  store.edit { put("active", entry.toJson()) }
  store.markBad(entry.bundleId)
  check("a bad bundle is dropped and remembered", store.downloaded == null && entry.bundleId in UpdateStore(root).badBundles)

  File(root, "state.json").writeText("{not json")
  check("corrupt state falls back to defaults", UpdateStore(root).mode == "bundled")

  println(if (failures == 0) "update logic: all checks passed" else "update logic: $failures FAILED")
  exitProcess(if (failures == 0) 0 else 1)
}
