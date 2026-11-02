#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Boundary cases for named system-test evidence; no device needed."""
import importlib.util
import os
import shutil
import subprocess
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("gate", Path(__file__).parents[1] / "assert-system-tests.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class EvidenceTest(unittest.TestCase):
    def check(self, profile="process-death", device="bench34", mutate=lambda suite, case: None,
              variant="benchmarkrelease", expected_error=None, folder=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / (folder or (f"connected/benchmarkRelease" if device == "connected"
                                     else f"managedDevice/{variant}/{device}"))
            path.mkdir(parents=True)
            classname, name = GATE.CASES[profile]
            suite = ET.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
            case = ET.SubElement(suite, "testcase", classname=classname, name=name, time="12")
            mutate(suite, case)
            ET.ElementTree(suite).write(path / "TEST-system.xml")
            if expected_error:
                with self.assertRaises(expected_error):
                    GATE.verify(root, profile, device)
            else:
                GATE.verify(root, profile, device)

    def test_passing_profiles(self):
        for profile, device in (("process-death", "bench34"), ("smoke", "api36"), ("smoke", "connected")):
            with self.subTest(profile=profile, device=device):
                self.check(profile, device)

    def test_missing_or_empty_root(self):
        with tempfile.TemporaryDirectory() as directory:
            for root in (Path(directory), Path(directory) / "missing"):
                with self.assertRaises(ValueError):
                    GATE.verify(root, "smoke", "api36")

    def test_nonpassing_cases_and_aggregate_counts(self):
        for tag in ("skipped", "failure", "error"):
            with self.subTest(tag=tag):
                self.check(mutate=lambda s, c: ET.SubElement(c, tag), expected_error=ValueError)
        for attr in ("failures", "errors", "skipped", "disabled"):
            with self.subTest(attr=attr):
                self.check(mutate=lambda s, c: s.set(attr, "1"), expected_error=ValueError)

    def test_empty_duplicate_and_wrong_identity(self):
        mutations = [lambda s, c: s.remove(c),
                     lambda s, c: ET.SubElement(s, "testcase", **c.attrib),
                     lambda s, c: c.set("name", c.get("name") + "[0]"),
                     lambda s, c: c.set("classname", "other.Test")]
        for mutation in mutations:
            self.check(mutate=mutation, expected_error=ValueError)

    def test_wrong_device_or_variant(self):
        for folder in ("managedDevice/benchmarkrelease/api36", "managedDevice/release/bench34",
                       "managedDevice/benchmarkrelease/bench34Other"):
            self.check(folder=folder, expected_error=ValueError)

    def test_invalid_durations(self):
        for duration in ("0", "-1", "nan", "inf", "unknown", "9"):
            with self.subTest(duration=duration):
                self.check("smoke", "api36", lambda s, c: c.set("time", duration), expected_error=ValueError)

    def test_sibling_skip(self):
        def skip(suite, case):
            ET.SubElement(ET.SubElement(suite, "testcase", classname="other", name="other"), "skipped")
        self.check(mutate=skip, expected_error=ValueError)


SCRIPT = Path(__file__).resolve().parents[1] / "system-tests.sh"


class CleanupTest(unittest.TestCase):
    def run_case(self, build=0, adb=0, installed=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts/ci").mkdir(parents=True)
            shutil.copy(SCRIPT, root / "scripts/ci/system-tests.sh")
            (root / "scripts/ci/assert-system-tests.py").write_text("# gate tested separately\n")
            (root / "bin").mkdir()
            stubs = {
                "gradlew": f"exit {build}",
                "bin/git": "printf '%040d\\n' 0",
                "bin/adb": ("if [ \"$1\" = shell ]; then\n" +
                            ("echo package:ch.lkmc.neutrodyne\necho package:ch.lkmc.neutrodyne.benchmark\n" if installed else ":\n") +
                            f"else exit {adb}; fi"),
            }
            for name, body in stubs.items():
                path = root / name
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o755)
            result = subprocess.run(["bash", "scripts/ci/system-tests.sh", "smoke", "connected"],
                                    cwd=root, env=dict(os.environ, PATH=f"{root / 'bin'}:{os.environ['PATH']}"),
                                    capture_output=True, text=True)
            return result.returncode

    def test_successful_cleanup(self):
        self.assertEqual(0, self.run_case())

    def test_uninstall_failure_fails_successful_test_run(self):
        self.assertEqual(1, self.run_case(adb=3))

    def test_build_failure_survives_cleanup_failure(self):
        self.assertEqual(7, self.run_case(build=7, adb=3))

    def test_already_uninstalled_is_clean(self):
        self.assertEqual(0, self.run_case(adb=3, installed=False))


if __name__ == "__main__":
    unittest.main()
