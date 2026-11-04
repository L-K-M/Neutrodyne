# SPDX-License-Identifier: Unlicense
import concurrent.futures
import io
import json
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch

from yt_dlp.networking import common
from yt_dlp.networking.common import Request, Response
from yt_dlp.networking.exceptions import HTTPError, TransportError
from yt_dlp.utils import DownloadError, ExtractorError, GeoRestrictedError

from neutrodyne_ytx.bridge import Bridge, CallState, call_scope, current_call, fixed_options, DESCRIPTION_BYTES
from neutrodyne_ytx.errors import Code, ShimError, classify
from neutrodyne_ytx.okhttp_rh import install, NeutrodyneOkHttpRH
from neutrodyne_ytx.replay import ReplayRH, Mode, request_key, scrub
from neutrodyne_ytx.selftest import probe
from make_scenarios import CTX, VIDEO_ID, CHANNEL_ID, FIXTURES


def payload(**fields):
    return json.dumps({'ctx': CTX, **fields})


def invoke(bridge, method='resolve', **fields):
    return json.loads(bridge.call(method, payload(**fields), time.monotonic() + 30, 1))


class FakeYdl:
    def __init__(self, info=None, warning=None, error=None):
        self.info = info or {}
        self.warning, self.error = warning, error
        self.options = None
        self.calls = []

    def __call__(self, options):
        self.options = options
        return self

    def extract_info(self, url, **options):
        self.calls.append((url, options))
        if self.warning:
            self.options['logger'].warning(self.warning)
        if self.error:
            raise self.error
        return self.info


class ReplayTests(unittest.TestCase):
    def setUp(self):
        ReplayRH.configure(FIXTURES / 'resolve')
        install(ReplayRH)

    def tearDown(self):
        self.assertEqual(ReplayRH.misses, 0)

    def test_strict_poison_even_when_extractor_swallows_error(self):
        class Swallow(FakeYdl):
            def extract_info(self, url, **options):
                rh = ReplayRH(logger=self.options['logger'])
                try:
                    rh.send(Request('https://www.youtube.com/not-recorded'))
                except TransportError:
                    pass
                return {'title': 'not success'}
        with self.assertRaisesRegex(ShimError, 'EXTRACTION'):
            invoke(Bridge(ydl_factory=Swallow()), 'facts', videoId=VIDEO_ID)
        self.assertEqual(ReplayRH.misses, 1)
        ReplayRH.misses = 0

    def test_strict_key_includes_client_query_and_continuation(self):
        a = Request('https://www.youtube.com/youtubei/v1/browse?x=1', data=b'{"continuation":"a"}')
        b = Request('https://www.youtube.com/youtubei/v1/browse?x=1', data=b'{"continuation":"b"}')
        self.assertNotEqual(request_key(a), request_key(b))
        self.assertEqual(request_key(a, Mode.CONTRACT), request_key(b, Mode.CONTRACT))

    def test_contract_unrecorded_is_404(self):
        ReplayRH.configure(FIXTURES / 'ping', Mode.CONTRACT)
        state = CallState(1, time.monotonic() + 10, CTX, threading.Event())
        with call_scope(state), self.assertRaises(HTTPError) as caught:
            ReplayRH(logger=None).send(Request('https://example.invalid/new'))
        self.assertEqual(caught.exception.status, 404)
        self.assertFalse(state.replay_miss)

    def test_real_resolve_and_tab_concurrently(self):
        directory = Path(__file__).resolve().parents[4] / 'youtube/ytdlp/build/shim/concurrent'
        directory.mkdir(parents=True, exist_ok=True)
        records = []
        for name in ('resolve', 'tab'):
            fixture = json.loads((FIXTURES / name / 'http.json').read_text())
            for record in fixture['exchanges']:
                response = record['response']
                if 'bodyRef' in response:
                    response['body'] = fixture['bodies'][response.pop('bodyRef')]
                records.append(record)
        (directory / 'http.json').write_text(json.dumps({'exchanges': records}))
        ReplayRH.configure(directory)
        bridge = Bridge()
        with patch('time.time', return_value=1792368000), concurrent.futures.ThreadPoolExecutor(2) as executor:
            resolve = executor.submit(invoke, bridge, 'resolve', videoId=VIDEO_ID)
            tab = executor.submit(invoke, bridge, 'tab_open', channelId=CHANNEL_ID, tab='videos')
            self.assertEqual(resolve.result()['formats'][0]['formatId'], '140')
            self.assertEqual(tab.result()['items'][0]['id'], VIDEO_ID)

    def test_real_probe_checks_worker_director(self):
        self.assertEqual(invoke(Bridge(), 'selftest'), {'ok': True, 'missing': []})
        common._REQUEST_HANDLERS.clear()
        self.assertIn('exclusive_handler', probe()['missing'])

    def test_broken_bridge_never_enables_urllib(self):
        install(NeutrodyneOkHttpRH)
        self.assertFalse(probe()['ok'])
        self.assertEqual(set(common._REQUEST_HANDLERS), {'NeutrodyneOkHttp'})

    def test_missing_api_symbol_is_reported(self):
        with patch.object(common, 'register_rh', None):
            # Replaced API must fail, not just pass an hasattr probe.
            self.assertIn('register_rh', probe()['missing'])

    def test_fixture_policy_and_scrub(self):
        for path in FIXTURES.rglob('*.json'):
            self.assertLessEqual(path.stat().st_size, 1_000_000)
        clean = scrub({'visitorData': 'secret', 'cookie': 'secret', 'url':
                      'https://example.invalid/a?ip=1.2.3.4&expire=4&sig=secret&lsig=secret&pot=secret'})
        self.assertEqual(set(clean), {'url'})
        self.assertNotIn('secret', str(clean))
        self.assertIn('4102444800', clean['url'])


def replay_case(scenario):
    def test(self):
        directory = FIXTURES / scenario
        ReplayRH.configure(directory)
        install(ReplayRH)
        request = json.loads((directory / 'request.json').read_text())
        expected = json.loads((directory / 'result.json').read_text())
        bridge = Bridge()
        self.enterContext(patch('time.time', return_value=1792368000))
        if 'error' in expected:
            with self.assertRaises(ShimError) as caught:
                invoke(bridge, request['method'], **{k: v for k, v in request['payload'].items() if k != 'ctx'})
            self.assertEqual(caught.exception.code, expected['error'])
        else:
            result = invoke(bridge, request['method'], **{k: v for k, v in request['payload'].items() if k != 'ctx'})
            if scenario == 'version':
                expected['python'] = result['python']
            self.assertEqual(result, expected)
    return test


for _scenario in sorted(path.name for path in FIXTURES.iterdir() if path.is_dir()):
    setattr(ReplayTests, 'test_replay_' + _scenario, replay_case(_scenario))


class BridgeTests(unittest.TestCase):
    def test_options_and_process_false(self):
        fake = FakeYdl({'title': 'synthetic'})
        invoke(Bridge(ydl_factory=fake), 'facts', videoId=VIDEO_ID)
        self.assertEqual(fake.calls[0][1], {'download': False, 'process': False})
        self.assertEqual(fake.options, fixed_options(CTX, False) | {'logger': fake.options['logger']})
        self.assertEqual(fake.options['extractor_args']['youtube']['player_client'], ['default', '-web'])
        self.assertEqual(fake.options['js_runtimes'], {})
        self.assertEqual(fake.options['remote_components'], set())
        self.assertEqual(fake.options['proxy'], '')
        self.assertNotIn('extract_flat', fake.options)
        self.assertNotIn('lazy_playlist', fake.options)

    def test_worker_and_locale_reuse(self):
        created = []
        def factory(options):
            created.append(options)
            return FakeYdl()
        bridge = Bridge(ydl_factory=factory)
        invoke(bridge, 'ping')
        invoke(bridge, 'ping')
        bridge.call('ping', json.dumps({'ctx': dict(CTX, hl='de')}), time.monotonic() + 5, 2)
        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            executor.submit(invoke, bridge, 'ping').result()
        self.assertEqual(len(created), 3)

    def test_lookup_never_iterates_entries(self):
        def forbidden():
            raise AssertionError('lookup consumed entries')
            yield
        result = invoke(Bridge(ydl_factory=FakeYdl({'id': CHANNEL_ID, 'entries': forbidden()})),
                        'lookup', url='https://www.youtube.com/@synthetic')
        self.assertEqual(result['channelId'], CHANNEL_ID)

    def test_description_utf8_and_truncation(self):
        result = invoke(Bridge(ydl_factory=FakeYdl({'description': '🙂' * DESCRIPTION_BYTES})), 'facts', videoId=VIDEO_ID)
        self.assertEqual(len(result['description'].encode()), DESCRIPTION_BYTES)
        self.assertTrue(result['descriptionTruncated'])
        self.assertNotIn('formats', result)

    def test_output_limit_rejects_without_partial_formats(self):
        fmt = {'format_id': '140-drc', 'url': 'https://example.invalid/' + 'x' * 15000,
               'vcodec': 'none', 'acodec': 'aac'}
        with self.assertRaisesRegex(ShimError, 'EXTRACTION'):
            invoke(Bridge(ydl_factory=FakeYdl({'formats': [fmt] * 10})), videoId=VIDEO_ID)

    def test_format_trim_identity_and_seconds(self):
        fmt = {'format_id': '140-drc-dub', 'url': 'https://example.invalid/audio',
               'vcodec': 'none', 'acodec': 'aac', 'available_at': 123.5, 'xtags': 'drc=1', 'player_js': 'private'}
        info = {'formats': [dict(fmt, vcodec='avc1'), fmt], 'release_timestamp': 321, 'duration': 123.5}
        result = invoke(Bridge(ydl_factory=FakeYdl(info)), videoId=VIDEO_ID)
        self.assertEqual(len(result['formats']), 1)
        self.assertEqual(result['formats'][0]['formatId'], '140-drc-dub')
        self.assertEqual(result['formats'][0]['availableAt'], 123.5)
        self.assertEqual(result['releaseTs'], 321)
        self.assertNotIn('player_js', str(result))

    def test_reject_untrusted_inputs_before_extraction(self):
        fake = FakeYdl()
        bridge = Bridge(ydl_factory=fake)
        for method, fields in [('resolve', {'videoId': '../x'}), ('lookup', {'url': 'https://evil.invalid/@x'}),
                               ('tab_open', {'channelId': CHANNEL_ID, 'tab': '../x'}),
                               ('search_open', {'query': ''}), ('search_open', {'query': 'x', 'pageSize': 21})]:
            with self.subTest(method=method, fields=fields), self.assertRaises(ShimError):
                invoke(bridge, method, **fields)
        for raw in ('{}', '[]', 'null', 'NaN', 'x' * 16385, payload(ctx={'ua': '\n'})):
            with self.subTest(raw=raw[:50]), self.assertRaises(ShimError):
                bridge.call('ping', raw, time.monotonic() + 2, 1)
        self.assertFalse(fake.calls)

    def test_language_locale_without_country(self):
        result = Bridge(ydl_factory=FakeYdl()).call('ping', payload(ctx=dict(CTX, gl='')), time.monotonic() + 5, 1)
        self.assertTrue(json.loads(result)['ready'])

    def test_cancel_and_expired_deadline_before_work(self):
        fake = FakeYdl()
        event = threading.Event()
        event.set()
        bridge = Bridge(ydl_factory=fake)
        with self.assertRaisesRegex(ShimError, 'CANCELLED'):
            bridge.call('ping', payload(), time.monotonic() + 5, 1, event)
        with self.assertRaisesRegex(ShimError, 'TIMEOUT'):
            bridge.call('ping', payload(), time.monotonic() - 1, 1)
        self.assertIsNone(fake.options)

    def test_baseexception_boundary_and_warning_reset(self):
        fake = FakeYdl(warning='Private video')
        bridge = Bridge(ydl_factory=fake)
        with self.assertRaisesRegex(ShimError, 'PRIVATE'):
            invoke(bridge, videoId=VIDEO_ID)
        fake.warning = None
        with self.assertRaisesRegex(ShimError, 'EXTRACTION'):
            invoke(bridge, videoId=VIDEO_ID)
        fake.error = KeyboardInterrupt()
        with self.assertRaisesRegex(ShimError, 'EXTRACTION'):
            invoke(bridge, videoId=VIDEO_ID)

    def test_simultaneous_resolve_tab_warning_isolation(self):
        barrier = threading.Barrier(2)
        class Concurrent(FakeYdl):
            def extract_info(self, url, **options):
                if '/watch?' in url:
                    self.options['logger'].warning('Private video')
                    barrier.wait(timeout=5)
                    return {}
                def entries():
                    self.options['logger'].warning('Sign in to confirm your age')
                    barrier.wait(timeout=5)
                    yield {'id': VIDEO_ID}
                return {'entries': entries()}
        bridge = Bridge(ydl_factory=lambda options: Concurrent()(options))
        with concurrent.futures.ThreadPoolExecutor(2) as executor:
            resolve = executor.submit(invoke, bridge, 'resolve', videoId=VIDEO_ID)
            tab = executor.submit(invoke, bridge, 'tab_open', channelId=CHANNEL_ID, tab='videos')
            self.assertEqual(tab.result()['items'][0]['id'], VIDEO_ID)
            with self.assertRaisesRegex(ShimError, 'PRIVATE'):
                resolve.result()


class CursorTests(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.pulled = 0
        def entries():
            for _ in range(1000):
                self.pulled += 1
                yield {'id': VIDEO_ID, 'channel_id': CHANNEL_ID, 'title': 'Synthetic'}
        self.bridge = Bridge(ydl_factory=lambda _: FakeYdl({'entries': entries()}), clock=lambda: self.now)

    def page(self, kind='tab'):
        fields = {'channelId': CHANNEL_ID, 'tab': 'videos'} if kind == 'tab' else {'query': 'synthetic'}
        return invoke(self.bridge, kind + '_open', **fields)

    def test_lazy_bounded_page_and_cross_worker_continuation(self):
        first = self.page()
        self.assertEqual(self.pulled, 30)
        self.assertEqual(len(first['cursor']), 32)
        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            next_page = executor.submit(invoke, self.bridge, 'tab_next', cursor=first['cursor']).result()
        self.assertEqual(self.pulled, 60)
        self.assertEqual(len(next_page['items']), 30)

    def test_search_20_and_wrong_kind(self):
        first = self.page('search')
        self.assertEqual(self.pulled, 20)
        with self.assertRaisesRegex(ShimError, 'CURSOR_EXPIRED'):
            invoke(self.bridge, 'tab_next', cursor=first['cursor'])
        self.assertEqual(len(invoke(self.bridge, 'search_next', cursor=first['cursor'])['hits']), 20)

    def test_ttl_and_lru_16(self):
        tokens = [self.page()['cursor'] for _ in range(16)]
        invoke(self.bridge, 'tab_next', cursor=tokens[0])
        self.page()
        with self.assertRaisesRegex(ShimError, 'CURSOR_EXPIRED'):
            invoke(self.bridge, 'tab_next', cursor=tokens[1])
        invoke(self.bridge, 'tab_next', cursor=tokens[0])
        self.now = 600
        with self.assertRaisesRegex(ShimError, 'CURSOR_EXPIRED'):
            invoke(self.bridge, 'tab_next', cursor=tokens[0])
        self.assertFalse(self.bridge._cursors)

    def test_unknown_restart_and_locale_mismatch(self):
        token = self.page()['cursor']
        with self.assertRaisesRegex(ShimError, 'CURSOR_EXPIRED'):
            invoke(Bridge(), 'tab_next', cursor=token)
        with self.assertRaisesRegex(ShimError, 'CURSOR_EXPIRED'):
            self.bridge.call('tab_next', json.dumps({'ctx': dict(CTX, hl='de'), 'cursor': token}), 10, 1)

    def test_deadline_between_generator_pulls(self):
        def entries():
            yield {'id': VIDEO_ID}
            self.now = 100
            yield {'id': VIDEO_ID}
        self.bridge = Bridge(ydl_factory=FakeYdl({'entries': entries()}), clock=lambda: self.now)
        with self.assertRaisesRegex(ShimError, 'TIMEOUT'):
            self.bridge.call('tab_open', payload(channelId=CHANNEL_ID, tab='videos'), 50, 1)
        self.assertFalse(self.bridge._cursors)


class ErrorTests(unittest.TestCase):
    def test_structured_and_warning_vectors(self):
        vectors = [({'age_limit': 18}, (), Code.AGE_RESTRICTED),
                   ({'availability': 'subscriber_only'}, (), Code.MEMBERS_ONLY),
                   ({'availability': 'premium_only'}, (), Code.MEMBERS_ONLY),
                   ({'availability': 'private'}, (), Code.PRIVATE),
                   ({'live_status': 'is_upcoming'}, (), Code.UPCOMING),
                   ({'live_status': 'is_live'}, (), Code.LIVE),
                   ({'live_status': 'post_live'}, (), Code.LIVE),
                   ({}, ('made for kids',), Code.KIDS_ONLY),
                   ({}, ('Sign in to confirm your age',), Code.AGE_RESTRICTED),
                   ({'availability': 'private'}, ("This content isn't available, try again later",), Code.RATE_LIMITED),
                   ({}, ('This video is no longer available because the YouTube account associated with this video has been terminated.',), Code.UNAVAILABLE),
                   ({}, ('unknown SABR-only',), Code.EXTRACTION)]
        for info, warnings, code in vectors:
            with self.subTest(code=code), self.assertRaisesRegex(ShimError, code.value):
                invoke(Bridge(ydl_factory=FakeYdl(info, '\n'.join(warnings))), videoId=VIDEO_ID)

    def test_facts_preserves_live_state_without_audio(self):
        for status in ('is_upcoming', 'is_live', 'post_live'):
            result = invoke(Bridge(ydl_factory=FakeYdl({'live_status': status})), 'facts', videoId=VIDEO_ID)
            self.assertEqual(result['liveStatus'], status)

    def test_exception_types_and_wrapped_causes(self):
        for error, code in [(GeoRestrictedError('x'), Code.REGION_BLOCKED), (TransportError('x'), Code.NETWORK),
                            (HTTPError(Response(io.BytesIO(), 'https://example.invalid', {}, 429)), Code.RATE_LIMITED),
                            (HTTPError(Response(io.BytesIO(), 'https://example.invalid', {}, 503)), Code.NETWORK),
                            (ExtractorError('Private video'), Code.PRIVATE), (ValueError('unknown'), Code.EXTRACTION)]:
            self.assertEqual(classify(error=error), code)
            wrapped = DownloadError('generic', exc_info=(type(error), error, None))
            self.assertEqual(classify(error=wrapped), code)


class SyntheticErrorReplayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from make_scenarios import generate
        cls.directory = Path(__file__).resolve().parents[4] / 'youtube/ytdlp/build/shim/generated'
        with patch('time.time', return_value=1792368000):
            generate(cls.directory, scenarios=('age', 'member', 'kids', 'region', 'private', 'removed', 'rate', 'bot', 'unknown', 'sabr'))


def error_replay_case(scenario, code):
    def test(self):
        directory = self.directory / scenario
        self.assertEqual(json.loads((directory / 'result.json').read_text()), {'error': code})
        ReplayRH.configure(directory)
        install(ReplayRH)
        with patch('time.time', return_value=1792368000), self.assertRaisesRegex(ShimError, code):
            invoke(Bridge(), videoId=VIDEO_ID)
        self.assertEqual(ReplayRH.misses, 0)
    return test


for _scenario, _code in {'age': 'AGE_RESTRICTED', 'member': 'MEMBERS_ONLY', 'kids': 'KIDS_ONLY',
                         'region': 'REGION_BLOCKED', 'private': 'PRIVATE', 'removed': 'UNAVAILABLE',
                         'rate': 'RATE_LIMITED', 'bot': 'RATE_LIMITED', 'unknown': 'EXTRACTION', 'sabr': 'EXTRACTION'}.items():
    setattr(SyntheticErrorReplayTests, 'test_replay_' + _scenario, error_replay_case(_scenario, _code))
