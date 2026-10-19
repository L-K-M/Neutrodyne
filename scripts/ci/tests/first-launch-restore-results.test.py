#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise the XML selector and its nightly gate with isolated GMD fixtures."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


REPO = Path(__file__).resolve().parents[3]
SCRIPT = REPO / "scripts/ci/check-first-launch-restore-results.py"
CLASS = "ch.lkmc.neutrodyne.FirstLaunchRestoreDeviceTest"
CASE = "firstLaunchRestoresTheSeededSnapshot"
RESULTS = Path("app/build/outputs/androidTest-results/managedDevice/debug")


def report(device, outcome="", classname=CLASS, name=CASE):
    # Shape and device property from nightly 39136456538's archived GMD XML.
    return f'''<testsuites tests="1" failures="0" errors="0" skipped="0">
  <testsuite name="{classname}" tests="1" failures="0" errors="0" skipped="0">
    <properties><property name="device" value="_app_{device}DebugAndroidTest" /></properties>
    <testcase name="{name}" classname="{classname}">{outcome}</testcase>
  </testsuite>
</testsuites>'''


class RestoreResultsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.results = self.root / RESULTS

    def write_report(self, device, xml=None, root=None):
        path = (root or self.results) / device / f"TEST-{device}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(report(device) if xml is None else xml)
        return path

    def run_nightly(self, devices, outcome=""):
        fixture = self.root / "fixture"
        for device in devices:
            self.write_report(device, report(device, outcome), fixture)
        fixture.mkdir(exist_ok=True)
        # Simulate a successful GMD command even when tests skip or a leg is absent.
        gradlew = self.root / "gradlew"
        gradlew.write_text(
            "#!/usr/bin/env python3\n"
            "import os, shutil\n"
            f"shutil.copytree(os.environ['GMD_FIXTURE'], {str(RESULTS)!r}, dirs_exist_ok=True)\n"
        )
        gradlew.chmod(0o755)
        if SCRIPT.exists():
            target = self.root / SCRIPT.relative_to(REPO)
            target.parent.mkdir(parents=True)
            shutil.copyfile(SCRIPT, target)
        workflow = (REPO / ".github/workflows/nightly.yml").read_text()
        job = workflow.split("  first-launch-restore:\n", 1)[1].split("\n  repro:", 1)[0]
        gate = job.split("      - name: First-launch restore on api26 + api36\n", 1)[1]
        gate = gate.split("      - name: Report failure\n", 1)[0]
        runs = re.findall(r"        run: ([|>]-?)\n((?:          .*\n)+)", gate)
        self.assertTrue(runs, "the real GMD run step must be exercised")
        for style, body in runs:
            command = textwrap.dedent(body)
            if style.startswith(">"):
                command = " ".join(command.splitlines())
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", command], cwd=self.root,
                env={**os.environ, "GMD_FIXTURE": str(fixture)},
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode:
                return result
        return result

    def test_nightly_rejects_successful_gmd_with_skipped_evidence(self):
        result = self.run_nightly(("api26", "api36"), "<skipped />")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("api26: skipped or failed test evidence", result.stderr)

    def test_nightly_cannot_reuse_a_stale_missing_leg(self):
        self.write_report("api36")
        result = self.run_nightly(("api26",))
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("api36: expected only", result.stderr)

    def test_nightly_accepts_both_executed_legs(self):
        result = self.run_nightly(("api26", "api36"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("api26: PASS", result.stdout)
        self.assertIn("api36: PASS", result.stdout)

    def select(self):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.results)],
                              capture_output=True, text=True, timeout=30)

    def assert_rejected(self, diagnostic):
        result = self.select()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(diagnostic, result.stderr)

    def test_accepts_archived_gmd_shape(self):
        for device in ("api26", "api36"):
            self.write_report(device)
        result = self.select()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, result.stdout.count(": PASS"))

    def test_rejects_missing_results(self):
        self.assert_rejected("api26:")

    def test_rejects_either_missing_leg(self):
        for device in ("api26", "api36"):
            with self.subTest(missing=device):
                self.write_report("api36" if device == "api26" else "api26")
                self.assert_rejected(f"{device}:")
                shutil.rmtree(self.results)

    def test_rejects_bad_evidence_on_either_leg(self):
        for device in ("api26", "api36"):
            invalid = {
                "empty file": "",
                "malformed": "<testsuites>",
                "empty suite": '<testsuites tests="0" />',
                "wrong class": report(device, classname="OtherTest"),
                "wrong case": report(device, name="someOtherTest"),
                "skipped": report(device, "<skipped />"),
                "failure": report(device, "<failure />"),
                "error": report(device, "<error />"),
                "wrong device": report("api36" if device == "api26" else "api26"),
                "no device": report(device).replace('name="device"', 'name="other"'),
                "no cases": report(device).replace(
                    f'<testcase name="{CASE}" classname="{CLASS}"></testcase>', ""),
                "duplicate case": report(device).replace(
                    "</testsuite>", f'<testcase name="{CASE}" classname="{CLASS}" /></testsuite>'),
            }
            # Counters alone cannot establish success, but contradictions must fail too.
            for count in ("skipped", "failures", "errors"):
                invalid[f"suite {count}"] = report(device).replace(
                    f'{count}="0"', f'{count}="1"')
            invalid["wrong count"] = report(device).replace('tests="1"', 'tests="2"')
            invalid["invalid count"] = report(device).replace('tests="1"', 'tests="NaN"')
            for label, xml in invalid.items():
                with self.subTest(device=device, evidence=label):
                    for other in ("api26", "api36"):
                        self.write_report(other)
                    self.write_report(device, xml)
                    self.assert_rejected("first-launch-restore:")

    def test_aggregate_cannot_supply_missing_device(self):
        self.write_report("api26")
        aggregate = self.results / "TEST-all.xml"
        aggregate.write_text(report("api36"))
        self.assert_rejected("api36:")

    def test_duplicate_api26_report_cannot_supply_api36(self):
        path = self.write_report("api26")
        self.write_report("api36", path.read_text())
        self.assert_rejected("api36:")

    def test_duplicate_aggregate_is_rejected_in_device_directory(self):
        for device in ("api26", "api36"):
            path = self.write_report(device)
        path.with_name("TEST-aggregate.xml").write_text(path.read_text())
        self.assert_rejected("api36:")


if __name__ == "__main__":
    unittest.main()
