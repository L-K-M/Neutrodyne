#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A dead desktop test worker must still hand its crash file to unit-reports.

The pin has to be the JVM property itself: OpenJDK's Linux builds report
java.io.tmpdir=/tmp no matter what TMPDIR says (Temurin JRE 25 container
probe), and this macOS JDK 21 keeps its per-user temp dir with TMPDIR set or
cleared. The fixture below is therefore a real JVM subprocess — a Python fake
that reads os.environ['TMPDIR'] would mask exactly that worker mismatch.
"""

import os
from pathlib import Path
import runpy
import shlex
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
FAKE_ROOT_PREFIX = "neutrodyne-test"  # tempAppDirs' default prefix
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


def _java() -> str | None:
    """A real java binary for the worker fixture: JAVA_HOME, then the known local JDK.

    A bare PATH lookup is never used — /usr/bin/java can be a no-JDK stub.
    Source-file mode (`java Fixture.java`) needs JDK 11+; CI and the local
    toolchain carry 21.
    """
    candidates = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java")
    candidates.append(Path.home() / ".local/jdks/jdk21/Contents/Home/bin/java")
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


class PreserveDesktopTestCrashesTest(unittest.TestCase):
    def test_both_unit_commands_run_and_either_failure_fails_the_step(self):
        block = _step_block((ROOT / WORKFLOW).read_text(), UNIT_STEP)
        self.assertIsNotNone(block)
        script = _run_script(block)
        self.assertIsNotNone(script)
        for aggregate, mpris in ((0, 0), (9, 0), (0, 7)):
            with self.subTest(aggregate=aggregate, mpris=mpris), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                bin_dir = root / "bin"
                bin_dir.mkdir()
                dbus = bin_dir / "dbus-run-session"
                dbus.write_text('#!/bin/sh\n[ "$1" = "--" ] && shift\nexec "$@"\n')
                dbus.chmod(0o755)
                gradlew = root / "gradlew"
                gradlew.write_text(
                    '#!/bin/sh\n'
                    'if [ "$1" = ":desktop:system:test" ]; then\n'
                    '  echo mpris >> "$NEUTRODYNE_TEST_CALLS"\n'
                    '  exit "$NEUTRODYNE_TEST_MPRIS_EXIT"\n'
                    'fi\n'
                    'echo aggregate >> "$NEUTRODYNE_TEST_CALLS"\n'
                    'exit "$NEUTRODYNE_TEST_AGGREGATE_EXIT"\n'
                )
                gradlew.chmod(0o755)
                calls = root / "calls.txt"
                env = {
                    **os.environ,
                    "TMPDIR": str(root / "tmp"),
                    "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                    "NEUTRODYNE_TEST_CALLS": str(calls),
                    "NEUTRODYNE_TEST_AGGREGATE_EXIT": str(aggregate),
                    "NEUTRODYNE_TEST_MPRIS_EXIT": str(mpris),
                }
                result = subprocess.run(
                    ["bash", "-euo", "pipefail", "-c", script],
                    cwd=root, env=env, capture_output=True, text=True, timeout=10,
                )
                self.assertEqual(["aggregate", "mpris"], calls.read_text().splitlines())
                if aggregate or mpris:
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                else:
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_failed_unit_command_still_preserves_the_crash_report(self):
        java = _java()
        if java is None:
            self.skipTest("no JDK found via JAVA_HOME or ~/.local/jdks/jdk21")
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

            # The fake worker is a real JVM in source-file mode: like
            # TempAppDirs.kt it opens a Files.createTempDirectory root under
            # java.io.tmpdir, drops a crash-<UTC>.txt in state/ and dies like
            # halt(1). Where the file lands is decided by the JVM property,
            # not by any env var the platform honours.
            fixture = work / "CrashFixture.java"
            fixture.write_text(
                textwrap.dedent(
                    f"""\
                    import java.nio.charset.StandardCharsets;
                    import java.nio.file.Files;
                    import java.nio.file.Path;

                    public class CrashFixture {{
                        public static void main(String[] args) throws Exception {{
                            Path root = Files.createTempDirectory("{FAKE_ROOT_PREFIX}");
                            Path report = Files.createDirectories(root.resolve("state"))
                                .resolve("{FAKE_REPORT}");
                            Files.writeString(report, "{PAYLOAD.decode()}", StandardCharsets.UTF_8);
                            System.out.println("java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
                            System.out.println("neutrodyne.probe=" + System.getProperty("neutrodyne.probe"));
                            System.out.println("crash-report=" + report);
                            System.exit(1);
                        }}
                    }}
                    """
                )
            )

            # Fake gradlew runs the fixture. TMPDIR is cleared only in this
            # child so the platform default can never be confused with the
            # pinned root — the step's own mechanism has to win the property.
            gradlew = work / "gradlew"
            gradlew.write_text(
                "#!/bin/sh\n"
                "unset TMPDIR\n"
                f"exec {shlex.quote(java)} {shlex.quote(str(fixture))}\n"
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
                # Options the step already carries must survive the step's own
                # JAVA_TOOL_OPTIONS edit — the fixture reports the property.
                "JAVA_TOOL_OPTIONS": "-Dneutrodyne.probe=keepme",
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

            jvm = {}
            for line in unit.stdout.splitlines():
                for key in ("java.io.tmpdir", "neutrodyne.probe", "crash-report"):
                    if line.startswith(f"{key}="):
                        jvm[key] = line.split("=", 1)[1]
            self.assertIn("Picked up JAVA_TOOL_OPTIONS", unit.stderr)
            self.assertEqual(
                jvm.get("neutrodyne.probe"),
                "keepme",
                "the step's JAVA_TOOL_OPTIONS edit must keep earlier options",
            )
            self.assertEqual(
                os.path.realpath(jvm.get("java.io.tmpdir", "")),
                os.path.realpath(tmpdir),
                "the worker's java.io.tmpdir must be the pinned root, not the platform default",
            )
            report = Path(jvm.get("crash-report", ""))
            self.assertTrue(
                os.path.realpath(report).startswith(os.path.realpath(tmpdir) + os.sep),
                f"crash report {report} escaped the pinned temp root",
            )

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
                work / "desktopApp/build/test-results/crash-evidence"
                / report.parent.parent.name
                / report.name
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
