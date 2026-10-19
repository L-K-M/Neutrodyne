#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A green task with missing, duplicate, skipped or failed cases is not M7 evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", Path(__file__).parents[1] / "assert-discovery-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DiscoveryEvidenceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def report(self, folder, expected, tag=None, omit=False):
        folder = self.root / folder
        folder.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite")
        for classname, names in expected.items():
            for name in sorted(names):
                case = ET.SubElement(suite, "testcase", classname=classname, name=name)
        if omit:
            suite.remove(case)
        elif tag:
            ET.SubElement(case, tag)
        path = folder / "TEST-discovery.xml"
        ET.ElementTree(suite).write(path)
        return path

    def test_host_suites_each_need_their_own_evidence(self):
        for folder, cases in gate.HOST.items():
            self.report(folder, cases)
        gate.verify(self.root, [])
        next(self.root.rglob("*.xml")).unlink()
        with self.assertRaises(ValueError):
            gate.verify(self.root, [])

    def test_each_device_needs_its_own_evidence(self):
        for device in ("api26", "api36"):
            self.report(f"{device}DebugAndroidTest", gate.DEVICE)
        gate.verify(self.root, ["api26", "api36"])
        with self.assertRaises(ValueError):
            gate.verify(self.root, ["api34"])

    def test_release_and_connected_paths(self):
        self.report("api36ReleaseAndroidTest", gate.DEVICE)
        gate.verify(self.root, ["api36"])
        gate.verify(self.root, ["connected"])

    def test_missing_skipped_failure_error_and_duplicate_are_rejected(self):
        with self.assertRaises(ValueError):
            gate.verify(self.root, ["api26"])
        for tag in ("skipped", "failure", "error"):
            with self.subTest(tag=tag):
                self.report("api26DebugAndroidTest", gate.DEVICE, tag)
                with self.assertRaises(ValueError):
                    gate.verify(self.root, ["api26"])
        self.report("api26DebugAndroidTest", gate.DEVICE, omit=True)
        with self.assertRaises(ValueError):
            gate.verify(self.root, ["api26"])
        path = self.report("api26DebugAndroidTest", gate.DEVICE)
        path.with_name("duplicate.xml").write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):
            gate.verify(self.root, ["api26"])


if __name__ == "__main__":
    unittest.main()
