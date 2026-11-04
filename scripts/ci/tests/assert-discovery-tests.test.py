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


class ExternalEvidenceTest(unittest.TestCase):
    setUp = DiscoveryEvidenceTest.setUp
    report = DiscoveryEvidenceTest.report

    def test_external_each_device_and_variant_is_required(self):
        for device in ("api26", "api36"):
            self.report(f"managedDevice/debug/{device}", gate.EXTERNAL_DEVICE)
        gate.verify_external(self.root, ["api26", "api36"])
        for device, variant in (("api34", "debug"), ("api36", "release"), ("connected", "debug")):
            with self.assertRaises(ValueError):
                gate.verify_external(self.root, [device], variant)

    def test_external_rejects_empty_missing_skipped_failed_duplicate_and_parameter_suffix(self):
        for folder in ("managedDevice/release/api36", "connected/release"):
            device = "connected" if folder.startswith("connected") else "api36"
            with self.assertRaises(ValueError):
                gate.verify_external(self.root, [device], "release")
            for tag in ("skipped", "failure", "error"):
                self.report(folder, gate.EXTERNAL_DEVICE, tag)
                with self.assertRaises(ValueError):
                    gate.verify_external(self.root, [device], "release")
            path = self.report(folder, gate.EXTERNAL_DEVICE, omit=True)
            with self.assertRaises(ValueError):
                gate.verify_external(self.root, [device], "release")
            ET.ElementTree(ET.Element("testsuite", tests="0")).write(path)
            with self.assertRaises(ValueError):
                gate.verify_external(self.root, [device], "release")
            path = self.report(folder, gate.EXTERNAL_DEVICE)
            suite = ET.parse(path)
            case = next(suite.getroot().iter("testcase"))
            case.set("name", case.get("name") + "[0]")
            suite.write(path)
            with self.assertRaises(ValueError):
                gate.verify_external(self.root, [device], "release")
            path = self.report(folder, gate.EXTERNAL_DEVICE)
            gate.verify_external(self.root, [device], "release")
            duplicate = path.with_name("duplicate.xml")
            duplicate.write_bytes(path.read_bytes())
            with self.assertRaises(ValueError):
                gate.verify_external(self.root, [device], "release")
            duplicate.unlink()
            path.unlink()

    def test_external_host_needs_both_named_results(self):
        with self.assertRaises(ValueError):
            gate.verify_external(self.root, [])
        for folder, cases in gate.EXTERNAL_HOST.items():
            path = self.report(folder, cases)
        gate.verify_external(self.root, [])
        path.unlink()
        with self.assertRaises(ValueError):
            gate.verify_external(self.root, [])


if __name__ == "__main__":
    unittest.main()
