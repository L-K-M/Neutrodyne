#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A class filter is evidence only when each named class executed, unskipped."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", Path(__file__).parents[1] / "assert-selected-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

CLASSES = (
    "ch.lkmc.neutrodyne.M10AccessibilityTest",
    "ch.lkmc.neutrodyne.NavigationStateRestorationTest",
    "ch.lkmc.neutrodyne.PerAppLanguageTest",
    "ch.lkmc.neutrodyne.SubscribeJourneyTest",
)


class SelectedClassesTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def report(self, cases, device="api36", directory=None, filename="TEST-api36.xml"):
        folder = self.root / (directory or f"managedDevice/debug/{device}")
        folder.mkdir(parents=True, exist_ok=True)
        suites = ET.Element("testsuites")
        suite = ET.SubElement(suites, "testsuite")
        for classname, names, marks in cases:
            for name in names:
                case = ET.SubElement(suite, "testcase", classname=classname, name=name)
                for mark in marks:
                    ET.SubElement(case, mark)
        path = folder / filename
        ET.ElementTree(suites).write(path)
        return path

    def green(self, device="api36"):
        return self.report([(classname, ["aCase", "anotherCase"], ()) for classname in CLASSES], device)

    def test_every_selected_class_executes_unskipped(self):
        self.green()
        gate.verify(self.root, "api36", CLASSES)

    def test_task_name_also_marks_the_lane(self):
        self.report([(classname, ["aCase"], ()) for classname in CLASSES], directory="api36DebugAndroidTest")
        gate.verify(self.root, "api36", CLASSES)

    def test_empty_evidence_and_missing_classes_fail(self):
        with self.assertRaisesRegex(ValueError, "executed no case"):
            gate.verify(self.root, "api36", CLASSES)
        self.report([(classname, ["aCase"], ()) for classname in CLASSES[1:]])
        with self.assertRaisesRegex(ValueError, "M10AccessibilityTest"):
            gate.verify(self.root, "api36", CLASSES)

    def test_an_all_skipped_class_never_reads_as_executed(self):
        self.report(
            [(CLASSES[0], ["aCase"], ("skipped",))]
            + [(classname, ["aCase"], ()) for classname in CLASSES[1:]]
        )
        with self.assertRaisesRegex(ValueError, "skipped, failed or errored"):
            gate.verify(self.root, "api36", CLASSES)

    def test_skipped_failure_and_error_cases_fail(self):
        for mark in ("skipped", "failure", "error"):
            with self.subTest(mark=mark), tempfile.TemporaryDirectory() as other:
                self.root = Path(other)
                self.report(
                    [(CLASSES[0], ["aCase"], (mark,),)]
                    + [(classname, ["aCase"], ()) for classname in CLASSES[1:]]
                )
                with self.assertRaisesRegex(ValueError, "skipped, failed or errored"):
                    gate.verify(self.root, "api36", CLASSES)

    def test_a_dropped_initialization_error_fails_the_lane(self):
        self.green()
        folder = self.root / "managedDevice/debug/api36"
        (folder / "logcat-ch.lkmc.neutrodyne.M10AccessibilityTest-initializationError.txt").write_text("")
        with self.assertRaisesRegex(ValueError, "M10AccessibilityTest-initializationError"):
            gate.require_no_initialization_errors(self.root, "api36")
        gate.verify(self.root, "api36", CLASSES)

    def test_other_lanes_and_stray_xml_are_ignored(self):
        self.green()
        other = self.root / "managedDevice/debug/api34"
        other.mkdir(parents=True)
        (other / "TEST-api34.xml").write_text("<coverage><unclosed")
        gate.verify(self.root, "api36", CLASSES)
        with self.assertRaisesRegex(ValueError, "executed no case"):
            gate.verify(self.root, "api34", CLASSES)

    def test_minimum_case_count_rejects_a_truncated_class(self):
        self.report([(CLASSES[-1], ["one", "two", "three"], ())])
        with self.assertRaisesRegex(ValueError, "3 cases, fewer than the required 4"):
            gate.verify(self.root, "api36", [CLASSES[-1]], minimum_cases=4)

    def test_minimum_case_count_accepts_all_four_executed_cases(self):
        self.report([(CLASSES[-1], ["one", "two", "three", "four"], ())])
        gate.verify(self.root, "api36", [CLASSES[-1]], minimum_cases=4)

    def test_cli_requires_a_selection(self):
        script = Path(__file__).parents[1] / "assert-selected-tests.py"
        result = subprocess.run(
            [sys.executable, str(script), str(self.root), "--device", "api36"],
            capture_output=True, text=True, check=False,
        )
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
