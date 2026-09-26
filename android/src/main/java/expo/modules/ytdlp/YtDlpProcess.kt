package expo.modules.ytdlp

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One yt-dlp child process. stdout and stderr are drained concurrently so large `-J` output can't deadlock. */
internal class YtDlpProcess(private val builder: ProcessBuilder) {
  class Result(val exitCode: Int, val stdout: String, val stderr: String)

  @Volatile
  private var process: Process? = null

  @Volatile
  var isCancelled = false
    private set

  /**
   * Runs to completion. When [onLine] is given, stdout is streamed line by line to it
   * instead of being collected. Only the tail of stderr is kept.
   */
  fun run(onLine: ((String) -> Unit)? = null): Result {
    val proc = synchronized(this) {
      if (isCancelled) throw YtDlpCancelledException()
      builder.start().also { process = it }
    }
    proc.outputStream.close()

    val stderrTail = ArrayDeque<String>()
    val stderrReader = thread(name = "yt-dlp-stderr") {
      try {
        proc.errorStream.bufferedReader().forEachLine { line ->
          synchronized(stderrTail) {
            stderrTail.addLast(line)
            if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
          }
        }
      } catch (e: IOException) {
        // Stream closed by cancel(); an uncaught exception here would crash the app.
      }
    }

    val stdout = StringBuilder()
    try {
      proc.inputStream.bufferedReader().forEachLine { line ->
        if (onLine != null) onLine(line) else stdout.append(line).append('\n')
      }
    } catch (e: IOException) {
      // destroy() closes the pipe under a blocked read ("read interrupted by close()").
      if (isCancelled) throw YtDlpCancelledException()
      throw e
    }
    val exitCode = proc.waitFor()
    stderrReader.join()

    if (isCancelled) throw YtDlpCancelledException()
    return Result(exitCode, stdout.toString(), synchronized(stderrTail) { stderrTail.joinToString("\n") })
  }

  /**
   * SIGTERM, then SIGKILL after a grace period. Grandchildren (QuickJS, ffmpeg) are not
   * signalled; they are short-lived and exit on their own.
   */
  fun cancel() {
    val proc = synchronized(this) {
      isCancelled = true
      process
    } ?: return
    proc.destroy()
    thread(name = "yt-dlp-kill") {
      if (!proc.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
  }

  companion object {
    private const val STDERR_TAIL_LINES = 200
    private const val KILL_GRACE_SECONDS = 3L

    /**
     * yt-dlp's last "ERROR: ..." line (earlier ones belong to attempts that were retried),
     * otherwise the last few lines of stderr.
     */
    fun errorMessage(result: Result): String {
      val lines = result.stderr.lines().filter { it.isNotBlank() }
      val message = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.takeLast(5).joinToString("\n")
      return message.ifEmpty { "yt-dlp exited with code ${result.exitCode}" }
    }
  }
}
