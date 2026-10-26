#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fixture checks for fetch_coordinate: mirror fall-through and the fetch cache."""
from contextlib import redirect_stderr, redirect_stdout
from http import HTTPStatus
import importlib.util
import io
import json
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
        """Map URLs to bytes, responses or errors; unlisted URLs answer 404."""
        def opener(url, timeout=None):
            self.requests.append(url)
            result = payloads.get(url)
            if result is None:
                raise urllib.error.HTTPError(url, HTTPStatus.NOT_FOUND, "missing", {}, None)
            if isinstance(result, int):
                raise urllib.error.HTTPError(url, result, "error", {}, None)
            if isinstance(result, Exception):
                raise result
            return io.BytesIO(result) if isinstance(result, bytes) else result
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

    def test_network_failure_tries_the_next_mirror(self):
        failures = (urllib.error.URLError("unreachable"), TimeoutError("connect timed out"),
                    ConnectionResetError("connection reset"))
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                dest = self.dest_for(type(failure).__name__)
                payloads = {self.url("m1", name): failure for name in NAMES}
                payloads[self.url("m2", f"{ARTIFACT}-{VERSION}.jar")] = b"jar"
                self.fetch(payloads, dest)
                self.assertTrue(dest.joinpath(REL, f"{ARTIFACT}-{VERSION}.jar").is_file())

    def test_read_timeout_tries_the_next_mirror(self):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.side_effect = TimeoutError("read timed out")
        dest = self.dest_for("read-timeout")
        self.fetch(
            {
                self.url("m1", f"{ARTIFACT}-{VERSION}.jar"): response,
                self.url("m2", f"{ARTIFACT}-{VERSION}.jar"): b"jar",
            },
            dest,
        )
        self.assertEqual(b"jar", dest.joinpath(REL, f"{ARTIFACT}-{VERSION}.jar").read_bytes())

    def test_exhausted_runtime_mirrors_raise(self):
        failures = (HTTPStatus.TOO_MANY_REQUESTS, HTTPStatus.SERVICE_UNAVAILABLE,
                    urllib.error.URLError("unreachable"), TimeoutError("timed out"))
        for index, failure in enumerate(failures):
            with self.subTest(failure=failure):
                dest = self.dest_for(f"exhausted-{index}")
                payloads = {self.url(base, f"{ARTIFACT}-{VERSION}.jar"): failure
                            for base in MIRRORS}
                with self.assertRaises(android_notices.InputError):
                    self.fetch(payloads, dest)

    def test_metadata_only_skip_requires_conclusive_mirror_responses(self):
        # KMP roots can declare packaging=pom while code lives behind redirects.
        # A mirror outage must not turn their missing runtime files into a clean scan.
        repo = self.root / "repo"
        pom = repo / REL / f"{ARTIFACT}-{VERSION}.pom"
        pom.parent.mkdir(parents=True)
        pom.write_text("<project><packaging>pom</packaging></project>")
        definitions = self.root / "definitions.json"
        definitions.write_text(json.dumps({
            "libraries": [{"uniqueId": "org.example:lib", "artifactVersion": VERSION,
                           "name": ARTIFACT, "licenses": []}],
            "licenses": {},
        }))
        for status in (HTTPStatus.NOT_FOUND, HTTPStatus.TOO_MANY_REQUESTS,
                       HTTPStatus.SERVICE_UNAVAILABLE):
            with self.subTest(status=status):
                def unavailable(url, timeout=None):
                    raise urllib.error.HTTPError(url, status, "error", {}, None)

                errors = io.StringIO()
                with mock.patch.object(android_notices.urllib.request, "urlopen", unavailable), \
                        redirect_stdout(io.StringIO()), redirect_stderr(errors):
                    result = android_notices.main([
                        "scan", str(definitions), "--quiet",
                        "--gradle-home", str(self.root / "empty-gradle"),
                        "--maven-repo", str(repo), "--fetch-from", "m1",
                    ])
                self.assertEqual(0 if status == HTTPStatus.NOT_FOUND else 2, result)
                if status != HTTPStatus.NOT_FOUND:
                    self.assertIn("org.example:lib:1.0", errors.getvalue())

    def test_other_http_errors_still_raise(self):
        dest = self.dest_for("forbidden")
        with self.assertRaises(android_notices.InputError):
            self.fetch({self.url("m1", f"{ARTIFACT}-{VERSION}.module"): 403}, dest)

    def test_metadata_without_a_runtime_file_tries_the_next_mirror(self):
        dest = self.dest_for("metadata")
        self.fetch(
            {
                self.url("m1", f"{ARTIFACT}-{VERSION}.module"): b"module",
                self.url("m1", f"{ARTIFACT}-{VERSION}.pom"): b"pom",
                self.url("m2", f"{ARTIFACT}-{VERSION}.aar"): b"aar",
            },
            dest,
        )
        self.assertTrue(dest.joinpath(REL, f"{ARTIFACT}-{VERSION}.aar").is_file())

    def test_a_cached_runtime_file_ends_the_fetch(self):
        dest = self.dest_for("cached")
        aar = dest.joinpath(REL, f"{ARTIFACT}-{VERSION}.aar")
        aar.parent.mkdir(parents=True)
        aar.write_bytes(b"cached")
        self.fetch({self.url("m2", f"{ARTIFACT}-{VERSION}.jar"): b"jar"}, dest)
        self.assertFalse(any(url.startswith("m2/") for url in self.requests))


if __name__ == "__main__":
    unittest.main()
