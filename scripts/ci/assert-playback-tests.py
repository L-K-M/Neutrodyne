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
FLAVOR_FOLDERS = {"debug", "release"}
RELEASE_SMOKE = {
    "ch.lkmc.neutrodyne.SmokeTest": (
        "fiveLabelledDestinations",
        "destinationsSurviveRotationAndDarkMode",
        "settingsOpensAndBackReturnsToTheTab",
        "aboutShowsVersionAndAbi",
        "licencesListsLibraries",
    ),
    "ch.lkmc.neutrodyne.YtxReleaseSmokeTest": ("selftestAnswersThroughYtxServiceInTheApp",),
    CLASS: ("notificationTransportAndLocalArtwork",),
}


def device_report(parts, device, flavors):
    """The report's own path must mark the device lane with an allowed flavor."""
    composite = re.compile(
        re.escape(device) + rf"(?:{'|'.join(f.title() for f in sorted(flavors))})AndroidTest"
    )
    for index, part in enumerate(parts):
        if composite.fullmatch(part):
            return True
        if part != device:
            continue
        # managedDevice/<flavor>/<device> and connected/<flavor> layouts; a
        # flavor folder elsewhere in the path proves nothing about this lane.
        after = parts[index + 1].lower() if index + 1 < len(parts) - 1 else ""
        if after in FLAVOR_FOLDERS:
            if after in flavors:
                return True
            continue
        if index and parts[index - 1].lower() in flavors:
            return True
    return False


def verify(root, profile, devices):
    for device in devices:
        # externalBackgroundStartDenialIsUnreachable runs in the external bgstart
        # lane; its aggregate asserts the case there (an instrumented target
        # cannot measure a denial).
        if profile == "release":
            expected = {classname: set(names) for classname, names in RELEASE_SMOKE.items()}
        else:
            expected = {CLASS: set(SHORT)}
            if profile == "nightly" and device in SOAK_DEVICES:
                expected[CLASS].add(LONG)
            if profile == "api37":
                expected[CLASS].add(HARDENING)
        evidence = {
            (classname, name): [] for classname, names in expected.items() for name in names
        }
        flavors = {"release"} if profile == "release" else FLAVOR_FOLDERS
        for report in root.rglob("*.xml"):
            if not device_report(report.parts, device, flavors):
                continue
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError:
                continue  # not a JUnit report (coverage/manifest output); absence check still fails closed
            for case in suite.iter("testcase"):
                key = (case.get("classname"), case.get("name", "").split("[")[0])
                if key in evidence:
                    evidence[key].append((report, case))
        for (classname, name), cases in evidence.items():
            if len(cases) != 1:
                raise ValueError(f"{device}: expected one {classname}#{name}, found {len(cases)}")
            report, case = cases[0]
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError(f"{device}: {classname}#{name} did not pass in {report}")
            if name == LONG:
                duration = float(case.get("time", "0"))
                if not math.isfinite(duration) or duration < 1800:
                    raise ValueError(f"{device}: {classname}#{name} has no valid 30-minute duration")
        print(f"{device}: {len(evidence)} expected cases passed ({profile})")


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
