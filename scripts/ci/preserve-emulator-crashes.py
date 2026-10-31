#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Retain local Crashpad reports before the next emulator deletes them."""

import argparse
import os
from pathlib import Path
import shutil
import sys
import time

_POLL_INTERVAL_SECONDS = 1


def _preserve_reports(source: Path, destination: Path) -> None:
    for database in source.glob("emu-crash-*.db"):
        if database.is_symlink():
            continue

        for report in database.rglob("*.dmp"):
            if report.is_symlink() or not report.is_file():
                continue

            target = destination / report.name
            try:
                original = report.stat()
                saved = target.stat() if target.exists() else None
                if saved and (saved.st_size, saved.st_mtime_ns) == (original.st_size, original.st_mtime_ns):
                    continue

                partial = target.with_suffix(".partial")
                shutil.copy2(report, partial)
                partial.replace(target)
                print(f"Preserved emulator crash report: {report.name} ({target.stat().st_size} bytes)", flush=True)
            except FileNotFoundError:
                # Emulator cleanup may race a snapshot; retry while the report remains.
                print(f"Emulator removed report during snapshot: {report}", file=sys.stderr, flush=True)


def _main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("stop_file", type=Path)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=True)
    parent = os.getppid()

    while not args.stop_file.exists() and os.getppid() == parent:
        _preserve_reports(args.source, args.destination)
        time.sleep(_POLL_INTERVAL_SECONDS)

    _preserve_reports(args.source, args.destination)


if __name__ == "__main__":
    _main()
