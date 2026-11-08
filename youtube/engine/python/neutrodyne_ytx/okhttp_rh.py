# SPDX-License-Identifier: Unlicense
"""Exclusive yt-dlp handler. The Java port owns redirects, gzip and socket cancellation."""

import io
import json
import threading
import urllib.request
from email.message import Message

from yt_dlp.networking.common import RequestHandler, Response, register_preference
from yt_dlp.networking import common
from yt_dlp.networking.exceptions import HTTPError, TransportError

from .bridge import current_call

HTTP_BODY_BYTES = 8 * 1024 * 1024
_registry_lock = threading.Lock()
_execute = None


def install(handler, execute=None):
    """Call once before creating workers; never swap transports during active calls."""
    global _execute
    if handler not in (NeutrodyneOkHttpRH,) and handler.__name__ not in ('ReplayRH', 'RecordingRH'):
        raise ValueError('Unapproved engine transport')
    with _registry_lock:
        _execute = execute
        common._REQUEST_HANDLERS.clear()
        common._REQUEST_HANDLERS[handler.RH_KEY] = handler


class NeutrodyneOkHttpRH(RequestHandler):
    _SUPPORTED_URL_SCHEMES = ('http', 'https')
    _SUPPORTED_PROXY_SCHEMES = ()

    def _check_extensions(self, extensions):
        super()._check_extensions(extensions)
        for key in ('cookiejar', 'timeout', 'legacy_ssl', 'keep_header_casing'):
            extensions.pop(key, None)

    def _send(self, request):
        state = current_call()
        try:
            headers = self._get_headers(request)
            headers = {k: v for k, v in headers.items() if k.lower() != 'accept-encoding'}
            if not any(key.lower() == 'user-agent' for key in headers):
                headers['User-Agent'] = state.ctx['ua']
            cookie_request = urllib.request.Request(request.url, headers=headers, method=request.method)
            jar = self._get_cookiejar(request)
            jar.add_cookie_header(cookie_request)
            if cookie_request.has_header('Cookie'):
                headers['Cookie'] = cookie_request.get_header('Cookie')
            timeout = min(self._calculate_timeout(request), state.deadline - state.clock())
            state.check()
            if _execute is None:
                raise TransportError('HTTP bridge unavailable')
            reply = _execute(state.call_id, request.method, request.url,
                             json.dumps(headers), request.data, max(1, int(timeout * 1000)), state.ctx.get('ipFamily'))
            state.check()
            # headersJson is a list of pairs so multiple Set-Cookie headers survive Java interop.
            pairs = json.loads(str(reply.headersJson))
            message = Message()
            for name, value in pairs:
                message.add_header(name, value)
            body = bytes(reply.body)
            if len(body) > HTTP_BODY_BYTES:
                raise TransportError('HTTP body limit exceeded')
            # PyHttp has already decoded gzip. Retaining Content-Encoding would cause double decode.
            clean = {k: v for k, v in pairs if k.lower() not in ('content-encoding', 'content-length')}
            response = Response(io.BytesIO(body), str(reply.url), clean, int(reply.status))
            response.headers = message
            if message.get('Content-Encoding', '').lower() == 'gzip':
                del response.headers['Content-Encoding']
                del response.headers['Content-Length']
            jar.extract_cookies(response, cookie_request)
            if not 200 <= response.status < 300:
                raise HTTPError(response)
            return response
        except HTTPError:
            raise
        except Exception:
            # RequestDirector retries other handlers for arbitrary exceptions. Preserve RequestError
            # semantics and do not leak Java exception messages (they may include credentials).
            state.check()
            raise TransportError('HTTP bridge failed') from None


@register_preference(NeutrodyneOkHttpRH)
def _prefer_okhttp(handler, request):
    return 1000
