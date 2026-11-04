#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Canary control flow uses a fake bridge and fake bytes, without network access."""
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


class CanaryTests(unittest.TestCase):
    def test_two_lookups_one_resolve_and_bounded_chunk(self):
        bridge = Bridge()
        result = canary.run(bridge, lambda url: canary.CHUNK)
        self.assertEqual([entry[0] for entry in bridge.calls], ['lookup', 'lookup', 'resolve'])
        self.assertTrue(result['ok'])
        self.assertEqual(canary.CHUNK, result['chunkBytes'])
        self.assertNotIn('https://', json.dumps(result))

    def test_empty_or_oversize_media_never_reports_success(self):
        for size in (0, canary.CHUNK + 1):
            with self.subTest(size=size), self.assertRaises(ValueError):
                canary.run(Bridge(), lambda url: size)


if __name__ == '__main__':
    unittest.main()
