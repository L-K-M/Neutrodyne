#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Inspect actual MD0 binary imports, deployment floors and output boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{' '.join(map(str, args))}: {result.stderr}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=("windows-x64", "macos-arm64", "linux-x64", "linux-arm64"))
    args = parser.parse_args()
    target = args.target
    root = Path(__file__).resolve().parents[2]
    base = root / "playback/native/build"
    ffmpeg = base / "ffmpeg" / target
    layout = json.loads((ffmpeg / "ffmpeg-layout.json").read_text())
    expected = {f"lib{name}.so.{major}" for name, major in layout["libraries"].items()}
    candidates = list((ffmpeg / ("bin" if target.startswith("windows") else "lib")).iterdir())
    candidates += list((base / "ndmedia" / target).iterdir())
    binaries = sorted({p.resolve() for p in candidates if p.is_file() and (
        p.name.endswith((".dll", ".dylib", ".so")) or re.search(r"\.so\.[0-9.]+$", p.name)
    )})
    problems = []
    records = []
    if len(binaries) != 5:
        problems.append(f"expected four FFmpeg libraries and ndmedia, found {len(binaries)}")
    for file in binaries:
        imports = []
        if target.startswith("linux"):
            dynamic = run("readelf", "-d", str(file))
            imports = re.findall(r"NEEDED.*\[([^]]+)\]", dynamic)
            allowed = expected | {"libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1"}
            for dependency in imports:
                if dependency not in allowed:
                    problems.append(f"{file.name}: unexpected import {dependency}")
            versions = run("readelf", "--version-info", str(file))
            for major, minor in re.findall(r"GLIBC_(\d+)\.(\d+)", versions):
                if (int(major), int(minor)) > (2, 31):
                    problems.append(f"{file.name}: GLIBC_{major}.{minor} exceeds 2.31")
            if file.name.startswith("libav") or file.name.startswith("libswresample"):
                if "$ORIGIN" not in dynamic:
                    problems.append(f"{file.name}: missing $ORIGIN search path")
            if file.name == "libndmedia.so":
                symbols = run("nm", "-a", str(file))
                helpers = sorted(set(re.findall(r"\b__aarch64_(?:cas|swp|ldadd|ldclr|ldeor|ldset)[A-Za-z0-9_]*", symbols)))
                if helpers:
                    problems.append(f"{file.name}: linked GCC out-of-line atomic helpers: {helpers}")
        elif target.startswith("windows"):
            # dumpbin is supplied by the same MSVC environment used for the build.
            imports = re.findall(r"(?im)^\s*([a-z0-9_.-]+\.dll)\s*$", run("dumpbin", "/DEPENDENTS", str(file)))
            if not imports:
                problems.append(f"{file.name}: no PE import inventory")
            for dependency in imports:
                if re.match(r"(?i)(?:msvcp|libgcc|libstdc\+\+)", dependency):
                    problems.append(f"{file.name}: unsupported compiler runtime import {dependency}")
        else:
            dependencies = run("otool", "-L", str(file)).splitlines()[1:]
            imports = [line.strip().split(" (")[0] for line in dependencies]
            for dependency in imports:
                if not dependency.startswith(("/usr/lib/", "/System/Library/", "@rpath/", "@loader_path/")):
                    problems.append(f"{file.name}: absolute build-path dependency {dependency}")
        if "ndmedia" in file.name and any(re.search(r"(?i)(?:lib)?(?:avcodec|avformat|avutil|swresample)", item) for item in imports):
            problems.append(f"{file.name}: ndmedia must not link FFmpeg")
        records.append({"file": file.name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest(), "imports": imports})
    output = root / "native-evidence/native-binary-policy.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({"commit": os.getenv("GITHUB_SHA"), "target": target, "binaries": records, "problems": problems}, indent=2) + "\n")
    for problem in problems:
        print(f"native binary policy: {problem}")
    print(f"native binary policy: {len(binaries)} binaries, {len(problems)} problems")
    return bool(problems)


if __name__ == "__main__":
    raise SystemExit(main())
