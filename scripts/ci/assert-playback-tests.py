#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require playback/download device evidence and inspect M6a backup exclusion."""
import argparse
import math
from pathlib import Path
import re
import tarfile
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


DOWNLOAD_CLASS = "ch.lkmc.neutrodyne.DownloadJourneyTest"
JOURNEY = "downloadServerOffPlaysLocalArtworkChaptersAndDefersDelete"
WORKER = "visibleApi33DownloadUsesDataSyncForegroundWorker"
UIDT = "backgroundUidtBesidePlaybackForTwentyMinutes"


def download_cases(profile, device):
    names = {JOURNEY}
    if profile in ("nightly", "downloads") and device == "api33":
        names.add(WORKER)
    if profile in ("nightly", "downloads") and device in ("api34", "api36"):
        names.add(UIDT)
    return {(DOWNLOAD_CLASS, name) for name in names}


def expected_cases(profile, device):
    if profile == "downloads":
        return download_cases(profile, device)
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
    } | download_cases(profile, device)


def verify(root, profile, devices):
    durations = {LONG: 1800, TIMER: 60, UIDT: 1200}
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
            if name in durations:
                minimum = durations[name]
                duration = float(case.get("time", "0"))
                if not math.isfinite(duration) or duration < minimum:
                    raise ValueError(f"{device}: {name} has no valid {minimum}-second duration")
        print(f"{device}: {len(expected)} expected device cases passed ({profile})")



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


def verify_backup(archive):
    prefix = "apps/ch.lkmc.neutrodyne/"
    allowed = {"f/backup/auto-snapshot.zip", "f/datastore/settings.preferences_pb"}
    seen = set()
    with tarfile.open(archive, "r:*") as backup:
        for entry in backup:
            if entry.isdir():
                continue
            if not entry.name.startswith(prefix):
                raise ValueError(f"unexpected backup package: {entry.name}")
            path = entry.name[len(prefix):]
            if path in ("_manifest", "_meta") or path.startswith("a/"):
                continue
            if path not in allowed or not entry.isfile() or entry.size <= 0 or path in seen:
                raise ValueError(f"unexpected, empty or duplicate backup data: {path}")
            seen.add(path)
    if seen != allowed:
        raise ValueError(f"backup missing included data: {allowed - seen}")
    print("bmgr: both included files present; no media/database/device data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("ci", "nightly", "api37", "release", "downloads", "bmgr"))
    parser.add_argument("root", type=Path)
    parser.add_argument("--device", action="append", default=[])
    parser.add_argument("--apk", type=Path, action="append", default=[])
    args = parser.parse_args()
    try:
        if args.profile == "bmgr":
            verify_backup(args.root)
            return
        if not args.device:
            parser.error("device evidence requires --device")
        for apk in args.apk:
            verify_assets(apk)
        verify(args.root, args.profile, args.device)
    except (ValueError, ET.ParseError, OSError, zipfile.BadZipFile, tarfile.TarError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
