#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Canary control flow uses a fake bridge and fake bytes, without network access."""
import ast
import contextlib
import hashlib
import tempfile
import types
from unittest import mock
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('engine_canary', Path(__file__).parents[1] / 'youtube-engine-canary.py')
canary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(canary)


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
                    mock.patch('sys.path', list(__import__('sys').path)):
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
