"""Driver used by the expo-yt-dlp Android module.

    python -m expo_yt_dlp_runner [--info] [--native-merge] [--max-res N] [--network-retries N]
                                 -- <yt-dlp options> URL

Takes the same options as the yt-dlp CLI. With --info it prints the info dict as JSON (like
yt-dlp -J). Otherwise it downloads and, instead of console output, prints one JSON object per
line to stdout, prefixed with __EYD__:

    {"event": "progress", ...}              throttled download progress
    {"event": "retry", ...}                 a network error is being retried
    {"event": "result", "items": [...]}     final files per video, plus pairs to mux natively

--network-retries N (default 3): yt-dlp retries the media download itself, but not the page
and API requests made while extracting, so a connection dropped there (common on mobile
networks) fails the whole call. Those are retried here, with backoff, when the root cause
is a transient network error; bot checks, 4xx responses and certificate errors are not.

--native-merge means no ffmpeg is available. For each video, the best H.264 video-only +
AAC audio-only pair (which Android's MediaMuxer can combine) is downloaded as two files and
reported under "merge"; videos without such a pair get the best single file with video.
Selecting per video here, rather than with a format string, avoids yt-dlp's comma groups
silently returning only one half of a pair.
"""
import http.client
import json
import ssl
import sys
import time

import yt_dlp
from yt_dlp.downloader.common import FileDownloader
from yt_dlp.networking.exceptions import (
    CertificateVerifyError,
    HTTPError,
    ProxyError,
    TransportError,
)

MARK = '__EYD__'
PROGRESS_INTERVAL = 0.25
RETRY_BACKOFF = (1, 2, 4, 8)  # seconds before retry 1, 2, 3, 4+


def emit(event, **data):
    sys.stdout.write(MARK + json.dumps({'event': event, **data}) + '\n')
    sys.stdout.flush()


def short_side(fmt):
    dims = [d for d in (fmt.get('width'), fmt.get('height')) if d]
    return min(dims) if dims else None


def native_merge_selector(max_res):
    def within(fmt):
        side = short_side(fmt)
        return not max_res or side is None or side <= max_res

    def select(ctx):
        formats = ctx['formats']  # sorted worst -> best
        videos = [f for f in formats if (f.get('vcodec') or '').startswith('avc1')
                  and f.get('acodec') == 'none' and within(f)]
        audios = [f for f in formats if (f.get('acodec') or '').startswith('mp4a') and f.get('vcodec') == 'none']
        if videos and audios:
            yield videos[-1]
            yield audios[-1]
            return
        # No muxable pair (e.g. Facebook's VP9 DASH): best single file with video, preferring
        # ones that carry audio or might (codec unknown).
        has_video = ([f for f in formats if f.get('vcodec') != 'none' and within(f)]
                     or [f for f in formats if f.get('vcodec') != 'none'] or formats)
        with_audio = [f for f in has_video if f.get('acodec') != 'none']
        if has_video:
            yield (with_audio or has_video)[-1]

    return select


class ProgressReporter:
    def __init__(self):
        self.last = 0.0

    def __call__(self, d):
        status = d.get('status')
        now = time.monotonic()
        if status == 'downloading' and now - self.last < PROGRESS_INTERVAL:
            return
        self.last = now
        info = d.get('info_dict') or {}
        total = d.get('total_bytes') or d.get('total_bytes_estimate')
        downloaded = d.get('downloaded_bytes')
        emit('progress',
             videoId=info.get('id'),
             formatId=info.get('format_id'),
             status=status,
             downloadedBytes=downloaded,
             totalBytes=total,
             percent=(downloaded / total * 100) if downloaded is not None and total else None,
             speed=d.get('speed'),
             eta=d.get('eta'),
             fragmentIndex=d.get('fragment_index'),
             fragmentCount=d.get('fragment_count'),
             # lets the module delete partial files if the download is cancelled
             tmpFilename=d.get('tmpfilename'))


def result_items(info, native_merge):
    entries = info.get('entries') if info.get('_type') in ('playlist', 'multi_video') else [info]
    items = []
    for entry in entries or []:
        if not entry:
            continue
        downloads = [{
            'path': dl.get('filepath'),
            'formatId': dl.get('format_id'),
            'vcodec': dl.get('vcodec'),
            'acodec': dl.get('acodec'),
        } for dl in entry.get('requested_downloads') or [] if dl.get('filepath')]
        merge = None
        if native_merge and len(downloads) == 2:
            video = next((d for d in downloads if (d['vcodec'] or '').startswith('avc1')), None)
            audio = next((d for d in downloads if (d['acodec'] or '').startswith('mp4a')), None)
            if video and audio and video is not audio:
                merge = {'video': video['path'], 'audio': audio['path']}
        items.append({'id': entry.get('id'), 'title': entry.get('title'), 'files': downloads, 'merge': merge})
    return items


def causes(exc):
    """The exception plus everything it wraps (yt-dlp nests them via exc_info and cause).

    __context__ is deliberately not followed: it can be an unrelated error that was being
    handled when e.g. a bot check was raised, which would make that look retryable.
    """
    seen, stack = set(), [exc]
    while stack:
        e = stack.pop()
        if e is None or id(e) in seen or not isinstance(e, BaseException):
            continue
        seen.add(id(e))
        yield e
        exc_info = getattr(e, 'exc_info', None)
        stack += [getattr(e, 'cause', None), e.__cause__,
                  exc_info[1] if isinstance(exc_info, tuple) and len(exc_info) > 1 else None]


def is_transient(exc):
    for e in causes(exc):
        if isinstance(e, (CertificateVerifyError, ProxyError, ssl.SSLCertVerificationError)):
            return False
        if isinstance(e, HTTPError):
            return e.status >= 500
        if isinstance(e, (TransportError, http.client.IncompleteRead, http.client.RemoteDisconnected,
                          ConnectionError, TimeoutError, ssl.SSLError,
                          yt_dlp.utils.ContentTooShortError)):
            return True
    return False


class RecordingYoutubeDL(yt_dlp.YoutubeDL):
    """Keeps the exception behind every reported error.

    The CLI default ignoreerrors='only_download' makes extraction errors get logged and
    swallowed (extract_info returns None) instead of raised, so this is the only way to see
    what went wrong.
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.recorded_errors = []
        self.download_give_ups = []  # filled by the FileDownloader.report_retry hook below

    def trouble(self, message=None, tb=None, is_error=True):
        if is_error:
            # Downloaders report "Giving up after N retries" outside any except block, so the
            # exception comes from the report_retry hook instead of sys.exc_info().
            exc = sys.exc_info()[1] or (self.download_give_ups.pop() if self.download_give_ups else None)
            self.recorded_errors.append(exc)
        return super().trouble(message, tb, is_error)


_report_retry = FileDownloader.report_retry


def _recording_report_retry(self, err, count, retries, *args, **kwargs):
    if count > retries and hasattr(self.ydl, 'download_give_ups'):
        self.ydl.download_give_ups.append(err)
    return _report_retry(self, err, count, retries, *args, **kwargs)


FileDownloader.report_retry = _recording_report_retry


def with_retries(ydl, retries, action, on_retry=None):
    for attempt in range(retries + 1):
        ydl._download_retcode = 0  # an earlier failed attempt must not fail the whole run
        ydl.recorded_errors.clear()
        ydl.download_give_ups.clear()
        try:
            result = action()
            errors = ydl.recorded_errors if ydl._download_retcode else []
            if not errors:
                return result
        except yt_dlp.utils.DownloadError as e:
            result, errors = e, [e]
        transient = all(e is not None and is_transient(e) for e in errors)
        if attempt >= retries or not transient:
            if isinstance(result, BaseException):
                raise result
            return result
        delay = RETRY_BACKOFF[min(attempt, len(RETRY_BACKOFF) - 1)]
        print(f'[expo-yt-dlp] network error, retrying in {delay}s ({attempt + 1}/{retries})',
              file=sys.stderr, flush=True)
        if on_retry:
            on_retry(attempt + 1, retries, delay)
        time.sleep(delay)


def main(argv):
    info_only, native_merge, max_res, retries = False, False, None, 3
    while argv and argv[0] != '--':
        flag = argv.pop(0)
        if flag == '--info':
            info_only = True
        elif flag == '--native-merge':
            native_merge = True
        elif flag == '--max-res':
            max_res = int(argv.pop(0))
        elif flag == '--network-retries':
            retries = int(argv.pop(0))
        else:
            sys.exit(f'expo_yt_dlp_runner: unknown option {flag}')
    argv = argv[1:]

    parsed = yt_dlp.parse_options(argv)
    opts = parsed.ydl_opts
    opts.update(quiet=True, noprogress=True)
    if not info_only:
        opts['progress_hooks'] = [ProgressReporter()]
    if native_merge:
        opts['format'] = native_merge_selector(max_res)

    def on_retry(attempt, total, delay):
        emit('retry', attempt=attempt, retries=total, delay=delay)

    with RecordingYoutubeDL(opts) as ydl:
        if info_only:
            url, = parsed.urls
            info = with_retries(ydl, retries, lambda: ydl.extract_info(url, download=False))
            print(json.dumps(ydl.sanitize_info(info)), flush=True)
            return ydl._download_retcode

        items = []
        for url in parsed.urls:
            info = with_retries(ydl, retries, lambda: ydl.extract_info(url, download=True), on_retry)
            if info:
                items += result_items(ydl.sanitize_info(info), native_merge)
        emit('result', items=items)
        return ydl._download_retcode


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv[1:]))
    except yt_dlp.utils.DownloadError:
        sys.exit(1)  # yt-dlp already printed "ERROR: ..." to stderr
    except KeyboardInterrupt:
        sys.exit(130)
