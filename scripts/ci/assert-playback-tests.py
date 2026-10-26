#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Reject absent, skipped, failed or duplicate playback evidence for each requested device."""
import argparse
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

CLASS = "ch.lkmc.neutrodyne.playback.impl.PlaybackServiceTest"
SHORT = {
    "queueFirstStartSeekAndTransitionSave",
    "notificationTransportAndLocalArtwork",
    "focusLossAndNoisyPause",
    "serviceRecreationLoadsPausedAtSavedPosition",
}
LONG = "screenOffStreamsForThirtyMinutesAndAdvances"
HARDENING = "hardeningUiNotificationAndColdMediaKeyStarts"
SOAK_DEVICES = {"api34"}


def verify(root, profile, devices):
    for device in devices:
        # externalBackgroundStartDenialIsUnreachable runs in the external bgstart
        # lane; its aggregate asserts the case there (an instrumented target
        # cannot measure a denial).
        expected = {"notificationTransportAndLocalArtwork"} if profile == "release" else set(SHORT)
        if profile == "nightly" and device in SOAK_DEVICES:
            expected.add(LONG)
        if profile == "api37":
            expected.add(HARDENING)
        evidence = {name: [] for name in expected}
        flavors = ("Release",) if profile == "release" else ("Debug", "Release")
        for report in root.rglob("*.xml"):
            if not any(
                re.fullmatch(re.escape(device) + rf"(?:{'|'.join(flavors)})AndroidTest", part) or part == device
                for part in report.parts
            ):
                continue
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError:
                continue  # not a JUnit report (coverage/manifest output); absence check still fails closed
            for case in suite.iter("testcase"):
                if case.get("classname") != CLASS:
                    continue
                name = case.get("name", "").split("[")[0]
                if name in evidence:
                    evidence[name].append((report, case))
        for name, cases in evidence.items():
            if len(cases) != 1:
                raise ValueError(f"{device}: expected one {CLASS}#{name}, found {len(cases)}")
            report, case = cases[0]
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError(f"{device}: {name} did not pass in {report}")
            if name == LONG:
                duration = float(case.get("time", "0"))
                if not math.isfinite(duration) or duration < 1800:
                    raise ValueError(f"{device}: {name} has no valid 30-minute duration")
        print(f"{device}: {len(expected)} expected playback cases passed ({profile})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("ci", "nightly", "api37", "release"))
    parser.add_argument("root", type=Path)
    parser.add_argument("--device", action="append", required=True)
    args = parser.parse_args()
    try:
        verify(args.root, args.profile, args.device)
    except (ValueError, ET.ParseError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
