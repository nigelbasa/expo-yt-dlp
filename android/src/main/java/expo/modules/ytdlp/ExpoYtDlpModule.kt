package expo.modules.ytdlp

import android.content.Context
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val PROGRESS_EVENT = "onProgress"
private const val UPDATE_EVENT = "onUpdate"

// stderr of a Python process whose yt-dlp bundle is damaged, as opposed to yt-dlp failing on a URL.
private val BROKEN_BUNDLE = Regex("ModuleNotFoundError|ImportError|bad magic number|zipimport\\.ZipImportError")

// getInfo and download run through python/expo_yt_dlp_runner.py (yt-dlp plus retries for
// transient network errors). In download mode it prints one JSON event per line with this
// prefix; anything else on stdout is ignored.
private const val RUNNER_MODULE = "expo_yt_dlp_runner"
private const val EVENT_MARK = "__EYD__"

class ExpoYtDlpModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val ytDlp by lazy { YtDlpRuntime(context.applicationContext) }
  private val updater by lazy { BundleUpdater(ytDlp) { event -> sendEvent(UPDATE_EVENT, event) } }
  private val engine by lazy { YtDlpEngine({ ytDlp.engineLaunch() }) }
  private val jobs = ConcurrentHashMap<String, JobHandle>()

  override fun definition() = ModuleDefinition {
    Name("ExpoYtDlp")

    Events(PROGRESS_EVENT, UPDATE_EVENT)

    Function("isSupported") {
      ytDlp.isSupported
    }

    AsyncFunction("prepare") Coroutine { ->
      withContext(Dispatchers.IO) { ytDlp.info() }
    }

    AsyncFunction("getInfo") Coroutine { id: String, url: String, options: InfoOptions ->
      withContext(Dispatchers.IO) { getInfo(id, url, options) }
    }

    AsyncFunction("download") Coroutine { id: String, url: String, options: DownloadOptions ->
      withContext(Dispatchers.IO) { download(id, url, options) }
    }

    AsyncFunction("exec") Coroutine { id: String, args: List<String> ->
      withContext(Dispatchers.IO) {
        val result = runJob(id, args)
        mapOf("exitCode" to result.exitCode, "stdout" to result.stdout, "stderr" to result.stderr)
      }
    }

    // yt-dlp updates: see BundleUpdater. The mode is stored, so it applies from app start.
    Function("setUpdates") { config: UpdateConfig ->
      updater.configure(config)
      updater.state()
    }

    Function("getUpdateState") {
      updater.state()
    }

    AsyncFunction("checkForUpdate") Coroutine { ->
      withContext(Dispatchers.IO) { updater.check() }
    }

    AsyncFunction("updateYtDlp") Coroutine { ->
      withContext(Dispatchers.IO) { updater.update() }
    }

    AsyncFunction("resetToBundled") Coroutine { ->
      withContext(Dispatchers.IO) { updater.reset() }
    }

    // The engine: one Python process kept running between calls (see YtDlpEngine).
    AsyncFunction("warmUp") Coroutine { ->
      withContext(Dispatchers.IO) {
        val running = ytDlp.isSupported && engine.warmUp()
        mapOf(
          "running" to running,
          "startupMs" to if (running) engine.startupMs?.toDouble() else null,
          "reason" to when {
            running -> null
            !ytDlp.isSupported -> "yt-dlp can't run on this device."
            !engine.enabled -> "The engine is switched off."
            else -> engine.startFailure
          },
        )
      }
    }

    Function("shutDown") {
      engine.shutDown()
    }

    Function("setEngine") { enabled: Boolean ->
      engine.enabled = enabled
    }

    Function("isEngineRunning") {
      engine.isRunning
    }

    Function("cancel") { id: String ->
      jobs[id]?.cancel() != null
    }

    OnDestroy {
      jobs.values.forEach { it.cancel() }
      engine.shutDown()
    }
  }

  private fun runJob(
    id: String,
    args: List<String>,
    module: String = "yt_dlp",
    moduleArgs: List<String> = emptyList(),
    onLine: ((String) -> Unit)? = null,
  ): YtDlpProcess.Result {
    // On an unsupported device the process below reports why; don't trip over update state first.
    if (ytDlp.isSupported) updater.autoCheck()
    val downloadedBundle = if (ytDlp.isSupported) ytDlp.store.activeBundle()?.first?.bundleId else null
    var result = runProcess(id, args, module, moduleArgs, onLine)
    if (result.exitCode != 0 && downloadedBundle != null && BROKEN_BUNDLE.containsMatchIn(result.stderr)) {
      // The downloaded yt-dlp can't even be imported: drop it and redo the call on the bundled one.
      updater.reportBroken(downloadedBundle, YtDlpProcess.errorMessage(result))
      result = runProcess(id, args, module, moduleArgs, onLine)
    }
    // A failing extractor is the usual sign that a newer yt-dlp is needed.
    if (result.exitCode != 0) updater.autoCheck(afterFailure = true)
    return result
  }

  private fun runProcess(
    id: String,
    args: List<String>,
    module: String,
    moduleArgs: List<String>,
    onLine: ((String) -> Unit)?,
  ): YtDlpProcess.Result {
    val handle = JobHandle()
    if (jobs.putIfAbsent(id, handle) != null) throw DuplicateJobException(id)
    try {
      if (module == RUNNER_MODULE && ytDlp.isSupported) {
        // The running engine takes the call if it is free; otherwise it gets its own process.
        engine.run(handle, moduleArgs + ytDlp.runtimeArgs() + args, onLine)?.let { return it }
      }
      val job = YtDlpProcess(ytDlp.processBuilder(args, module, moduleArgs))
      handle.attach(job)
      return job.run(onLine)
    } finally {
      jobs.remove(id, handle)
    }
  }

  private fun getInfo(id: String, url: String, options: InfoOptions): String {
    val args = mutableListOf<String>()
    if (options.flatPlaylist) args += "--flat-playlist"
    args += if (options.noPlaylist) "--no-playlist" else "--yes-playlist"
    options.cookiesFile?.let { args += listOf("--cookies", it.removePrefix("file://")) }
    args += options.extraArgs
    args += listOf("--", url)

    val moduleArgs = listOf("--info", "--network-retries", options.networkRetries.toString(), "--")
    val result = runJob(id, args, RUNNER_MODULE, moduleArgs)
    if (result.exitCode != 0) throw YtDlpException(YtDlpProcess.errorMessage(result))
    return result.stdout // parsed on the JS side; avoids converting a huge nested map over the bridge
  }

  private fun download(id: String, url: String, options: DownloadOptions): Map<String, Any?> {
    val outputDir = File(options.outputDir?.removePrefix("file://") ?: File(context.filesDir, "downloads").path)
    outputDir.mkdirs()

    // Without ffmpeg the runner picks, per video, an H.264 video + AAC audio pair to merge
    // here with MediaMuxer (or a single file with video when there is no such pair).
    val nativeMerge = options.format == null && !options.audioOnly && !options.skipMedia && !ytDlp.hasFfmpeg
    val moduleArgs = mutableListOf("--network-retries", options.networkRetries.toString())
    if (nativeMerge) {
      moduleArgs += "--native-merge"
      options.maxHeight?.let { moduleArgs += listOf("--max-res", it.toString()) }
    }
    moduleArgs += "--"

    val name = "%(title).150B [%(id)s]"
    val args = mutableListOf("-P", outputDir.path, "--no-mtime")
    if (nativeMerge) {
      // Video and audio are separate downloads, so their names carry the format id until
      // they're merged. Subtitles/thumbnails would otherwise be written once per format.
      args += listOf("-o", "$name.f%(format_id)s.%(ext)s", "-o", "subtitle:$name.%(ext)s", "-o", "thumbnail:$name.%(ext)s")
    } else {
      args += listOf("-o", "$name.%(ext)s")
    }
    when {
      options.format != null -> args += listOf("-f", options.format)
      options.audioOnly -> args += listOf("-f", "ba[ext=m4a]/ba/b")
      !nativeMerge -> {
        // ffmpeg merges. Prefer H.264/AAC for playback compatibility; maxHeight caps the
        // short side, so portrait videos are treated like landscape ones.
        val res = options.maxHeight?.let { ",res:$it" } ?: ""
        args += listOf("-f", "bv*+ba/b", "-S", "vcodec:h264$res,acodec:aac", "--merge-output-format", "mp4")
      }
    }
    if (options.simulate) args += "--simulate"
    if (options.skipMedia) args += "--skip-download"
    args += if (options.noPlaylist) "--no-playlist" else "--yes-playlist"
    options.cookiesFile?.let { args += listOf("--cookies", it.removePrefix("file://")) }
    args += options.extraArgs
    args += listOf("--", url)

    var items: JSONArray? = null
    val partials = mutableSetOf<String>()
    fun handleLine(line: String) {
      if (!line.startsWith(EVENT_MARK)) return
      val event = try {
        JSONObject(line.substring(EVENT_MARK.length))
      } catch (e: JSONException) {
        return
      }
      when (event.optString("event")) {
        "progress" -> {
          event.optStringOrNull("tmpFilename")?.let { partials += it }
          sendEvent(PROGRESS_EVENT, mapOf(
            "id" to id,
            "videoId" to event.optStringOrNull("videoId"),
            "formatId" to event.optStringOrNull("formatId"),
            "status" to event.optString("status"),
            "downloadedBytes" to event.optNumber("downloadedBytes"),
            "totalBytes" to event.optNumber("totalBytes"),
            "percent" to event.optNumber("percent"),
            "speed" to event.optNumber("speed"),
            "eta" to event.optNumber("eta"),
            "fragmentIndex" to event.optNumber("fragmentIndex"),
            "fragmentCount" to event.optNumber("fragmentCount"),
          ))
        }
        "retry" -> sendEvent(PROGRESS_EVENT, mapOf(
          "id" to id,
          "status" to "retrying",
          "attempt" to event.optNumber("attempt"),
          "retries" to event.optNumber("retries"),
          "retryDelay" to event.optNumber("delay"),
        ))
        "result" -> items = event.optJSONArray("items")
      }
    }

    val result = try {
      runJob(id, args, RUNNER_MODULE, moduleArgs, ::handleLine)
    } catch (e: YtDlpCancelledException) {
      // yt-dlp is killed, so it can't clean up; the API has no resume, so drop partial files.
      partials.forEach(::deletePartial)
      throw e
    }
    if (result.exitCode != 0) throw YtDlpException(YtDlpProcess.errorMessage(result))
    val reported = items ?: throw YtDlpException("yt-dlp finished without reporting its files")

    val results = List(reported.length()) {
      finishItem(id, reported.getJSONObject(it), simulate = options.simulate, tidyNames = nativeMerge)
    }
    val outputs = results.sumOf { it.files.size + it.subtitleCount + it.thumbnailCount }
    if (!options.simulate && outputs == 0) throw YtDlpException("yt-dlp finished without writing any file")
    return mapOf(
      "id" to id,
      "files" to results.flatMap { item -> item.files.map { it["path"] } },
      "items" to results.map { it.toMap() },
    )
  }

  private class FinishedItem(val fields: Map<String, Any?>, val files: List<Map<String, Any?>>) {
    val subtitleCount = (fields["subtitles"] as? List<*>)?.size ?: 0
    val thumbnailCount = (fields["thumbnails"] as? List<*>)?.size ?: 0
    fun toMap() = fields + ("files" to files)
  }

  /**
   * Muxes the video/audio pair the runner asked for (its native-merge picks, or a `+` format
   * requested without ffmpeg), tidies a lone native-merge file's name, and converts the item
   * for JS.
   */
  @Suppress("UNCHECKED_CAST")
  private fun finishItem(jobId: String, item: JSONObject, simulate: Boolean, tidyNames: Boolean): FinishedItem {
    val fields = (item.toJsValue() as Map<String, Any?>).toMutableMap()
    val files = (fields.remove("files") as List<Map<String, Any?>>).toMutableList()
    val merge = fields.remove("merge") as Map<String, Any?>?
    if (simulate) return FinishedItem(fields, files)
    if (merge != null) {
      sendEvent(PROGRESS_EVENT, mapOf("id" to jobId, "videoId" to fields["id"], "status" to "merging"))
      val video = files.first { it["path"] == merge["video"] }
      val audio = files.first { it["path"] == merge["audio"] }
      val videoPath = video["path"] as String
      val merged = File(merge["output"] as String)
      NativeMuxer.merge(videoPath, audio["path"] as String, merged.path)
      File(videoPath).delete()
      File(audio["path"] as String).delete()
      files.clear()
      files += video + mapOf(
        "path" to merged.path,
        "formatId" to "${video["formatId"]}+${audio["formatId"]}",
        "ext" to merged.extension,
        "acodec" to audio["acodec"],
        "size" to merged.length().toDouble(),
        "sizeIsEstimate" to false,
      )
      fields["size"] = merged.length().toDouble()
      fields["sizeIsEstimate"] = false
    } else if (tidyNames && files.size == 1) {
      files[0] = files[0] + ("path" to stripFormatId(files[0]["path"] as String))
    }
    return FinishedItem(fields, files)
  }

  private fun deletePartial(path: String) {
    val file = File(path)
    file.delete()
    File("$path.ytdl").delete()
    file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}-Frag") }?.forEach { it.delete() }
  }

  /** "<title> [<id>].f<format>.<ext>" -> "<title> [<id>].<ext>", for single-file results. */
  private fun stripFormatId(path: String): String {
    val ext = File(path).extension
    val target = File(FORMAT_ID_SUFFIX.replace(path, "") + ".$ext")
    return if (File(path).renameTo(target)) target.path else path
  }
}

// Matches the ".f<format_id>.<ext>" tail of the native-merge output template.
private val FORMAT_ID_SUFFIX = Regex("""\.f[^./]+\.[^./]+$""")

private fun JSONObject.optNumber(key: String): Double? =
  if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.optStringOrNull(key: String): String? =
  if (isNull(key)) null else optString(key)

/** org.json values -> Maps/Lists/primitives the Expo bridge can pass to JS. */
private fun Any?.toJsValue(): Any? = when (this) {
  null, JSONObject.NULL -> null
  is JSONObject -> keys().asSequence().associateWith { get(it).toJsValue() }
  is JSONArray -> List(length()) { get(it).toJsValue() }
  else -> this
}
