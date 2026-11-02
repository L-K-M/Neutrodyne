#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A reboot phase must complete exactly one test, without a skipped result."""
from functools import partial
import http.client
from http.server import ThreadingHTTPServer
import importlib.util
from pathlib import Path
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[3]

spec = importlib.util.spec_from_file_location("reboot", Path(__file__).parents[1] / "playback-reboot.py")
reboot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reboot)


class RebootEvidenceTest(unittest.TestCase):
    def test_requires_one_non_skipped_pass(self):
        reboot.require_one_pass("INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)")
        for output in ("", "OK (0 tests)", "OK (2 tests)", "FAILURES!!! Tests run: 1", "OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3"):
            with self.assertRaises(ValueError):
                reboot.require_one_pass(output)


class SystemUiEvidenceTest(unittest.TestCase):
    def test_keeps_only_media_lines(self):
        dump = "QS tiles\nMediaDataManager: entries=1\nnothing\nresumption enabled\n"
        self.assertEqual("MediaDataManager: entries=1\nresumption enabled\n", reboot.media_lines(dump))


class AutoplayEvidenceTest(unittest.TestCase):
    SESSION = "      package=ch.lkmc.neutrodyne\n      active=true\n      state=PlaybackState {{state={state}, position=0}}\n"
    OTHER = "      package=com.other\n      state=PlaybackState {state=PLAYING(3), position=0}\n"

    def test_a_paused_or_absent_session_passes(self):
        for dump in ("", self.OTHER, self.SESSION.format(state="PAUSED(2)") + self.OTHER, self.SESSION.format(state="2")):
            reboot.require_no_autoplay(dump, "ch.lkmc.neutrodyne")

    def test_playing_or_buffering_is_refused(self):
        for state in ("PLAYING(3)", "3", "BUFFERING(6)", "6"):
            with self.assertRaises(ValueError):
                reboot.require_no_autoplay(self.SESSION.format(state=state), "ch.lkmc.neutrodyne")


class RangeHandlerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.payload = bytes(range(256))
        (Path(self.directory.name) / "episode.mp3").write_bytes(self.payload)
        handler = partial(reboot.RangeHandler, directory=self.directory.name)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def get(self, byte_range):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        self.addCleanup(connection.close)
        connection.request("GET", "/episode.mp3", headers={"Range": byte_range})
        return connection.getresponse()

    def test_a_range_returns_exactly_the_requested_bytes(self):
        response = self.get("bytes=10-19")
        self.assertEqual(206, response.status)
        self.assertEqual("bytes 10-19/256", response.getheader("Content-Range"))
        self.assertEqual(self.payload[10:20], response.read())

    def test_an_open_range_reaches_the_end_of_the_file(self):
        response = self.get("bytes=250-")
        self.assertEqual(206, response.status)
        self.assertEqual(self.payload[250:], response.read())

    def test_an_unsatisfiable_range_is_refused(self):
        for byte_range in ("bytes=256-", "bytes=20-10"):
            self.assertEqual(416, self.get(byte_range).status)


class ReleaseLegWiringTest(unittest.TestCase):
    """The leg only runs nightly, so its preconditions are pinned here."""

    def test_the_release_smoke_leaves_its_apks_for_the_reboot_driver(self):
        nightly = (ROOT / ".github/workflows/nightly.yml").read_text(encoding="utf-8")
        before_driver, driver_found, _ = nightly.partition("python3 scripts/ci/playback-reboot.py")
        self.assertTrue(driver_found)
        last_gradle_call = before_driver[before_driver.rindex("./gradlew"):]
        self.assertIn(":app:connectedReleaseAndroidTest", last_gradle_call)
        self.assertIn("-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true", last_gradle_call)

    def test_abi_tracing_roots_cover_the_reboot_phases(self):
        roots = (ROOT / "scripts/ci/instrumentation-abi-roots.pro").read_text(encoding="utf-8")
        self.assertIn(f"-keep class {reboot.CLASS} ", roots)


if __name__ == "__main__":
    unittest.main()
