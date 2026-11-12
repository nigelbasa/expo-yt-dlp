package io.github.nigelbasa.ytdlp

/**
 * Every failure carries a stable [code] (the same codes the JavaScript API uses) and a message
 * written as a full sentence an app can show as it is.
 */
open class YtDlpException(val code: String, message: String) : Exception(message) {
  constructor(message: String) : this(ERROR, message)

  companion object {
    /** yt-dlp failed on the URL; the message is yt-dlp's own error. */
    const val ERROR = "ERR_YTDLP"
    const val CANCELLED = "ERR_YTDLP_CANCELLED"
    const val UNSUPPORTED = "ERR_YTDLP_UNSUPPORTED"
    const val DUPLICATE_ID = "ERR_YTDLP_DUPLICATE_ID"
  }
}

internal class YtDlpCancelledException : YtDlpException(CANCELLED, "The operation was cancelled")

internal class UnsupportedRuntimeException(message: String) : YtDlpException(UNSUPPORTED, message)

internal class DuplicateJobException(id: String) : YtDlpException(DUPLICATE_ID, "A job with id '$id' is already running")

/** A failed yt-dlp update. */
class UpdateException(code: String, message: String) : YtDlpException(code, message) {
  companion object {
    const val NETWORK = "ERR_YTDLP_UPDATE_NETWORK"
    const val SIGNATURE = "ERR_YTDLP_UPDATE_SIGNATURE"
    const val CORRUPT = "ERR_YTDLP_UPDATE_CORRUPT"
    const val BROKEN = "ERR_YTDLP_UPDATE_BROKEN"
    const val BUSY = "ERR_YTDLP_UPDATE_BUSY"
    const val DISABLED = "ERR_YTDLP_UPDATE_DISABLED"
    const val CONFIG = "ERR_YTDLP_UPDATE_CONFIG"
  }
}
