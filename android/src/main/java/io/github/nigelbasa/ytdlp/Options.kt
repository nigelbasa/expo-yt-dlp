package io.github.nigelbasa.ytdlp

/** Options for [YtDlp.getInfo]. */
data class InfoOptions(
  /** List a playlist's entries without resolving each video. */
  val flatPlaylist: Boolean = false,
  /** For a video inside a playlist link, take only the video. */
  val noPlaylist: Boolean = true,
  /** A Netscape-format cookies file. */
  val cookiesFile: String? = null,
  /** More yt-dlp options, added before the URL. */
  val extraArgs: List<String> = emptyList(),
  /** Retries for dropped connections and similar network errors. */
  val networkRetries: Int = 3,
)

/** Options for [YtDlp.download]. */
data class DownloadOptions(
  /** Where files go. Defaults to `files/downloads` in the app's private storage. */
  val outputDir: String? = null,
  /** A yt-dlp format selector, such as "bv*+ba/b". Overrides [maxHeight] and [audioOnly]. */
  val format: String? = null,
  /** Caps the short side of the video, so portrait videos are treated like landscape ones. */
  val maxHeight: Int? = null,
  val audioOnly: Boolean = false,
  val noPlaylist: Boolean = true,
  val cookiesFile: String? = null,
  val extraArgs: List<String> = emptyList(),
  val networkRetries: Int = 3,
  /** Resolve formats, sizes and file names without downloading (yt-dlp --simulate). */
  val simulate: Boolean = false,
  /** Only write side files such as subtitles and thumbnails (yt-dlp --skip-download). */
  val skipMedia: Boolean = false,
)

/** How yt-dlp updates itself. See [YtDlp.setUpdates]. */
data class UpdateConfig(
  /** "bundled" (never update), "auto" (check in the background) or "manual". */
  val mode: String = "bundled",
  val checkIntervalHours: Double = 24.0,
  /** A feed other than the official one; needs [feedPublicKey]. */
  val feedUrl: String? = null,
  val feedPublicKey: String? = null,
)
