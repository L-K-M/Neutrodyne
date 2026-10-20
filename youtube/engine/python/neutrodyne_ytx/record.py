# SPDX-License-Identifier: Unlicense
"""Manual host/live urllib adapter. This is not Android PyHttp or a desktop host."""

import argparse
import datetime
import io
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

from yt_dlp.networking.common import Response
from yt_dlp.networking.exceptions import HTTPError, TransportError

from .bridge import Bridge, current_call
from .errors import ShimError
from .okhttp_rh import NeutrodyneOkHttpRH, install
from .replay import FIXTURE_BYTES, request_key, scrub

_TEXT_KEYS = {'title', 'description', 'shortDescription', 'text', 'simpleText', 'author', 'ownerChannelName'}


def minimize(value):
    value = scrub(value)
    if isinstance(value, dict):
        return {k: 'Synthetic replacement' if k in _TEXT_KEYS and isinstance(v, str) else minimize(v)
                for k, v in value.items()}
    if isinstance(value, list):
        return [minimize(v) for v in value]
    return value


def minimize_body(data):
    text = data.decode('utf-8')
    try:
        return json.dumps(minimize(json.loads(text)), separators=(',', ':'))
    except ValueError:
        # Retain only recognized JSON bootstrap objects, never arbitrary watch-page prose/scripts.
        scripts = []
        decoder = json.JSONDecoder()
        for pattern, prefix, suffix in (
            (r'(?:var\s+)?ytInitialPlayerResponse\s*=\s*', 'var ytInitialPlayerResponse = ', ';'),
            (r'(?:var\s+)?ytInitialData\s*=\s*', 'var ytInitialData = ', ';'),
            (r'ytcfg\.set\(\s*', 'ytcfg.set(', ');'),
        ):
            for match in re.finditer(pattern, text):
                try:
                    value, _ = decoder.raw_decode(text[match.end():])
                except ValueError:
                    continue
                scripts.append('<script>' + prefix + json.dumps(minimize(value), separators=(',', ':')) + suffix + '</script>')
        if not scripts:
            raise ValueError('Unrecognized response; minimize manually outside the repository')
        return ''.join(scripts)


class RecordingRH(NeutrodyneOkHttpRH):
    directory = None
    records = []
    opener = None

    def _send(self, request):
        state = current_call()
        try:
            headers = self._get_headers(request)
            headers = {k: v for k, v in headers.items() if k.lower() != 'accept-encoding'}
            headers['Accept-Encoding'] = 'identity'
            headers.setdefault('User-Agent', state.ctx['ua'])
            outgoing = urllib.request.Request(request.url, data=request.data, headers=headers, method=request.method)
            self._get_cookiejar(request).add_cookie_header(outgoing)
            timeout = min(self._calculate_timeout(request), state.deadline - state.clock())
            state.check()
            try:
                response = self.opener.open(outgoing, timeout=timeout)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                body = response.read(FIXTURE_BYTES + 1)
                if len(body) > FIXTURE_BYTES:
                    raise ValueError('Capture exceeds fixture cap; minimize manually')
                minimized = minimize_body(body)
                clean_headers = {k: v for k, v in response.headers.items() if k.lower() in ('content-type', 'retry-after')}
                record = {'request': request_key(request), 'response': {
                    'url': scrub(response.url), 'status': response.status, 'headers': clean_headers, 'body': minimized}}
                self.records.append(record)
                encoded = json.dumps({'provenance': 'captured then minimized, host/live urllib',
                                      'exchanges': self.records}, separators=(',', ':'))
                if len(encoded.encode()) > FIXTURE_BYTES:
                    raise ValueError('Scenario exceeds fixture cap; split/minimize manually')
                (self.directory / 'http.json').write_text(encoded + '\n')
                result = Response(io.BytesIO(body), response.url, dict(response.headers), response.status)
                self._get_cookiejar(request).extract_cookies(result, outgoing)
            state.check()
            if not 200 <= result.status < 300:
                raise HTTPError(result)
            return result
        except HTTPError:
            raise
        except Exception:
            state.check()
            raise TransportError('Manual capture failed; inspect/minimize locally') from None


class _HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.startswith('https://'):
            raise ValueError('Non-HTTPS recording redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def main():
    if os.environ.get('CI'):
        raise SystemExit('Recording is manual and may not run in CI')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('method', choices=('resolve', 'facts', 'lookup', 'tab_open', 'search_open'))
    parser.add_argument('value', help='video ID, channel URL/ID or channel search text')
    parser.add_argument('directory', type=Path, help='new staging directory, inspect before copying to fixtures')
    parser.add_argument('--tab', choices=('videos', 'shorts', 'streams'), default='videos')
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=False)
    RecordingRH.directory = args.directory
    RecordingRH.records = []
    RecordingRH.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _HttpsRedirect())
    install(RecordingRH)
    request = {'ctx': {'hl': 'en', 'gl': 'US', 'ua': 'Neutrodyne manual host/live capture'}}
    field = {'resolve': 'videoId', 'facts': 'videoId', 'lookup': 'url', 'tab_open': 'channelId', 'search_open': 'query'}[args.method]
    request[field] = args.value
    if args.method == 'tab_open':
        request['tab'] = args.tab
    try:
        result = json.loads(Bridge(host='host/live urllib').call(args.method, json.dumps(request), time.monotonic() + 45, 1))
    except ShimError as error:
        result = {'error': error.code}
    (args.directory / 'request.json').write_text(json.dumps({'method': args.method, 'payload': request}, indent=2) + '\n')
    (args.directory / 'result.json').write_text(json.dumps(minimize(result), indent=2) + '\n')
    today = datetime.date.today().isoformat()
    (args.directory / 'README.md').write_text(f'Host/live urllib capture, minimized {today}.\n'
        'Origin: https://www.youtube.com/. Adapter: explicit host/live urllib, not Android PyHttp.\n'
        'Cookies/visitor data removed; IP/signature/expiry scrubbed; prose replaced.\n'
        'Inspect for additional personal data and copyrighted content, then verify strict replay\n'
        'before copying to recorded/. Minimization may require manual structural repair.\n')
    if 'error' in result:
        raise SystemExit('Capture did not succeed: ' + result['error'])


if __name__ == '__main__':
    main()
