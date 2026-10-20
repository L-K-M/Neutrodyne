# SPDX-License-Identifier: Unlicense
import io
import json
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from yt_dlp.cookies import YoutubeDLCookieJar
from yt_dlp.networking.common import Request
from yt_dlp.networking.exceptions import HTTPError, TransportError, UnsupportedRequest

from neutrodyne_ytx.bridge import Bridge, CallState, call_scope
from neutrodyne_ytx.errors import ShimError
from neutrodyne_ytx.okhttp_rh import install, NeutrodyneOkHttpRH
from neutrodyne_ytx.selftest import probe
from test_shim import CTX, payload, FIXTURES, VIDEO_ID


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.reply = SimpleNamespace(url='https://example.invalid/final', body=b'decoded gzip',
                                     headersJson=json.dumps([['Content-Encoding', 'gzip'], ['Content-Length', '10'],
                                                            ['Set-Cookie', 'one=1; Path=/'], ['Set-Cookie', 'two=2; Path=/']]), status=200)
        def execute(*args):
            self.requests.append(args)
            return self.reply
        self.execute = execute
        install(NeutrodyneOkHttpRH, execute)
        self.rh = NeutrodyneOkHttpRH(logger=None)
        self.state = CallState(42, time.monotonic() + 5, CTX, threading.Event())

    def test_headers_cookies_gzip_final_url_extensions_ip_timeout(self):
        jar = YoutubeDLCookieJar()
        request = Request('https://example.invalid/start', headers={'Accept-Encoding': 'br, gzip', 'User-Agent': 'client-specific'},
                          extensions={'cookiejar': jar, 'timeout': 2, 'legacy_ssl': True, 'keep_header_casing': True})
        self.rh.validate(request)
        with call_scope(self.state):
            response = self.rh.send(request)
            self.assertEqual(response.read(), b'decoded gzip')
            self.assertIsNone(response.headers.get('Content-Encoding'))
            self.assertEqual(response.url, self.reply.url)
            self.assertEqual(response.headers.get_all('Set-Cookie'), ['one=1; Path=/', 'two=2; Path=/'])
            self.rh.send(Request('https://example.invalid/again', extensions={'cookiejar': jar}))
        first, second = self.requests
        self.assertEqual(first[0], 42)
        self.assertEqual(first[5:], (2000, 'V4'))
        headers = json.loads(first[3])
        self.assertNotIn('Accept-Encoding', headers)
        self.assertEqual(headers['User-Agent'], 'client-specific')
        self.assertIn('one=1', json.loads(second[3])['Cookie'])
        self.assertIn('two=2', json.loads(second[3])['Cookie'])

    def test_user_agent_fallback_and_deadline_timeout(self):
        self.state.deadline = time.monotonic() + 0.5
        with call_scope(self.state):
            self.rh.send(Request('https://example.invalid'))
        self.assertEqual(json.loads(self.requests[0][3])['User-Agent'], CTX['ua'])
        self.assertLessEqual(self.requests[0][5], 500)
        self.assertGreater(self.requests[0][5], 0)

    def test_all_non_2xx_statuses_raise_http_error(self):
        for status in (301, 403, 404, 429, 500):
            self.reply.status = status
            with call_scope(self.state), self.assertRaises(HTTPError) as caught:
                self.rh.send(Request('https://example.invalid'))
            self.assertEqual(caught.exception.status, status)

    def test_java_exception_is_sanitized_transport_error(self):
        def fail(*args):
            raise ValueError('secret signed URL')
        install(NeutrodyneOkHttpRH, fail)
        with call_scope(self.state), self.assertRaises(TransportError) as caught:
            self.rh.send(Request('https://example.invalid'))
        self.assertNotIn('secret', str(caught.exception))

    def test_body_cap(self):
        self.reply.body = b'x' * (8 * 1024 * 1024 + 1)
        with call_scope(self.state), self.assertRaises(TransportError):
            self.rh.send(Request('https://example.invalid'))

    def test_cancel_during_execute_and_deadline_after_execute(self):
        def cancelled(*args):
            self.state.cancel.set()
            raise RuntimeError('socket cancelled')
        install(NeutrodyneOkHttpRH, cancelled)
        with self.assertRaisesRegex(ShimError, 'CANCELLED'), call_scope(self.state):
            self.rh.send(Request('https://example.invalid'))
        self.state.cancel.clear()
        def expired(*args):
            self.state.deadline = time.monotonic() - 1
            return self.reply
        install(NeutrodyneOkHttpRH, expired)
        with self.assertRaisesRegex(ShimError, 'TIMEOUT'), call_scope(self.state):
            self.rh.send(Request('https://example.invalid'))

    def test_unsupported_extension_and_proxy_never_fall_back(self):
        for request in (Request('https://example.invalid', extensions={'new_extension': True}),
                        Request('https://example.invalid', proxies={'https': 'http://example.invalid'})):
            with self.assertRaises(UnsupportedRequest):
                self.rh.validate(request)
        from yt_dlp.networking import common
        self.assertEqual(list(common._REQUEST_HANDLERS), ['NeutrodyneOkHttp'])
        self.assertTrue(probe()['ok'])

    def test_real_director_surfaces_transport_without_fallback(self):
        def fail(*args):
            raise RuntimeError('secret')
        install(NeutrodyneOkHttpRH, fail)
        from yt_dlp import YoutubeDL
        with call_scope(self.state), self.assertRaises(TransportError):
            YoutubeDL({'quiet': True, 'proxy': '', 'js_runtimes': {}, 'remote_components': set()}).urlopen('https://example.invalid')


class HostTests(unittest.TestCase):
    def setUp(self):
        from neutrodyne_ytx import host_chaquopy
        self.host = host_chaquopy
        self.host._bridge = None
        self.host._calls.clear()

    def tearDown(self):
        self.host._bridge = None
        self.host._calls.clear()

    def test_java_stub_configure_and_call(self):
        calls = []
        stub = SimpleNamespace(execute=lambda *args: calls.append(args))
        java = SimpleNamespace(jclass=lambda name: stub)
        with patch.dict('sys.modules', java=java):
            self.host.configure(False)
        self.assertEqual(json.loads(self.host.call('ping', payload(), 5000, 1)), {'ready': True})
        self.assertTrue(json.loads(self.host.call('selftest', payload(), 5000, 2))['ok'])
        self.assertFalse(calls)
        with self.assertRaisesRegex(ShimError, 'ENGINE_UNAVAILABLE'):
            self.host.configure(False, stub)

    def test_host_adapter_resolve_through_strict_replay(self):
        from neutrodyne_ytx.replay import ReplayRH
        self.host.configure(False, replay_directory=FIXTURES / 'resolve')
        with patch('time.time', return_value=1792368000):
            result = json.loads(self.host.call('resolve', payload(videoId=VIDEO_ID), 5000, 42))
        self.assertEqual(result['formats'][0]['formatId'], '140')
        self.assertEqual(ReplayRH.misses, 0)
        self.assertFalse(self.host._calls)

    def test_cancel_is_call_local(self):
        one, two = threading.Event(), threading.Event()
        self.host._calls.update({1: one, 2: two})
        self.host.cancel(1)
        self.host.cancel(999)
        self.assertTrue(one.is_set())
        self.assertFalse(two.is_set())

    def test_unconfigured_and_expired_calls(self):
        with self.assertRaisesRegex(ShimError, 'ENGINE_UNAVAILABLE'):
            self.host.call('ping', payload(), 10, 1)
        self.host.configure(False, SimpleNamespace(execute=lambda *args: None))
        with self.assertRaisesRegex(ShimError, 'TIMEOUT'):
            self.host.call('ping', payload(), 0, 1)
        self.assertFalse(self.host._calls)
