#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Offline canary tests: fake bridge/bytes, recorded replay through the real child,
and debug-hook child faults. No test reaches live YouTube."""
import ast
import contextlib
import hashlib
import json
import tempfile
import threading
import time
import types
from unittest import mock
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('engine_canary', Path(__file__).parents[1] / 'youtube-engine-canary.py')
canary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(canary)

ROOT = Path(__file__).resolve().parents[3]
RECORDED = ROOT / 'youtube/engine/src/test/resources/recorded'


def bundled_metadata():
    return json.loads((canary.BUNDLE.parent / 'bundled.json').read_text())


def child_host(replay=None, debug_hooks=False, **kwargs):
    return canary.StdioHost(
        bootstrap=canary.BOOTSTRAP, package=canary.PACKAGE, lib=canary.BUNDLE,
        engine_version=bundled_metadata()['version'], replay=replay,
        debug_hooks=debug_hooks, **kwargs)


def without_env(value):
    """Drop wall-clock-derived fields before comparing with a recorded vector."""
    if isinstance(value, dict):
        return {key: without_env(item) for key, item in value.items()
                if key != 'availableAt'}
    if isinstance(value, list):
        return [without_env(item) for item in value]
    return value


class Bridge:
    def __init__(self):
        self.calls = []

    def call(self, method, payload, deadline, call_id, cancel):
        self.calls.append((method, json.loads(payload)))
        if method == 'lookup':
            return json.dumps({'channelId': 'UC' + 'a' * 21 + 'A'})
        return json.dumps({'formats': [{'protocol': 'https', 'url': 'https://media.example.invalid/audio'}]})


class FakeShimError(Exception):
    """Stands in for neutrodyne_ytx.errors.ShimError, which needs the vendored engine to import."""

    def __init__(self, code):
        self.code = code
        super().__init__(str(code))


class FailingBridge(Bridge):
    def __init__(self, fail_id, error):
        super().__init__()
        self._fail_id = fail_id
        self._error = error

    def call(self, method, payload, deadline, call_id, cancel):
        if call_id == self._fail_id:
            raise self._error
        return super().call(method, payload, deadline, call_id, cancel)


def probe_error(bridge, fetch_chunk=lambda url: canary.CHUNK):
    try:
        canary.run(bridge, fetch_chunk)
    except Exception as error:
        return error
    raise AssertionError('the probe must fail')


class DiagnosticsTests(unittest.TestCase):
    def test_main_failure_artifact_records_stage_without_sensitive_text(self):
        sensitive = 'https://media.example.invalid/audio?sig=PRIVATE&password=hidden'
        bridge = FailingBridge(1, RuntimeError(sensitive))
        package = types.ModuleType('neutrodyne_ytx')
        bridge_module = types.ModuleType('neutrodyne_ytx.bridge')
        bridge_module.Bridge = lambda **kwargs: bridge
        errors_module = types.ModuleType('neutrodyne_ytx.errors')
        errors_module.ShimError = FakeShimError
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'yt-dlp'
            bundle.write_bytes(b'offline fixture')
            (root / 'bundled.json').write_text(json.dumps({'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest()}))
            with contextlib.chdir(root), mock.patch.object(canary, 'BUNDLE', bundle), \
                    mock.patch.dict('sys.modules', {'neutrodyne_ytx': package,
                                                   'neutrodyne_ytx.bridge': bridge_module,
                                                   'neutrodyne_ytx.errors': errors_module}), \
                    mock.patch('sys.path', list(__import__('sys').path)), \
                    mock.patch.object(canary, 'run_stdio_leg',
                                      return_value={'ok': True, 'channels': [], 'chunkBytes': 1}):
                self.assertEqual(1, canary.main())
                text = (root / 'youtube-engine-canary.json').read_text()
                result = json.loads(text)
            self.assertEqual('lookup-mkbhd', result.get('stage'))
            self.assertEqual('UNEXPECTED', result.get('code'))
            self.assertFalse(result['ok'])
            for secret in (sensitive, 'PRIVATE', 'password=', 'hidden', 'RuntimeError'):
                self.assertNotIn(secret, text)

    def test_every_stage_reports_a_finite_identifier_and_the_shim_code(self):
        for call_id, stage in ((1, 'lookup-mkbhd'), (2, 'lookup-nasa'), (3, 'resolve')):
            with self.subTest(stage=stage):
                error = probe_error(FailingBridge(call_id, FakeShimError('NETWORK')))
                result = canary.report(error, FakeShimError)
                self.assertEqual({'ok': False, 'error': canary.GENERIC_ERROR,
                                  'stage': stage, 'code': 'NETWORK'}, result)
        error = probe_error(Bridge(), lambda url: (_ for _ in ()).throw(FakeShimError('NETWORK')))
        result = canary.report(error, FakeShimError)
        self.assertEqual(('chunk', 'NETWORK'), (result['stage'], result['code']))

    def test_only_allowlisted_shim_codes_are_reported(self):
        for bogus in ('https://googlevideo.com/videoplayback?sig=x', 'BotChallenge', 500, None):
            with self.subTest(code=bogus):
                error = probe_error(FailingBridge(1, FakeShimError(bogus)))
                self.assertEqual('UNEXPECTED', canary.report(error, FakeShimError)['code'])
        # A non-shim failure never exposes a same-named attribute either.
        error = probe_error(FailingBridge(1, ValueError('x')))
        error.code = 'NETWORK'
        self.assertEqual('UNEXPECTED', canary.report(error, FakeShimError)['code'])

    def test_non_shim_and_unattributed_failures_use_the_unexpected_constants(self):
        result = canary.report(probe_error(FailingBridge(2, ValueError('boom'))), FakeShimError)
        self.assertEqual(('lookup-nasa', 'UNEXPECTED'), (result['stage'], result['code']))
        result = canary.report(ValueError('outside any stage'), FakeShimError)
        self.assertEqual(('unknown', 'UNEXPECTED'), (result['stage'], result['code']))

    def test_no_raw_error_text_signed_url_or_class_names_in_the_report(self):
        signed = ('https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&sig=SECRET'
                  '&ratebypass=yes&password=hunter2')
        errors = [probe_error(FailingBridge(3, RuntimeError('resolve failed: ' + signed))),
                  probe_error(Bridge(), lambda url: (_ for _ in ()).throw(
                      RuntimeError('chunk failed: ' + signed)))]
        for error in errors:
            blob = json.dumps(canary.report(error, FakeShimError))
            for needle in (signed, 'googlevideo.com', 'videoplayback', 'hunter2',
                           'RuntimeError', 'sig=SECRET', 'ratebypass', 'resolve failed', 'chunk failed'):
                with self.subTest(needle=needle):
                    self.assertNotIn(needle, blob)

    def test_report_fields_are_bounded_constants(self):
        for error in (probe_error(FailingBridge(1, FakeShimError('LIVE'))),
                      probe_error(FailingBridge(3, ValueError('x'))),
                      probe_error(Bridge(), lambda url: 0)):
            result = canary.report(error, FakeShimError)
            self.assertEqual({'ok', 'error', 'stage', 'code'}, set(result))
            self.assertFalse(result['ok'])
            self.assertEqual(canary.GENERIC_ERROR, result['error'])
            self.assertIn(result['stage'], canary.STAGES + (canary.UNKNOWN_STAGE,))
            self.assertIn(result['code'], canary.SAFE_CODES | {canary.UNEXPECTED_CODE})

    def test_code_allowlist_mirrors_the_shim_error_enum(self):
        # Offline: parse the enum source; importing errors.py would need the engine.
        tree = ast.parse((Path(__file__).resolve().parents[3] /
                          'youtube/engine/python/neutrodyne_ytx/errors.py').read_text())
        for node in tree.body:
            if isinstance(node, ast.ClassDef) and node.name == 'Code':
                codes = {item.value.value for item in node.body
                         if isinstance(item, ast.Assign) and isinstance(item.value, ast.Constant)}
                break
        else:
            self.fail('Code enum not found in errors.py')
        self.assertEqual(canary.SAFE_CODES, codes)


class RealChildReplayTests(unittest.TestCase):
    """The real bootstrap child against recorded vectors; no network leaves the box."""

    def test_ping_vector_and_clean_eof(self):
        with child_host(replay=RECORDED / 'ping') as host:
            self.assertEqual('stdio', host.hello['host'])
            self.assertEqual(1, host.hello['shimApi'])
            reply = host.call('ping', json.dumps({'ctx': canary.CTX}),
                              time.monotonic() + 30, 1)
            self.assertEqual({'ready': True}, json.loads(reply))
        self.assertEqual(0, host.returncode)

    def test_lookup_vector_through_real_child(self):
        scenario = RECORDED / 'lookup'
        request = json.loads((scenario / 'request.json').read_text())
        expected = json.loads((scenario / 'result.json').read_text())
        with child_host(replay=scenario) as host:
            reply = json.loads(host.call(request['method'], json.dumps(request['payload']),
                                         time.monotonic() + 30, 1))
        self.assertEqual(expected, reply)
        self.assertEqual(0, host.returncode)

    def test_resolve_vector_through_real_child(self):
        scenario = RECORDED / 'resolve'
        request = json.loads((scenario / 'request.json').read_text())
        expected = json.loads((scenario / 'result.json').read_text())
        with child_host(replay=scenario) as host:
            reply = json.loads(host.call(request['method'], json.dumps(request['payload']),
                                         time.monotonic() + 30, 1))
        self.assertEqual(without_env(expected), without_env(reply))
        self.assertEqual(0, host.returncode)

    def test_error_frame_is_rejected(self):
        with child_host(replay=RECORDED / 'resolve') as host:
            with self.assertRaises(canary.StdioError) as caught:
                host.call('resolve', json.dumps({'ctx': canary.CTX}),
                          time.monotonic() + 30, 1)
        self.assertEqual('UNAVAILABLE', caught.exception.code)

    def test_stdio_leg_returns_the_run_result_through_the_real_child(self):
        captured = {}

        def fake_run(bridge, fetch_chunk):
            captured['bridge'] = bridge
            captured['fetch_chunk'] = fetch_chunk
            reply = json.loads(bridge.call('ping', json.dumps({'ctx': canary.CTX}),
                                           time.monotonic() + 30, 1, threading.Event()))
            self.assertEqual({'ready': True}, reply)
            return {'ok': True, 'channels': [], 'chunkBytes': 1}

        with mock.patch.object(canary, 'run', fake_run):
            result = canary.run_stdio_leg(lambda url: 1, bundled_metadata(),
                                          replay=RECORDED / 'ping')
        self.assertTrue(result['ok'])
        self.assertIsInstance(captured['bridge'], canary.StdioHost)
        self.assertEqual(0, captured['bridge'].returncode)


class ChildFaultTests(unittest.TestCase):
    """Deterministic real-child faults; each one must kill and reap only this child."""

    def test_malformed_response_kills_and_reaps_child(self):
        host = child_host(replay=RECORDED / 'ping', debug_hooks=True)
        host.start()
        with self.assertRaises(canary.StdioError) as caught:
            host.call('_emit_raw', json.dumps({'ctx': canary.CTX, 'raw': '{not json'}),
                      time.monotonic() + 30, 1)
        self.assertEqual(canary.UNEXPECTED_CODE, caught.exception.code)
        host.close()
        self.assertIsNotNone(host.returncode)
        self.assertIsNotNone(host._proc.poll())

    def test_hang_past_deadline_kills_and_reaps_child(self):
        host = child_host(replay=RECORDED / 'ping', debug_hooks=True, hang_grace=0.2)
        host.start()
        with self.assertRaises(canary.StdioError) as caught:
            host.call('_hang', json.dumps({'ctx': canary.CTX}),
                      time.monotonic() + 1.0, 1)
        self.assertEqual('TIMEOUT', caught.exception.code)
        self.assertIsNotNone(host.returncode)
        self.assertIsNotNone(host._proc.poll())

    def test_child_exit_without_reply_fails_and_reaps(self):
        host = child_host(replay=RECORDED / 'ping', debug_hooks=True)
        host.start()
        with self.assertRaises(canary.StdioError):
            host.call('_crash', json.dumps({'ctx': canary.CTX}),
                      time.monotonic() + 30, 1)
        host.close()
        self.assertEqual(70, host.returncode)

    def test_close_during_inflight_call_kills_and_reaps_child(self):
        host = child_host(replay=RECORDED / 'ping', debug_hooks=True)
        host.start()
        host._frame_sent.clear()
        outcome = {}

        def hang():
            try:
                host.call('_hang', json.dumps({'ctx': canary.CTX}),
                          time.monotonic() + 30, 1)
            except Exception as error:
                outcome['error'] = error

        thread = threading.Thread(target=hang)
        thread.start()
        self.assertTrue(host._frame_sent.wait(10), 'the hang call was never sent')
        host.close()
        thread.join(10)
        self.assertFalse(thread.is_alive())
        self.assertIsInstance(outcome.get('error'), canary.StdioError)
        self.assertIsNotNone(host.returncode)
        self.assertIsNotNone(host._proc.poll())


class ScriptedChildTests(unittest.TestCase):
    """Adapter handshake faults that need no engine bundle."""

    def write_child(self, directory, lines):
        script = directory / 'child.py'
        script.write_text('\n'.join(lines) + '\n')
        return script

    def test_wrong_shim_api_is_rejected_and_child_reaped(self):
        with tempfile.TemporaryDirectory() as directory:
            script = self.write_child(Path(directory), [
                "import json, os, sys, time",
                "sys.stdout.write(json.dumps({'t': 'hello', 'state': 'ready', 'shimApi': 2,",
                "                             'host': 'stdio', 'pid': os.getpid()}) + '\\n')",
                "sys.stdout.flush()",
                "time.sleep(30)",
            ])
            host = canary.StdioHost(script, canary.PACKAGE, canary.BUNDLE, 'v',
                                    start_timeout=2.0)
            with self.assertRaises(canary.StdioError) as caught:
                host.start()
            self.assertEqual(canary.UNEXPECTED_CODE, caught.exception.code)
            self.assertIsNotNone(host.returncode)
            self.assertIsNotNone(host._proc.poll())

    def test_silent_child_times_out_and_is_reaped(self):
        with tempfile.TemporaryDirectory() as directory:
            script = self.write_child(Path(directory), ['import time', 'time.sleep(30)'])
            host = canary.StdioHost(script, canary.PACKAGE, canary.BUNDLE, 'v',
                                    start_timeout=1.0)
            with self.assertRaises(canary.StdioError):
                host.start()
            self.assertIsNotNone(host.returncode)
            self.assertIsNotNone(host._proc.poll())

    def test_oversize_line_is_rejected_and_child_reaped(self):
        with tempfile.TemporaryDirectory() as directory:
            script = self.write_child(Path(directory), [
                "import json, os, sys",
                "sys.stdout.write(json.dumps({'t': 'hello', 'state': 'ready', 'shimApi': 1,",
                "                             'host': 'stdio', 'pid': os.getpid()}) + '\\n')",
                "sys.stdout.write('x' * (256 * 1024 + 1) + '\\n')",
                "sys.stdout.flush()",
                "import time; time.sleep(30)",
            ])
            host = canary.StdioHost(script, canary.PACKAGE, canary.BUNDLE, 'v',
                                    start_timeout=2.0)
            with self.assertRaises(canary.StdioError) as caught:
                host.start()
            self.assertEqual(canary.UNEXPECTED_CODE, caught.exception.code)
            self.assertIsNotNone(host.returncode)

    def test_oversize_result_is_rejected_and_child_reaped(self):
        with tempfile.TemporaryDirectory() as directory:
            script = self.write_child(Path(directory), [
                "import json, os, sys",
                "sys.stdout.write(json.dumps({'t': 'hello', 'state': 'ready', 'shimApi': 1,",
                "                             'host': 'stdio', 'pid': os.getpid()}) + '\\n')",
                "sys.stdout.flush()",
                "frame = json.loads(sys.stdin.readline())",
                "sys.stdout.write(json.dumps({'t': 'ok', 'id': frame['id'],",
                "                             'r': 'x' * (128 * 1024 + 1)}) + '\\n')",
                "sys.stdout.flush()",
                "import time; time.sleep(30)",
            ])
            host = canary.StdioHost(script, canary.PACKAGE, canary.BUNDLE, 'v',
                                    start_timeout=2.0)
            with self.assertRaises(canary.StdioError) as caught:
                host.start()
            self.assertEqual(canary.UNEXPECTED_CODE, caught.exception.code)
            self.assertIsNotNone(host.returncode)


class LegReportTests(unittest.TestCase):
    def test_stdio_failure_is_not_concealed_by_embedded_success(self):
        embedded = {'ok': True, 'channels': [{'channelId': 'UC' + 'a' * 21 + 'A'}],
                    'chunkBytes': canary.CHUNK}
        stdio = {'ok': False, 'error': canary.GENERIC_ERROR,
                 'stage': 'resolve', 'code': 'NETWORK'}
        result = canary.combine(embedded, stdio)
        self.assertFalse(result['ok'])
        self.assertEqual(('resolve', 'NETWORK'), (result['stage'], result['code']))
        self.assertEqual(embedded, result['legs']['embedded'])
        self.assertEqual(stdio, result['legs']['stdio'])

    def test_both_success_reports_both_legs(self):
        embedded = {'ok': True, 'channels': [], 'chunkBytes': 1}
        stdio = {'ok': True, 'channels': [], 'chunkBytes': 2}
        result = canary.combine(embedded, stdio)
        self.assertTrue(result['ok'])
        self.assertEqual(embedded, result['legs']['embedded'])
        self.assertEqual(stdio, result['legs']['stdio'])

    def test_embedded_failure_still_reports_stdio_independently(self):
        embedded = {'ok': False, 'error': canary.GENERIC_ERROR,
                    'stage': 'lookup-mkbhd', 'code': 'UNEXPECTED'}
        stdio = {'ok': True, 'channels': [], 'chunkBytes': 1}
        result = canary.combine(embedded, stdio)
        self.assertFalse(result['ok'])
        self.assertEqual(('lookup-mkbhd', 'UNEXPECTED'), (result['stage'], result['code']))
        self.assertTrue(result['legs']['stdio']['ok'])

    def test_stdio_failure_codes_are_allowlisted(self):
        self.assertEqual({'ok': False, 'error': canary.GENERIC_ERROR,
                          'stage': 'unknown', 'code': 'NETWORK'},
                         canary.report(canary.StdioError('NETWORK'), canary.StdioError))
        self.assertEqual('UNEXPECTED', canary.report(
            canary.StdioError('https://googlevideo.com/?sig=SECRET'),
            canary.StdioError)['code'])

    def test_shim_api_constant_matches_the_shim_source(self):
        source = (ROOT / 'youtube/engine/python/neutrodyne_ytx/__init__.py').read_text()
        self.assertIn('SHIM_API_VERSION = %d' % canary.SHIM_API, source)


class MainLegTests(unittest.TestCase):
    def test_main_fails_when_only_the_stdio_leg_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'yt-dlp'
            bundle.write_bytes(b'offline fixture')
            (root / 'bundled.json').write_text(json.dumps(
                {'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(), 'version': 'v'}))
            with contextlib.chdir(root), \
                    mock.patch.object(canary, 'BUNDLE', bundle), \
                    mock.patch.object(canary, 'run_embedded',
                                      return_value={'ok': True, 'channels': [], 'chunkBytes': 1}), \
                    mock.patch.object(canary, 'run_stdio_leg',
                                      return_value={'ok': False,
                                                    'error': canary.GENERIC_ERROR,
                                                    'stage': 'resolve', 'code': 'NETWORK'}):
                self.assertEqual(1, canary.main())
            result = json.loads((root / 'youtube-engine-canary.json').read_text())
        self.assertFalse(result['ok'])
        self.assertEqual(('resolve', 'NETWORK'), (result['stage'], result['code']))
        self.assertTrue(result['legs']['embedded']['ok'])
        self.assertFalse(result['legs']['stdio']['ok'])

    def test_main_succeeds_when_both_legs_succeed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'yt-dlp'
            bundle.write_bytes(b'offline fixture')
            (root / 'bundled.json').write_text(json.dumps(
                {'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(), 'version': 'v'}))
            with contextlib.chdir(root), \
                    mock.patch.object(canary, 'BUNDLE', bundle), \
                    mock.patch.object(canary, 'run_embedded',
                                      return_value={'ok': True, 'channels': [],
                                                    'chunkBytes': 1}), \
                    mock.patch.object(canary, 'run_stdio_leg',
                                      return_value={'ok': True, 'channels': [],
                                                    'chunkBytes': 2}):
                self.assertEqual(0, canary.main())
            result = json.loads((root / 'youtube-engine-canary.json').read_text())
        self.assertTrue(result['ok'])
        self.assertEqual(1, result['legs']['embedded']['chunkBytes'])
        self.assertEqual(2, result['legs']['stdio']['chunkBytes'])


class CanaryTests(unittest.TestCase):
    def test_two_lookups_one_resolve_and_bounded_chunk(self):
        bridge = Bridge()
        result = canary.run(bridge, lambda url: canary.CHUNK)
        self.assertEqual([entry[0] for entry in bridge.calls], ['lookup', 'lookup', 'resolve'])
        self.assertTrue(result['ok'])
        self.assertEqual(canary.CHUNK, result['chunkBytes'])
        self.assertNotIn('https://', json.dumps(result))

    def test_feed_probe_runs_after_an_engine_probe_failure(self):
        workflow = (Path(__file__).resolve().parents[3] / '.github/workflows/nightly.yml').read_text()
        feed_step = workflow.split('- name: Bounded controls and feed variants', 1)[1].split('- name:', 1)[0]
        self.assertIn('if: always()', feed_step)

    def test_empty_or_oversize_media_never_reports_success(self):
        for size in (0, canary.CHUNK + 1):
            with self.subTest(size=size), self.assertRaises(ValueError):
                canary.run(Bridge(), lambda url: size)


if __name__ == '__main__':
    unittest.main()
