#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Host-only release evidence: seed, finish instrumentation, reboot, inspect offline, tap online."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time

CLASS = "ch.lkmc.neutrodyne.playback.impl.RebootResumptionTest"
PHASES = (
    "seedPausedEpisodeWithLocalArtwork",
    "offlineSystemUiCardHasLocalArtworkWithoutAutoplay",
    "tapSystemUiCardResumesSavedPosition",
)


def require_one_pass(output):
    if not re.search(r"OK \(1 test\)", output) or re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:2|3|4)", output):
        raise ValueError("Expected exactly one passing, non-skipped host phase")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hardening", action="store_true")
    parser.add_argument("--package", default="ch.lkmc.neutrodyne")
    parser.add_argument("--output", type=Path, default=Path("app/build/reports/playback-reboot"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command, timeout=90):
        return subprocess.run(["adb", *command], check=True, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout).stdout

    package = adb("shell", "dumpsys", "package", args.package)
    if "DEBUGGABLE" in package or "versionCode=" not in package:
        raise ValueError("Install the optimized release app and its test APK first")
    (args.output / "package.txt").write_text(package)
    apk_path = adb("shell", "pm", "path", args.package).splitlines()[0].removeprefix("package:").strip()
    media = args.output / "media"
    media.mkdir(exist_ok=True)
    (media / "episode.mp3").write_bytes(
        (root / "playback/impl/src/test/resources/media/cbr_128k_2s.mp3").read_bytes() * 60)
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(SimpleHTTPRequestHandler, directory=str(media)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = str(server.server_port)
    runner = f"{args.package}.test/androidx.test.runner.AndroidJUnitRunner"
    record = {"sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
              "image": adb("shell", "getprop", "ro.build.fingerprint").strip(),
              "package": args.package, "buildType": "release",
              "abi": adb("shell", "getprop", "ro.product.cpu.abi").strip(),
              "apkSha256": adb("shell", "sha256sum", apk_path).split()[0],
              "passed": [], "durations": {}, "skips": 0}

    def phase(name):
        started = time.monotonic()
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{CLASS}#{name}",
                     "-e", "hostReboot", "true", "-e", "sourceUrl", f"http://127.0.0.1:{port}/episode.mp3",
                     runner, timeout=180)
        (args.output / f"{name}.txt").write_text(result)
        require_one_pass(result)
        record["passed"].append(name)
        record["durations"][name] = time.monotonic() - started

    try:
        adb("shell", "settings", "put", "secure", "qs_media_resumption", "1")
        adb("reverse", f"tcp:{port}", f"tcp:{port}")
        phase(PHASES[0])
        adb("reverse", "--remove", f"tcp:{port}")
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        # The instrumentation process has exited normally before this host reboot.
        adb("reboot")
        adb("wait-for-device", timeout=180)
        deadline = time.monotonic() + 300
        while adb("shell", "getprop", "sys.boot_completed").strip() != "1":
            if time.monotonic() > deadline:
                raise TimeoutError("Device did not finish reboot")
            time.sleep(2)
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        if args.hardening:
            adb("shell", "cmd", "audio", "set-enable-hardening", "throw")
        if adb("shell", "settings", "get", "global", "airplane_mode_on").strip() != "1":
            raise ValueError("Offline card phase requires airplane mode after reboot")
        phase(PHASES[1])
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
        adb("reverse", f"tcp:{port}", f"tcp:{port}")
        phase(PHASES[2])
        if args.hardening:
            hardening = adb("logcat", "-d", "-s", "AudioHardening")
            (args.output / "audio-hardening.txt").write_text(hardening)
            if args.package in hardening:
                raise ValueError("Audio hardening reported the application")
    finally:
        record["tests"] = len(record["passed"])
        (args.output / "evidence.json").write_text(json.dumps(record, indent=2) + "\n")
        try:
            for name, command in {
                "logcat.txt": ("logcat", "-d", "-v", "threadtime"),
                "media-session.txt": ("shell", "dumpsys", "media_session"),
                "connectivity.txt": ("shell", "dumpsys", "connectivity"),
            }.items():
                (args.output / name).write_text(adb(*command))
            adb("pull", f"/sdcard/Android/data/{args.package}/files/m5-reboot", str(args.output))
        finally:
            server.shutdown()
            server.server_close()
            adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
            adb("shell", "svc", "wifi", "enable")
            adb("shell", "svc", "data", "enable")
            adb("reverse", "--remove", f"tcp:{port}")
            if args.hardening:
                adb("shell", "cmd", "audio", "set-enable-hardening", "enable")


if __name__ == "__main__":
    main()
