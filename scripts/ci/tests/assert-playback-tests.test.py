#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fixture checks: a green empty/skipped suite never proves playback."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", Path(__file__).parents[1] / "assert-playback-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PlaybackEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def report(self, device, names, skipped=None, flavor="Debug", directory=None):
        folder = self.root / (directory or f"{device}{flavor}AndroidTest")
        folder.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite")
        for name in names:
            case = ET.SubElement(suite, "testcase", classname=gate.CLASS, name=name, time="1801")
            if name == skipped:
                ET.SubElement(case, "skipped")
        ET.ElementTree(suite).write(folder / "TEST-playback.xml")

    def test_each_device_needs_its_own_cases(self):
        # The app-APK set never contains the routed background case; the
        # external bgstart lane asserts
        # externalBackgroundStartDenialIsUnreachable.
        self.report("api26", gate.SHORT)
        self.report("api36", gate.SHORT)
        gate.verify(self.root, "ci", ["api26", "api36"])
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api34"])

    def test_empty_and_skipped_are_rejected(self):
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        self.report("api26", gate.SHORT, skipped=next(iter(gate.SHORT)))
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])

    def test_nightly_requires_full_duration(self):
        self.report("api34", gate.SHORT | {gate.LONG})
        gate.verify(self.root, "nightly", ["api34"])
        path = self.root / "api34DebugAndroidTest" / "TEST-playback.xml"
        original = path.read_text()
        for duration in ("1", "nan", "inf"):
            path.write_text(original.replace('time="1801"', f'time="{duration}"'))
            with self.assertRaises(ValueError):
                gate.verify(self.root, "nightly", ["api34"])

    def test_api37_requires_hardening_and_release_requires_smoke(self):
        self.report("connected", gate.SHORT)
        with self.assertRaises(ValueError):
            gate.verify(self.root, "api37", ["connected"])
        self.report("connected", {"notificationTransportAndLocalArtwork"}, flavor="Release")
        gate.verify(self.root, "release", ["connected"])

    def test_release_lane_rejects_debug_flavor_evidence(self):
        self.report("api36", {"notificationTransportAndLocalArtwork"})
        with self.assertRaises(ValueError):
            gate.verify(self.root, "release", ["api36"])
        self.report("api36", {"notificationTransportAndLocalArtwork"}, flavor="Release")
        gate.verify(self.root, "release", ["api36"])

    def test_connected_lane_only_accepts_connected_evidence(self):
        self.report("api26", gate.SHORT)
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["connected"])
        # Gradle writes connected results under a literal "connected" directory.
        self.report("connected", gate.SHORT, directory="connected/debug")
        gate.verify(self.root, "ci", ["connected"])
        gate.verify(self.root, "ci", ["api26"])

    def test_stray_xml_is_skipped_and_absence_still_fails_closed(self):
        folder = self.root / "api26DebugAndroidTest"
        folder.mkdir()
        (folder / "coverage-report.xml").write_text("<coverage><unclosed")
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        self.report("api26", gate.SHORT)
        gate.verify(self.root, "ci", ["api26"])
        (folder / "manifest-fragment.xml").write_text("<manifest/>")
        gate.verify(self.root, "ci", ["api26"])

    def test_failures_and_duplicate_evidence_are_rejected(self):
        self.report("api26", gate.SHORT)
        path = self.root / "api26DebugAndroidTest" / "TEST-playback.xml"
        tree = ET.parse(path)
        ET.SubElement(tree.getroot().find("testcase"), "failure")
        tree.write(path)
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        self.report("api26", gate.SHORT)
        path.with_name("duplicate.xml").write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])


if __name__ == "__main__":
    unittest.main()
