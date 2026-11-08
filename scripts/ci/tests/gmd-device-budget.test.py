#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Execute the nightly shell with fake Gradle; this is not emulator proof."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/nightly.yml"
DEVICE_PROPERTY = "android.experimental.testOptions.managedDevices.maxConcurrentDevices"
DEVICE_ARGUMENT = f"-P{DEVICE_PROPERTY}=1"
GPU_PROPERTY = "android.testoptions.manageddevices.emulator.gpu"


def _run_block(step_name):
    """The dedented `run: |` body of the uniquely named workflow step."""
    source = WORKFLOW.read_text()
    marker = f"      - name: {step_name}\n"
    assert source.count(marker) == 1
    body = source.split(marker, 1)[1].split("        run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith("          "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines))


class GmdDeviceBudgetTest(unittest.TestCase):
    def _run_script(self, script):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory(prefix="android-") as crashes:
            root = Path(directory)
            runner = root / "runner"
            runner.mkdir()
            helper = root / "scripts/ci/preserve-emulator-crashes.py"
            helper.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / "scripts/ci/preserve-emulator-crashes.py", helper)
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
                ["bash", "-euo", "pipefail", "-c", script],
                cwd=root,
                env={
                    **os.environ,
                    "GMD_ARGUMENTS": str(record),
                    "RUNNER_TEMP": str(runner),
                    "USER": Path(crashes).name.removeprefix("android-"),
                },
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(record.read_text())

    def _run_scope(self, scope):
        script = _run_block("GMD nightly group (debug)").replace("${{ inputs.scope }}", scope)
        return self._run_script(script)

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

    def test_full_uses_the_alternate_software_renderer(self):
        arguments = self._run_scope("full")
        renderers = [arg for arg in arguments if arg.startswith(f"-P{GPU_PROPERTY}=")]
        self.assertEqual(renderers, [f"-P{GPU_PROPERTY}=lavapipe"], "Do not restore the crashing legacy renderer")

    def test_youtube_smoke_bounds_active_devices(self):
        arguments = self._run_scope("youtube-smoke")
        self._assert_device_budget(arguments)
        self.assertIn("api36DebugAndroidTest", arguments)
        self.assertIn(
            "-Pandroid.testInstrumentationRunnerArguments.class="
            "ch.lkmc.neutrodyne.YouTubeReleaseSmokeTest,ch.lkmc.neutrodyne.SmokeTest",
            arguments,
        )

    def test_m10_a11y_runs_four_serial_invocations_one_per_class(self):
        classes = [
            "M10AccessibilityTest",
            "NavigationStateRestorationTest",
            "PerAppLanguageTest",
            "SubscribeJourneyTest",
        ]
        for classname in classes:
            step_name = f"M10 journeys on the api36 GMD - {classname}"
            arguments = self._run_script(_run_block(step_name))
            self._assert_device_budget(arguments)
            self.assertIn(":app:api36DebugAndroidTest", arguments)
            self.assertIn("-Pneutrodyne.testScope=nightly", arguments)
            self.assertEqual(
                [arg for arg in arguments if arg.startswith("-Pandroid.testInstrumentationRunnerArguments.class=")],
                [
                    f"-Pandroid.testInstrumentationRunnerArguments.class=ch.lkmc.neutrodyne.{classname}"
                ],
                f"The M10 lane runs {classname} in its own isolated invocation",
            )
            renderers = [arg for arg in arguments if arg.startswith(f"-P{GPU_PROPERTY}=")]
            self.assertEqual(renderers, [f"-P{GPU_PROPERTY}=lavapipe"], "Do not restore the crashing legacy renderer")

    def test_benchmark_dryrun_selects_only_macrobenchmark_journeys(self):
        source = WORKFLOW.read_text().split("  benchmark-dryrun:\n", 1)[1].split("\n  bmgr-download-exclusion:", 1)[0]
        body = source.split("      - name: Exercise all Macrobenchmark journeys\n", 1)[1]
        lines = body.split("        run: >-\n", 1)[1].splitlines()
        command = []
        for line in lines:
            if line.strip() and not line.startswith("          "):
                break
            command.append(line.strip())
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
                ["bash", "-euo", "pipefail", "-c", " ".join(command)],
                cwd=root, env={**os.environ, "GMD_ARGUMENTS": str(record)},
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            arguments = json.loads(record.read_text())
            self._assert_device_budget(arguments)
            self.assertIn(":benchmark:bench34BenchmarkReleaseAndroidTest", arguments)
            selections = [arg for arg in arguments if arg.startswith("-Pandroid.testInstrumentationRunnerArguments.class=")]
            self.assertEqual(
                selections,
                ["-Pandroid.testInstrumentationRunnerArguments.class=ch.lkmc.neutrodyne.benchmark.Journeys"],
                "Dry-run mode only affects MacrobenchmarkRule; unrelated system tests require a different app build",
            )

    RECORDED_APP_CLASSES = (
        "ch.lkmc.neutrodyne.YtxIsolationTest",
        "ch.lkmc.neutrodyne.YouTubeReleaseSmokeTest",
        "ch.lkmc.neutrodyne.ExternalYouTubeModeTest",
    )
    RECORDED_LIB_CLASS = "ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxSelfTestInstrumentedTest"
    CLASS_FILTER = "-Pandroid.testInstrumentationRunnerArguments.class="

    def _gradle_stub(self, fail_at=None, silent=False):
        """Fake Gradle records argv, then writes one device report per selected class."""
        return (
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "args = sys.argv[1:]\n"
            "open(os.environ['GMD_ARGUMENTS'], 'a').write(json.dumps(args) + '\\n')\n"
            "cls = next(a.split('=', 1)[1] for a in args if '.class=' in a)\n"
            "task = next(a for a in args if a.endswith('DebugAndroidTest'))\n"
            "api = task.split(':')[-1].removesuffix('DebugAndroidTest')\n"
            "mod = 'app/build' if task.startswith(':app:') else 'youtube/ytdlp/build'\n"
            "out = Path(mod) / 'outputs/androidTest-results/managedDevice/debug' / api\n"
            "out.mkdir(parents=True, exist_ok=True)\n"
            "print('device stdout evidence'); print('device stderr evidence', file=sys.stderr)\n"
            f"failing = cls == {fail_at!r}\n"
            f"if not {silent!r}:\n"
            "    mark = '<failure/>' if failing else ''\n"
            "    for name in cls.split(','):\n"
            "        (out / ('TEST-' + name + '.xml')).write_text(\n"
            "            '<testsuite><testcase classname=\"' + name + '\" name=\"aCase\">'\n"
            "            + mark + '</testcase></testsuite>')\n"
            "sys.exit(37 if failing else 0)\n"
        )

    def _run_recorded_step(self, step_name, api, gradle_body):
        """Execute one youtube-recorded `run: |` body in a stub Gradle sandbox."""
        sandbox = tempfile.TemporaryDirectory()
        # The caller inspects the sandbox, so it outlives this helper.
        self.addCleanup(sandbox.cleanup)
        root = Path(sandbox.name)
        runner = root / "runner"
        runner.mkdir()
        for helper in ("preserve-emulator-crashes.py", "assert-selected-tests.py"):
            target = root / "scripts/ci" / helper
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / "scripts/ci" / helper, target)
        record = root / "arguments.jsonl"
        gradle = root / "gradlew"
        gradle.write_text(f"#!{sys.executable}\n" + gradle_body)
        gradle.chmod(0o755)
        script = _run_block(step_name).replace(
            "${{ matrix.api }}", api.removeprefix("api")
        )
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", script],
            cwd=root,
            env={**os.environ, "GMD_ARGUMENTS": str(record),
                 "RUNNER_TEMP": str(runner), "USER": "runner"},
            capture_output=True,
            text=True,
            timeout=30,
        )
        calls = [json.loads(line) for line in record.read_text().splitlines()] if record.exists() else []
        return result, root, calls

    APP_STEP = "Recorded YouTube on api${{ matrix.api }} - app classes"
    YTDLP_STEP = "Recorded YouTube on api${{ matrix.api }} - ytdlp selftest"

    def test_youtube_recorded_app_step_runs_each_class_serially_in_its_own_invocation(self):
        for api in ("api34", "api36"):
            with self.subTest(api=api):
                result, root, calls = self._run_recorded_step(self.APP_STEP, api, self._gradle_stub())
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(calls), len(self.RECORDED_APP_CLASSES))
                for arguments, fqcn in zip(calls, self.RECORDED_APP_CLASSES):
                    self._assert_device_budget(arguments)
                    self.assertIn(f":app:{api}DebugAndroidTest", arguments)
                    self.assertIn("-Pneutrodyne.testScope=nightly", arguments)
                    self.assertIn(
                        "-Pandroid.testInstrumentationRunnerArguments.ytxReplay=true",
                        arguments,
                        "the recorded app cases replay 04's captured traffic",
                    )
                    self.assertEqual(
                        [arg for arg in arguments if arg.startswith(self.CLASS_FILTER)],
                        [self.CLASS_FILTER + fqcn],
                        "each app class runs in its own isolated serial invocation",
                    )
                    self.assertEqual(
                        [arg for arg in arguments if arg.startswith(f"-P{GPU_PROPERTY}=")],
                        [f"-P{GPU_PROPERTY}=lavapipe"],
                    )
                    simple = fqcn.rpartition(".")[2]
                    evidence = (
                        root
                        / f"app/build/outputs/androidTest-results/youtube-recorded/{simple}/{api}"
                    )
                    self.assertTrue((evidence / f"TEST-{fqcn}.xml").is_file())
                    self.assertTrue((evidence / "gradle.log").is_file())

    def test_youtube_recorded_app_step_failure_keeps_earlier_evidence(self):
        failing = self.RECORDED_APP_CLASSES[1]
        result, root, calls = self._run_recorded_step(
            self.APP_STEP, "api36", self._gradle_stub(fail_at=failing)
        )
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(len(calls), 2, "a failed class stops the leg before later classes run")
        evidence = root / "app/build/outputs/androidTest-results/youtube-recorded"
        self.assertTrue(
            (evidence / "YtxIsolationTest/api36" / f"TEST-{self.RECORDED_APP_CLASSES[0]}.xml").is_file(),
            "the later invocation must not erase the earlier class's snapshot",
        )
        failed = evidence / "YouTubeReleaseSmokeTest/api36"
        self.assertTrue(
            (failed / f"TEST-{failing}.xml").is_file(),
            "a failed class must still snapshot its device XML",
        )
        log = (failed / "gradle.log").read_text()
        self.assertIn("device stdout evidence", log)
        self.assertIn("device stderr evidence", log)
        self.assertFalse((evidence / "ExternalYouTubeModeTest/api36").exists())

    def test_youtube_recorded_app_step_rejects_a_silently_deselected_class(self):
        result, root, calls = self._run_recorded_step(
            self.APP_STEP, "api34", self._gradle_stub(silent=True)
        )
        self.assertEqual(result.returncode, 1, "an empty selection must fail the leg")
        self.assertIn("executed no case", result.stderr)
        self.assertEqual(len(calls), 1)

    def test_youtube_recorded_runs_the_library_selftest_in_its_own_module(self):
        for api in ("api34", "api36"):
            with self.subTest(api=api):
                result, root, calls = self._run_recorded_step(self.YTDLP_STEP, api, self._gradle_stub())
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(calls), 1)
                arguments = calls[0]
                self._assert_device_budget(arguments)
                self.assertIn(f":youtube:ytdlp:{api}DebugAndroidTest", arguments)
                self.assertIn("-Pneutrodyne.testScope=nightly", arguments)
                self.assertEqual(
                    [arg for arg in arguments if arg.startswith(self.CLASS_FILTER)],
                    [self.CLASS_FILTER + self.RECORDED_LIB_CLASS],
                    "the library selftest keeps its own module invocation and filter",
                )
                self.assertNotIn(
                    "-Pandroid.testInstrumentationRunnerArguments.ytxReplay=true",
                    arguments,
                    "the selftest binds a real :ytx ping, not a recorded case",
                )
                self.assertFalse(
                    any(arg.startswith(":app:") for arg in arguments),
                    "the library invocation must not drag the app module's task along",
                )

    def test_youtube_recorded_ytdlp_failure_preserves_evidence_and_full_log(self):
        result, root, calls = self._run_recorded_step(
            self.YTDLP_STEP, "api34", self._gradle_stub(fail_at=self.RECORDED_LIB_CLASS)
        )
        self.assertEqual(result.returncode, 37, result.stderr)
        evidence = (
            root
            / "youtube/ytdlp/build/outputs/androidTest-results/youtube-recorded"
            / "YtxSelfTestInstrumentedTest/api34"
        )
        self.assertTrue(
            (evidence / f"TEST-{self.RECORDED_LIB_CLASS}.xml").is_file(),
            "Failed Gradle must still snapshot XML",
        )
        log = (evidence / "gradle.log").read_text()
        self.assertIn("device stdout evidence", log)
        self.assertIn("device stderr evidence", log)

    def test_youtube_recorded_final_gate_requires_every_class(self):
        body = _run_block("Require every recorded class executed unskipped")
        self.assertIn("app/build/outputs/androidTest-results/youtube-recorded", body)
        self.assertIn("youtube/ytdlp/build/outputs/androidTest-results/youtube-recorded", body)
        self.assertIn("--device api${{ matrix.api }}", body)
        for fqcn in (*self.RECORDED_APP_CLASSES, self.RECORDED_LIB_CLASS):
            self.assertIn(f"--require-class {fqcn}", body)
        self.assertIn("youtube-recorded-results-api${{ matrix.api }}", WORKFLOW.read_text())

    def test_m10_failure_preserves_class_evidence_and_full_log(self):
        classname = "NavigationStateRestorationTest"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = root / "runner"
            runner.mkdir()
            # A prior class has already stopped its own collector.
            (runner / "gmd-crash-preserver.stop").touch()
            helper = root / "scripts/ci/preserve-emulator-crashes.py"
            helper.parent.mkdir(parents=True)
            helper.write_text("import sys\nfrom pathlib import Path\n"
                              "assert not Path(sys.argv[3]).exists(), 'stale stop file'\n")
            gradle = root / "gradlew"
            gradle.write_text(
                f"#!{sys.executable}\n"
                "import sys\nfrom pathlib import Path\n"
                "p=Path('app/build/outputs/androidTest-results/managedDevice/debug/api36')\n"
                "p.mkdir(parents=True)\n"
                "(p/'TEST-failed.xml').write_text('<testsuite failures=\"1\"/>')\n"
                "print('device stdout evidence')\n"
                "print('device stderr evidence', file=sys.stderr)\n"
                "sys.exit(37)\n"
            )
            gradle.chmod(0o755)
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", _run_block(f"M10 journeys on the api36 GMD - {classname}")],
                cwd=root, env={**os.environ, "RUNNER_TEMP": str(runner)},
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(result.returncode, 37, result.stderr)
            evidence = root / f"app/build/outputs/androidTest-results/m10-a11y/{classname}/api36"
            self.assertTrue((evidence / "TEST-failed.xml").is_file(), "Failed Gradle must still snapshot XML")
            log = (evidence / "gradle.log").read_text()
            self.assertIn("device stdout evidence", log)
            self.assertIn("device stderr evidence", log)
            self.assertNotIn("stale stop file", result.stderr)

    def test_gradle_worker_limit_does_not_replace_device_limit(self):
        with self.assertRaises(AssertionError):
            self._assert_device_budget(["--max-workers=1"])


if __name__ == "__main__":
    unittest.main()
