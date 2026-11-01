#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Boundary fixtures for M6a evidence: an empty green lane is never acceptance."""
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("downloads_gate", Path(__file__).parents[1] / "assert-playback-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DownloadEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def report(self, device, profile="nightly", bad=None, duration="1201"):
        folder = self.root / f"{device}DebugAndroidTest"
        folder.mkdir(exist_ok=True)
        suite = ET.Element("testsuite")
        for classname, name in gate.expected_cases(profile, device):
            case = ET.SubElement(suite, "testcase", classname=classname, name=name, time="1801" if name == gate.LONG else duration)
            if bad:
                ET.SubElement(case, bad)
        path = folder / "TEST-downloads.xml"
        ET.ElementTree(suite).write(path)
        return path

    def test_profiles_require_journey_and_only_applicable_runners(self):
        for profile in ("ci", "release", "api37"):
            self.assertEqual({(gate.DOWNLOAD_CLASS, gate.JOURNEY)}, gate.download_cases(profile, "connected"))
        for device, runner in (("api33", gate.WORKER), ("api34", gate.UIDT), ("api36", gate.UIDT)):
            self.assertIn((gate.DOWNLOAD_CLASS, runner), gate.expected_cases("nightly", device))
            self.assertEqual(gate.download_cases("nightly", device), gate.expected_cases("downloads", device))
            self.report(device)
            gate.verify(self.root, "nightly", [device])

    def test_absent_wrong_device_skipped_failed_error_and_duplicate_fail(self):
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        self.report("api36")
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        for tag in ("skipped", "failure", "error"):
            self.report("api26", "ci", bad=tag)
            with self.assertRaises(ValueError):
                gate.verify(self.root, "ci", ["api26"])
        path = self.report("api26", "ci")
        path.with_name("duplicate.xml").write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])

    def test_soak_duration_is_finite_and_at_least_twenty_minutes(self):
        for duration in ("0", "1199", "nan", "inf", "-inf"):
            self.report("api36", duration=duration)
            with self.assertRaises(ValueError):
                gate.verify(self.root, "nightly", ["api36"])
        self.report("api36", duration="1200")
        gate.verify(self.root, "nightly", ["api36"])

    def backup(self, names):
        archive = self.root / "transport.tar"
        with tarfile.open(archive, "w") as output:
            for name in names:
                info = tarfile.TarInfo(f"apps/ch.lkmc.neutrodyne/{name}")
                info.size = 4
                output.addfile(info, io.BytesIO(b"data"))
        return archive

    def test_transport_requires_both_includes_and_rejects_media_or_database(self):
        allowed = ["_manifest", "f/backup/auto-snapshot.zip", "f/datastore/settings.preferences_pb"]
        gate.verify_backup(self.backup(allowed))
        for names in ([], allowed[:-1], allowed + [allowed[-1]], allowed + ["ef/Podcasts/episode.mp3"], allowed + ["f/downloads/episode.mp3"], allowed + ["db/neutrodyne.db"]):
            with self.assertRaises(ValueError):
                gate.verify_backup(self.backup(names))


if __name__ == "__main__":
    unittest.main()
