#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require complete Macrobenchmark evidence from a hosted benchmark run.

The nightly `benchmark` job is report-only for emulator timings, but its
retained evidence must still be complete (09 Macrobenchmark and profiles):
both gated classes executed, the cumulative benchmarkData JSON captured with
every iteration's samples (15 TTID and 15 TTFD for the cold start, per
COLD_START_ITERATIONS), and all per-iteration traces retained (15 cold, 5
cover-grid, per FRAME_ITERATIONS). Hosted runs 42515546426-42530327038 lost
some or all of this while staying green; this gate fails the job honestly
instead.

Exit status: 0 when every required artifact and sample count is present,
1 with a numbered error list otherwise.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ElementTree

COLD_CLASS = "ch.lkmc.neutrodyne.benchmark.ColdStartBenchmark"
COLD_TEST = "coldStartToFeeds"
GRID_CLASS = "ch.lkmc.neutrodyne.benchmark.CoverGridBenchmark"
GRID_TEST = "coverGridFling"
CUMULATIVE_JSON = "ch.lkmc.neutrodyne.benchmark-benchmarkData.json"
COLD_TRACE = re.compile(rf"{COLD_CLASS.rsplit('.', 1)[1]}_{COLD_TEST}_iter\d+_.+\.perfetto-trace$")
GRID_TRACE = re.compile(rf"{GRID_CLASS.rsplit('.', 1)[1]}_{GRID_TEST}_iter\d+_.+\.perfetto-trace$")

TTID_METRIC = "timeToInitialDisplayMs"
TTFD_METRIC = "timeToFullDisplayMs"
GRID_METRIC = "frameOverrunMs"

# Mirrors COLD_START_ITERATIONS / FRAME_ITERATIONS in
# benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/JourneyActions.kt;
# assert-benchmark-run.test.py fails when those constants drift.
DEFAULT_COLD_ITERATIONS = 15
DEFAULT_FRAME_ITERATIONS = 5


def _xml_testcases(results_dir: Path) -> set[str]:
    cases: set[str] = set()
    for xml in sorted(results_dir.rglob("TEST-*.xml")):
        try:
            root = ElementTree.parse(xml).getroot()
        except ElementTree.ParseError:
            continue
        for case in root.iter("testcase"):
            cases.add(f"{case.get('classname')}.{case.get('name')}")
    return cases


def _runs(report: dict, test_name: str, metric: str) -> list | None:
    for result in report.get("benchmarks", []):
        if result.get("name") != test_name:
            continue

        entry = result.get("metrics", {}).get(metric)
        if entry is not None:
            return entry.get("runs")

    return None


def _sampled_samples(report: dict, test_name: str, metric: str) -> list | None:
    for result in report.get("benchmarks", []):
        if result.get("name") != test_name:
            continue

        entry = result.get("sampledMetrics", {}).get(metric)
        if entry is not None:
            return entry.get("data")

    return None


def check(host_collected: Path, results_dir: Path, cold_iterations: int, frame_iterations: int) -> list[str]:
    problems: list[str] = []

    cases = _xml_testcases(results_dir)
    for expected in (f"{COLD_CLASS}.{COLD_TEST}", f"{GRID_CLASS}.{GRID_TEST}"):
        if expected not in cases:
            problems.append(f"test case did not execute: {expected}")

    reports = sorted(host_collected.rglob(CUMULATIVE_JSON))
    if not reports:
        problems.append(f"cumulative benchmark JSON missing: no {CUMULATIVE_JSON} under {host_collected}")
        report = None
    else:
        try:
            report = json.loads(reports[-1].read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            problems.append(f"cumulative benchmark JSON is not valid JSON ({reports[-1]}): {error}")
            report = None

    if report is not None:
        if not isinstance(report.get("benchmarks"), list):
            problems.append("cumulative benchmark JSON has no benchmarks list")
            report = None

    if report is not None:
        for metric in (TTID_METRIC, TTFD_METRIC):
            runs = _runs(report, COLD_TEST, metric)
            if runs is None:
                problems.append(f"{COLD_TEST}: metric {metric} missing from the JSON")
            elif len(runs) != cold_iterations:
                problems.append(
                    f"{COLD_TEST}: metric {metric} has {len(runs)} samples, expected {cold_iterations}"
                )

        samples = _sampled_samples(report, GRID_TEST, GRID_METRIC)
        if samples is None:
            problems.append(f"{GRID_TEST}: sampled metric {GRID_METRIC} missing from the JSON")
        elif not samples:
            problems.append(f"{GRID_TEST}: sampled metric {GRID_METRIC} has no samples")

    traces = [path.name for path in host_collected.rglob("*.perfetto-trace")]
    cold_traces = [name for name in traces if COLD_TRACE.fullmatch(name)]
    grid_traces = [name for name in traces if GRID_TRACE.fullmatch(name)]
    if len(cold_traces) != cold_iterations:
        problems.append(
            f"cold-start traces retained {len(cold_traces)}, expected {cold_iterations}"
        )
    if len(grid_traces) != frame_iterations:
        problems.append(
            f"cover-grid traces retained {len(grid_traces)}, expected {frame_iterations}"
        )

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host_collected", type=Path, nargs="?", default=Path("benchmark/build/outputs/host-collected"))
    parser.add_argument("results_dir", type=Path, nargs="?", default=Path("benchmark/build/outputs/androidTest-results"))
    parser.add_argument("--cold-iterations", type=int, default=DEFAULT_COLD_ITERATIONS)
    parser.add_argument("--frame-iterations", type=int, default=DEFAULT_FRAME_ITERATIONS)
    args = parser.parse_args()

    problems = check(args.host_collected, args.results_dir, args.cold_iterations, args.frame_iterations)
    if problems:
        for index, problem in enumerate(problems, start=1):
            print(f"::error::benchmark evidence gap {index}/{len(problems)}: {problem}", file=sys.stderr)
        return 1

    print("benchmark evidence complete: both classes, full JSON samples, all traces")
    return 0


if __name__ == "__main__":
    sys.exit(main())
