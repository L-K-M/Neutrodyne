#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Run a Gradle managed-device benchmark command and pull its public on-device
additional-test-output files to the host while the emulator is still alive.

AGP 9.4.1's managed-device runner (android-test-engine 1.0.1) writes the
Macrobenchmark Perfetto traces and the benchmark JSON to
/sdcard/Android/media/<benchmark package>/additional_test_output/ on the
device, and its host-side collector runs in the test runner's finally - yet
hosted runs reached the CI host with zero of those files (09-quality-and-release.md,
"Macrobenchmark and profiles", Trace retention). The device is torn down
inside the Gradle command, so post-hoc recovery is impossible. This wrapper
runs the child command unchanged, polls that exact public directory through
adb while the child runs, and pulls every stable new benchmark file into an
uploaded build-outputs subtree before teardown.

Invariants (the fixture tests in scripts/ci/tests/benchmark-live-capture.test.py
hold them):
- The child's exit status is returned exactly (a signaled child maps to the
  shell convention 128+signal); wrapper problems never mask it.
- Every adb call is a bounded subprocess; the child is reaped on normal exit
  and on SIGINT/SIGTERM, which are forwarded to it first.
- Traces are unique per iteration and immutable once moved: they are pulled
  once per name into <output>/<serial>/ via .partial + rename and never
  re-pulled.
- The one cumulative benchmarkData JSON (rewritten whole after every test
  method, ResultWriter.appendTestResult) is re-examined on every sweep and on
  every "Moving" file announcement seen in a per-device logcat tail - the
  final rewrite lands inside one poll interval before GMD shutdown - and
  republished by content, so a same-size rewrite counts. A truncated or
  in-flight rewrite keeps the prior complete snapshot and is retried.
  Replacements adjust the stored byte total instead of consuming it twice and
  never count as a new file.
- The destination is cleared at startup so no stale or half-written files
  survive a rerun. The logcat tail is one bounded owned subprocess, restarted
  with a backoff and killed on exit and interruption.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import time
from typing import Callable

DEFAULT_REMOTE_DIR = "/sdcard/Android/media/ch.lkmc.neutrodyne.benchmark/additional_test_output"
DEFAULT_OUTPUT = Path("benchmark/build/outputs/host-collected")

# Flat additional-test-output names the benchmark library produces: per-iteration
# Perfetto traces and the per-run summary JSONs. No directories, no traversal.
EXPECTED_FILE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.(perfetto-trace|json)$")
SAFE_SERIAL = re.compile(r"^[A-Za-z0-9._-]+$")

# The pinned androidx.benchmark 1.5.0 ResultWriter overwrites this one file
# (<instrumentation target package>-benchmarkData.json, self-instrumenting)
# with the cumulative report after EVERY test method; it is not immutable, so
# it is refreshed instead of collected once.
CUMULATIVE_JSON_NAME = "ch.lkmc.neutrodyne.benchmark-benchmarkData.json"

DEVICE_QUERY_TIMEOUT_SECONDS = 10
PULL_TIMEOUT_SECONDS = 60
SLEEP_SLICE_SECONDS = 0.2
FINAL_SWEEP_PAUSE_SECONDS = 1.0
LOGCAT_RESTART_BACKOFF_SECONDS = 5.0
TAIL_STOP_TIMEOUT_SECONDS = 5.0

# The benchmark library announces every file move into the public output
# directory ("Benchmark: Moving <src> to <dir>/<name>", benchmark-common
# Outputs.kt). The final cumulative JSON lands there less than one poll
# interval before GMD shutdown, so the tail is the only channel fast enough.
MOVE_EVENT = re.compile(rf"Moving \S+ to {re.escape(DEFAULT_REMOTE_DIR)}/([A-Za-z0-9._-]+)")


def _default_adb() -> str:
    override = os.environ.get("BENCHMARK_CAPTURE_ADB")
    if override:
        return override

    # The same adb the Gradle plugin uses, so both sides share one adb server
    # and one view of the emulator's serial.
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidate = Path(android_home) / "platform-tools" / "adb"
        if candidate.is_file():
            return str(candidate)

    return "adb"


def _run_adb(adb: str, argv: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            [adb, *argv],
            capture_output=True,
            text=True,
            timeout=DEVICE_QUERY_TIMEOUT_SECONDS,
            check=False,
        )
    except (subprocess.TimeoutExpired, OSError):
        return None


def _valid_report(data: bytes) -> dict | None:
    """The pinned library's file is one BenchmarkData JSON object with a
    'benchmarks' list (androidx.benchmark.json.BenchmarkData, Moshi-serialized);
    a truncated or in-flight rewrite fails this."""
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None

    if isinstance(parsed, dict) and isinstance(parsed.get("benchmarks"), list):
        return parsed

    return None


class CaptureState:
    """Counts that turn a zero-file run into an explained diagnostic gap."""

    def __init__(self) -> None:
        self.query_failures = 0
        self.device_seen = False
        self.ambiguous_seen = False
        self.requested_serial_missing = ""
        self.listing_failures = 0
        self.listing_successes = 0
        self.pull_failures = 0
        self.invalid_rewrites = 0
        self.tail_failures = 0
        self.unstable_left: set[str] = set()
        self.capped_out = False

    def summary(self) -> str:
        reasons = []
        if self.ambiguous_seen:
            reasons.append("multiple adb devices were connected, serial left ambiguous")
        elif self.requested_serial_missing:
            reasons.append(self.requested_serial_missing)
        elif not self.device_seen and self.query_failures:
            reasons.append(f"every adb query failed ({self.query_failures} attempts)")
        elif not self.device_seen:
            reasons.append("adb never reported a device")
        elif self.listing_successes == 0:
            reasons.append(f"directory listing never succeeded ({self.listing_failures} attempts)")
        elif self.pull_failures:
            reasons.append(f"{self.pull_failures} pulls failed")
        if self.unstable_left:
            reasons.append(f"{len(self.unstable_left)} files never stabilized")
        if self.invalid_rewrites:
            reasons.append(f"{self.invalid_rewrites} cumulative-JSON rewrites failed validation")
        if self.tail_failures:
            reasons.append(f"the logcat event tail failed {self.tail_failures} times")
        if self.capped_out:
            reasons.append("capture cap reached")
        if not reasons:
            reasons.append("no matching files appeared on the device")
        return "; ".join(reasons)


class LiveCollector:
    def __init__(self, args: argparse.Namespace) -> None:
        self.adb = args.adb
        self.serial_arg = args.serial
        self.remote_dir = args.remote_dir
        self.output = args.output
        self.max_files = args.max_files
        self.max_total_bytes = args.max_total_bytes
        self.collected: set[str] = set()
        self.total_bytes = 0
        self.state = CaptureState()
        self.collector_failure = ""
        self.report_bytes: bytes | None = None
        self.report_device_size: int | None = None
        self._tail: subprocess.Popen[bytes] | None = None
        self._tail_serial: str | None = None
        self._tail_buffer = b""
        self._tail_last_start = 0.0

    def ensure_tail(self, serial: str) -> None:
        """Start (or restart after failure backoff) the per-device logcat tail
        that turns the library's file-move announcements into immediate
        captures. One owned process at most; failures only count."""
        if self._tail is not None and self._tail.poll() is None and self._tail_serial == serial:
            return

        if self._tail is not None:
            self._tail_wait()

        if time.monotonic() - self._tail_last_start < LOGCAT_RESTART_BACKOFF_SECONDS:
            return

        self._tail_last_start = time.monotonic()
        try:
            self._tail = subprocess.Popen(
                [self.adb, "-s", serial, "logcat"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            self._tail_serial = serial
            self._tail_buffer = b""
        except OSError:
            self._tail = None
            self._tail_serial = None
            self.state.tail_failures += 1

    def _tail_wait(self) -> None:
        if self._tail is None:
            return

        if self._tail.poll() is None:
            self._tail.terminate()
            try:
                self._tail.wait(timeout=TAIL_STOP_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                self._tail.kill()
                self._tail.wait()
        elif self._tail.returncode not in (0,):
            self.state.tail_failures += 1

        if self._tail.stdout is not None:
            self._tail.stdout.close()
        self._tail = None
        self._tail_serial = None

    def shutdown_tail(self) -> None:
        if self._tail is not None:
            self._tail_wait()

    def drain_events(self) -> None:
        """Collect ready logcat lines and capture the announced files at once.
        Select keeps this non-blocking inside the poll-sleep slices."""
        tail = self._tail
        if tail is None or tail.stdout is None or tail.poll() is not None:
            return

        try:
            ready, _, _ = select.select([tail.stdout], [], [], 0)
        except (OSError, ValueError):
            return

        if not ready:
            return

        try:
            chunk = os.read(tail.stdout.fileno(), 65536)
        except OSError:
            return

        if not chunk:
            return

        self._tail_buffer += chunk
        while b"\n" in self._tail_buffer:
            line_bytes, self._tail_buffer = self._tail_buffer.split(b"\n", 1)
            self._handle_move_line(line_bytes.decode("utf-8", errors="replace"))

    def _handle_move_line(self, line: str) -> None:
        if "Moving " not in line or self._tail_serial is None:
            return

        match = MOVE_EVENT.search(line)
        if match is None or not EXPECTED_FILE.fullmatch(match.group(1)):
            return

        name = match.group(1)
        if name == CUMULATIVE_JSON_NAME:
            self.refresh_cumulative(self._tail_serial)
        elif name not in self.collected:
            self.capture_file(self._tail_serial, name)

    def resolve_serial(self) -> str | None:
        result = _run_adb(self.adb, ["devices"])
        if result is None:
            self.state.query_failures += 1
            return None

        if result.returncode != 0:
            self.state.query_failures += 1
            return None

        online: list[str] = []
        for line in result.stdout.splitlines():
            line = line.strip()
            if not line or line.startswith(("List of devices", "*")):
                continue
            fields = line.split()
            if len(fields) >= 2 and fields[1] == "device":
                online.append(fields[0])

        self.state.device_seen = self.state.device_seen or bool(online)
        if self.serial_arg:
            if self.serial_arg in online:
                return self.serial_arg

            if online:
                self.state.requested_serial_missing = (
                    f"requested serial {self.serial_arg} never appeared online"
                )
            return None

        if len(online) == 1:
            return online[0]

        if len(online) > 1:
            self.state.ambiguous_seen = True
        return None

    def list_names(self, serial: str) -> list[str] | None:
        result = _run_adb(self.adb, ["-s", serial, "shell", "ls", "-1", self.remote_dir])
        if result is None or result.returncode != 0:
            self.state.listing_failures += 1
            return None

        self.state.listing_successes += 1
        return [name for name in result.stdout.split() if EXPECTED_FILE.fullmatch(name)]

    def remote_size(self, serial: str, name: str) -> int | None:
        result = _run_adb(
            self.adb, ["-s", serial, "shell", "stat", "-c", "%s", f"{self.remote_dir}/{name}"]
        )
        if result is None or result.returncode != 0:
            return None

        try:
            return int(result.stdout.strip().strip("\r"))
        except ValueError:
            return None

    def capture_file(self, serial: str, name: str) -> bool:
        """Pull one name and accept it only if the pulled size equals the size
        reported just before the pull; a file still being written differs and
        is retried on a later sweep."""
        size = self.remote_size(serial, name)
        if size is None:
            self.state.unstable_left.add(name)
            return False

        target_dir = self.output / serial
        target_dir.mkdir(parents=True, exist_ok=True)
        partial = target_dir / f"{name}.partial"
        try:
            try:
                result = subprocess.run(
                    [self.adb, "-s", serial, "pull", f"{self.remote_dir}/{name}", str(partial)],
                    capture_output=True,
                    text=True,
                    timeout=PULL_TIMEOUT_SECONDS,
                    check=False,
                )
            except (subprocess.TimeoutExpired, OSError):
                self.state.pull_failures += 1
                return False

            if result.returncode != 0:
                self.state.pull_failures += 1
                return False

            if not partial.is_file() or partial.stat().st_size != size:
                self.state.unstable_left.add(name)
                return False

            partial.replace(target_dir / name)
        finally:
            if partial.exists():
                partial.unlink()

        self.collected.add(name)
        self.total_bytes += size
        print(
            f"benchmark-live-capture: collected {serial}:{name} ({size} bytes)",
            file=sys.stderr,
            flush=True,
        )
        return True

    def refresh_cumulative(self, serial: str) -> None:
        """Re-pull the single cumulative benchmarkData JSON the pinned library
        rewrites whole after every test; publish the latest complete rewrite
        through .partial + atomic replace. A truncated or in-flight rewrite
        keeps the prior complete snapshot and is retried next sweep."""
        size = self.remote_size(serial, CUMULATIVE_JSON_NAME)
        if size is None:
            self.state.unstable_left.add(CUMULATIVE_JSON_NAME)
            return

        self.report_device_size = size
        target_dir = self.output / serial
        target_dir.mkdir(parents=True, exist_ok=True)
        partial = target_dir / f"{CUMULATIVE_JSON_NAME}.partial"
        try:
            try:
                result = subprocess.run(
                    [self.adb, "-s", serial, "pull", f"{self.remote_dir}/{CUMULATIVE_JSON_NAME}", str(partial)],
                    capture_output=True,
                    text=True,
                    timeout=PULL_TIMEOUT_SECONDS,
                    check=False,
                )
            except (subprocess.TimeoutExpired, OSError):
                self.state.pull_failures += 1
                return

            if result.returncode != 0:
                self.state.pull_failures += 1
                return

            if not partial.is_file() or partial.stat().st_size != size:
                self.state.unstable_left.add(CUMULATIVE_JSON_NAME)
                return

            data = partial.read_bytes()
            report = _valid_report(data)
            if report is None:
                self.state.invalid_rewrites += 1
                print(
                    f"benchmark-live-capture: keeping prior {CUMULATIVE_JSON_NAME}; "
                    "the device rewrite failed validation",
                    file=sys.stderr,
                    flush=True,
                )
                return

            if data == self.report_bytes:
                return

            partial.replace(target_dir / CUMULATIVE_JSON_NAME)
        finally:
            if partial.exists():
                partial.unlink()

        # A replacement swaps stored bytes; it is not another file and does not
        # consume the byte budget twice.
        previous = len(self.report_bytes) if self.report_bytes is not None else 0
        first = CUMULATIVE_JSON_NAME not in self.collected
        self.report_bytes = data
        self.collected.add(CUMULATIVE_JSON_NAME)
        self.total_bytes += size - previous
        kind = "collected" if first else "refreshed"
        print(
            f"benchmark-live-capture: {kind} {serial}:{CUMULATIVE_JSON_NAME} "
            f"({size} bytes, {len(report['benchmarks'])} tests)",
            file=sys.stderr,
            flush=True,
        )

    def sweep(self, serial: str) -> None:
        names = self.list_names(serial)
        if names is None:
            return

        self.state.unstable_left.clear()
        # The cumulative JSON is refreshed even at the file cap and even when
        # already collected: its later rewrites carry the later tests' results.
        if CUMULATIVE_JSON_NAME in names:
            self.refresh_cumulative(serial)

        for name in names:
            if name == CUMULATIVE_JSON_NAME:
                continue

            if name in self.collected:
                continue

            if len(self.collected) >= self.max_files or self.total_bytes >= self.max_total_bytes:
                self.state.capped_out = True
                return

            self.capture_file(serial, name)

    def sweep_resolved(self) -> bool | None:
        """One sweep against the resolved serial; False when no single serial
        could be resolved (nothing to do this round). A collector-side
        exception disables further sweeps without ever masking the child's
        exit status."""
        try:
            serial = self.resolve_serial()
            if serial is None or not SAFE_SERIAL.fullmatch(serial):
                return False

            self.ensure_tail(serial)
            self.sweep(serial)
            return True
        except Exception as error:  # noqa: BLE001 - never mask the child's status
            self.collector_failure = f"collector aborted: {error!r}"
            print(
                f"benchmark-live-capture: {self.collector_failure}",
                file=sys.stderr,
                flush=True,
            )
            return None

    def finish_line(self) -> None:
        if not self.collected:
            detail = self.state.summary()
            if self.collector_failure:
                detail = f"{self.collector_failure}; {detail}"
            print(
                "::warning::benchmark-live-capture: no benchmark files were captured "
                f"(diagnostic gap; {detail}); expected {self.remote_dir} "
                f"files under {self.output}",
                file=sys.stderr,
                flush=True,
            )
        elif self.state.capped_out:
            print(
                "::warning::benchmark-live-capture: capture stopped at the size cap with "
                f"{len(self.collected)} files; later device files were not collected",
                file=sys.stderr,
                flush=True,
            )
        if self.report_bytes is not None and self.report_device_size not in (
            None,
            len(self.report_bytes),
        ):
            print(
                f"::warning::benchmark-live-capture: the last on-device rewrite of "
                f"{CUMULATIVE_JSON_NAME} ({self.report_device_size} bytes) was never "
                f"captured; the retained snapshot ({len(self.report_bytes)} bytes) is "
                "from an earlier test",
                file=sys.stderr,
                flush=True,
            )


def _sleep_responsive(seconds: float, stop: Callable[[], bool], tick: Callable[[], None] | None = None) -> None:
    deadline = time.monotonic() + seconds
    while not stop() and time.monotonic() < deadline:
        if tick is not None:
            tick()

        time.sleep(min(SLEEP_SLICE_SECONDS, max(0.0, deadline - time.monotonic())))


def _exit_code(returncode: int | None) -> int:
    if returncode is None or returncode < 0:
        return 128 + (-(returncode or 0) or 0)
    return returncode


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--adb", default=_default_adb(), help="adb binary to use")
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help="host directory for collected files (cleared at startup)",
    )
    parser.add_argument(
        "--serial", help="explicit device serial; required when several are online"
    )
    parser.add_argument("--remote-dir", default=DEFAULT_REMOTE_DIR)
    parser.add_argument(
        "--poll-interval", type=float, default=5.0, help="seconds between device sweeps"
    )
    parser.add_argument("--final-sweeps", type=int, default=3, help="sweeps after the child exits")
    parser.add_argument("--max-files", type=int, default=200)
    parser.add_argument("--max-total-bytes", type=int, default=512 * 1024 * 1024)
    parser.add_argument(
        "--child-grace", type=float, default=30.0, help="seconds to wait for a signaled child"
    )
    parser.add_argument("child", nargs=argparse.REMAINDER, help="child command after --")
    args = parser.parse_args()

    child_argv = args.child[1:] if args.child and args.child[0] == "--" else args.child
    if not child_argv:
        parser.error("a child command is required after --")

    # The wrapper owns <output> exclusively (a disposable build-outputs
    # subtree); clearing it prevents stale files from a previous run mixing
    # into this one.
    if args.output.exists():
        shutil.rmtree(args.output)
    args.output.mkdir(parents=True, exist_ok=True)

    try:
        child = subprocess.Popen(child_argv)
    except OSError as error:
        print(f"benchmark-live-capture: cannot start child: {error}", file=sys.stderr)
        return 127

    collector = LiveCollector(args)
    stop = [False]
    received = [0]

    def _on_signal(signum: int, _frame: object) -> None:
        if not received[0]:
            received[0] = signum
        stop[0] = True
        try:
            child.send_signal(signum)
        except ProcessLookupError:
            pass

    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    def _stopped() -> bool:
        return stop[0] or child.poll() is not None

    try:
        while child.poll() is None and not stop[0]:
            collector.sweep_resolved()
            _sleep_responsive(args.poll_interval, _stopped, collector.drain_events)

        if stop[0]:
            # The child got the signal too (same process group on CI, plus the
            # explicit forward); wait for it, escalating once past the grace.
            try:
                child.wait(timeout=args.child_grace)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()

        # Teardown normally happens inside the child, so these sweeps only pay off
        # when the device outlives it (early failure, failed teardown); a device
        # that is already gone fails fast and ends the loop.
        for _ in range(max(0, args.final_sweeps)):
            if not collector.sweep_resolved() or collector.state.capped_out:
                break
            _sleep_responsive(FINAL_SWEEP_PAUSE_SECONDS, lambda: False, collector.drain_events)
    finally:
        collector.shutdown_tail()

    collector.finish_line()
    if received[0]:
        return 128 + received[0]
    return _exit_code(child.returncode)


if __name__ == "__main__":
    sys.exit(main())
