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
    binaries = sorted({p.resolve() for p in candidates if p.is_file() and (
        p.name.endswith((".dll", ".dylib", ".so")) or re.search(r"\.so\.[0-9.]+$", p.name)
    )})
    problems = []
    records = []
    if len(binaries) != 4:
        problems.append(f"expected four FFmpeg libraries, found {len(binaries)}")
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
            # Inspect FFmpeg before install strips local compiler-runtime symbols.
            # The delivered ELF alone cannot establish absence of copied helpers.
            symbol_file = file
            if "ndmedia" not in file.name:
                component = file.name.split(".so")[0]
                source = base / "ffmpeg-cache" / f"ffmpeg-{layout['ffmpegVersion']}" / component
                unstripped = {p.resolve() for p in source.glob(f"{component}.so*") if p.is_file()}
                if len(unstripped) != 1:
                    problems.append(f"{file.name}: missing unique unstripped compiler-runtime audit input")
                    symbol_file = None
                else:
                    symbol_file = unstripped.pop()
            if symbol_file is not None:
                symbols = run("nm", "-a", str(symbol_file))
                if not symbols.strip():
                    problems.append(f"{file.name}: no symbols for compiler-runtime audit")
                helpers = sorted(set(re.findall(
                    r"\b(?:__aarch64_(?:cas|swp|ldadd|ldclr|ldeor|ldset)[A-Za-z0-9_]*|"
                    r"__do_global_dtors_aux|frame_dummy|crtstuff\.c|"
                    r"__(?:u?div|u?mod|multi|mulv)(?:di|ti)3)\b", symbols,
                )))
                if helpers:
                    problems.append(f"{file.name}: copied GCC runtime code: {helpers}")
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
