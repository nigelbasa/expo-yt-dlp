package io.github.nigelbasa.ytdlp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the library on a device against the clip server from scripts/emulator_selftest.py
 * (`--serve`), since YouTube blocks CI machines. The live sites are tested from a real network.
 */
@RunWith(AndroidJUnit4::class)
class YtDlpTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val server = InstrumentationRegistry.getArguments().getString("testServer") ?: "http://10.0.2.2:8765"
  private val ytDlp = YtDlp(context)
  private val out = File(context.cacheDir, "ytdlp-test")

  @Before
  fun clean() {
    out.deleteRecursively()
  }

  @Test
  fun prepareAndVersion() = runBlocking {
    assertTrue(ytDlp.isSupported)
    val info = ytDlp.prepare()
    assertTrue(info.python.startsWith("3."))
    val result = ytDlp.exec("--version")
    assertEquals(0, result.exitCode)
    // yt-dlp prints 2026.08.19 where the package reports 2026.8.19.
    assertEquals(info.ytDlp, result.stdout.trim().split('.').joinToString(".") { it.toInt().toString() })
  }

  @Test
  fun getInfo() = runBlocking {
    val info = ytDlp.getInfo("$server/clip.mp4")
    assertEquals("clip", info.getString("id"))
    assertTrue(info.getJSONArray("formats").length() > 0)
  }

  @Test
  fun downloadWithProgress() = runBlocking {
    val updates = mutableListOf<DownloadProgress>()
    val result = ytDlp.download("$server/clip.mp4", DownloadOptions(outputDir = out.path)) { updates += it }
    assertEquals(1, result.files.size)
    assertTrue(result.files[0].length() > 1000)
    assertEquals("clip [clip].mp4", result.files[0].name)
    assertEquals(100.0, updates.last { it.status == "finished" }.percent)
  }

  @Test
  fun failureHasCode() = runBlocking {
    try {
      ytDlp.getInfo("$server/missing.mp4", InfoOptions(networkRetries = 0))
      fail("expected an error")
    } catch (e: YtDlpException) {
      assertEquals(YtDlpException.ERROR, e.code)
      assertTrue(e.message!!.contains("404"))
    }
  }

  @Test
  fun cancellingStopsTheDownload() = runBlocking {
    val task = async { ytDlp.download("$server/slow.mp4", DownloadOptions(outputDir = out.path)) }
    delay(3000)
    task.cancel()
    try {
      task.await()
      fail("expected cancellation")
    } catch (e: CancellationException) {
      // expected
    }
    assertTrue("partial files left: ${out.list()?.toList()}", out.listFiles().orEmpty().none { it.isFile })
  }

  @Test
  fun engine() = runBlocking {
    ytDlp.engineEnabled = true
    val state = ytDlp.warmUp()
    assertTrue(state.reason ?: "", state.running)
    assertTrue(ytDlp.isEngineRunning)
    assertEquals("clip", ytDlp.getInfo("$server/clip.mp4").getString("id"))
    ytDlp.shutDown()
    assertFalse(ytDlp.isEngineRunning)
  }

  @Test
  fun updateState() {
    val state = ytDlp.updateState()
    assertNotNull(state.current.ytDlp)
    assertEquals("bundled", ytDlp.setUpdates(UpdateConfig(mode = "bundled")).mode)
    try {
      ytDlp.setUpdates(UpdateConfig(mode = "sometimes"))
      fail("expected a config error")
    } catch (e: UpdateException) {
      assertEquals(UpdateException.CONFIG, e.code)
    }
  }
}
