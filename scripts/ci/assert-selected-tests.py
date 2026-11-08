#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require every explicitly selected class to report executed device cases.

A class filter is evidence only when each named class ran at least one case
and no case in the device lane carries a skipped, failure or error marker:
an empty selection or a fully deselected run must fail, never read as a pass.
Lane matching follows assert-discovery-tests.py — a report belongs to the lane
when a path part is the device name or the `<device><Variant>AndroidTest` task.
"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

BAD_CASE_TAGS = ("skipped", "failure", "error")


def _device_report(parts, device):
    task = re.compile(re.escape(device) + r"(?:Debug|Release)AndroidTest")
    return any(part == device or task.fullmatch(part) for part in parts)


def require_no_initialization_errors(root, device):
    """Fail on GMD logcat runs of a class JUnit4 refused to construct.

    The orchestrator runs a pseudo-case `initializationError` that the
    managed-device result XML drops while the Gradle task stays green
    (2026-11-04). The per-test logcat file is the only surviving evidence.
    """
    hidden = sorted(
        {
            path.name[len("logcat-"):-len(".txt")]
            for path in root.rglob("logcat-*-initializationError.txt")
            if _device_report(path.parts, device)
        }
    )
    if hidden:
        raise ValueError(f"{device}: classes reported initializationError, never executed: {', '.join(hidden)}")


def verify(root, device, classes, minimum_cases=None):
    classes = set(classes)
    executed = {classname: 0 for classname in classes}
    bad = []
    for report in root.rglob("*.xml"):
        if not _device_report(report.parts, device):
            continue
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            continue  # not a JUnit report; absence still fails closed
        for case in suite.iter("testcase"):
            if any(case.find(tag) is not None for tag in BAD_CASE_TAGS):
                bad.append(f"{case.get('classname')}#{case.get('name')} in {report}")
            elif case.get("classname") in executed:
                executed[case.get("classname")] += 1
    if bad:
        raise ValueError(f"{device}: skipped, failed or errored cases: {', '.join(sorted(bad))}")
    missing = sorted(name for name, count in executed.items() if count == 0)
    if missing:
        raise ValueError(f"{device}: selected classes executed no case: {', '.join(missing)}")
    total = sum(executed.values())
    if minimum_cases is not None and total < minimum_cases:
        raise ValueError(
            f"{device}: selected classes executed {total} cases, fewer than the required {minimum_cases} - "
            "a truncated lane must not read as a full pass"
        )
    print(f"{device}: {total} cases passed across {len(classes)} selected classes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--device", required=True, help="managed-device or connected lane name, e.g. api36")
    parser.add_argument(
        "--require-class",
        action="append",
        required=True,
        dest="classes",
        metavar="FQCN",
        help="class that must report at least one executed case (repeatable)",
    )
    parser.add_argument(
        "--require-cases",
        type=int,
        metavar="N",
        help="fail unless the selected classes report at least N executed cases in total",
    )
    args = parser.parse_args()
    try:
        require_no_initialization_errors(args.root, args.device)
        verify(args.root, args.device, args.classes, minimum_cases=args.require_cases)
    except (ValueError, ET.ParseError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
