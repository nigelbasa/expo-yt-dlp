package expo.modules.ytdlp

import android.content.Context
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import io.github.nigelbasa.ytdlp.YtDlpCore
import io.github.nigelbasa.ytdlp.YtDlpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PROGRESS_EVENT = "onProgress"
private const val UPDATE_EVENT = "onUpdate"

/**
 * The JavaScript API. All the work is in [YtDlpCore] (shared with the Kotlin library); this
 * converts options and errors and forwards events.
 */
class ExpoYtDlpModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val core by lazy {
    YtDlpCore.get(context).also { core ->
      removeUpdateListener = core.addUpdateListener { event -> sendEvent(UPDATE_EVENT, event) }
    }
  }
  private var removeUpdateListener: (() -> Unit)? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoYtDlp")

    Events(PROGRESS_EVENT, UPDATE_EVENT)

    Function("isSupported") {
      core.runtime.isSupported
    }

    AsyncFunction("prepare") Coroutine { ->
      io { core.runtime.info() }
    }

    AsyncFunction("getInfo") Coroutine { id: String, url: String, options: InfoOptionsRecord ->
      // Parsed on the JS side; avoids converting a huge nested map over the bridge.
      io { core.getInfo(id, url, options.toCore()) }
    }

    AsyncFunction("download") Coroutine { id: String, url: String, options: DownloadOptionsRecord ->
      io { core.download(id, url, options.toCore()) { event -> sendEvent(PROGRESS_EVENT, event) } }
    }

    AsyncFunction("exec") Coroutine { id: String, args: List<String> ->
      io {
        val result = core.exec(id, args)
        mapOf("exitCode" to result.exitCode, "stdout" to result.stdout, "stderr" to result.stderr)
      }
    }

    // yt-dlp updates: see BundleUpdater. The mode is stored, so it applies from app start.
    Function("setUpdates") { config: UpdateConfigRecord ->
      coded {
        core.updater.configure(config.toCore())
        core.updater.state()
      }
    }

    Function("getUpdateState") {
      coded { core.updater.state() }
    }

    AsyncFunction("checkForUpdate") Coroutine { ->
      io { core.updater.check() }
    }

    AsyncFunction("updateYtDlp") Coroutine { ->
      io { core.updater.update() }
    }

    AsyncFunction("resetToBundled") Coroutine { ->
      io { core.updater.reset() }
    }

    // The engine: one Python process kept running between calls (see YtDlpEngine).
    AsyncFunction("warmUp") Coroutine { ->
      io { core.warmUp() }
    }

    Function("shutDown") {
      core.engine.shutDown()
    }

    Function("setEngine") { enabled: Boolean ->
      core.engine.enabled = enabled
    }

    Function("isEngineRunning") {
      core.engine.isRunning
    }

    Function("cancel") { id: String ->
      core.cancel(id)
    }

    OnDestroy {
      removeUpdateListener?.invoke()
      core.cancelAll()
      core.engine.shutDown()
    }
  }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { coded(block) }

/** The core's errors as Expo errors, so JS sees the same `code` and message. */
private inline fun <T> coded(block: () -> T): T = try {
  block()
} catch (e: YtDlpException) {
  throw CodedException(e.code, e.message, e)
}
