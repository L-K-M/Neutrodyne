#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Preserve desktop unit-test crash reports for the unit-reports artifact.

Desktop tests build their AppDirs under java.io.tmpdir (TempAppDirs.kt), so a
crash file lands at <tmpdir>/<root>/state/crash-<UTC>.txt — outside the
unit-reports glob, where a worker that exits without writing test XML leaves
no evidence. The unit job pins TMPDIR to a dedicated root, so every immediate
child directory belongs to this run's JVMs. Each matching
<root>/state/crash-*.txt regular file is copied to <destination>/<root name>/
— distinct roots keep same-named reports apart — inside the existing
**/build/test-results/ artifact glob. Missing roots and missing reports are
normal (a green run leaves nothing); symlinked entries and unrelated names
are ignored, caps bound the artifact, and every skip or failed copy is
reported rather than hidden.
"""

import argparse
from dataclasses import dataclass, field
from pathlib import Path
import shutil
import sys

CRASH_GLOB = "crash-*.txt"  # DesktopCrashReporter's crash-<UTC>.txt
MAX_ROOTS = 256  # far above the handful of roots a test run creates
MAX_FILES = 64  # a process death leaves a few files; bound the pathological case
MAX_FILE_BYTES = 2 * 1024 * 1024  # a report is a redacted stack plus recent log lines
MAX_TOTAL_BYTES = 16 * 1024 * 1024


@dataclass
class PreserveResult:
    copied: int = 0
    bytes_copied: int = 0
    roots: int = 0
    skipped: int = 0
    failed: int = 0
    warnings: list = field(default_factory=list)

    def warn(self, message: str) -> None:
        self.warnings.append(message)
        print(f"warning: {message}", file=sys.stderr, flush=True)


def preserve_crash_reports(source: Path, destination: Path) -> PreserveResult:
    """Copy every <source>/<root>/state/crash-*.txt to <destination>/<root>/."""
    result = PreserveResult()
    if not source.is_dir():
        return result
    for root in sorted(source.iterdir()):
        if root.is_symlink() or not root.is_dir():
            continue
        if result.roots >= MAX_ROOTS:
            result.warn(f"temp root bound {MAX_ROOTS} reached; remaining roots unscanned")
            break
        result.roots += 1
        state = root / "state"
        if state.is_symlink() or not state.is_dir():
            continue
        for report in sorted(state.glob(CRASH_GLOB)):
            if report.is_symlink() or not report.is_file():
                continue
            try:
                size = report.stat().st_size
            except OSError as error:
                result.failed += 1
                result.warn(f"could not stat {report}: {error}")
                continue
            if result.copied >= MAX_FILES:
                result.skipped += 1
                result.warn(f"report bound {MAX_FILES} reached; {report} not preserved")
                continue
            if size > MAX_FILE_BYTES:
                result.skipped += 1
                result.warn(
                    f"{report} is {size} bytes, over the {MAX_FILE_BYTES}-byte report bound"
                )
                continue
            if result.bytes_copied + size > MAX_TOTAL_BYTES:
                result.skipped += 1
                result.warn(f"total bound {MAX_TOTAL_BYTES} bytes reached; {report} not preserved")
                continue
            target = destination / root.name / report.name
            try:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(report, target)
            except OSError as error:
                result.failed += 1
                result.warn(f"could not copy {report} to {target}: {error}")
                continue
            result.copied += 1
            result.bytes_copied += size
    return result


def _main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="the temp root the unit step pins via TMPDIR")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    result = preserve_crash_reports(args.source, args.destination)
    print(
        f"preserved {result.copied} desktop crash report(s) "
        f"({result.bytes_copied} bytes) from {result.roots} scanned temp root(s); "
        f"{result.skipped} skipped, {result.failed} failed",
        flush=True,
    )
    if result.failed:
        sys.exit(1)


if __name__ == "__main__":
    _main()
