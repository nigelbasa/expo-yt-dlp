package expo.modules.ytdlp

import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class InfoOptions : Record {
  @Field val flatPlaylist: Boolean = false
  @Field val noPlaylist: Boolean = true
  @Field val cookiesFile: String? = null
  @Field val extraArgs: List<String> = emptyList()
  @Field val networkRetries: Int = 3
}

class DownloadOptions : Record {
  @Field val outputDir: String? = null
  @Field val format: String? = null
  @Field val maxHeight: Int? = null
  @Field val audioOnly: Boolean = false
  @Field val noPlaylist: Boolean = true
  @Field val cookiesFile: String? = null
  @Field val extraArgs: List<String> = emptyList()
  @Field val networkRetries: Int = 3
}

internal class YtDlpException(message: String) : CodedException("ERR_YTDLP", message, null)

internal class YtDlpCancelledException : CodedException("ERR_YTDLP_CANCELLED", "The operation was cancelled", null)

internal class UnsupportedRuntimeException(message: String) : CodedException("ERR_YTDLP_UNSUPPORTED", message, null)

internal class DuplicateJobException(id: String) : CodedException("ERR_YTDLP_DUPLICATE_ID", "A job with id '$id' is already running", null)
