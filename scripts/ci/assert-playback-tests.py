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
VIDEO_CLASS = "ch.lkmc.neutrodyne.playback.impl.VideoAsAudioTest"
CAR_CLASS = "ch.lkmc.neutrodyne.playback.impl.CarLibraryDeviceTest"
SHORT = {
    "queueFirstStartSeekAndTransitionSave",
    "notificationTransportAndLocalArtwork",
    "focusLossAndNoisyPause",
    "serviceRecreationLoadsPausedAtSavedPosition",
    "sleepEndOfEpisodeKeepsQueueAndMarksPlayedOnce",
    "sleepEndOfEpisodeRespectsMeteredBoundary",
    "chaptersAndTimestampSeekThroughRealSession",
}
VIDEO_CASES = {
    "muxedH264AacAdvancesAudioWithScreenOffAndNoVideoDecoder",
    "videoRequestsAudioAlternateAndKeepsVideoMetadata",
}
CAR_CASES = {
    "legacyBrowserListsFourRootsAndQueuedGroupTapKeepsGroupContext",
    "legacyAssistantSearchStartsExactGroupWithoutExplicitIndexOrTime",
    "legacyHardwareSkipUsesIntervalsAndEpisodeModeAdvances",
}
TIMER = "screenOffListeningTimerExpiresAndRestoresVolume"
EMBEDDED = "embeddedId3NeroAndQuickTimeChaptersReachSession"
LONG = "screenOffStreamsForThirtyMinutesAndAdvances"
HARDENING = "hardeningUiNotificationAndColdMediaKeyStarts"
MIN_DURATION = {LONG: 1800, TIMER: 60}
SOAK_DEVICES = {"api34"}
FLAVOR_FOLDERS = {"debug", "release"}
ACCESSIBILITY_MIN_API = 34
QUEUE_JOURNEY = ("ch.lkmc.neutrodyne.UpNextJourneyTest",
                 "queueEditsPersistUndoRestoresOrderAndNothingPlays")
PLAY_JOURNEY = ("ch.lkmc.neutrodyne.PlayJourneyTest",
                "groupFeedPlayQueueFirstSheetShadePauseAndReopen")
ATF_CONTROL = ("ch.lkmc.neutrodyne.AccessibilityChecksTest",
               "enabledChecksFailAnUnlabelledTinyTargetBeforeTheClick")
RELEASE_SMOKE = {
    "ch.lkmc.neutrodyne.SmokeTest": (
        "fiveLabelledDestinations",
        "destinationsSurviveRotationAndDarkMode",
        "settingsOpensAndBackReturnsToTheTab",
        "aboutShowsVersionAndAbi",
        "licencesListsLibraries",
    ),
    "ch.lkmc.neutrodyne.YtxReleaseSmokeTest": ("selftestAnswersThroughYtxServiceInTheApp",),
    CLASS: (
        "notificationTransportAndLocalArtwork",
        "sleepEndOfEpisodeKeepsQueueAndMarksPlayedOnce",
        "chaptersAndTimestampSeekThroughRealSession",
    ),
    VIDEO_CLASS: tuple(sorted(VIDEO_CASES)),
    CAR_CLASS: tuple(sorted(CAR_CASES)),
}

DOWNLOAD_CLASS = "ch.lkmc.neutrodyne.DownloadJourneyTest"
JOURNEY = "downloadServerOffPlaysLocalArtworkChaptersAndDefersDelete"
WORKER = "visibleApi33DownloadUsesDataSyncForegroundWorker"
UIDT = "backgroundUidtBesidePlaybackForTwentyMinutes"


def _device_report(parts, device, flavors):
    """Use the nearest device/flavor marker, not an ancestor lane."""
    composite = re.compile(
        re.escape(device) + rf"(?P<flavor>{'|'.join(f.title() for f in sorted(FLAVOR_FOLDERS))})AndroidTest"
    )
    for index in range(len(parts) - 1, -1, -1):
        part = parts[index]
        matched = composite.fullmatch(part)
        if matched:
            return matched.group("flavor").lower() in flavors
        if part != device:
            continue
        # managedDevice/<flavor>/<device> and connected/<flavor> identify
        # this lane even when a release folder appears farther up the path.
        after = parts[index + 1].lower() if index + 1 < len(parts) - 1 else ""
        if after in FLAVOR_FOLDERS:
            return after in flavors
        before = parts[index - 1].lower() if index else ""
        if before in FLAVOR_FOLDERS:
            return before in flavors
    return False


def download_cases(profile, device):
    names = {JOURNEY}
    if profile in ("nightly", "downloads") and device == "api33":
        names.add(WORKER)
    if profile in ("nightly", "downloads") and device in ("api34", "api36"):
        names.add(UIDT)
    return {(DOWNLOAD_CLASS, name) for name in names}


def expected_map(profile, device):
    """Every (class -> cases) the lane must report once, passing."""
    if profile == "downloads":
        expected = {}
    elif profile == "release":
        expected = {classname: set(names) for classname, names in RELEASE_SMOKE.items()}
    else:
        # externalBackgroundStartDenialIsUnreachable runs in the external bgstart
        # lane; its aggregate asserts the case there (an instrumented target
        # cannot measure a denial).
        expected = {CLASS: set(SHORT), VIDEO_CLASS: set(VIDEO_CASES), CAR_CLASS: set(CAR_CASES), QUEUE_JOURNEY[0]: {QUEUE_JOURNEY[1]},
                    PLAY_JOURNEY[0]: {PLAY_JOURNEY[1]}}
        api = re.fullmatch(r"api([0-9]+)", device)
        if profile == "api37" or (api and int(api[1]) >= ACCESSIBILITY_MIN_API):
            expected[ATF_CONTROL[0]] = {ATF_CONTROL[1]}
        if profile in ("nightly", "api37"):
            expected[CLASS] |= {TIMER, EMBEDDED}
        if profile == "nightly" and device in SOAK_DEVICES:
            expected[CLASS].add(LONG)
        if profile == "api37":
            expected[CLASS].add(HARDENING)
    for classname, name in download_cases(profile, device):
        expected.setdefault(classname, set()).add(name)
    return expected


def expected_cases(profile, device):
    return {
        (classname, name) for classname, names in expected_map(profile, device).items() for name in names
    }


def verify(root, profile, devices):
    durations = {**MIN_DURATION, UIDT: 1200}
    for device in devices:
        expected = expected_map(profile, device)
        evidence = {
            (classname, name): [] for classname, names in expected.items() for name in names
        }
        flavors = {"release"} if profile == "release" else FLAVOR_FOLDERS
        for report in root.rglob("*.xml"):
            if not _device_report(report.parts, device, flavors):
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
            if name in durations:
                minimum = durations[name]
                duration = float(case.get("time", "0"))
                if not math.isfinite(duration) or duration < minimum:
                    raise ValueError(f"{device}: {classname}#{name} has no valid {minimum}-second duration")
        print(f"{device}: {len(evidence)} expected cases passed ({profile})")


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
