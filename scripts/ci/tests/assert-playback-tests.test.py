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

    def report(self, device, names, skipped=None):
        folder = self.root / f"{device}DebugAndroidTest"
        folder.mkdir(exist_ok=True)
        suite = ET.Element("testsuite")
        for name in names:
            case = ET.SubElement(suite, "testcase", classname=gate.CLASS, name=name, time="1801")
            if name == skipped:
                ET.SubElement(case, "skipped")
        ET.ElementTree(suite).write(folder / "TEST-playback.xml")

    def test_each_device_needs_its_own_cases(self):
        # The app-APK set never contains the routed background case; the
        # external bgstart lane asserts deniedBackgroundStartOffersWorkingResume.
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
        gate.verify(self.root, "release", ["connected"])

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
