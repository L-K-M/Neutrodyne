#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fixture checks: the server-suite gate must hold from any working directory."""
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "assert-server-tests.py"
REPO = Path(__file__).resolve().parents[3]
VECTORS = REPO / "sync" / "protocol" / "vectors"


def write_suite(directory, classname, cases, system_out=""):
    suite = ET.Element("testsuite", name=f"ch.lkmc.neutrodyne.sync.server.{classname}")
    for name, mark in cases:
        case = ET.SubElement(suite, "testcase", name=name)
        if mark:
            ET.SubElement(case, mark)
    if system_out:
        ET.SubElement(suite, "system-out").text = system_out
    ET.ElementTree(suite).write(directory / f"TEST-{classname}.xml")


def conformance_cases(count, mark=None):
    return [(f"vector-{index}", mark) for index in range(count)]


def convergence_cases(count=2, system_out="seeds=1000 start=0 "):
    return ([(f"seed-{index}", None) for index in range(count)], system_out)


class AssertServerTestsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.count = len(list(VECTORS.glob("*.json")))
        self.assertGreater(self.count, 0)
        cases, out = convergence_cases()
        write_suite(self.root, "ServerConvergenceTest", cases, out)
        write_suite(self.root, "ServerConformanceTest", conformance_cases(self.count))

    def run_gate(self, *extra, cwd=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.root), "--seeds", "1000", *extra],
            capture_output=True, cwd=cwd,
        )

    def test_passes_from_repo_root_and_foreign_directory(self):
        self.assertEqual(0, self.run_gate().returncode)
        self.assertEqual(0, self.run_gate(cwd=self.root).returncode)

    def test_vector_floor_applies_from_a_foreign_directory(self):
        # Fewer cases than the vector count must fail even when the script is
        # invoked outside the repository: the floor comes from the script's
        # location, never from the caller's working directory.
        (self.root / "TEST-ServerConformanceTest.xml").unlink()
        write_suite(self.root, "ServerConformanceTest", conformance_cases(self.count - 1))
        self.assertNotEqual(0, self.run_gate(cwd=self.root).returncode)
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_missing_skipped_failed_and_empty_suites_are_rejected(self):
        (self.root / "TEST-ServerConformanceTest.xml").unlink()
        self.assertNotEqual(0, self.run_gate().returncode)
        write_suite(self.root, "ServerConformanceTest", [])
        self.assertNotEqual(0, self.run_gate().returncode)
        write_suite(self.root, "ServerConformanceTest", conformance_cases(self.count, "skipped"))
        self.assertNotEqual(0, self.run_gate().returncode)
        write_suite(self.root, "ServerConformanceTest", conformance_cases(self.count, "failure"))
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_convergence_requires_its_seed_range(self):
        (self.root / "TEST-ServerConvergenceTest.xml").unlink()
        self.assertNotEqual(0, self.run_gate().returncode)
        cases, out = convergence_cases(system_out="seeds=999 start=0 ")
        write_suite(self.root, "ServerConvergenceTest", cases, out)
        self.assertNotEqual(0, self.run_gate().returncode)
        cases, out = convergence_cases()
        write_suite(self.root, "ServerConvergenceTest", cases, out)
        self.assertEqual(0, self.run_gate("--start", "0").returncode)

    def test_missing_vector_directory_fails_closed(self):
        # A script copy outside the repository resolves no vectors and must
        # fail rather than silently dropping the conformance floor to zero.
        fake = self.root / "scripts" / "ci"
        fake.mkdir(parents=True)
        copied = fake / "assert-server-tests.py"
        copied.write_bytes(SCRIPT.read_bytes())
        result = subprocess.run(
            [sys.executable, str(copied), str(self.root / "xml"), "--seeds", "1000"],
            capture_output=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"no protocol vectors", result.stderr)

    def test_convergence_only_does_not_need_conformance_or_vectors(self):
        (self.root / "TEST-ServerConformanceTest.xml").unlink()
        self.assertEqual(0, self.run_gate("--convergence-only", cwd=self.root).returncode)


if __name__ == "__main__":
    unittest.main()
