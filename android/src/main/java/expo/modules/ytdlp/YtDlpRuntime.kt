package expo.modules.ytdlp

import android.content.Context
import android.os.Build
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Timer
import java.util.zip.ZipInputStream
import kotlin.concurrent.schedule

/**
 * The bundled Python + yt-dlp runtime.
 *
 * Native pieces (libpython, the `python` launcher, QuickJS, optional ffmpeg, stdlib C
 * extensions) are installed by Android into nativeLibraryDir, the only app location that
 * allows exec. Pure-Python pieces ship as zips in assets and are extracted once per bundle
 * version into noBackupFilesDir.
 *
 * yt-dlp itself (site-packages) can also come from a newer, verified download; see
 * [BundleUpdater]. The copy in the APK is always kept as the fallback.
 */
internal class YtDlpRuntime(private val context: Context) {
  private val nativeDir = File(context.applicationInfo.nativeLibraryDir)

  /** The ABI the package manager installed, e.g. .../lib/arm64 -> arm64-v8a. */
  private val installedAbi = if (nativeDir.name == "arm64") "arm64-v8a" else nativeDir.name
  private val root = File(context.noBackupFilesDir, "expo-yt-dlp")
  val cacheDir = File(context.cacheDir, "expo-yt-dlp")

  val python = File(nativeDir, "libytdlp_python.so")
  val quickJs = File(nativeDir, "libqjs.so")
  val ffmpeg = File(nativeDir, "libffmpeg.so")
  private val ffprobe = File(nativeDir, "libffprobe.so")

  val isSupported: Boolean
    get() = python.canExecute()

  val hasFfmpeg: Boolean
    get() = ffmpeg.canExecute() && ffprobe.canExecute()

  val hasJsRuntime: Boolean
    get() = quickJs.canExecute()

  class Installation(val pythonHome: File, val sitePackages: File, val manifest: JSONObject)

  /** assets/expo-yt-dlp/manifest.json, written by scripts/build_android_runtime.py. */
  val manifest: JSONObject by lazy {
    JSONObject(context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() })
  }

  val runtimeApi: Int
    get() = manifest.optInt("runtimeApi", 0)

  val bundledYtDlp: String
    get() = manifest.getString("ytDlp")

  /** Update state, reconciled with this app version the first time it is touched. */
  val store: UpdateStore by lazy {
    UpdateStore(root).also { it.reconcile(runtimeApi, bundledYtDlp) }
  }

  @Volatile
  private var installation: Installation? = null

  @Synchronized
  fun ensureInstalled(): Installation {
    installation?.let { return it }
    if (!isSupported) {
      throw UnsupportedRuntimeException(unsupportedReason())
    }

    val bundleId = manifest.getString("bundleId")
    val pyMinor = manifest.getString("python").split(".").take(2).joinToString(".")
    val dir = File(root, bundleId)

    if (!File(dir, COMPLETE_MARKER).exists()) {
      // Extract into a temp dir and rename it into place, so a first launch killed halfway
      // never leaves a partial runtime that later looks valid.
      root.mkdirs()
      val tmp = File(root, ".tmp-$bundleId-${System.nanoTime()}")
      try {
        context.assets.open("$ASSET_DIR/python-stdlib.zip").use { unzip(it, File(tmp, "python/lib/python$pyMinor")) }
        context.assets.open("$ASSET_DIR/site-packages.zip").use { unzip(it, File(tmp, "site-packages")) }
        File(tmp, COMPLETE_MARKER).createNewFile()
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
          throw IOException("Could not move extracted runtime into $dir")
        }
      } finally {
        tmp.deleteRecursively()
      }
    }
    // Drop runtimes from previous app versions and leftovers from interrupted extractions.
    // Downloaded bundles and the update state are looked after by UpdateStore.
    val keep = setOf(bundleId, UpdateStore.OTA_DIR, UpdateStore.STATE_FILE)
    root.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }

    // nativeLibraryDir changes on every app update, so the links are rebuilt once per process.
    val extSuffix = manifest.getJSONObject("abis").getJSONObject(installedAbi).getString("extSuffix")
    linkExtensionModules(File(dir, "python/lib/python$pyMinor/lib-dynload"), extSuffix)

    return Installation(File(dir, "python"), File(dir, "site-packages"), manifest).also { installation = it }
  }

  /**
   * Stdlib C extensions ship as jniLibs/<abi>/libpymod_<name>.so (Android only installs
   * lib*.so). Symlink them into lib-dynload under the name Python looks for,
   * <name>.cpython-314-<triplet>.so (Android's libpython doesn't accept a bare .so).
   */
  private fun linkExtensionModules(dynload: File, extSuffix: String) {
    dynload.deleteRecursively()
    dynload.mkdirs()
    val prefix = "libpymod_"
    nativeDir.listFiles()?.forEach { lib ->
      if (lib.name.startsWith(prefix) && lib.name.endsWith(".so")) {
        val module = lib.name.removePrefix(prefix).removeSuffix(".so")
        Os.symlink(lib.absolutePath, File(dynload, module + extSuffix).absolutePath)
      }
    }
  }

  /**
   * Extracts a zip into [dest]. Entries can only become regular files or directories inside
   * [dest]; [maxBytes] bounds the extracted size (for zips that did not come from the APK).
   */
  fun unzip(input: InputStream, dest: File, maxBytes: Long = Long.MAX_VALUE) {
    dest.mkdirs()
    val destPath = dest.canonicalPath + File.separator
    var remaining = maxBytes
    val buffer = ByteArray(64 * 1024)
    ZipInputStream(input.buffered()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val out = File(dest, entry.name)
        if (!out.canonicalPath.startsWith(destPath)) {
          throw IOException("Bad zip entry ${entry.name}")
        }
        if (entry.isDirectory) {
          out.mkdirs()
        } else {
          out.parentFile?.mkdirs()
          out.outputStream().use { file ->
            while (true) {
              val n = zip.read(buffer)
              if (n < 0) break
              remaining -= n
              if (remaining < 0) throw IOException("Zip expands to more than $maxBytes bytes")
              file.write(buffer, 0, n)
            }
          }
        }
      }
    }
  }

  private fun unsupportedReason(): String {
    val bundledAbis = try {
      JSONObject(context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() })
        .getJSONObject("abis").keys().asSequence().toList()
    } catch (e: IOException) {
      return "expo-yt-dlp runtime assets are missing from the app. Run `npm run build:runtime` in the package before publishing."
    }
    val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull()
    if (Build.SUPPORTED_ABIS.none { it in bundledAbis }) {
      return "expo-yt-dlp supports $bundledAbis; this device is $deviceAbi."
    }
    return "The bundled binaries were not extracted to ${nativeDir.path}. Add \"expo-yt-dlp\" to the " +
      "plugins in app.json (it sets expo.useLegacyPackaging=true) and rebuild the app."
  }

  /**
   * Builds a `python -m <module>` process with the runtime's environment. [moduleArgs] (the module's own flags) go
   * before the yt-dlp options, which always start with the fixed runtime flags.
   */
  fun processBuilder(args: List<String>, module: String = "yt_dlp", moduleArgs: List<String> = emptyList()): ProcessBuilder {
    val sitePackages = activeSitePackages() // first: reports an unsupported device properly
    val command = listOf(python.absolutePath, "-m", module) + moduleArgs + runtimeArgs() + args
    return pythonProcess(command, sitePackages)
  }

  /** The yt-dlp options every call starts with. */
  fun runtimeArgs(): List<String> {
    val args = mutableListOf("--ignore-config", "--color", "never", "--cache-dir", File(cacheDir, "yt-dlp").path)
    if (hasJsRuntime) {
      args += listOf("--no-js-runtimes", "--js-runtimes", "quickjs:${quickJs.absolutePath}")
    }
    if (hasFfmpeg) {
      // yt-dlp finds ffprobe by replacing "ffmpeg" in this file name: libffprobe.so.
      args += listOf("--ffmpeg-location", ffmpeg.absolutePath)
    }
    return args
  }

  /** The yt-dlp in use: a downloaded bundle if there is one, otherwise the one shipped in the app. */
  private fun activeSitePackages(): File {
    val bundled = ensureInstalled().sitePackages
    return store.activeBundle()?.second ?: bundled
  }

  /** The long-running process behind [YtDlpEngine], for the yt-dlp in use right now. */
  fun engineLaunch(): YtDlpEngine.Launch {
    val sitePackages = activeSitePackages()
    val command = listOf(python.absolutePath, "-m", "expo_yt_dlp_runner", "--serve")
    return YtDlpEngine.Launch(sitePackages.path, pythonProcess(command, sitePackages))
  }

  /**
   * Imports a freshly extracted bundle the way a real job would and returns the yt-dlp
   * version it reports, or null if it does not start (or hangs).
   */
  fun selfCheck(sitePackages: File): String? {
    val code = "import expo_yt_dlp_runner, yt_dlp.version; print(yt_dlp.version.__version__)"
    val process = YtDlpProcess(pythonProcess(listOf(python.absolutePath, "-c", code), sitePackages))
    val watchdog = Timer(true)
    watchdog.schedule(SELF_CHECK_TIMEOUT_MS) { process.cancel() }
    return try {
      val result = process.run()
      result.stdout.trim().takeIf { result.exitCode == 0 && it.isNotEmpty() }
    } catch (e: YtDlpCancelledException) {
      null
    } finally {
      watchdog.cancel()
    }
  }

  private fun pythonProcess(command: List<String>, sitePackages: File): ProcessBuilder {
    val inst = ensureInstalled()
    val tmp = File(cacheDir, "tmp").apply { mkdirs() }
    val home = File(root, "home").apply { mkdirs() }

    return ProcessBuilder(command).apply {
      directory(tmp)
      environment().apply {
        put("PYTHONHOME", inst.pythonHome.path)
        put("PYTHONPATH", sitePackages.path)
        put("LD_LIBRARY_PATH", nativeDir.path)
        put("PYTHONUNBUFFERED", "1")
        put("PYTHONUTF8", "1")
        put("PYTHONDONTWRITEBYTECODE", "1")
        put("PYTHONNOUSERSITE", "1")
        put("TMPDIR", tmp.path) // QuickJS challenge scripts are written to temp files
        put("HOME", home.path)
        put("SSL_CERT_FILE", File(sitePackages, "certifi/cacert.pem").path)
        put("LANG", "C.UTF-8")
      }
    }
  }

  fun info(): Map<String, Any?> {
    val manifest = ensureInstalled().manifest
    val downloaded = store.activeBundle()?.first
    return mapOf(
      "python" to manifest.getString("python"),
      "ytDlp" to (downloaded?.ytDlp ?: bundledYtDlp),
      "quickjs" to if (hasJsRuntime) manifest.getString("quickjsNg") else null,
      "hasFfmpeg" to hasFfmpeg,
      "abi" to installedAbi,
      "extractors" to (downloaded?.extractors ?: manifest.getJSONArray("extractors").toStringList()),
      "source" to if (downloaded != null) "downloaded" else "bundled",
      "bundleId" to (downloaded?.bundleId ?: manifest.optString("siteBundleId")),
      "runtimeApi" to runtimeApi,
    )
  }

  companion object {
    const val ASSET_DIR = "expo-yt-dlp"
    private const val COMPLETE_MARKER = ".complete"
    private const val SELF_CHECK_TIMEOUT_MS = 60_000L
  }
}
