package expo.modules.ytdlp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

internal fun interface Cancellable {
  fun cancel()
}

/**
 * A call's cancel switch. It exists before the call has a process, and works whether the call
 * ends up in the engine or in a process of its own.
 */
internal class JobHandle : Cancellable {
  @Volatile
  var isCancelled = false
    private set
  private var target: Cancellable? = null

  @Synchronized
  override fun cancel() {
    isCancelled = true
    target?.cancel()
  }

  @Synchronized
  fun attach(target: Cancellable?) {
    this.target = target
    if (isCancelled) target?.cancel()
  }
}

/**
 * One Python process that stays up between calls (`expo_yt_dlp_runner --serve`), so Python
 * and yt-dlp are started once instead of on every call.
 *
 * It runs one job at a time. [run] returns null whenever the engine can't take the job (it is
 * busy, switched off, not supported by the installed yt-dlp bundle, or it died), and the
 * caller then runs the job in a process of its own, exactly as before the engine existed.
 * Cancelling a job kills the engine; the next call starts a new one.
 *
 * No Android dependencies, so it can be exercised on a plain JVM.
 */
internal class YtDlpEngine(
  /** The command for the engine as things are now; [Launch.key] changes when the yt-dlp bundle does. */
  private val launch: () -> Launch,
  private val idleMs: Long = 5 * 60_000L,
  private val startTimeoutMs: Long = 60_000L,
) {
  class Launch(val key: String, val builder: ProcessBuilder)

  private class Session(val key: String, val process: Process) {
    val input = process.outputStream.bufferedWriter()
    val output = process.inputStream.bufferedReader()
  }

  private val busy = ReentrantLock()
  private var session: Session? = null // only touched while holding [busy]
  private val withoutEngine = ConcurrentHashMap.newKeySet<String>()
  private val jobCounter = AtomicLong()
  private val timer by lazy { Timer("yt-dlp-engine-idle", true) }
  private var idleTask: TimerTask? = null

  @Volatile
  private var stopAfterJob = false

  @Volatile
  private var process: Process? = null

  /** How long the running engine took to start, for diagnostics. */
  @Volatile
  var startupMs: Long? = null
    private set

  @Volatile
  var enabled = true
    set(value) {
      field = value
      if (!value) shutDown()
    }

  val isRunning: Boolean
    get() = process?.isAlive == true

  /** Starts the engine if it isn't up. @return whether an engine is running afterwards */
  fun warmUp(): Boolean {
    if (!enabled) return false
    if (!busy.tryLock()) return true // a job is running in it right now
    try {
      return ensureSession() != null
    } finally {
      scheduleIdleStop()
      busy.unlock()
    }
  }

  /**
   * Runs one runner call in the engine. [argv] is what a single `python -m expo_yt_dlp_runner`
   * call would get. Event lines go to [onLine]; what the call printed otherwise comes back in
   * the result.
   *
   * @return null if the engine did not run the job to the end; the caller should then run it itself
   * @throws YtDlpCancelledException if [handle] was cancelled
   */
  fun run(handle: JobHandle, argv: List<String>, onLine: ((String) -> Unit)? = null): YtDlpProcess.Result? {
    if (!enabled || !busy.tryLock()) return null
    try {
      val current = ensureSession() ?: return null
      handle.attach { kill(current.process) }
      if (handle.isCancelled) throw YtDlpCancelledException()

      val id = "job-${jobCounter.incrementAndGet()}"
      try {
        current.input.write(JSONObject().put("id", id).put("argv", JSONArray(argv)).toString())
        current.input.newLine()
        current.input.flush()
        while (true) {
          val line = current.output.readLine() ?: break
          val event = parseEvent(line) ?: continue // stray output from yt-dlp's helpers
          if (event.optString("event") == "done" && event.optString("id") == id) {
            if (handle.isCancelled) throw YtDlpCancelledException()
            return YtDlpProcess.Result(event.optInt("code", 1), event.optString("stdout"), event.optString("stderr"))
          }
          onLine?.invoke(line)
        }
      } catch (e: IOException) {
        // The pipe broke: the engine was killed (cancel) or crashed. Handled below.
      }
      drop()
      if (handle.isCancelled) throw YtDlpCancelledException()
      return null
    } finally {
      handle.attach(null)
      if (stopAfterJob) drop() else scheduleIdleStop()
      busy.unlock()
    }
  }

  /** Stops the engine and frees its memory. A job that is running in it finishes first. */
  fun shutDown() {
    if (busy.tryLock()) {
      try {
        drop()
      } finally {
        busy.unlock()
      }
    } else {
      stopAfterJob = true
    }
  }

  private fun ensureSession(): Session? {
    val wanted = launch()
    session?.let {
      if (it.key == wanted.key && it.process.isAlive) return it
      drop() // dead, or yt-dlp was updated since it started
    }
    if (wanted.key in withoutEngine) return null

    val started = System.nanoTime()
    val proc = try {
      wanted.builder.redirectErrorStream(true).start()
    } catch (e: IOException) {
      withoutEngine += wanted.key
      return null
    }
    val candidate = Session(wanted.key, proc)
    val watchdog = object : TimerTask() {
      override fun run() {
        proc.destroyForcibly()
      }
    }
    timer.schedule(watchdog, startTimeoutMs)
    val ready = try {
      generateSequence { candidate.output.readLine() }
        .any { parseEvent(it)?.optString("event") == "ready" }
    } catch (e: IOException) {
      false
    } finally {
      watchdog.cancel()
    }
    if (!ready) {
      // An older yt-dlp bundle whose runner has no --serve, or one that doesn't start at all.
      // Either way the single-process path deals with it, and reports it properly.
      withoutEngine += wanted.key
      kill(proc)
      return null
    }
    stopAfterJob = false
    startupMs = (System.nanoTime() - started) / 1_000_000
    process = proc
    session = candidate
    return candidate
  }

  private fun drop() {
    val current = session ?: return
    session = null
    process = null
    startupMs = null
    stopAfterJob = false
    try {
      current.input.close() // the runner exits when its input ends
    } catch (e: IOException) {
      // already gone
    }
    kill(current.process)
  }

  @Synchronized
  private fun scheduleIdleStop() {
    idleTask?.cancel()
    if (session == null) return
    val task = object : TimerTask() {
      override fun run() {
        if (!busy.tryLock()) return // in use; the job reschedules this when it ends
        try {
          drop()
        } finally {
          busy.unlock()
        }
      }
    }
    idleTask = task
    timer.schedule(task, idleMs)
  }

  private fun parseEvent(line: String): JSONObject? {
    if (!line.startsWith(EVENT_MARK)) return null
    return try {
      JSONObject(line.substring(EVENT_MARK.length))
    } catch (e: JSONException) {
      null
    }
  }

  /** SIGTERM, then SIGKILL after a grace period (see [YtDlpProcess.cancel]). */
  private fun kill(proc: Process) {
    proc.destroy()
    thread(name = "yt-dlp-engine-kill") {
      if (!proc.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
  }

  private companion object {
    const val EVENT_MARK = "__EYD__"
    const val KILL_GRACE_SECONDS = 3L
  }
}
