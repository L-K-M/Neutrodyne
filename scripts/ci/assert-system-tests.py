#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require named, unique, non-skipped system tests from the requested device and variant."""
import argparse
import math
from pathlib import Path
import xml.etree.ElementTree as ET

PACKAGE = "ch.lkmc.neutrodyne.benchmark."
CASES = {
    "process-death": (PACKAGE + "ProcessDeathResumeTest", "killReconcilesSystemAndResumesFromPartLength"),
    "smoke": (PACKAGE + "ReleaseSmokeJourneyTest", "fiveDestinationsSubscribePlayDownloadAndBack"),
}
VARIANT = "benchmarkRelease"


def verify(root, profile, device):
    if not root.is_dir():
        raise ValueError(f"Missing result directory: {root}")
    expected = CASES[profile]
    matches = []
    reports = []
    # AGP 9.4.1's configured outputs: managedDevice/benchmarkrelease/<device>,
    # or connected/benchmarkRelease/<serial>. Require the variant and device pair.
    for report in root.rglob("*.xml"):
        parts = report.relative_to(root).parts
        if device == "connected":
            selected = parts[:2] == ("connected", VARIANT)
        else:
            selected = parts[:3] == ("managedDevice", VARIANT.lower(), device)
        if not selected:
            continue
        reports.append(report)
        suite = ET.parse(report).getroot()
        for case in suite.iter("testcase"):
            # This suite has no parameterized cases: suffixes cannot masquerade as evidence.
            key = (case.get("classname"), case.get("name"))
            if key == expected:
                matches.append((report, case))
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError(f"{device}: non-passing case {key} in {report}")
        for group in suite.iter("testsuite"):
            for attr in ("failures", "errors", "skipped", "disabled"):
                value = group.get(attr, "0")
                if not value.isdigit() or int(value) != 0:
                    raise ValueError(f"{device}: {attr}={value} in {report}")
    if not reports:
        raise ValueError(f"{device}: no {VARIANT} JUnit reports found in {root}")
    if len(matches) != 1:
        raise ValueError(f"{device}: expected exactly one {expected}, found {len(matches)}")
    report, case = matches[0]
    duration = float(case.get("time", "0"))
    minimum = 10 if profile == "smoke" else 0
    if not math.isfinite(duration) or duration <= 0 or duration < minimum:
        raise ValueError(f"{device}: invalid duration {duration} for {profile} in {report}")
    print(f"{device}: 1 named {profile} case passed against {VARIANT}; published release coverage: false")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=CASES)
    parser.add_argument("root", type=Path)
    parser.add_argument("--device", required=True, choices=("bench34", "api36", "connected"))
    args = parser.parse_args()
    try:
        verify(args.root, args.profile, args.device)
    except (OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
