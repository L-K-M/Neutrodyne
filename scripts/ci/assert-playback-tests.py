#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Reject absent, skipped, failed or duplicate playback evidence for each requested device."""
import argparse
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

CLASS = "ch.lkmc.neutrodyne.playback.impl.PlaybackServiceTest"
SHORT = {
    "queueFirstStartSeekAndTransitionSave",
    "notificationTransportAndLocalArtwork",
    "focusLossAndNoisyPause",
    "serviceRecreationLoadsPausedAtSavedPosition",
}
BACKGROUND = "deniedBackgroundStartOffersWorkingResume"
LONG = "screenOffStreamsForThirtyMinutesAndAdvances"
HARDENING = "hardeningUiNotificationAndColdMediaKeyStarts"
M5 = {
    CLASS: {
        "sleepEndOfEpisodeKeepsQueueAndMarksPlayedOnce",
        "chaptersAndTimestampSeekThroughRealSession",
    },
    "ch.lkmc.neutrodyne.playback.impl.VideoAsAudioTest": {
        "muxedH264AacAdvancesAudioWithScreenOffAndNoVideoDecoder",
        "videoRequestsAudioAlternateAndKeepsVideoMetadata",
    },
    "ch.lkmc.neutrodyne.playback.impl.CarLibraryDeviceTest": {
        "legacyBrowserListsFourRootsAndQueuedGroupTapKeepsGroupContext",
        "legacyAssistantSearchStartsExactGroupWithoutExplicitIndexOrTime",
        "legacyHardwareSkipUsesIntervalsAndEpisodeModeAdvances",
    },
}
TIMER = "screenOffListeningTimerExpiresAndRestoresVolume"
EMBEDDED = "embeddedId3NeroAndQuickTimeChaptersReachSession"


def expected_cases(profile, device):
    names = {"notificationTransportAndLocalArtwork"} if profile == "release" else set(SHORT)
    if profile != "release" and device != "api26":
        names.add(BACKGROUND)
    if profile == "nightly":
        names.add(TIMER)
        names.add(EMBEDDED)
        if device == "api34":
            names.add(LONG)
    if profile == "api37":
        names.add(HARDENING)
        names.add(TIMER)
        names.add(EMBEDDED)
    return {(CLASS, name) for name in names} | {
        (classname, name) for classname, cases in M5.items() for name in cases
    }


def verify(root, profile, devices):
    for device in devices:
        expected = expected_cases(profile, device)
        evidence = {case: [] for case in expected}
        for report in root.rglob("*.xml"):
            if device != "connected" and not any(
                re.fullmatch(re.escape(device) + r"(?:Debug|Release)AndroidTest", part) or part == device
                for part in report.parts
            ):
                continue
            suite = ET.parse(report).getroot()
            for case in suite.iter("testcase"):
                name = case.get("name", "").split("[")[0]
                key = (case.get("classname"), name)
                if key in evidence:
                    evidence[key].append((report, case))
        for (classname, name), cases in evidence.items():
            if len(cases) != 1:
                raise ValueError(f"{device}: expected one {classname}#{name}, found {len(cases)}")
            report, case = cases[0]
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError(f"{device}: {name} did not pass in {report}")
            if name in (LONG, TIMER):
                minimum = 1800 if name == LONG else 60
                duration = float(case.get("time", "0"))
                if not math.isfinite(duration) or duration < minimum:
                    raise ValueError(f"{device}: {name} has no valid {minimum}-second duration")
        print(f"{device}: {len(expected)} expected playback cases passed ({profile})")



ASSETS = {
    "cbr_128k_2s.mp3", "vbr_xing_2s.mp3", "silence_gaps_3s.mp3", "h264_aac_8s.mp4",
    "chapters_id3.mp3", "chapters_nero.m4b", "chapters_quicktime.m4b", "chapters_both.m4b", "chapters.json",
}


def verify_assets(apk):
    with zipfile.ZipFile(apk) as archive:
        for asset in ASSETS:
            name = f"assets/media/{asset}"
            if name not in archive.namelist() or archive.getinfo(name).file_size == 0:
                raise ValueError(f"{apk}: missing or empty {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("ci", "nightly", "api37", "release"))
    parser.add_argument("root", type=Path)
    parser.add_argument("--device", action="append", required=True)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    args = parser.parse_args()
    try:
        for apk in args.apk:
            verify_assets(apk)
        verify(args.root, args.profile, args.device)
    except (ValueError, ET.ParseError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
