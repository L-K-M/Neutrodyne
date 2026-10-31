#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Execute the nightly shell with fake Gradle; this is not emulator proof."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/nightly.yml"
DEVICE_PROPERTY = "android.experimental.testOptions.managedDevices.maxConcurrentDevices"
DEVICE_ARGUMENT = f"-P{DEVICE_PROPERTY}=1"


def _nightly_script():
    source = WORKFLOW.read_text()
    marker = "      - name: GMD nightly group (debug)\n"
    assert source.count(marker) == 1
    body = source.split(marker, 1)[1].split("        run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith("          "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines))


class GmdDeviceBudgetTest(unittest.TestCase):
    def _run_scope(self, scope):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record = root / "arguments.json"
            gradle = root / "gradlew"
            gradle.write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "Path(os.environ['GMD_ARGUMENTS']).write_text(json.dumps(sys.argv[1:]))\n"
            )
            gradle.chmod(0o755)
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", _nightly_script().replace("${{ inputs.scope }}", scope)],
                cwd=root,
                env={**os.environ, "GMD_ARGUMENTS": str(record)},
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(record.read_text())

    def _assert_device_budget(self, arguments):
        limits = [arg for arg in arguments if arg.startswith(f"-P{DEVICE_PROPERTY}=")]
        self.assertEqual(limits, [DEVICE_ARGUMENT], "GMD slots need their own explicit bound")
        workers = [arg for arg in arguments if arg.startswith("--max-workers=")]
        self.assertEqual(workers, ["--max-workers=1"], "Do not queue a second device lease behind the 30-minute case")

    def test_full_nightly_bounds_active_devices(self):
        arguments = self._run_scope("full")
        self._assert_device_budget(arguments)
        self.assertIn("nightlyGroupDebugAndroidTest", arguments)
        self.assertIn("nightlyGroupAndroidDeviceTest", arguments)
        self.assertIn("-Pneutrodyne.testScope=nightly", arguments)

    def test_youtube_smoke_bounds_active_devices(self):
        arguments = self._run_scope("youtube-smoke")
        self._assert_device_budget(arguments)
        self.assertIn("api36DebugAndroidTest", arguments)
        self.assertIn(
            "-Pandroid.testInstrumentationRunnerArguments.class="
            "ch.lkmc.neutrodyne.YouTubeReleaseSmokeTest,ch.lkmc.neutrodyne.SmokeTest",
            arguments,
        )

    def test_gradle_worker_limit_does_not_replace_device_limit(self):
        with self.assertRaises(AssertionError):
            self._assert_device_budget(["--max-workers=1"])


if __name__ == "__main__":
    unittest.main()
