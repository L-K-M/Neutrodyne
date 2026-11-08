#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A dead desktop test worker must still hand its crash file to unit-reports."""

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
WORKFLOW = ".github/workflows/ci.yml"
COLLECTOR = "scripts/ci/preserve-desktop-test-crashes.py"
UNIT_STEP = "Unit tests"
COLLECTOR_STEP = "Preserve desktop test crash reports"
FAKE_ROOT = "neutrodyne-test4242424242"
FAKE_REPORT = "crash-20261108-010203.txt"
PAYLOAD = b"Neutrodyne crash report fixture: bytes only, not a real trace"


def _step_block(source: str, name: str) -> str | None:
    """The named step's child lines (indent > 6), or None when the step is absent."""
    marker = f"      - name: {name}"
    if marker not in source:
        return None
    lines = []
    for line in source.split(marker, 1)[1].splitlines()[1:]:
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) < 8:
            break
        lines.append(line)
    return "\n".join(lines)


def _run_script(block: str) -> str | None:
    """A step's `run:` text — the inline command or the indented block body."""
    lines = block.splitlines()
    for index, line in enumerate(lines):
        stripped = line.strip()
        if len(line) - len(line.lstrip()) != 8 or not stripped.startswith("run:"):
            continue
        inline = stripped.removeprefix("run:").strip()
        if inline and inline not in ("|", ">", "|-", ">-", "|+", ">+"):
            return inline
        body = []
        for run_line in lines[index + 1:]:
            if run_line.strip() and len(run_line) - len(run_line.lstrip()) <= 8:
                break
            body.append(run_line)
        return textwrap.dedent("\n".join(body))
    return None


def _step_env(block: str, key: str) -> str | None:
    in_env = False
    for line in block.splitlines():
        indent = len(line) - len(line.lstrip())
        stripped = line.strip()
        if indent <= 8:
            in_env = stripped == "env:"
            continue
        if in_env and stripped.startswith(f"{key}:"):
            return stripped.split(":", 1)[1].strip()
    return None


class PreserveDesktopTestCrashesTest(unittest.TestCase):
    def test_failed_unit_command_still_preserves_the_crash_report(self):
        source = (ROOT / WORKFLOW).read_text()
        unit_block = _step_block(source, UNIT_STEP)
        self.assertIsNotNone(unit_block, "ci.yml has no unit step")
        unit_script = _run_script(unit_block)
        self.assertIsNotNone(unit_script, "the unit step carries no run script")
        collector_block = _step_block(source, COLLECTOR_STEP)
        collector_script = _run_script(collector_block) if collector_block else None

        with tempfile.TemporaryDirectory(prefix="crash-evidence-") as directory:
            root = Path(directory)
            work = root / "work"
            work.mkdir()
            runner = root / "runner"
            runner.mkdir()
            bin_dir = root / "bin"
            bin_dir.mkdir()

            # Fake dbus-run-session drops its `--` and execs the wrapped command.
            dbus = bin_dir / "dbus-run-session"
            dbus.write_text('#!/bin/sh\n[ "$1" = "--" ] && shift\nexec "$@"\n')
            dbus.chmod(0o755)

            # Fake gradlew dies like a halted test worker: a crash file in the
            # tempAppDirs shape under java.io.tmpdir, then exit 1.
            gradlew = work / "gradlew"
            gradlew.write_text(
                f"#!{sys.executable}\n"
                "import os, sys\n"
                "from pathlib import Path\n"
                f"report = Path(os.environ['TMPDIR']) / '{FAKE_ROOT}/state' / '{FAKE_REPORT}'\n"
                "report.parent.mkdir(parents=True)\n"
                f"report.write_bytes({PAYLOAD!r})\n"
                "sys.exit(1)\n"
            )
            gradlew.chmod(0o755)

            helper = work / COLLECTOR
            if (ROOT / COLLECTOR).is_file():
                helper.parent.mkdir(parents=True)
                shutil.copyfile(ROOT / COLLECTOR, helper)

            pinned = _step_env(unit_block, "TMPDIR")
            tmpdir = (
                pinned.replace("${{ runner.temp }}", str(runner))
                if pinned
                else str(root / "unpinned-tmp")
            )
            env = {
                **os.environ,
                "RUNNER_TEMP": str(runner),
                "TMPDIR": tmpdir,
                "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
            }

            unit = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", unit_script],
                cwd=work,
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(unit.returncode, 1, unit.stderr + unit.stdout)

            collector = subprocess.run(
                [
                    "bash",
                    "-euo",
                    "pipefail",
                    "-c",
                    (collector_script or ":").replace("${{ runner.temp }}", str(runner)),
                ],
                cwd=work,
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(collector.returncode, 0, collector.stderr + collector.stdout)

            saved = (
                work / "desktopApp/build/test-results/crash-evidence" / FAKE_ROOT / FAKE_REPORT
            )
            self.assertEqual(
                saved.read_bytes() if saved.is_file() else None,
                PAYLOAD,
                "the unit job does not preserve the desktop worker's crash file",
            )

        self.assertIn(
            "if: always()",
            collector_block or "",
            "the preservation step must run when the unit step failed",
        )
        reports_section = source.split("name: unit-reports", 1)[1][:400]
        self.assertIn("**/build/test-results/", reports_section)

    def test_collector_preserves_each_root_and_ignores_non_reports(self):
        preserve = runpy.run_path(str(ROOT / COLLECTOR))["preserve_crash_reports"]
        with tempfile.TemporaryDirectory(prefix="crash-src-") as directory:
            source = Path(directory) / "tmp"
            destination = Path(directory) / "out"

            # Two roots carrying the same report name must stay distinct.
            for name, payload in (("neutrodyne-test111", b"first"), ("nd-shutdown222", b"second")):
                state = source / name / "state"
                state.mkdir(parents=True)
                (state / "crash-20261108-010203.txt").write_bytes(payload)

            # A real report beside entries the glob must ignore.
            state = source / "m7-privacy333" / "state"
            state.mkdir(parents=True)
            (state / "crash-20261108-010204.txt").write_bytes(b"third")
            decoy = source / "decoy.txt"
            decoy.write_bytes(b"decoy")
            (state / "crash-20261108-010205.txt").symlink_to(decoy)
            (state / "instance.lock").write_bytes(b"not a report")
            (state / "logs").mkdir()

            # A symlinked root and a symlinked state dir are never followed.
            outside = Path(directory) / "outside" / "state"
            outside.mkdir(parents=True)
            (outside / "crash-20261108-010206.txt").write_bytes(b"outside")
            (source / "linked-root").symlink_to(outside.parent, target_is_directory=True)
            (source / "linked-state").mkdir()
            (source / "linked-state" / "state").symlink_to(outside, target_is_directory=True)

            # Neither a bare file nor a report outside any state dir matches.
            (source / "neutrodyne-test-file").write_bytes(b"not a dir")
            (source / "crash-20261108-010207.txt").write_bytes(b"no state dir")

            result = preserve(source, destination)

            self.assertEqual(result.failed, 0, result.warnings)
            self.assertEqual(result.copied, 3, result.warnings)
            saved = {
                path.relative_to(destination).as_posix(): path.read_bytes()
                for path in destination.rglob("*.txt")
            }
            self.assertEqual(
                saved,
                {
                    "neutrodyne-test111/crash-20261108-010203.txt": b"first",
                    "nd-shutdown222/crash-20261108-010203.txt": b"second",
                    "m7-privacy333/crash-20261108-010204.txt": b"third",
                },
            )

    def test_collector_bounds_and_copy_failures_are_reported(self):
        preserve = runpy.run_path(str(ROOT / COLLECTOR))["preserve_crash_reports"]
        preserve.__globals__["MAX_FILE_BYTES"] = 8
        preserve.__globals__["MAX_FILES"] = 2
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "tmp/neutrodyne-test1/state"
            state.mkdir(parents=True)
            (state / "crash-a.txt").write_bytes(b"123456789")
            (state / "crash-b.txt").write_bytes(b"b")
            (state / "crash-c.txt").write_bytes(b"c")
            (state / "crash-d.txt").write_bytes(b"d")

            result = preserve(Path(directory) / "tmp", Path(directory) / "out")

            self.assertEqual(result.copied, 2, result.warnings)
            self.assertEqual(result.skipped, 2, result.warnings)
            self.assertEqual(result.failed, 0, result.warnings)
            self.assertTrue(any("crash-a" in warning for warning in result.warnings))
            self.assertTrue(any("crash-d" in warning for warning in result.warnings))

            preserve.__globals__["MAX_FILES"] = 99
            blocked = Path(directory) / "out2"
            blocked.mkdir()
            (blocked / "neutrodyne-test1").write_bytes(b"not a directory")

            result = preserve(Path(directory) / "tmp", blocked)

            self.assertEqual(result.failed, 3, result.warnings)
            self.assertTrue(any("could not copy" in warning for warning in result.warnings))

    def test_missing_temp_root_is_a_successful_noop(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / COLLECTOR),
                    str(Path(directory) / "absent"),
                    str(Path(directory) / "out"),
                ],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(missing.returncode, 0, missing.stderr)


if __name__ == "__main__":
    unittest.main()
