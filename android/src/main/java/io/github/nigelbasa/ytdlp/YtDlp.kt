package io.github.nigelbasa.ytdlp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * yt-dlp for Android apps, with its own Python runtime.
 *
 * ```kotlin
 * val ytDlp = YtDlp(context)
 * val info = ytDlp.getInfo("https://www.youtube.com/watch?v=jNQXAC9IVRw")
 * val result = ytDlp.download(url, DownloadOptions(maxHeight = 720)) { p -> println(p.percent) }
 * ```
 *
 * Every function that runs yt-dlp suspends and works off the main thread. Cancelling the
 * coroutine stops yt-dlp and removes partial files. Failures throw [YtDlpException] with a
 * stable [YtDlpException.code]. Instances are cheap: they all share one runtime, one engine
 * and one update state per app process.
 *
 * The app must package native libraries uncompressed so they can be run:
 * `android { packaging { jniLibs { useLegacyPackaging = true } } }`.
 */
class YtDlp(context: Context) {
  private val core = YtDlpCore.get(context)

  /** False on devices this build has no binaries for (only arm64-v8a and x86_64 are built). */
  val isSupported: Boolean get() = core.runtime.isSupported

  /** Unpacks the runtime if needed (first call after install or app update takes a few seconds). */
  suspend fun prepare(): RuntimeInfo = io { RuntimeInfo.from(core.runtime.info()) }

  /** yt-dlp's full JSON for a URL (title, formats, subtitles, thumbnails, ...). */
  suspend fun getInfo(url: String, options: InfoOptions = InfoOptions()): JSONObject =
    JSONObject(job { id -> core.getInfo(id, url, options) })

  /**
   * Downloads a URL: by default the best H.264 video plus AAC audio, merged into one MP4.
   * [onProgress] is called on a background thread.
   */
  suspend fun download(
    url: String,
    options: DownloadOptions = DownloadOptions(),
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadResult = job { id ->
    DownloadResult.from(core.download(id, url, options) { onProgress(DownloadProgress.from(it)) })
  }

  /** Runs yt-dlp with any arguments, as on the command line. Doesn't throw on a non-zero exit. */
  suspend fun exec(vararg args: String): ExecResult = job { id ->
    core.exec(id, args.toList()).let { ExecResult(it.exitCode, it.stdout, it.stderr) }
  }

  // ---------------------------------------------------------------------------------------------
  // The engine: one Python process kept running between calls, which saves seconds per call.
  // It starts on first use and stops after 5 idle minutes; warmUp() starts it ahead of time.
  // ---------------------------------------------------------------------------------------------

  suspend fun warmUp(): EngineState = io {
    val state = core.warmUp()
    EngineState(state["running"] as Boolean, (state["startupMs"] as Double?)?.toLong(), state["reason"] as String?)
  }

  fun shutDown() = core.engine.shutDown()

  /** Off: every call starts its own Python process (slower; what versions before 0.3 did). */
  var engineEnabled: Boolean
    get() = core.engine.enabled
    set(value) {
      core.engine.enabled = value
    }

  val isEngineRunning: Boolean get() = core.engine.isRunning

  // ---------------------------------------------------------------------------------------------
  // Updates: newer yt-dlp versions, signed and verified, without an app update.
  // ---------------------------------------------------------------------------------------------

  /** Sets the update mode. Stored, so it applies from the next app start too. */
  fun setUpdates(config: UpdateConfig): UpdateState {
    core.updater.configure(config)
    return UpdateState.from(core.updater.state())
  }

  fun updateState(): UpdateState = UpdateState.from(core.updater.state())

  /** Asks the feed whether a newer yt-dlp exists. Installs nothing. */
  suspend fun checkForUpdate(): UpdateCheck = io { UpdateCheck.from(core.updater.check()) }

  /** Downloads, verifies and switches to a newer yt-dlp if there is one. Needs mode "auto" or "manual". */
  suspend fun update(): UpdateResult = io { UpdateResult.from(core.updater.update()) }

  /** Goes back to the yt-dlp shipped in the app. */
  suspend fun resetToBundled(): UpdateState = io { UpdateState.from(core.updater.reset()) }

  /** Update activity, including background checks in auto mode. Call the result to stop listening. */
  fun addUpdateListener(listener: (UpdateEvent) -> Unit): () -> Unit =
    core.addUpdateListener { listener(UpdateEvent.from(it)) }

  private suspend fun <T> io(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

  /** Runs a cancellable yt-dlp job: cancelling the coroutine kills the process. */
  private suspend fun <T> job(block: (String) -> T): T = coroutineScope {
    val id = "kt-${UUID.randomUUID()}"
    val work = async(Dispatchers.IO) {
      try {
        block(id)
      } catch (e: YtDlpCancelledException) {
        throw CancellationException(e.message)
      }
    }
    work.invokeOnCompletion { core.forget(id) }
    try {
      work.await()
    } catch (e: CancellationException) {
      core.cancel(id, early = true)
      throw e
    }
  }
}

data class RuntimeInfo(
  val ytDlp: String,
  val python: String,
  val abi: String?,
  val hasFfmpeg: Boolean,
  /** "bundled" (shipped in the app) or "downloaded" (an update). */
  val source: String,
  val bundleId: String?,
  val extractors: List<String>,
) {
  internal companion object {
    @Suppress("UNCHECKED_CAST")
    fun from(m: Map<String, Any?>) = RuntimeInfo(
      m["ytDlp"] as String, m["python"] as String, m["abi"] as String?, m["hasFfmpeg"] as Boolean,
      m["source"] as String, m["bundleId"] as String?, m["extractors"] as List<String>,
    )
  }
}

data class DownloadProgress(
  /** "downloading", "finished", "retrying" or "merging". */
  val status: String,
  val videoId: String?,
  val formatId: String?,
  val downloadedBytes: Long?,
  val totalBytes: Long?,
  /** 0-100; 100 when a file is finished. */
  val percent: Double?,
  /** Bytes per second. */
  val speed: Double?,
  /** Seconds left. */
  val eta: Double?,
  /** For "retrying": which attempt is next, of how many. */
  val attempt: Int?,
  val retries: Int?,
) {
  internal companion object {
    fun from(m: Map<String, Any?>): DownloadProgress {
      val status = m["status"] as String
      val percent = m["percent"] as Double?
      return DownloadProgress(
        status, m["videoId"] as String?, m["formatId"] as String?,
        (m["downloadedBytes"] as Double?)?.toLong(), (m["totalBytes"] as Double?)?.toLong(),
        // yt-dlp's last event for a file often has no total, so the percentage would be missing.
        if (status == "finished" && percent == null) 100.0 else percent,
        m["speed"] as Double?, m["eta"] as Double?,
        (m["attempt"] as Double?)?.toInt(), (m["retries"] as Double?)?.toInt(),
      )
    }
  }
}

data class DownloadResult(
  /** The media files written, in order. */
  val files: List<File>,
  /** One map per video: id, title, files (with format details), subtitles, thumbnails, size. */
  val items: List<Map<String, Any?>>,
) {
  internal companion object {
    @Suppress("UNCHECKED_CAST")
    fun from(m: Map<String, Any?>) = DownloadResult(
      (m["files"] as List<String>).map(::File),
      m["items"] as List<Map<String, Any?>>,
    )
  }
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

data class EngineState(
  val running: Boolean,
  val startupMs: Long?,
  /** Why it isn't running, as a sentence; null when it is. */
  val reason: String?,
)

/** A yt-dlp version: in use, or offered by the update feed. */
data class YtDlpVersion(val ytDlp: String, val bundleId: String?, val source: String? = null, val size: Long? = null) {
  internal companion object {
    fun from(m: Map<*, *>?) = m?.let {
      YtDlpVersion(
        it["ytDlp"] as String, it["bundleId"] as String?, it["source"] as String?, (it["size"] as Number?)?.toLong(),
      )
    }
  }
}

data class UpdateState(
  val mode: String,
  val checkIntervalHours: Double,
  val feedUrl: String,
  val current: YtDlpVersion,
  val latest: YtDlpVersion?,
  val updateAvailable: Boolean,
  /** Epoch milliseconds. */
  val lastCheck: Long?,
  val lastError: YtDlpException?,
) {
  internal companion object {
    fun from(m: Map<String, Any?>) = UpdateState(
      m["mode"] as String,
      m["checkIntervalHours"] as Double,
      (m["feed"] as Map<*, *>)["url"] as String,
      YtDlpVersion.from(m["current"] as Map<*, *>)!!,
      YtDlpVersion.from(m["latest"] as Map<*, *>?),
      m["updateAvailable"] as Boolean,
      (m["lastCheck"] as Double?)?.toLong(),
      (m["lastError"] as Map<*, *>?)?.let { YtDlpException(it["code"] as String, it["message"] as String) },
    )
  }
}

data class UpdateCheck(
  /** "available", "up-to-date" or "app-update-required" (the newest yt-dlp needs a newer app). */
  val status: String,
  val current: YtDlpVersion?,
  val latest: YtDlpVersion?,
) {
  internal companion object {
    fun from(m: Map<String, Any?>) = UpdateCheck(
      m["status"] as String,
      YtDlpVersion.from(m["current"] as Map<*, *>?),
      YtDlpVersion.from(m["latest"] as Map<*, *>?),
    )
  }
}

data class UpdateEvent(
  /** "checking", "downloading", "updated", "failed", or a check status. */
  val type: String,
  val ytDlp: String?,
  /** For "downloading": 0-1. */
  val fraction: Double?,
  val error: YtDlpException?,
) {
  internal companion object {
    fun from(m: Map<String, Any?>) = UpdateEvent(
      m["type"] as String, m["ytDlp"] as String?, m["fraction"] as Double?,
      (m["error"] as Map<*, *>?)?.let { YtDlpException(it["code"] as String, it["message"] as String) },
    )
  }
}

data class UpdateResult(
  /** True when a newer yt-dlp is now in use. */
  val updated: Boolean,
  /** The yt-dlp now in use, and the one before the call. */
  val ytDlp: String,
  val previous: String,
) {
  internal companion object {
    fun from(m: Map<String, Any?>) = UpdateResult(m["updated"] as Boolean, m["ytDlp"] as String, m["previous"] as String)
  }
}
