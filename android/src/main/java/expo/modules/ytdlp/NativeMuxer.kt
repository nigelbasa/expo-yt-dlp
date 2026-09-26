package expo.modules.ytdlp

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Merges a video-only and an audio-only file with Android's MediaMuxer (stream copy, no
 * re-encode). Used when no ffmpeg is bundled. MP4 output takes H.264/HEVC (+ AV1 on
 * Android 14+) with AAC; WebM output takes VP8/VP9 with Opus/Vorbis.
 */
internal object NativeMuxer {
  private const val DEFAULT_BUFFER = 4 * 1024 * 1024

  private class Input(val extractor: MediaExtractor, val format: MediaFormat, var outTrack: Int = -1) {
    var done = false
  }

  fun outputFormatFor(videoMime: String?): Int =
    if (videoMime == MediaFormat.MIMETYPE_VIDEO_VP8 || videoMime == MediaFormat.MIMETYPE_VIDEO_VP9) {
      MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
    } else {
      MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    }

  fun merge(videoPath: String, audioPath: String, outputPath: String) {
    val video = open(videoPath, "video/")
    val audio = open(audioPath, "audio/")
    val inputs = listOf(video, audio)
    val out = File(outputPath)
    out.delete()
    val muxer = MediaMuxer(outputPath, outputFormatFor(video.format.getString(MediaFormat.KEY_MIME)))
    try {
      for (input in inputs) input.outTrack = muxer.addTrack(input.format)
      muxer.start()

      val maxInput = inputs.maxOf {
        if (it.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
      }
      val buffer = ByteBuffer.allocateDirect(maxOf(maxInput, DEFAULT_BUFFER))
      val info = MediaCodec.BufferInfo()

      // Interleave by timestamp so the muxer never has to buffer a whole track.
      while (true) {
        val next = inputs.filter { !it.done }.minByOrNull { it.extractor.sampleTime } ?: break
        val size = next.extractor.readSampleData(buffer, 0)
        if (size < 0) {
          next.done = true
          continue
        }
        val flags = if (next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
          MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
          0
        }
        info.set(0, size, next.extractor.sampleTime, flags)
        muxer.writeSampleData(next.outTrack, buffer, info)
        next.extractor.advance()
      }
      muxer.stop()
    } catch (e: Exception) {
      out.delete()
      throw e
    } finally {
      muxer.release()
      inputs.forEach { it.extractor.release() }
    }
  }

  private fun open(path: String, mimePrefix: String): Input {
    val extractor = MediaExtractor()
    extractor.setDataSource(path)
    for (i in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(i)
      if (format.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) {
        extractor.selectTrack(i)
        return Input(extractor, format)
      }
    }
    extractor.release()
    throw IllegalArgumentException("No $mimePrefix track in $path")
  }
}
