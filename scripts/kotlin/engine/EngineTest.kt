// JVM tests for YtDlpEngine.kt, run by scripts/test_engine.py against the real runner
// (python/expo_yt_dlp_runner.py in the built bundle) and a local web server.
package expo.modules.ytdlp

import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
  println("${if (ok) "PASS" else "FAIL"}  $name${if (!ok && detail.isNotEmpty()) "  -- $detail" else ""}")
  if (!ok) failures++
}

fun main(args: Array<String>) {
  val (python, site, base, out) = args
  fun launch(vararg command: String, key: String = site) = YtDlpEngine.Launch(
    key,
    ProcessBuilder(*command).apply {
      environment()["PYTHONPATH"] = site
      environment()["PYTHONUTF8"] = "1"
    },
  )
  val serve = arrayOf(python, "-S", "-m", "expo_yt_dlp_runner", "--serve")
  fun argv(url: String, info: Boolean = true) =
    (if (info) listOf("--info") else emptyList()) + listOf("--", "--ignore-config", "-P", out, "--", url)

  val engine = YtDlpEngine({ launch(*serve) })
  check("not running before first use", !engine.isRunning)
  check("warmUp starts it", engine.warmUp() && engine.isRunning && engine.startupMs != null)

  var result = engine.run(JobHandle(), argv("$base/a.html"))
  check("info call returns the info", result?.exitCode == 0 && result.stdout.contains("retry test"), "result=${result?.stdout?.take(200)}")

  val lines = mutableListOf<String>()
  result = engine.run(JobHandle(), argv("$base/b.html", info = false)) { lines += it }
  check(
    "download call streams events",
    result?.exitCode == 0 && lines.any { it.contains("\"progress\"") } && lines.any { it.contains("\"result\"") },
    "exit=${result?.exitCode} lines=${lines.size} stderr=${result?.stderr?.takeLast(200)}",
  )

  result = engine.run(JobHandle(), argv("$base/missing-403.html"))
  check("a failing call reports yt-dlp's error", result != null && result.exitCode != 0 && result.stderr.contains("ERROR:"))
  check("and leaves the engine running", engine.isRunning)

  // Busy: a second call while one is running is turned away, to run in its own process.
  var slow: YtDlpProcess.Result? = null
  val worker = thread { slow = engine.run(JobHandle(), argv("$base/slow.html")) }
  Thread.sleep(700)
  check("a busy engine turns the call away", engine.run(JobHandle(), argv("$base/a.html")) == null)
  check("warmUp while busy reports it running", engine.warmUp())
  worker.join()
  check("the running call still finishes", slow?.exitCode == 0)

  // Cancel: the call throws, the engine is replaced on the next call.
  val handle = JobHandle()
  var cancelled = false
  val cancelWorker = thread {
    try {
      engine.run(handle, argv("$base/slow.html"))
    } catch (e: YtDlpCancelledException) {
      cancelled = true
    }
  }
  Thread.sleep(700)
  handle.cancel()
  cancelWorker.join(10_000)
  check("cancel stops the call", cancelled)
  result = engine.run(JobHandle(), argv("$base/a.html"))
  check("the next call gets a new engine", result?.exitCode == 0 && engine.isRunning)

  val early = JobHandle().apply { cancel() }
  val earlyCancelled = try {
    engine.run(early, argv("$base/a.html"))
    false
  } catch (e: YtDlpCancelledException) {
    true
  }
  check("a call cancelled before it starts never runs", earlyCancelled)

  engine.shutDown()
  check("shutDown stops it", !engine.isRunning)
  check("a call after shutDown starts it again", engine.run(JobHandle(), argv("$base/a.html"))?.exitCode == 0)
  engine.enabled = false
  check("switched off: stops and turns calls away", !engine.isRunning && engine.run(JobHandle(), argv("$base/a.html")) == null && !engine.warmUp())

  val idle = YtDlpEngine({ launch(*serve) }, idleMs = 400)
  idle.warmUp()
  Thread.sleep(1500)
  check("stops itself when idle", !idle.isRunning)
  check("and starts again on demand", idle.run(JobHandle(), argv("$base/a.html"))?.exitCode == 0)
  idle.shutDown()

  // A yt-dlp bundle from before the engine existed: its runner rejects --serve.
  val old = YtDlpEngine({ launch(python, "-c", "import sys; sys.exit('expo_yt_dlp_runner: unknown option --serve')") })
  check("an older bundle: no engine, calls are turned away", !old.warmUp() && old.run(JobHandle(), argv("$base/a.html")) == null)
  val missing = YtDlpEngine({ launch(File(out, "no-such-python").path) })
  check("a runtime that won't start: calls are turned away", missing.run(JobHandle(), argv("$base/a.html")) == null)

  // The yt-dlp in use changed (an update was installed): the engine restarts for it.
  var key = "first"
  val switching = YtDlpEngine({ launch(*serve, key = key) })
  switching.warmUp()
  key = "second"
  check("restarts when yt-dlp is updated", switching.run(JobHandle(), argv("$base/a.html"))?.exitCode == 0 && switching.isRunning)
  switching.shutDown()

  exitProcess(if (failures == 0) 0 else 1)
}
