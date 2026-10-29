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

RELEASE_SMOKE = {
    "ch.lkmc.neutrodyne.SmokeTest": (
        "fiveLabelledDestinations",
        "destinationsSurviveRotationAndDarkMode",
        "settingsOpensAndBackReturnsToTheTab",
        "aboutShowsVersionAndAbi",
        "licencesListsLibraries",
    ),
    "ch.lkmc.neutrodyne.YtxReleaseSmokeTest": ("selftestAnswersThroughYtxServiceInTheApp",),
    gate.CLASS: ("notificationTransportAndLocalArtwork",),
}


QUEUE_JOURNEY = ("ch.lkmc.neutrodyne.UpNextJourneyTest",
                 "queueEditsPersistUndoRestoresOrderAndNothingPlays")
ATF_CONTROL = ("ch.lkmc.neutrodyne.AccessibilityChecksTest",
               "enabledChecksFailAnUnlabelledTinyTargetBeforeTheClick")


class PlaybackEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def report(self, device, names, skipped=None, flavor="Debug", directory=None, omit_ui=()):
        folder = self.root / (directory or f"{device}{flavor}AndroidTest")
        folder.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite")
        for name in names:
            case = ET.SubElement(suite, "testcase", classname=gate.CLASS, name=name, time="1801")
            if name == skipped:
                ET.SubElement(case, "skipped")
        ui = [QUEUE_JOURNEY]
        if device in ("api34", "api36", "api37"):
            ui.append(ATF_CONTROL)
        for classname, name in ui:
            if name not in omit_ui:
                ET.SubElement(suite, "testcase", classname=classname, name=name, time="1")
        ET.ElementTree(suite).write(folder / "TEST-playback.xml")

    def release_suite(self, directory, omit=(), marks=None, root=None,
                      filename="TEST-release-smoke.xml"):
        marks = marks or {}
        folder = (root or self.root) / directory
        folder.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite")
        for classname, names in RELEASE_SMOKE.items():
            for name in names:
                if name in omit:
                    continue
                case = ET.SubElement(suite, "testcase", classname=classname, name=name, time="1")
                if name in marks:
                    ET.SubElement(case, marks[name])
        ET.ElementTree(suite).write(folder / filename)
        return folder

    def test_each_device_needs_its_own_cases(self):
        # The app-APK set never contains the routed background case; the
        # external bgstart lane asserts
        # externalBackgroundStartDenialIsUnreachable.
        self.report("api26", gate.SHORT)
        self.report("api36", gate.SHORT)
        gate.verify(self.root, "ci", ["api26", "api36"])
        with self.assertRaises(ValueError):
            gate.verify(self.root, "ci", ["api34"])

    def test_debug_requires_each_queue_accessibility_receipt(self):
        for classname, name in (QUEUE_JOURNEY, ATF_CONTROL):
            with self.subTest(case=name):
                self.report("api36", gate.SHORT, omit_ui=(name,))
                with self.assertRaisesRegex(ValueError, name):
                    gate.verify(self.root, "ci", ["api36"])

    def test_debug_rejects_failed_skipped_foreign_and_duplicate_ui_cases(self):
        for classname, name in (QUEUE_JOURNEY, ATF_CONTROL):
            for mark in ("skipped", "failure", "error", "foreign", "duplicate"):
                with self.subTest(case=name, mark=mark):
                    self.report("api36", gate.SHORT)
                    path = self.root / "api36DebugAndroidTest" / "TEST-playback.xml"
                    tree = ET.parse(path)
                    case = next(c for c in tree.getroot() if c.get("name") == name)
                    if mark == "foreign":
                        case.set("classname", "foreign." + classname)
                    elif mark == "duplicate":
                        ET.SubElement(tree.getroot(), "testcase", classname=classname, name=name)
                    else:
                        ET.SubElement(case, mark)
                    tree.write(path)
                    with self.assertRaisesRegex(ValueError, name):
                        gate.verify(self.root, "ci", ["api36"])

    def test_api26_requires_journey_without_unsupported_framework_control(self):
        self.report("api26", gate.SHORT)
        gate.verify(self.root, "ci", ["api26"])
        self.report("api26", gate.SHORT, omit_ui=(QUEUE_JOURNEY[1],))
        with self.assertRaisesRegex(ValueError, QUEUE_JOURNEY[1]):
            gate.verify(self.root, "ci", ["api26"])

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
        self.release_suite("connected/release")
        gate.verify(self.root, "release", ["connected"])

    def test_release_requires_the_full_seven_case_suite(self):
        self.release_suite("api36ReleaseAndroidTest", omit=("licencesListsLibraries",))
        with self.assertRaises(ValueError):
            gate.verify(self.root, "release", ["api36"])
        self.release_suite("api36ReleaseAndroidTest")
        gate.verify(self.root, "release", ["api36"])

    def test_release_rejects_skipped_failed_errored_and_duplicate_cases(self):
        directory = "api36ReleaseAndroidTest"
        for mark in ("skipped", "failure", "error"):
            self.release_suite(directory, marks={"fiveLabelledDestinations": mark})
            with self.assertRaises(ValueError):
                gate.verify(self.root, "release", ["api36"])
        self.release_suite(directory)
        self.release_suite(directory, filename="TEST-duplicate.xml")
        with self.assertRaises(ValueError):
            gate.verify(self.root, "release", ["api36"])

    def test_release_foreign_class_is_not_evidence(self):
        folder = self.release_suite("api36ReleaseAndroidTest", omit=("licencesListsLibraries",))
        suite = ET.Element("testsuite")
        ET.SubElement(suite, "testcase", classname="com.example.SmokeTest",
                      name="licencesListsLibraries", time="1")
        ET.ElementTree(suite).write(folder / "TEST-foreign.xml")
        with self.assertRaises(ValueError):
            gate.verify(self.root, "release", ["api36"])

    def test_release_accepts_managed_device_and_connected_release_paths(self):
        self.release_suite("managedDevice/release/api36")
        self.release_suite("connected/release")
        gate.verify(self.root, "release", ["api36", "connected"])

    def test_release_rejects_debug_lane_report_paths(self):
        for directory in ("managedDevice/debug/api36", "connected/debug",
                          "release/managedDevice/debug/api36", "release/connected/debug"):
            device = "connected" if "connected" in directory else "api36"
            with self.subTest(directory=directory), tempfile.TemporaryDirectory() as other:
                self.release_suite(directory, root=Path(other))
                with self.assertRaises(ValueError):
                    gate.verify(Path(other), "release", [device])

    def test_nearest_device_lane_overrides_release_ancestors(self):
        for directory in (
            "api36ReleaseAndroidTest/managedDevice/debug/api36",
            "release/api36/managedDevice/debug/api36",
            "api36ReleaseAndroidTest/nested/api36DebugAndroidTest",
        ):
            with self.subTest(directory=directory), tempfile.TemporaryDirectory() as other:
                self.release_suite(directory, root=Path(other))
                with self.assertRaises(ValueError):
                    gate.verify(Path(other), "release", ["api36"])

    def test_release_lane_rejects_debug_flavor_evidence(self):
        self.release_suite("api36DebugAndroidTest")
        with self.assertRaises(ValueError):
            gate.verify(self.root, "release", ["api36"])
        self.release_suite("api36ReleaseAndroidTest")
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
