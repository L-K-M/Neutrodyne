#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require named, unskipped first-launch restore evidence from both GMDs."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


TEST_CLASS = "ch.lkmc.neutrodyne.FirstLaunchRestoreDeviceTest"
TEST_CASES = {"firstLaunchRestoresTheSeededSnapshot"}
DEVICES = ("api26", "api36")


def check_device(results, device):
    directory = results / device
    report = directory / f"TEST-{device}.xml"
    # Select each device's own report, never a cross-device aggregate or retry copy.
    if list(directory.rglob("*.xml")) != [report]:
        raise ValueError(f"{device}: expected only {report}")
    root = ET.parse(report).getroot()
    suites = root.findall("testsuite")
    if root.tag != "testsuites" or len(suites) != 1:
        raise ValueError(f"{device}: expected one filtered testsuite")
    suite = suites[0]
    if suite.get("name") != TEST_CLASS:
        raise ValueError(f"{device}: wrong testsuite class")
    devices = [p.get("value") for p in suite.findall("properties/property")
               if p.get("name") == "device"]
    if devices != [f"_app_{device}DebugAndroidTest"]:
        raise ValueError(f"{device}: missing or mismatched device property")
    cases = suite.findall("testcase")
    if (len(cases) != len(TEST_CASES)
            or {case.get("name") for case in cases} != TEST_CASES
            or any(case.get("classname") != TEST_CLASS for case in cases)
            or len(list(root.iter("testcase"))) != len(cases)):
        raise ValueError(f"{device}: missing, duplicate or unexpected test cases")
    if any(node.tag in {"skipped", "failure", "error"} for node in root.iter()):
        raise ValueError(f"{device}: skipped or failed test evidence")
    for node in (root, suite):
        for name, expected in (("tests", len(TEST_CASES)), ("skipped", 0),
                               ("failures", 0), ("errors", 0)):
            if int(node.get(name, "-1")) != expected:
                raise ValueError(f"{device}: inconsistent {name} count")
    print(f"{device}: PASS {TEST_CLASS}: {', '.join(sorted(TEST_CASES))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="managedDevice/debug result directory")
    args = parser.parse_args()
    try:
        for device in DEVICES:
            check_device(args.results, device)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"first-launch-restore: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
