#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fixture checks for fetch_coordinate: mirror fall-through and the fetch cache."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest import mock

spec = importlib.util.spec_from_file_location(
    "android_notices", Path(__file__).parents[1] / "android_notices.py")
android_notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(android_notices)

ARTIFACT, VERSION = "lib", "1.0"
REL = f"org/example/{ARTIFACT}/{VERSION}"
NAMES = [f"{ARTIFACT}-{VERSION}.{ext}" for ext in ("module", "pom", "aar", "jar")]
MIRRORS = ["m1", "m2"]


class FetchCoordinateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.requests = []

    def fetch(self, payloads, dest):
        """Run fetch_coordinate with a fake urlopen; payloads maps url -> bytes
        or an int HTTP status to fail with (unlisted urls answer 404)."""
        def opener(url, timeout=None):
            self.requests.append(url)
            result = payloads.get(url)
            if result is None:
                raise urllib.error.HTTPError(url, 404, "missing", {}, None)
            if isinstance(result, int):
                raise urllib.error.HTTPError(url, result, "error", {}, None)
            return io.BytesIO(result)
        with mock.patch.object(android_notices.urllib.request, "urlopen", opener):
            android_notices.fetch_coordinate("org.example", ARTIFACT, VERSION, MIRRORS, dest)

    def dest_for(self, case):
        path = self.root / case
        path.mkdir()
        return path

    def url(self, base, name):
        return f"{base}/{REL}/{name}"

    def test_flaky_mirror_falls_through_to_the_next_source(self):
        for status in (429, 500, 503):
            with self.subTest(status=status):
                dest = self.dest_for(f"s{status}")
                payloads = {self.url("m1", name): status for name in NAMES}
                payloads[self.url("m2", f"{ARTIFACT}-{VERSION}.aar")] = b"aar"
                self.fetch(payloads, dest)
                self.assertTrue(dest.joinpath(REL, f"{ARTIFACT}-{VERSION}.aar").is_file())

    def test_other_http_errors_still_raise(self):
        dest = self.dest_for("forbidden")
        with self.assertRaises(android_notices.InputError):
            self.fetch({self.url("m1", f"{ARTIFACT}-{VERSION}.module"): 403}, dest)

if __name__ == "__main__":
    unittest.main()
