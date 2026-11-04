#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require executed M7 journeys and shared replay in the XML uploaded by CI."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

REPLAY = {
    "appleSearchAndLookup", "modernAndLegacyChartOrder", "fyydAndIndexFields",
}
HTML = {"allRecordedCandidatesMatch", "htmlReadStopsAtTwoMiB"}
JOURNEYS = {
    "sharedAppleLinkResolvesWithoutWritingUntilConfirmation", "directFeedSchemeWaitsForSubscribe",
    "externalSubscribeWrapperWaitsForSubscribe", "sharedTextWaitsForSubscribe",
}
HOST = {
    "desktopApp/build/test-results/test": {
        "ch.lkmc.neutrodyne.desktop.di.DiscoveryJourneyTest": {
            "previewConfirmationWritesEpisodesAndMembership", "addConfirmationWritesEpisodesAndMembership",
        },
        "ch.lkmc.neutrodyne.desktop.di.DesktopAppGraphTest": {"discoveryEntriesResolveWithTheirIntendedScenes"},
    },
    "app/build/test-results/testDebugUnitTest": {
        "ch.lkmc.neutrodyne.di.AndroidAppGraphTest": {"discoveryEntriesResolveWithTheirIntendedScenes"},
    },
    "feeds/build/test-results/desktopTest": {"ch.lkmc.neutrodyne.feeds.directory.DirectoryReplayTest": REPLAY},
    "core/data/build/test-results/desktopTest": {"ch.lkmc.neutrodyne.core.data.DiscoveryHtmlReplayTest": HTML},
    "core/data/build/test-results/testAndroidHostTest": {
        "ch.lkmc.neutrodyne.feeds.directory.DirectoryReplayTest": REPLAY,
        "ch.lkmc.neutrodyne.core.data.DiscoveryHtmlReplayTest": HTML,
    },
}
for module, classname in (("discover", "DiscoveryScreenshotTest"), ("podcast", "PreviewScreenshotTest")):
    for task in ("desktopTest", "testAndroidHostTest"):
        HOST[f"feature/{module}/build/test-results/{task}"] = {
            f"ch.lkmc.neutrodyne.feature.{module}.{classname}": {"loading", "error", "content"},
        }

DEVICE = {
    "ch.lkmc.neutrodyne.SubscribeJourneyTest": JOURNEYS,
    "ch.lkmc.neutrodyne.DiscoveryFixtureDeviceTest": REPLAY | HTML,
}


EXTERNAL_NAMES = {"browserFailureSuccessfulOpenUndoAndSettings", "recordedSharedHandleAndImportedChannelsStayExternal"}
EXTERNAL_HOST = {
    "desktopApp/build/test-results/test": {"ch.lkmc.neutrodyne.desktop.di.ExternalYouTubeModeTest": EXTERNAL_NAMES},
}
EXTERNAL_DEVICE = {"ch.lkmc.neutrodyne.ExternalYouTubeModeTest": EXTERNAL_NAMES}


def require_cases(reports, expected, label, exact=False):
    evidence = {(classname, name): [] for classname, names in expected.items() for name in names}
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            name = case.get("name", "")
            key = (case.get("classname"), name if exact else name.split("[")[0])
            if key in evidence:
                evidence[key].append((report, case))
    for (classname, name), cases in evidence.items():
        if len(cases) != 1:
            raise ValueError(f"{label}: expected one {classname}#{name}, found {len(cases)}")
        report, case = cases[0]
        if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
            raise ValueError(f"{label}: {classname}#{name} did not pass in {report}")
    print(f"{label}: {len(evidence)} named cases passed, 0 skipped")


def verify(root, devices):
    if not devices:
        for folder, cases in HOST.items():
            require_cases((root / folder).rglob("*.xml"), cases, folder)
        return
    for device in devices:
        reports = (report for report in root.rglob("*.xml") if device == "connected" or any(
            re.fullmatch(re.escape(device) + r"(?:Debug|Release)AndroidTest", part) or part == device
            for part in report.parts
        ))
        require_cases(reports, DEVICE, device)


def verify_external(root, devices, variant="debug"):
    if not devices:
        for folder, cases in EXTERNAL_HOST.items():
            require_cases((root / folder).rglob("*.xml"), cases, folder, exact=True)
        return
    for device in devices:
        reports = []
        for report in root.rglob("*.xml"):
            parts = report.parts
            task = device + variant.capitalize() + "AndroidTest"
            connected = any(parts[i:i + 2] == ("connected", variant) for i in range(len(parts) - 1))
            if (device == "connected" and connected) or (device != "connected" and (
                task in parts or (device in parts and variant in parts)
            )):
                reports.append(report)
        require_cases(reports, EXTERNAL_DEVICE, f"{device}/{variant} E8", exact=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--device", action="append", default=[])
    parser.add_argument("--external-youtube", action="store_true", help="Require exact E8 results instead of M7")
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    args = parser.parse_args()
    try:
        if args.external_youtube:
            verify_external(args.root, args.device, args.variant)
        else:
            verify(args.root, args.device)
    except (ValueError, ET.ParseError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
