#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""assert-benchmark-run.py's evidence gate over synthetic artifact trees.

The RED baseline is the actual hosted shape of nightly run 42530327038
(artifact 13639290067): one executed class, 15 cold traces, no metric JSON -
a run that stayed green. The gate must fail that shape and pass only the
complete one (both classes, 15 TTID + 15 TTFD samples, frame samples,
15 + 5 traces).
"""

from pathlib import Path
import importlib.util
import json
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "assert_benchmark_run", ROOT / "scripts/ci/assert-benchmark-run.py"
)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)

COLD_TRACE = "ColdStartBenchmark_coldStartToFeeds_iter{:03d}_2026-11-08-23-17-45.perfetto-trace"
GRID_TRACE = "CoverGridBenchmark_coverGridFling_iter{:03d}_2026-11-08-23-20-00.perfetto-trace"


def _xml(cases: list[tuple[str, str]]) -> str:
    suites = "".join(
        f'<testsuite name="{classname}" tests="1" failures="0" errors="0" skipped="0">'
        f'<testcase name="{name}" classname="{classname}" time="1.0" /></testsuite>'
        for classname, name in cases
    )
    return (
        "<?xml version='1.0' encoding='UTF-8' ?>"
        f'<testsuites tests="{len(cases)}" failures="0" errors="0" skipped="0">{suites}</testsuites>'
    )


def _report(cold_runs: list[float] | None, ttfd_runs: list[float] | None, frame_samples: list[float] | None) -> str:
    cold_metrics = {}
    if cold_runs is not None:
        cold_metrics["timeToInitialDisplayMs"] = {"runs": cold_runs}
    if ttfd_runs is not None:
        cold_metrics["timeToFullDisplayMs"] = {"runs": ttfd_runs}
    cold = {
        "name": "coldStartToFeeds",
        "className": "ch.lkmc.neutrodyne.benchmark.ColdStartBenchmark",
        "metrics": cold_metrics,
    }
    grid = {
        "name": "coverGridFling",
        "className": "ch.lkmc.neutrodyne.benchmark.CoverGridBenchmark",
        "metrics": {},
        "sampledMetrics": {"frameOverrunMs": {"data": frame_samples or []}},
    }
    return json.dumps({"context": {}, "benchmarks": [cold, grid]})


class Tree:
    """A disposable benchmark evidence tree."""

    def __init__(self, cases, traces, report: str | None):
        self._temp = tempfile.TemporaryDirectory(prefix="assert-benchmark-run-")
        base = Path(self._temp.name)
        self.host_collected = base / "outputs" / "host-collected" / "emulator-5554"
        self.host_collected.mkdir(parents=True)
        self.results_dir = base / "outputs" / "androidTest-results" / "managedDevice" / "benchmarkrelease" / "bench34"
        self.results_dir.mkdir(parents=True)
        (self.results_dir / "TEST-bench34.xml").write_text(_xml(cases), encoding="utf-8")
        for name in traces:
            (self.host_collected / name).write_bytes(b"trace fixture")
        if report is not None:
            (self.host_collected / "ch.lkmc.neutrodyne.benchmark-benchmarkData.json").write_text(
                report, encoding="utf-8"
            )

    def close(self):
        self._temp.cleanup()


BOTH_CASES = [
    ("ch.lkmc.neutrodyne.benchmark.ColdStartBenchmark", "coldStartToFeeds"),
    ("ch.lkmc.neutrodyne.benchmark.CoverGridBenchmark", "coverGridFling"),
]
COLD_ONLY = BOTH_CASES[:1]
ALL_TRACES = [COLD_TRACE.format(i) for i in range(15)] + [GRID_TRACE.format(i) for i in range(5)]
COLD_TRACES = [COLD_TRACE.format(i) for i in range(15)]
GOOD_REPORT = _report([100.0] * 15, [300.0] * 15, [1.0, -2.0, 0.5])


class AssertBenchmarkRunTest(unittest.TestCase):
    def _check(self, tree):
        return CHECKER.check(tree.host_collected.parent, tree.results_dir.parent.parent.parent.parent, 15, 5)

    def test_the_hosted_single_class_shape_fails(self):
        tree = Tree(COLD_ONLY, COLD_TRACES, None)
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertTrue(any("CoverGridBenchmark.coverGridFling" in p for p in problems), problems)
        self.assertTrue(any("benchmark JSON missing" in p for p in problems), problems)
        self.assertTrue(any("cover-grid traces retained 0" in p for p in problems), problems)
        self.assertEqual(3, len(problems), problems)

    def test_complete_evidence_passes(self):
        tree = Tree(BOTH_CASES, ALL_TRACES, GOOD_REPORT)
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertEqual([], problems)

    def test_dropped_iterations_are_not_disguised_by_green(self):
        # The ec5 logcat: "Skipping results from iter 5, it didn't capture all
        # metrics" - one dropped iteration means 14 samples, not 15.
        report = _report([100.0] * 14, [300.0] * 15, [1.0, -2.0, 0.5])
        tree = Tree(BOTH_CASES, ALL_TRACES, report)
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertTrue(any("timeToInitialDisplayMs has 14 samples, expected 15" in p for p in problems), problems)

    def test_missing_ttfd_samples_fail(self):
        report = _report([100.0] * 15, None, [1.0])
        tree = Tree(BOTH_CASES, ALL_TRACES, report)
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertTrue(any("timeToFullDisplayMs missing" in p for p in problems), problems)

    def test_truncated_json_fails_as_invalid(self):
        tree = Tree(BOTH_CASES, ALL_TRACES, GOOD_REPORT[: len(GOOD_REPORT) // 2])
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertTrue(any("not valid JSON" in p for p in problems), problems)

    def test_one_missing_trace_fails(self):
        tree = Tree(BOTH_CASES, ALL_TRACES[:-1], GOOD_REPORT)
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertTrue(any("cover-grid traces retained 4, expected 5" in p for p in problems), problems)

    def test_missing_frame_samples_fail(self):
        report = _report([100.0] * 15, [300.0] * 15, [])
        tree = Tree(BOTH_CASES, ALL_TRACES, report)
        try:
            problems = self._check(tree)
        finally:
            tree.close()
        self.assertTrue(any("frameOverrunMs has no samples" in p for p in problems), problems)

    def test_journey_constants_still_mirror_the_gate_defaults(self):
        actions = (ROOT / "benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/JourneyActions.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("COLD_START_ITERATIONS = 15", actions)
        self.assertIn("FRAME_ITERATIONS = 5", actions)


if __name__ == "__main__":
    unittest.main()
