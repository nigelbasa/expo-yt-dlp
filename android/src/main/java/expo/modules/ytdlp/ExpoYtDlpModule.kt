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

// getInfo and download run through python/expo_yt_dlp_runner.py (yt-dlp plus retries for
// transient network errors). In download mode it prints one JSON event per line with this
// prefix; anything else on stdout is ignored.
private const val RUNNER_MODULE = "expo_yt_dlp_runner"
private const val EVENT_MARK = "__EYD__"

class ExpoYtDlpModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val ytDlp by lazy { YtDlpRuntime(context.applicationContext) }
  private val jobs = ConcurrentHashMap<String, YtDlpProcess>()

  override fun definition() = ModuleDefinition {
    Name("ExpoYtDlp")

    Events(PROGRESS_EVENT)

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

    Function("cancel") { id: String ->
      jobs[id]?.cancel() != null
    }

    OnDestroy {
      jobs.values.forEach { it.cancel() }
    }
  }

  private fun runJob(
    id: String,
    args: List<String>,
    module: String = "yt_dlp",
    moduleArgs: List<String> = emptyList(),
    onLine: ((String) -> Unit)? = null,
  ): YtDlpProcess.Result {
    val job = YtDlpProcess(ytDlp.processBuilder(args, module, moduleArgs))
    if (jobs.putIfAbsent(id, job) != null) throw DuplicateJobException(id)
    try {
      return job.run(onLine)
    } finally {
      jobs.remove(id, job)
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
    val nativeMerge = options.format == null && !options.audioOnly && !ytDlp.hasFfmpeg
    val moduleArgs = mutableListOf("--network-retries", options.networkRetries.toString())
    if (nativeMerge) {
      moduleArgs += "--native-merge"
      options.maxHeight?.let { moduleArgs += listOf("--max-res", it.toString()) }
    }
    moduleArgs += "--"

    val args = mutableListOf(
      "-P", outputDir.path,
      "-o", if (nativeMerge) "%(title).150B [%(id)s].f%(format_id)s.%(ext)s" else "%(title).150B [%(id)s].%(ext)s",
      "--no-mtime",
    )
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
    val finalItems = items ?: throw YtDlpException("yt-dlp finished without reporting its files")

    val paths = mutableListOf<String>()
    for (i in 0 until finalItems.length()) {
      val item = finalItems.getJSONObject(i)
      val files = item.getJSONArray("files")
      val merge = item.optJSONObject("merge")
      if (merge == null) {
        for (j in 0 until files.length()) {
          val path = files.getJSONObject(j).getString("path")
          paths += if (nativeMerge && files.length() == 1) stripFormatId(path) else path
        }
        continue
      }
      sendEvent(PROGRESS_EVENT, mapOf("id" to id, "videoId" to item.optStringOrNull("id"), "status" to "merging"))
      val videoPath = merge.getString("video")
      val audioPath = merge.getString("audio")
      val merged = FORMAT_ID_SUFFIX.replace(videoPath, "") + ".mp4"
      NativeMuxer.merge(videoPath, audioPath, merged)
      File(videoPath).delete()
      File(audioPath).delete()
      paths += merged
    }
    if (paths.isEmpty()) throw YtDlpException("yt-dlp finished without downloading any file")
    return mapOf("id" to id, "files" to paths)
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
