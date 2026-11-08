#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Pin the windows-identity and desktop-windows scopes of nightly.yml.

The dispatch-only `windows-identity` job exists so the windows-x64 ndmedia
boundary (identity_test.c) can be iterated without paying for the
desktop-packages matrix's FFmpeg build and four package legs. `desktop-windows`
reuses that same matrix but filters it down to the single Windows leg. This
fixture asserts the scope declarations, the job gating and the always-on
artifact coverage — it is not workflow evidence itself.
"""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/nightly.yml"


def _job_block(source, name):
    """The yaml block of one top-level job, from `  <name>:` to the next job."""
    marker = f"\n  {name}:\n"
    assert marker in source, f"job {name} missing from nightly.yml"
    body = source.split(marker, 1)[1]
    end = re.search(r"\n  [a-zA-Z][a-zA-Z0-9_-]*:", body)
    return body[: end.start()] if end else body


class NightlyWindowsIdentityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text(encoding="utf-8")
        cls.identity = _job_block(cls.source, "windows-identity")
        cls.packages = _job_block(cls.source, "desktop-packages")

    def test_scopes_are_declared_in_the_input_description(self):
        description = self.source.split("        description:", 1)[1].split("\n", 1)[0]
        self.assertIn("desktop-windows", description)
        self.assertIn("windows-identity", description)

    def test_identity_scope_gates_one_dispatch_only_windows_job(self):
        self.assertIn("if: inputs.scope == 'windows-identity'", self.identity)
        self.assertIn("runs-on: windows-2025", self.identity)
        self.assertNotIn("github.event.schedule", self.identity)

    def test_identity_job_runs_the_existing_native_build_task(self):
        self.assertIn(":playback:native:buildNdmedia", self.identity)
        self.assertIn("--rerun-tasks", self.identity)
        for excluded in ("packageMsi", "createDistributable", "build-desktop-release", "packageDmg"):
            self.assertNotIn(excluded, self.identity, "the boundary scope must not pay for packaging")

    def test_identity_job_keeps_the_windows_native_environment(self):
        for step in ("MSYS2", "Android SDK", "setup-java"):
            self.assertIn(step, self.identity, f"{step} provisioning is required for buildNdmedia")

    def test_identity_job_uploads_native_diagnostics_even_on_failure(self):
        upload = self.identity.split("upload-artifact", 1)[1]
        self.assertIn("if: always()", upload)
        self.assertIn("playback/native/build/ndmedia", upload)

    def package_rows(self, scope):
        # Include combinations are added after exclusions in GitHub Actions.
        # Static include-only rows therefore survive the earlier exclude pass.
        matrix = self.packages.split("      matrix:", 1)[1].split("\n    runs-on:", 1)[0]
        expression = re.search(r"include: (\$\{\{[^\n]+\}\})", matrix)
        if expression:
            self.assertIn("inputs.scope == 'desktop-windows'", expression.group(1))
            choices = re.findall(r"fromJSON\('([^']+)'\)", expression.group(1))
            self.assertEqual(2, len(choices))
            return json.loads(choices[0 if scope == "desktop-windows" else 1])
        return [{"target": name} for name in re.findall(r"^\s*- target: (\S+)$", matrix, re.MULTILINE)]

    def test_desktop_windows_scope_filters_the_packages_matrix(self):
        condition = next(line for line in self.packages.splitlines() if "inputs.scope ==" in line)
        self.assertIn("inputs.scope == 'desktop-packages'", condition)
        self.assertIn("inputs.scope == 'desktop-windows'", condition)
        rows = self.package_rows("desktop-windows")
        self.assertEqual(["windows-x64"], [row["target"] for row in rows])
        self.assertEqual("windows-2025", rows[0]["runs-on"])
        self.assertEqual(210, rows[0]["timeout-minutes"])

    def test_packages_matrix_still_lists_all_four_targets(self):
        rows = self.package_rows("desktop-packages")
        self.assertEqual(
            {"windows-x64", "macos-arm64", "linux-x64", "linux-arm64"},
            {row["target"] for row in rows},
        )

    def test_packages_upload_keeps_the_compose_jpackage_diagnostics(self):
        upload = self.packages.split("upload-artifact", 1)[1]
        self.assertIn("if: always()", upload)
        self.assertIn("desktopApp/build/compose/logs/**", upload)
        self.assertIn("desktopApp/build/compose/tmp/*.args.txt", upload)


if __name__ == "__main__":
    unittest.main()
