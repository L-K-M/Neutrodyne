# SPDX-License-Identifier: Unlicense
"""Test-only strict replay and scrubbed recording; never used as a network fallback."""

import base64
import io
import json
import re
import threading
from enum import Enum
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from yt_dlp.networking.common import RequestHandler, Response
from yt_dlp.networking.exceptions import HTTPError, TransportError

from .bridge import current_call

FIXTURE_BYTES = 1_000_000
_SECRET = re.compile(r'^(?:cookie|set-cookie|authorization|visitor.*|.*visitor.*|.*token.*|signature|signatureCipher|cipher|s|sig|lsig|sparams|lsparams|key)$', re.I)
_URL = re.compile(r'https?://[^\s"<>]+')


class Mode(str, Enum):
    STRICT = 'strict'
    CONTRACT = 'contract'


def scrub_url(value):
    parts = urlsplit(value)
    query = []
    for key, item in parse_qsl(parts.query, keep_blank_values=True):
        if key == 'ip':
            item = '0.0.0.0'
        elif key == 'expire':
            item = '4102444800'
        elif key.lower() in ('sig', 'lsig', 'signature'):
            item = 'X'
        elif _SECRET.fullmatch(key) or key.lower() in ('ei', 'cpn', 'pot', 'n'):
            continue
        query.append((key, item))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), ''))


def scrub(value):
    if isinstance(value, dict):
        return {key: scrub(item) for key, item in value.items() if not _SECRET.fullmatch(key)}
    if isinstance(value, list):
        return [scrub(item) for item in value]
    if isinstance(value, str):
        return _URL.sub(lambda match: scrub_url(match.group()), value)
    return value


def _body(data):
    if data is None:
        return None
    if not isinstance(data, bytes):
        raise TransportError('Unsupported replay body')
    try:
        return scrub(json.loads(data))
    except (UnicodeDecodeError, ValueError):
        raise TransportError('Only JSON request bodies may be recorded') from None


def request_key(request, mode=Mode.STRICT):
    body = _body(request.data)
    url = scrub_url(request.url)
    if mode == Mode.CONTRACT:
        parts = urlsplit(url)
        client = ((body or {}).get('context') or {}).get('client') or {}
        return [request.method, parts.netloc, parts.path, client.get('clientName')]
    # Client version, query, continuation and JSON body all matter. Dictionary ordering does not.
    return [request.method, url, body]


class ReplayRH(RequestHandler):
    _SUPPORTED_URL_SCHEMES = ('http', 'https')
    _SUPPORTED_PROXY_SCHEMES = ()
    _directory = None
    _mode = Mode.STRICT
    misses = 0

    @classmethod
    def configure(cls, directory, mode=Mode.STRICT):
        cls.misses = 0
        cls._directory = Path(directory)
        cls._mode = Mode(mode)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._lock = threading.Lock()
        path = self._directory / 'http.json'
        if path.stat().st_size > FIXTURE_BYTES:
            raise ValueError('Fixture exceeds 1 MB')
        fixture = json.loads(path.read_text())
        self._records = fixture['exchanges']
        self._bodies = fixture.get('bodies', [])
        self._used = set()

    def _check_extensions(self, extensions):
        super()._check_extensions(extensions)
        for key in ('cookiejar', 'timeout', 'legacy_ssl', 'keep_header_casing'):
            extensions.pop(key, None)

    def _send(self, request):
        state = current_call()
        wanted = request_key(request, self._mode)
        with self._lock:
            for index, record in enumerate(self._records):
                expected = record['request']
                if self._mode == Mode.CONTRACT:
                    from yt_dlp.networking.common import Request
                    expected = request_key(Request(expected[1], method=expected[0],
                                                   data=json.dumps(expected[2]).encode() if expected[2] is not None else None), self._mode)
                if index not in self._used and expected == wanted:
                    self._used.add(index)
                    break
            else:
                if self._mode == Mode.CONTRACT:
                    raise HTTPError(Response(io.BytesIO(), request.url, {}, 404))
                # Extractors may swallow TransportError. Poison the call too, so retries or
                # warning-only no-formats recovery cannot turn an unrecorded request green.
                type(self).misses += 1
                state.replay_miss = True
                raise TransportError('Unrecorded replay request; re-record scenario')
        response = record['response']
        body = base64.b64decode(response['bodyBase64'], validate=True) if 'bodyBase64' in response else (self._bodies[response['bodyRef']] if 'bodyRef' in response else response['body']).encode()
        result = Response(io.BytesIO(body), response.get('url', request.url), response.get('headers', {}), response['status'])
        state.check()
        if not 200 <= result.status < 300:
            raise HTTPError(result)
        return result
