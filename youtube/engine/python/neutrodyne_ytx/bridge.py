# SPDX-License-Identifier: Unlicense
"""Wire-v1 methods, worker-local extractors and process-local lazy cursors.

The host supplies an already prepared engine/cache path and a Python-monotonic
absolute deadline. No preparation, subprocess, database or network fallback lives here.
"""

import json
import math
import platform
import re
import secrets
import threading
import time
from collections import OrderedDict
from contextlib import contextmanager
from dataclasses import dataclass, field
from urllib.parse import urlencode, urlsplit

from yt_dlp import YoutubeDL
from yt_dlp.version import __version__

from . import SHIM_API_VERSION
from .errors import Code, ShimError, classify

PAYLOAD_BYTES = 16 * 1024
RESULT_BYTES = 128 * 1024
DESCRIPTION_BYTES = 32 * 1024
TAB_PAGE_SIZE = 30
SEARCH_PAGE_SIZE = 20
CURSOR_LIMIT = 16
CURSOR_TTL = 600
VIDEO = re.compile(r'[0-9A-Za-z_-]{11}\Z')
CHANNEL = re.compile(r'UC[0-9A-Za-z_-]{21}[AQgw]\Z')
_current = threading.local()


@dataclass
class CallState:
    call_id: int
    deadline: float
    ctx: dict
    cancel: threading.Event
    clock: object = time.monotonic
    warnings: list = field(default_factory=list)
    replay_miss: bool = False

    def check(self):
        if self.cancel.is_set():
            raise ShimError(Code.CANCELLED)
        if self.clock() >= self.deadline:
            raise ShimError(Code.TIMEOUT)


def current_call():
    state = getattr(_current, 'state', None)
    if state is None:
        raise ShimError(Code.ENGINE_UNAVAILABLE)
    state.check()
    return state


@contextmanager
def call_scope(state):
    previous = getattr(_current, 'state', None)
    _current.state = state
    try:
        state.check()
        yield
        state.check()
    finally:
        _current.state = previous


class _Logger:
    # Raw warnings are retained only for this call's classification, never logged or returned.
    def warning(self, message):
        state = current_call()
        if len(state.warnings) < 64:
            state.warnings.append(str(message)[:4096])

    def debug(self, message):
        pass

    def error(self, message):
        self.warning(message)


def fixed_options(ctx, cache_dir):
    return {
        'quiet': True, 'no_warnings': False, 'logger': _Logger(),
        'skip_download': True, 'noplaylist': True, 'ignore_no_formats_error': True,
        'socket_timeout': 15, 'cachedir': cache_dir, 'proxy': '',
        'js_runtimes': {}, 'remote_components': set(),
        'extractor_args': {
            'youtube': {'skip': ['hls', 'dash', 'translated_subs'],
                        'player_client': ['default', '-web'], 'lang': [ctx['hl']]},
            'youtubetab': {'approximate_date': ['']},
        },
    }


def encode(value, limit=RESULT_BYTES):
    result = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
    # Leave Binder framing/UTF-16 accounting to the host, which owns callback delivery.
    if len(result.encode('utf-8')) > limit:
        raise ShimError(Code.EXTRACTION)
    return result


def _text(value, limit=2048):
    return value[:limit] if isinstance(value, str) else None


def _number(value):
    return value if type(value) in (int, float) and math.isfinite(value) else None


def _integer(value):
    value = _number(value)
    return int(value) if value is not None else None


def _description(value):
    if not isinstance(value, str):
        return None, False
    raw = value.encode('utf-8')
    return raw[:DESCRIPTION_BYTES].decode('utf-8', errors='ignore'), len(raw) > DESCRIPTION_BYTES


def _http(value):
    if not isinstance(value, str) or len(value) > 16384:
        return None
    parts = urlsplit(value)
    return value if parts.scheme in ('http', 'https') and parts.hostname and not parts.username and not parts.password else None


def _metadata(info, video_id):
    return {'videoId': video_id, 'duration': _number(info.get('duration')),
            'liveStatus': _text(info.get('live_status')), 'availability': _text(info.get('availability')),
            'releaseTs': _integer(info.get('release_timestamp'))}


def _formats(info):
    formats = []
    for fmt in info.get('formats') or []:
        current_call().check()
        if not isinstance(fmt, dict) or fmt.get('vcodec') != 'none' or fmt.get('acodec') in (None, 'none'):
            continue
        url = _http(fmt.get('url'))
        protocol = fmt.get('protocol') or (urlsplit(url).scheme if url else None)
        if not url or protocol not in ('http', 'https') or not isinstance(fmt.get('format_id'), str):
            continue
        formats.append({
            'formatId': fmt['format_id'], 'url': url, 'ext': _text(fmt.get('ext')),
            'acodec': _text(fmt.get('acodec')), 'abr': _number(fmt.get('abr')),
            'tbr': _number(fmt.get('tbr')), 'filesize': _integer(fmt.get('filesize')),
            'language': _text(fmt.get('language')), 'languagePreference': _integer(fmt.get('language_preference')),
            'formatNote': _text(fmt.get('format_note')), 'protocol': protocol,
            'availableAt': _number(fmt.get('available_at')), 'xtags': _text(fmt.get('xtags')),
        })
    return formats


def _images(info, kind):
    result = []
    for image in info.get('thumbnails') or []:
        if not isinstance(image, dict) or kind not in str(image.get('id', '')):
            continue
        url = _http(image.get('url'))
        if url:
            result.append({'url': url, 'w': _integer(image.get('width')) or 0,
                           'h': _integer(image.get('height')) or 0})
    return result[:16]


@dataclass
class _Worker:
    ydl: object
    lock: object = field(default_factory=threading.RLock)


@dataclass
class _Cursor:
    entries: object
    worker: _Worker
    kind: str
    locale: tuple
    used: float
    busy: bool = False


class Bridge:
    def __init__(self, cache_dir=False, host='chaquopy', ydl_factory=YoutubeDL, clock=time.monotonic):
        self._cache_dir = cache_dir
        self._host = host
        self._factory = ydl_factory
        self._clock = clock
        self._workers = threading.local()
        self._cursors = OrderedDict()
        self._lock = threading.RLock()

    def _worker(self, ctx):
        workers = getattr(self._workers, 'locales', None)
        if workers is None:
            workers = self._workers.locales = {}
        locale = (ctx['hl'], ctx['gl'])
        if locale not in workers:
            workers[locale] = _Worker(self._factory(fixed_options(ctx, self._cache_dir)))
        return workers[locale]

    def call(self, method, payload, deadline, call_id, cancel=None):
        """Return JSON or sanitized ShimError.code. deadline is absolute monotonic seconds."""
        state = None
        try:
            if not isinstance(payload, str) or len(payload.encode('utf-8')) > PAYLOAD_BYTES:
                raise ShimError(Code.EXTRACTION)
            request = json.loads(payload, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            ctx = self._context(request)
            if type(call_id) is not int or not math.isfinite(deadline):
                raise ShimError(Code.EXTRACTION)
            state = CallState(call_id, deadline, ctx, cancel if cancel is not None else threading.Event(), self._clock)
            with call_scope(state):
                if method in ('tab_next', 'search_next'):
                    result = self._next(request, method.removesuffix('_next'))
                else:
                    worker = self._worker(ctx)
                    with worker.lock:
                        state.check()
                        result = self._dispatch(method, request, worker)
                if state.replay_miss:
                    raise ShimError(Code.EXTRACTION)
                return encode(result)
        except ShimError:
            if state is not None and state.replay_miss:
                raise ShimError(Code.EXTRACTION) from None
            raise
        except BaseException as error:
            if state is not None:
                state.check()
            if state is not None and state.replay_miss:
                raise ShimError(Code.EXTRACTION) from None
            raise ShimError(classify(warnings=state.warnings if state else (), error=error)) from None

    @staticmethod
    def _context(request):
        if not isinstance(request, dict) or not isinstance(request.get('ctx'), dict):
            raise ShimError(Code.EXTRACTION)
        ctx = request['ctx']
        for key, limit in (('hl', 32), ('gl', 8), ('ua', 512)):
            if not isinstance(ctx.get(key), str) or (key != 'gl' and not ctx[key]) or len(ctx[key]) > limit or any(ord(c) < 32 for c in ctx[key]):
                raise ShimError(Code.EXTRACTION)
        if ctx.get('ipFamily') not in (None, 'V4', 'V6'):
            raise ShimError(Code.EXTRACTION)
        return ctx

    def _extract(self, worker, url):
        current_call().check()
        result = worker.ydl.extract_info(url, download=False, process=False)
        current_call().check()
        if not isinstance(result, dict):
            raise ShimError(Code.EXTRACTION)
        return result

    def _dispatch(self, method, request, worker):
        if method == 'ping':
            return {'ready': True}
        if method == 'version':
            from yt_dlp_ejs import version as ejs_version
            return {'ytDlp': __version__, 'ejs': ejs_version, 'shimApi': SHIM_API_VERSION,
                    'python': platform.python_version(), 'jsc': False, 'host': self._host}
        if method == 'selftest':
            from .selftest import probe
            return probe(worker.ydl)
        if method in ('resolve', 'facts'):
            video_id = request.get('videoId')
            if not isinstance(video_id, str) or not VIDEO.fullmatch(video_id):
                raise ShimError(Code.UNAVAILABLE)
            info = self._extract(worker, 'https://www.youtube.com/watch?v=' + video_id)
            result = _metadata(info, video_id)
            if method == 'facts':
                reason = classify(info, current_call().warnings)
                if not info.get('formats') and reason not in (Code.EXTRACTION, Code.UPCOMING, Code.LIVE):
                    raise ShimError(reason)
                description, truncated = _description(info.get('description'))
                result.update(title=_text(info.get('title')), description=description, descriptionTruncated=truncated)
                return result
            formats = _formats(info)
            reason = classify(info, current_call().warnings)
            if info.get('live_status') in ('is_upcoming', 'is_live', 'post_live') or not formats:
                raise ShimError(reason)
            result.update(ageLimit=_integer(info.get('age_limit')), formats=formats)
            return result
        if method == 'lookup':
            url = request.get('url')
            if not self._channel_url(url):
                raise ShimError(Code.UNAVAILABLE)
            info = self._extract(worker, url)
            channel_id = info.get('channel_id') or info.get('id')
            if not isinstance(channel_id, str) or not CHANNEL.fullmatch(channel_id):
                raise ShimError(Code.EXTRACTION)
            description, _ = _description(info.get('description'))
            return {'channelId': channel_id, 'title': _text(info.get('channel') or info.get('title')),
                    'handle': _text(info.get('uploader_id')), 'description': description,
                    'followerCount': _integer(info.get('channel_follower_count')),
                    'avatars': _images(info, 'avatar'), 'banners': _images(info, 'banner')}
        if method in ('tab_open', 'search_open'):
            return self._open(method, request, worker)
        raise ShimError(Code.EXTRACTION)

    @staticmethod
    def _channel_url(url):
        if not isinstance(url, str):
            return False
        parts = urlsplit(url)
        if parts.scheme != 'https' or parts.netloc not in ('www.youtube.com', 'youtube.com', 'm.youtube.com') or parts.query or parts.fragment:
            return False
        return bool(re.fullmatch(r'/(?:@[^\s/?#]{1,100}|(?:c|user)/[^\s/?#]{1,100}|channel/UC[0-9A-Za-z_-]{21}[AQgw]|[A-Za-z0-9_.-]{1,100})/?', parts.path))

    def _open(self, method, request, worker):
        kind = method.removesuffix('_open')
        size = TAB_PAGE_SIZE if kind == 'tab' else SEARCH_PAGE_SIZE
        if type(request.get('pageSize', size)) is not int or request.get('pageSize', size) != size:
            raise ShimError(Code.EXTRACTION)
        if kind == 'tab':
            channel_id, tab = request.get('channelId'), request.get('tab')
            if not isinstance(channel_id, str) or not CHANNEL.fullmatch(channel_id) or tab not in ('videos', 'shorts', 'streams'):
                raise ShimError(Code.UNAVAILABLE)
            url = f'https://www.youtube.com/channel/{channel_id}/{tab}'
        else:
            query = request.get('query')
            if not isinstance(query, str) or not query.strip() or len(query) > 200:
                raise ShimError(Code.EXTRACTION)
            url = 'https://www.youtube.com/results?' + urlencode({'search_query': query, 'sp': 'EgIQAg%3D%3D'})
        info = self._extract(worker, url)
        ctx = current_call().ctx
        cursor = _Cursor(iter(info.get('entries') or ()), worker, kind, (ctx['hl'], ctx['gl']), self._clock())
        return self._page(cursor)

    def _expire(self):
        now = self._clock()
        for token, cursor in list(self._cursors.items()):
            if not cursor.busy and now - cursor.used >= CURSOR_TTL:
                del self._cursors[token]

    def _next(self, request, kind):
        token = request.get('cursor')
        if not isinstance(token, str):
            raise ShimError(Code.CURSOR_EXPIRED)
        ctx = current_call().ctx
        with self._lock:
            self._expire()
            cursor = self._cursors.get(token)
            if cursor is None or cursor.busy or cursor.kind != kind or cursor.locale != (ctx['hl'], ctx['gl']):
                raise ShimError(Code.CURSOR_EXPIRED)
            cursor.busy = True
        try:
            # A retained generator keeps its creator's mutable extractor. Serialize against
            # that worker even when a later host call is assigned to the other worker thread.
            with cursor.worker.lock:
                current_call().check()
                return self._page(cursor, token)
        except BaseException:
            with self._lock:
                self._cursors.pop(token, None)
            raise
        finally:
            with self._lock:
                cursor.busy = False

    def _page(self, cursor, token=None):
        size = TAB_PAGE_SIZE if cursor.kind == 'tab' else SEARCH_PAGE_SIZE
        items, exhausted = [], False
        for _ in range(size):
            current_call().check()
            try:
                entry = next(cursor.entries)
            except StopIteration:
                exhausted = True
                break
            if not isinstance(entry, dict):
                raise ShimError(Code.EXTRACTION)
            if cursor.kind == 'tab':
                item_id = entry.get('id')
                if not isinstance(item_id, str) or not VIDEO.fullmatch(item_id):
                    raise ShimError(Code.EXTRACTION)
                items.append({'id': item_id, 'title': _text(entry.get('title')),
                              'duration': _number(entry.get('duration')), 'liveStatus': _text(entry.get('live_status')),
                              'availability': _text(entry.get('availability')), 'timestamp': _integer(entry.get('timestamp'))})
            else:
                channel_id = entry.get('channel_id') or entry.get('id')
                if not isinstance(channel_id, str) or not CHANNEL.fullmatch(channel_id):
                    raise ShimError(Code.EXTRACTION)
                thumbs = entry.get('thumbnails') or []
                items.append({'channelId': channel_id, 'title': _text(entry.get('channel') or entry.get('title')),
                              'handle': _text(entry.get('uploader_id')), 'followerCount': _integer(entry.get('channel_follower_count')),
                              'avatar': _http(thumbs[0].get('url')) if thumbs else None,
                              'description': _text(entry.get('description')), 'verified': entry.get('channel_is_verified') is True})
        current_call().check()
        # Do not prefetch a 31st/21st entry: doing so may trigger an extra browse request.
        result = {'cursor': None if exhausted else token or secrets.token_hex(16),
                  'items' if cursor.kind == 'tab' else 'hits': items, 'hasMore': not exhausted}
        encode(result)
        with self._lock:
            self._expire()
            if exhausted:
                self._cursors.pop(token, None)
            else:
                token = result['cursor']
                cursor.used = self._clock()
                self._cursors[token] = cursor
                self._cursors.move_to_end(token)
                while len(self._cursors) > CURSOR_LIMIT:
                    candidate = next((key for key, value in self._cursors.items() if not value.busy), None)
                    if candidate is None:
                        raise ShimError(Code.EXTRACTION)
                    del self._cursors[candidate]
        return result
