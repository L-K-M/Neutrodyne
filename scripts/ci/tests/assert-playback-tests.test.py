#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fixture checks: a green empty/skipped suite never proves playback."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

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
        for classname, name in names:
            case = ET.SubElement(suite, "testcase", classname=classname, name=name, time="1801")
            if name == skipped:
                ET.SubElement(case, "skipped")
        ET.ElementTree(suite).write(folder / "TEST-playback.xml")

    def test_each_device_needs_its_own_cases(self):
        self.report("api26", gate.expected_cases("ci", "api26"))
        self.report("api36", gate.expected_cases("ci", "api36"))
        gate.verify(self.root, "ci", ["api26", "api36"])
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api34"])

    def test_empty_and_skipped_are_rejected(self):
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        self.report("api26", gate.expected_cases("ci", "api26"), skipped=next(iter(gate.SHORT)))
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])

    def test_nightly_requires_full_duration(self):
        self.report("api34", gate.expected_cases("nightly", "api34"))
        gate.verify(self.root, "nightly", ["api34"])
        path = self.root / "api34DebugAndroidTest" / "TEST-playback.xml"
        original = path.read_text()
        for duration in ("1", "nan", "inf"):
            path.write_text(original.replace('time="1801"', f'time="{duration}"'))
            with self.assertRaises(ValueError):
                gate.verify(self.root, "nightly", ["api34"])

    def test_api37_requires_hardening_and_release_requires_smoke(self):
        self.report("connected", gate.expected_cases("ci", "api36"))
        with self.assertRaises(ValueError):
            gate.verify(self.root, "api37", ["connected"])
        gate.verify(self.root, "release", ["connected"])

    def test_every_milestone_case_is_required_including_release(self):
        for profile in ("ci", "release", "nightly", "api37"):
            expected = gate.expected_cases(profile, "api36")
            for classname, names in gate.M5.items():
                for name in names:
                    self.report("api36", expected - {(classname, name)})
                    with self.assertRaises(ValueError):
                        gate.verify(self.root, profile, ["api36"])
            self.report("api36", expected)
            gate.verify(self.root, profile, ["api36"])

    def test_host_apk_must_contain_every_nonempty_fixture(self):
        apk = self.root / "app-androidTest.apk"
        for omitted in (None, "chapters_both.m4b"):
            with zipfile.ZipFile(apk, "w") as archive:
                for asset in gate.ASSETS:
                    if asset != omitted:
                        archive.writestr(f"assets/media/{asset}", b"fixture")
            if omitted is None:
                gate.verify_assets(apk)
            else:
                with self.assertRaises(ValueError):
                    gate.verify_assets(apk)
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("assets/media/chapters_both.m4b", b"")
        with self.assertRaises(ValueError):
            gate.verify_assets(apk)

    def test_failures_and_duplicate_evidence_are_rejected(self):
        self.report("api26", gate.expected_cases("ci", "api26"))
        path = self.root / "api26DebugAndroidTest" / "TEST-playback.xml"
        tree = ET.parse(path)
        ET.SubElement(tree.getroot().find("testcase"), "failure")
        tree.write(path)
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])
        self.report("api26", gate.expected_cases("ci", "api26"))
        path.with_name("duplicate.xml").write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api26"])


if __name__ == "__main__":
    unittest.main()
