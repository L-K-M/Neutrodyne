#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The emulator deletes unconsented reports at its next launch."""

import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
PRESERVER = "scripts/ci/preserve-emulator-crashes.py"
PAYLOAD = b"MDMP fixture: preserve bytes, not a valid native crash"


def _nightly_script():
    source = (ROOT / ".github/workflows/nightly.yml").read_text()
    section = source.split("      - name: GMD nightly group (debug)\n", 1)[1]
    body = section.split("        run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith("          "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines)).replace("${{ inputs.scope }}", "full")


class PreserveEmulatorCrashesTest(unittest.TestCase):
    def _run_case(self, exit_code):
        with tempfile.TemporaryDirectory(prefix="android-") as directory:
            root = Path(directory)
            work = root / "work"
            work.mkdir()
            runner = root / "runner"
            runner.mkdir()
            report = root / "emu-crash-37.2.13.db/pending/report.dmp"
            saved = work / "build/outputs/androidTest-results/emulator-crashes/report.dmp"
            helper = work / PRESERVER
            if (ROOT / PRESERVER).is_file():
                helper.parent.mkdir(parents=True)
                shutil.copyfile(ROOT / PRESERVER, helper)
            gradle = work / "gradlew"
            gradle.write_text(
                f"#!{sys.executable}\n"
                "import os, sys, time\n"
                "from pathlib import Path\n"
                "report = Path(os.environ['FAKE_REPORT'])\n"
                "report.parent.mkdir(parents=True)\n"
                f"report.write_bytes({PAYLOAD!r})\n"
                "saved = Path(os.environ['FAKE_SAVED'])\n"
                "deadline = time.monotonic() + 4\n"
                "while not saved.exists() and time.monotonic() < deadline:\n"
                "    time.sleep(0.01)\n"
                "report.unlink()\n"
                "sys.exit(int(os.environ['FAKE_EXIT_CODE']))\n"
            )
            gradle.chmod(0o755)
            env = {
                **os.environ,
                "USER": root.name.removeprefix("android-"),
                "RUNNER_TEMP": str(runner),
                "FAKE_REPORT": str(report),
                "FAKE_SAVED": str(saved),
                "FAKE_EXIT_CODE": str(exit_code),
            }
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", _nightly_script()],
                cwd=work,
                env=env,
                capture_output=True,
                text=True,
                timeout=10,
            )
            return result, report.exists(), saved.read_bytes() if saved.is_file() else None

    def test_report_survives_the_next_emulator_cleanup(self):
        result, original_exists, saved = self._run_case(0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(original_exists)
        self.assertEqual(saved, PAYLOAD, "The next emulator erased the only native crash report")

    def test_gradle_failure_survives_collector_shutdown(self):
        failure_code = 37
        result, _, saved = self._run_case(failure_code)
        self.assertEqual(result.returncode, failure_code, result.stderr)
        self.assertEqual(saved, PAYLOAD)

    def test_updated_reports_are_recopied_without_following_symlinks(self):
        preserve = runpy.run_path(str(ROOT / PRESERVER))["_preserve_reports"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            report = source / "emu-crash-test.db/pending/report.dmp"
            report.parent.mkdir(parents=True)
            destination = root / "saved"
            destination.mkdir()
            private = root / "private.dmp"
            private.write_bytes(b"not a crash report")
            (report.parent / "link.dmp").symlink_to(private)
            report.write_bytes(PAYLOAD)
            preserve(source, destination)
            preserve(source, destination)
            self.assertEqual(sorted(p.name for p in destination.iterdir()), ["report.dmp"])
            self.assertEqual((destination / "report.dmp").read_bytes(), PAYLOAD)

            report.write_bytes(PAYLOAD + b" completed")
            preserve(source, destination)
            report.unlink()
            preserve(source, destination)
            self.assertEqual((destination / "report.dmp").read_bytes(), PAYLOAD + b" completed")


if __name__ == "__main__":
    unittest.main()
