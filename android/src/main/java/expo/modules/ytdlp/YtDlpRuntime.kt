package expo.modules.ytdlp

import android.content.Context
import android.os.Build
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The bundled Python + yt-dlp runtime.
 *
 * Native pieces (libpython, the `python` launcher, QuickJS, optional ffmpeg, stdlib C
 * extensions) are installed by Android into nativeLibraryDir, the only app location that
 * allows exec. Pure-Python pieces ship as zips in assets and are extracted once per bundle
 * version into noBackupFilesDir.
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

  @Volatile
  private var installation: Installation? = null

  @Synchronized
  fun ensureInstalled(): Installation {
    installation?.let { return it }
    if (!isSupported) {
      throw UnsupportedRuntimeException(unsupportedReason())
    }

    val manifest = JSONObject(context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() })
    val bundleId = manifest.getString("bundleId")
    val pyMinor = manifest.getString("python").split(".").take(2).joinToString(".")
    val dir = File(root, bundleId)

    if (!File(dir, COMPLETE_MARKER).exists()) {
      // Extract into a temp dir and rename it into place, so a first launch killed halfway
      // never leaves a partial runtime that later looks valid.
      root.mkdirs()
      val tmp = File(root, ".tmp-$bundleId-${System.nanoTime()}")
      try {
        unzipAsset("$ASSET_DIR/python-stdlib.zip", File(tmp, "python/lib/python$pyMinor"))
        unzipAsset("$ASSET_DIR/site-packages.zip", File(tmp, "site-packages"))
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
    root.listFiles()?.filter { it.name != bundleId }?.forEach { it.deleteRecursively() }

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

  private fun unzipAsset(asset: String, dest: File) {
    dest.mkdirs()
    val destPath = dest.canonicalPath + File.separator
    ZipInputStream(context.assets.open(asset).buffered()).use { zip ->
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
          out.outputStream().use { zip.copyTo(it) }
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
    val inst = ensureInstalled()
    val tmp = File(cacheDir, "tmp").apply { mkdirs() }
    val home = File(root, "home").apply { mkdirs() }

    val command = mutableListOf(python.absolutePath, "-m", module)
    command += moduleArgs
    command += listOf("--ignore-config", "--color", "never", "--cache-dir", File(cacheDir, "yt-dlp").path)
    if (hasJsRuntime) {
      command += listOf("--no-js-runtimes", "--js-runtimes", "quickjs:${quickJs.absolutePath}")
    }
    if (hasFfmpeg) {
      // yt-dlp finds ffprobe by replacing "ffmpeg" in this file name: libffprobe.so.
      command += listOf("--ffmpeg-location", ffmpeg.absolutePath)
    }
    command += args

    return ProcessBuilder(command).apply {
      directory(tmp)
      environment().apply {
        put("PYTHONHOME", inst.pythonHome.path)
        put("PYTHONPATH", inst.sitePackages.path)
        put("LD_LIBRARY_PATH", nativeDir.path)
        put("PYTHONUNBUFFERED", "1")
        put("PYTHONUTF8", "1")
        put("PYTHONDONTWRITEBYTECODE", "1")
        put("PYTHONNOUSERSITE", "1")
        put("TMPDIR", tmp.path) // QuickJS challenge scripts are written to temp files
        put("HOME", home.path)
        put("SSL_CERT_FILE", File(inst.sitePackages, "certifi/cacert.pem").path)
        put("LANG", "C.UTF-8")
      }
    }
  }

  fun info(): Map<String, Any?> {
    val manifest = ensureInstalled().manifest
    val extractors = manifest.getJSONArray("extractors")
    return mapOf(
      "python" to manifest.getString("python"),
      "ytDlp" to manifest.getString("ytDlp"),
      "quickjs" to if (hasJsRuntime) manifest.getString("quickjsNg") else null,
      "hasFfmpeg" to hasFfmpeg,
      "abi" to installedAbi,
      "extractors" to List(extractors.length()) { extractors.getString(it) },
    )
  }

  companion object {
    const val ASSET_DIR = "expo-yt-dlp"
    private const val COMPLETE_MARKER = ".complete"
  }
}
