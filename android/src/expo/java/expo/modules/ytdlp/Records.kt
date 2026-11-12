package expo.modules.ytdlp

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import io.github.nigelbasa.ytdlp.DownloadOptions
import io.github.nigelbasa.ytdlp.InfoOptions
import io.github.nigelbasa.ytdlp.UpdateConfig

// What JS passes in, converted to the core's options. Defaults match the core's.

class InfoOptionsRecord : Record {
  @Field val flatPlaylist: Boolean = false
  @Field val noPlaylist: Boolean = true
  @Field val cookiesFile: String? = null
  @Field val extraArgs: List<String> = emptyList()
  @Field val networkRetries: Int = 3

  fun toCore() = InfoOptions(flatPlaylist, noPlaylist, cookiesFile, extraArgs, networkRetries)
}

class DownloadOptionsRecord : Record {
  @Field val outputDir: String? = null
  @Field val format: String? = null
  @Field val maxHeight: Int? = null
  @Field val audioOnly: Boolean = false
  @Field val noPlaylist: Boolean = true
  @Field val cookiesFile: String? = null
  @Field val extraArgs: List<String> = emptyList()
  @Field val networkRetries: Int = 3
  @Field val simulate: Boolean = false
  @Field val skipMedia: Boolean = false

  fun toCore() = DownloadOptions(
    outputDir, format, maxHeight, audioOnly, noPlaylist, cookiesFile, extraArgs, networkRetries, simulate, skipMedia,
  )
}

class UpdateConfigRecord : Record {
  @Field val mode: String = "bundled"
  @Field val checkIntervalHours: Double = 24.0
  @Field val feedUrl: String? = null
  @Field val feedPublicKey: String? = null

  fun toCore() = UpdateConfig(mode, checkIntervalHours, feedUrl, feedPublicKey)
}
