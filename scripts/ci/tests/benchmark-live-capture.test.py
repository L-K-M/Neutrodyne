#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""benchmark-live-capture.py's lifecycle against a fake adb and a fake child.

The reproduction this guards against is hosted and already observed twice
(nightly runs 42515546426 and 42522440261): the GMD emulator wrote the
Perfetto traces to its public additional-test-output directory, the device
was torn down with them, and the uploaded artifact contained zero traces and
no benchmark JSON. These fixtures exercise the wrapper boundary with fakes
only - no device, no Gradle, no network.
"""

from pathlib import Path
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[3]
WRAPPER = ROOT / "scripts/ci/benchmark-live-capture.py"
REMOTE_DIR = "/sdcard/Android/media/ch.lkmc.neutrodyne.benchmark/additional_test_output"

FAKE_ADB = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

REMOTE_DIR = "/sdcard/Android/media/ch.lkmc.neutrodyne.benchmark/additional_test_output"

scenario = json.loads(Path(os.environ["CAPTURE_SCENARIO"]).read_text())
state_path = Path(os.environ["CAPTURE_STATE"])
args = sys.argv[1:]

if args[:3] == ["-s", scenario["serial"], "logcat"]:
    import time as _time

    _time.sleep(scenario.get("logcat_start_delay", 0))
    # The tail streams for the whole run, so it never rewrites the shared
    # state json (concurrent stat/pull calls own it); its pid goes to a
    # dedicated file instead.
    with open(os.environ["CAPTURE_STATE"] + ".logcat", "a") as pid_log:
        pid_log.write(f"{os.getpid()}\n")
    marker = scenario.get("logcat_marker")
    emitted = False
    deadline = _time.monotonic() + 60
    while _time.monotonic() < deadline:
        if marker and not emitted and Path(marker).exists():
            for name in scenario.get("logcat_moving_names", []):
                print(
                    f"11-08 23:19:18.415 D/Benchmark( 2467): Moving /storage/emulated/0/Android/media/"
                    f"ch.lkmc.neutrodyne.benchmark/{name} to {REMOTE_DIR}/{name}",
                    flush=True,
                )
            emitted = True
        _time.sleep(0.02)
    raise SystemExit(0)

state = json.loads(state_path.read_text())
state["calls"].append(args)
calls = len(state["calls"])
online = not scenario.get("never_online") and calls >= scenario.get("online_after", 0)
code = 0

def save():
    state_path.write_text(json.dumps(state))

if args == ["devices"]:
    if scenario.get("ambiguous"):
        print("List of devices attached")
        print("emulator-5554\tdevice")
        print("emulator-5556\tdevice")
    elif scenario.get("device_dies_after_marker"):
        marker = scenario.get("device_dies_after_marker")
        if online and not Path(marker).exists():
            print("List of devices attached")
            print(f"{scenario['serial']}\tdevice")
        else:
            print("List of devices attached")
    elif online:
        print("List of devices attached")
        print(f"{scenario['serial']}\tdevice")
    else:
        print("List of devices attached")
elif args[:3] == ["-s", scenario["serial"], "shell"] and args[3:6] == ["ls", "-1", REMOTE_DIR]:
    if online and calls >= scenario.get("list_after", 0):
        names = list(scenario["names"])
        marker = scenario.get("late_names_marker")
        if marker and Path(marker).exists():
            names += scenario["late_names"]
        for name in names:
            print(name)
    else:
        code = 1
elif args[:3] == ["-s", scenario["serial"], "shell"] and args[3:5] == ["stat", "-c"]:
    name = args[6].rsplit("/", 1)[1]
    growth = scenario.get("grow", {}).get(name)
    versions = scenario.get("versions", {}).get(name)
    if versions:
        # The library rewrites the cumulative JSON whole after every test; the
        # fixture advances one version per pull of the name.
        pulled = state.get("pulls", {}).get(name, 0)
        print(str(len(versions[min(pulled, len(versions) - 1)])))
    elif growth:
        seen = state["stats"].get(name, 0)
        state["stats"][name] = seen + 1
        print(str(growth[min(seen, len(growth) - 1)]))
    elif name in scenario["contents"]:
        print(str(len(scenario["contents"][name])))
    else:
        code = 1
elif args[:3] == ["-s", scenario["serial"], "pull"]:
    name = args[3].rsplit("/", 1)[1]
    target = Path(args[4])
    versions = scenario.get("versions", {}).get(name)
    if scenario.get("pull_error"):
        print(f"adb: error: failed to pull {name}", file=sys.stderr)
        code = 1
    elif versions:
        pulls = state.get("pulls", {})
        pulled = pulls.get(name, 0)
        pulls[name] = pulled + 1
        state["pulls"] = pulls
        target.write_bytes(versions[min(pulled, len(versions) - 1)].encode("utf-8"))
    elif name in scenario["contents"]:
        payload = scenario["contents"][name]
        growth = scenario.get("grow", {}).get(name)
        if growth:
            seen = state["stats"].get(name, 0)
            payload = payload[: growth[min(seen, len(growth) - 1)]]
        target.write_bytes(payload.encode("utf-8") if isinstance(payload, str) else payload)
    else:
        code = 1
else:
    print("unexpected adb command: " + repr(args), file=sys.stderr)
    code = 3
save()
raise SystemExit(code)
'''

FAKE_CHILD = r'''#!/usr/bin/env python3
import os
import sys
import time
from pathlib import Path

print("child stdout passes through", flush=True)
pid_file = os.environ.get("CAPTURE_CHILD_PID_FILE")
if pid_file:
    Path(pid_file).write_text(str(os.getpid()))
time.sleep(float(os.environ.get("CAPTURE_CHILD_SLEEP", "0")))
ready_file = os.environ.get("CAPTURE_CHILD_WAIT_FOR")
if ready_file:
    deadline = time.monotonic() + 10
    while not Path(ready_file).exists():
        if time.monotonic() >= deadline:
            sys.exit("the fake logcat tail did not become ready")
        time.sleep(0.01)
marker = os.environ.get("CAPTURE_CHILD_MARKER")
if marker:
    Path(marker).write_text("marked")
    # The teardown lag of a real run: the move event is live for a moment
    # while the Gradle child keeps running.
    time.sleep(float(os.environ.get("CAPTURE_CHILD_MARKER_LINGER", "0.8")))
sys.exit(int(os.environ.get("CAPTURE_CHILD_EXIT", "0")))
'''


TRACE = "ColdStartBenchmark_coldStartToFeeds_iter005_2026-11-08-21-38-12.perfetto-trace"
# The pinned androidx.benchmark 1.5.0 ResultWriter overwrites ONE cumulative
# file per run - <target package>-benchmarkData.json, a Moshi-serialized
# BenchmarkData object {"context": ..., "benchmarks": [TestResult...]} - after
# EVERY test method. There are no per-test JSONs (R5 finding I1).
REPORT = "ch.lkmc.neutrodyne.benchmark-benchmarkData.json"
TRACE_TEXT = "perfetto trace fixture, bytes matter, not a real trace"
REPORT_COLD = '{"context": {"coreCount": 4}, "benchmarks": [{"name": "coldStartToFeeds"}]}'
REPORT_FULL = (
    '{"context": {"coreCount": 4}, "benchmarks": '
    '[{"name": "coldStartToFeeds"}, {"name": "coverGridFling"}]}'
)
# A same-length rewrite: only a content comparison can see it changed.
REPORT_FULL_SAME_SIZE = (
    '{"context": {"coreCount": 5}, "benchmarks": '
    '[{"name": "coldStartToFeeds"}, {"name": "coverGridFling"}]}'
)
assert len(REPORT_FULL) == len(REPORT_FULL_SAME_SIZE)
# A rewrite caught mid-write (unterminated JSON).
REPORT_TRUNCATED = REPORT_FULL[: len(REPORT_FULL) // 2]
TRACE_BODY = TRACE_TEXT.encode()
REPORT_COLD_BODY = REPORT_COLD.encode()


class BenchmarkLiveCaptureTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory(prefix="benchmark-live-capture-")
        self.folder = Path(self._temp.name)
        self.adb = self.folder / "adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(0o755)
        self.child = self.folder / "child"
        self.child.write_text(FAKE_CHILD)
        self.child.chmod(0o755)
        self.scenario_path = self.folder / "scenario.json"
        self.state_path = self.folder / "state.json"
        self.output = self.folder / "host-collected"

    def tearDown(self):
        self._temp.cleanup()

    def _scenario(self, **overrides):
        scenario = {
            "serial": "emulator-5554",
            "online_after": 3,
            "list_after": 6,
            "names": [TRACE, REPORT],
            "contents": {TRACE: TRACE_TEXT, REPORT: REPORT_COLD},
            "late_names_marker": None,
        }
        scenario.update(overrides)
        self.scenario_path.write_text(json.dumps(scenario))
        return scenario

    def _run_wrapper(self, *extra, child_env=None, timeout=30):
        env = dict(
            os.environ,
            CAPTURE_SCENARIO=str(self.scenario_path),
            CAPTURE_STATE=str(self.state_path),
            **(child_env or {}),
        )
        argv = [
            sys.executable,
            str(WRAPPER),
            "--adb",
            str(self.adb),
            "--output",
            str(self.output),
            "--poll-interval",
            "0.05",
            *extra,
            "--",
            sys.executable,
            str(self.child),
        ]
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, env=env)
        calls = json.loads(self.state_path.read_text())["calls"] if self.state_path.exists() else []
        return result, calls

    def _collected(self, serial="emulator-5554"):
        directory = self.output / serial
        if not directory.is_dir():
            return {}
        return {path.name: path.read_bytes() for path in sorted(directory.iterdir())}

    def test_delayed_device_and_directory_then_collected(self):
        self._scenario(online_after=3, list_after=8)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, calls = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {TRACE: TRACE_BODY, REPORT: REPORT_COLD_BODY},
            self._collected(),
            "files that appeared late were never pulled",
        )
        self.assertIn("child stdout passes through", result.stdout)
        self.assertIn(f"collected emulator-5554:{TRACE}", result.stderr)
        self.assertTrue(any(call[:1] == ["-s"] for call in calls))
        self.assertFalse(
            any(name.endswith(".partial") for name in self._collected()),
            "a partial pull leaked into the collected tree",
        )

    def test_file_appearing_after_child_exit_is_pulled_by_final_sweeps(self):
        late_trace = "ColdStartBenchmark_coldStartToFeeds_iter012_2026-11-08-19-59-15.perfetto-trace"
        marker = self.folder / "late.marker"
        self._scenario(
            online_after=0,
            list_after=0,
            names=[],
            late_names_marker=str(marker),
            late_names=[late_trace],
            contents={late_trace: "late but stable"},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, _ = self._run_wrapper(
            child_env={"CAPTURE_CHILD_MARKER": str(marker), "CAPTURE_CHILD_MARKER_LINGER": "0"}
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({late_trace: b"late but stable"}, self._collected())

    def test_changing_file_retried_then_kept_without_clobber(self):
        self._scenario(
            online_after=0,
            list_after=0,
            names=[TRACE],
            contents={TRACE: "A" * 64},
            grow={TRACE: [3, 11, 64]},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, calls = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        # The device copy grows between the stat and each pull (3 -> 11 -> 64):
        # both mismatching pulls are discarded, and only the stable size is
        # accepted.
        self.assertEqual({TRACE: b"A" * 64}, self._collected())
        # Once accepted the name is never pulled again, so a later device-side
        # change cannot clobber the collected copy.
        pulls = [call for call in calls if "pull" in call and call[3].endswith(TRACE)]
        self.assertEqual(3, len(pulls), calls)

    def test_failed_child_status_is_preserved(self):
        self._scenario(online_after=0, list_after=0)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, _ = self._run_wrapper(child_env={"CAPTURE_CHILD_EXIT": "37"})
        self.assertEqual(37, result.returncode, "the child's failure code was masked")
        self.assertIn(TRACE, self._collected())

    def test_cumulative_json_rewrite_supersedes_the_cold_start_snapshot(self):
        # Macrobenchmark 1.5.0 overwrites <pkg>-benchmarkData.json after every
        # test: the cold-start snapshot first, then the rewrite that adds
        # cover-grid results under the SAME name. Skipping collected names
        # keeps the stale first snapshot forever (R5 I1).
        self._scenario(
            online_after=0,
            list_after=0,
            names=[REPORT],
            versions={REPORT: [REPORT_COLD, REPORT_FULL]},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, _ = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.6"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {REPORT: REPORT_FULL.encode()},
            self._collected(),
            "the retained JSON is the stale cold-start snapshot, not the rewrite "
            "that carries the cover-grid results",
        )

    def test_same_size_cumulative_rewrite_is_detected_by_content(self):
        rewrite = REPORT_FULL_SAME_SIZE
        self._scenario(
            online_after=0,
            list_after=0,
            names=[REPORT],
            versions={REPORT: [REPORT_FULL, rewrite]},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, _ = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.6"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {REPORT: rewrite.encode()},
            self._collected(),
            "a same-size rewrite slipped past a size-only snapshot cache",
        )

    def test_malformed_rewrite_keeps_prior_json_and_retries_until_valid(self):
        valid_full = REPORT_FULL
        self._scenario(
            online_after=0,
            list_after=0,
            names=[REPORT],
            versions={REPORT: [REPORT_COLD, REPORT_TRUNCATED, valid_full]},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, calls = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.6"})
        self.assertEqual(0, result.returncode, result.stderr)
        # The mid-write snapshot never replaces the prior complete JSON; the
        # next valid rewrite does.
        self.assertEqual({REPORT: valid_full.encode()}, self._collected())
        report_pulls = [call for call in calls if "pull" in call and call[3].endswith(REPORT)]
        self.assertGreaterEqual(len(report_pulls), 3, calls)
        self.assertNotIn("was never captured", result.stderr)

    def test_uncapturable_final_rewrite_keeps_snapshot_and_warns(self):
        self._scenario(
            online_after=0,
            list_after=0,
            names=[REPORT],
            versions={REPORT: [REPORT_COLD, REPORT_TRUNCATED]},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, _ = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.6"})
        self.assertEqual(0, result.returncode, result.stderr)
        # The last complete JSON stays; the un-captured final rewrite is an
        # explicit diagnostic gap, not a silent success.
        self.assertEqual({REPORT: REPORT_COLD_BODY}, self._collected())
        self.assertIn("::warning::", result.stderr)
        self.assertIn("was never captured", result.stderr)

    def test_replacement_is_not_a_new_file_or_consumed_budget(self):
        other = "CoverGridBenchmark_coverGridFling_iter000_2026-11-08-21-40-00.perfetto-trace"
        self._scenario(
            online_after=0,
            list_after=0,
            names=[other, REPORT],
            contents={other: "second trace"},
            versions={REPORT: [REPORT_COLD, REPORT_FULL]},
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, _ = self._run_wrapper(
            "--max-files", "1", child_env={"CAPTURE_CHILD_SLEEP": "0.6"}
        )
        self.assertEqual(0, result.returncode, result.stderr)
        # The file cap admits the JSON once; its rewrite is a replacement of
        # stored bytes, not another file, so it must still be published while
        # the cap keeps gating genuinely new names.
        self.assertEqual(
            {REPORT: REPORT_FULL.encode()},
            self._collected(),
            "the rewrite was refused as if it were a new file",
        )
        self.assertIn("size cap", result.stderr)

    def test_pull_failures_leave_a_diagnostic_gap_and_preserve_status(self):
        self._scenario(online_after=0, list_after=0, pull_error=True)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, _ = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.4"})
        self.assertEqual(0, result.returncode, "pull failures masked the child's success")
        self.assertEqual({}, self._collected())
        self.assertIn("::warning::", result.stderr)
        self.assertIn("diagnostic gap", result.stderr)
        self.assertIn("pulls failed", result.stderr)

    def test_final_json_is_captured_from_the_move_event_before_teardown(self):
        # The hosted shape of run 42530327038: the cumulative JSON is moved
        # into the public directory ~0.6s before GMD shutdown - inside one
        # 5s poll interval - and the device disappears at teardown. Only the
        # library's own "Moving ... to <dir>" logcat line announces it in
        # time; polling alone cannot retain the final JSON.
        marker = self.folder / "teardown.marker"
        self._scenario(
            online_after=0,
            list_after=0,
            names=[TRACE],
            contents={TRACE: TRACE_TEXT, REPORT: REPORT_FULL},
            late_names_marker=str(marker),
            late_names=[REPORT],
            logcat_marker=str(marker),
            logcat_moving_names=[REPORT],
            device_dies_after_marker=str(marker),
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        result, _ = self._run_wrapper(
            "--poll-interval", "5", child_env={"CAPTURE_CHILD_MARKER": str(marker), "CAPTURE_CHILD_SLEEP": "1.2"}
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {TRACE: TRACE_BODY, REPORT: REPORT_FULL.encode()},
            self._collected(),
            "the final JSON was lost to the poll-interval teardown race",
        )
        self.assertNotIn("was never captured", result.stderr)

    def test_logcat_startup_does_not_read_mutable_poll_state(self):
        self._scenario(online_after=0, list_after=0, names=[])
        # A polling adb call may be between truncating and rewriting its JSON.
        # The streaming logcat process neither consumes nor updates that state.
        self.state_path.write_text("{")
        env = dict(os.environ, CAPTURE_SCENARIO=str(self.scenario_path), CAPTURE_STATE=str(self.state_path))
        process = subprocess.Popen(
            [sys.executable, str(self.adb), "-s", "emulator-5554", "logcat"],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=env,
        )
        pid_log = Path(f"{self.state_path}.logcat")
        try:
            deadline = time.monotonic() + 5
            while not pid_log.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(pid_log.exists(), "logcat startup read unrelated mutable polling state")
        finally:
            if process.poll() is None:
                process.terminate()
            process.communicate(timeout=5)

    def test_logcat_tail_is_never_orphaned(self):
        marker = self.folder / "teardown.marker"
        self._scenario(
            online_after=0,
            list_after=0,
            names=[],
            logcat_start_delay=1.8,
            logcat_marker=str(marker),
            logcat_moving_names=[],
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}, "pulls": {}}))
        pid_log = Path(f"{self.state_path}.logcat")
        result, _ = self._run_wrapper(
            child_env={
                "CAPTURE_CHILD_WAIT_FOR": str(pid_log),
                "CAPTURE_CHILD_MARKER": str(marker),
                "CAPTURE_CHILD_MARKER_LINGER": "0",
            }
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(pid_log.exists(), "the wrapper never started a logcat tail")
        for line in pid_log.read_text().splitlines():
            try:
                os.kill(int(line), 0)
                self.fail(f"the wrapper orphaned a logcat tail: pid {line}")
            except ProcessLookupError:
                pass

    def test_ambiguous_serials_are_never_pulled(self):
        self._scenario(ambiguous=True, online_after=0, list_after=0)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, calls = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.4"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self._collected())
        self.assertFalse(any("-s" in call for call in calls), "a confused serial was used")
        self.assertIn("ambiguous", result.stderr)

    def test_a_requested_serial_that_never_appears_is_reported(self):
        self._scenario(serial="emulator-9999", online_after=0, list_after=0)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, calls = self._run_wrapper(
            "--serial", "emulator-5554", child_env={"CAPTURE_CHILD_SLEEP": "0.4"}
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self._collected())
        self.assertFalse(any("-s" in call for call in calls))
        self.assertIn("requested serial emulator-5554 never appeared", result.stderr)

    def test_unexpected_names_are_never_pulled(self):
        self._scenario(
            names=["../escape.perfetto-trace", "sub/x.json", "readme.txt", ".hidden.json", TRACE],
            online_after=0,
            list_after=0,
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, _ = self._run_wrapper(child_env={"CAPTURE_CHILD_SLEEP": "0.4"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({TRACE: TRACE_BODY}, self._collected())

    def test_growth_caps_stop_collection_with_a_warning(self):
        other = "CoverGridBenchmark_coverGridFling_iter000_2026-11-08-21-40-00.perfetto-trace"
        self._scenario(
            names=[TRACE, other],
            contents={TRACE: TRACE_TEXT, other: "second trace"},
            online_after=0,
            list_after=0,
        )
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, _ = self._run_wrapper("--max-files", "1", child_env={"CAPTURE_CHILD_SLEEP": "0.4"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({TRACE: TRACE_BODY}, self._collected())
        self.assertIn("size cap", result.stderr)

    def test_stale_destination_is_cleared(self):
        stale = self.output / "emulator-5554" / "stale.perfetto-trace"
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b"from a previous run")
        self._scenario(never_online=True)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        result, _ = self._run_wrapper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self._collected())
        self.assertFalse(stale.exists(), "a stale trace survived into this run")
        self.assertIn("::warning::", result.stderr)

    def _signal_case(self, signum, expected_code):
        self._scenario(never_online=True)
        self.state_path.write_text(json.dumps({"calls": [], "stats": {}}))
        pid_file = self.folder / "child.pid"
        env = dict(
            os.environ,
            CAPTURE_SCENARIO=str(self.scenario_path),
            CAPTURE_STATE=str(self.state_path),
            CAPTURE_CHILD_SLEEP="60",
            CAPTURE_CHILD_PID_FILE=str(pid_file),
        )
        argv = [
            sys.executable,
            str(WRAPPER),
            "--adb",
            str(self.adb),
            "--output",
            str(self.output),
            "--poll-interval",
            "0.05",
            "--child-grace",
            "5",
            "--",
            sys.executable,
            str(self.child),
        ]
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        deadline = time.monotonic() + 10
        while not pid_file.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertTrue(pid_file.exists(), "the fake child never started")
        time.sleep(0.3)
        process.send_signal(signum)
        process.communicate(timeout=20)
        self.assertEqual(expected_code, process.returncode)
        try:
            os.kill(int(pid_file.read_text()), 0)
            self.fail("the wrapper orphaned the child command")
        except ProcessLookupError:
            pass

    def test_sigterm_is_forwarded_and_maps_to_143(self):
        self._signal_case(signal.SIGTERM, 143)

    def test_sigint_is_forwarded_and_maps_to_130(self):
        self._signal_case(signal.SIGINT, 130)


class BenchmarkWorkflowWiringTest(unittest.TestCase):
    def setUp(self):
        nightly = (ROOT / ".github/workflows/nightly.yml").read_text(encoding="utf-8")
        start = nightly.index("  benchmark:\n")
        self.job = nightly[start : nightly.index("\n  baseline-profile:", start)]
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.ci = ci

    def test_benchmark_step_runs_gradle_through_the_live_capture_wrapper(self):
        step = self.job[self.job.index("Run the cold-start and cover-grid Macrobenchmarks") :]
        # run: >- folds the block into one argv line; compare the folded form.
        folded = " ".join(step.split())
        self.assertIn("python3 scripts/ci/benchmark-live-capture.py", folded)
        self.assertIn("--output benchmark/build/outputs/host-collected", folded)
        self.assertIn(" -- ", folded, "the child command must stay an explicit argv")

    def test_benchmark_step_gradle_arguments_are_unchanged(self):
        step = self.job[self.job.index("Run the cold-start and cover-grid Macrobenchmarks") :]
        for argument in (
            "./gradlew :benchmark:bench34BenchmarkReleaseAndroidTest",
            "--max-workers=1",
            "-Pandroid.experimental.testOptions.managedDevices.maxConcurrentDevices=1",
            # A single comma-free suite class: AGP 9.4.1's engine bridge joins
            # instrumentation args into one string and splits it back on
            # commas, so a comma-separated class list cannot reach the runner.
            "-Pandroid.testInstrumentationRunnerArguments.class="
            "ch.lkmc.neutrodyne.benchmark.GatedBenchmarks",
            "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR",
        ):
            self.assertIn(argument, step)

    def test_benchmark_step_gates_on_complete_evidence(self):
        gate = self.job[self.job.index("Require complete benchmark evidence") :]
        self.assertIn("python3 scripts/ci/assert-benchmark-run.py", gate)

    def test_fixture_suite_runs_the_new_tests(self):
        self.assertIn("benchmark-live-capture.test.py", self.ci)
        self.assertIn("assert-benchmark-run.test.py", self.ci)


if __name__ == "__main__":
    unittest.main()
