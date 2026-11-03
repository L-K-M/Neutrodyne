#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Diagnostic executables never enter the shipping native output or artifacts.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"
version="$(python3 -c 'import tomllib; print(tomllib.load(open("playback/native/native-components.lock", "rb"))["miniaudio"]["version"])')"
include="${ND_CACHE:-$HOME/.cache/neutrodyne-native}/miniaudio-$version"
python3 - "$include/miniaudio.h" <<'PY'
import hashlib, sys, tomllib
expected = tomllib.load(open("playback/native/native-components.lock", "rb"))["miniaudio"]["sha256"]
assert hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest() == expected, "miniaudio checksum mismatch"
PY
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/ndmedia-sanitizers.XXXXXX")"
trap 'rm -rf "$work"' EXIT
flags=()
case "$(uname -s)" in
    Linux) flags=(-lpthread -lm -ldl) ;;
    Darwin) flags=(-framework AudioToolbox -framework CoreAudio -framework CoreFoundation) ;;
    *) echo 'Unsupported sanitizer host' >&2; exit 2 ;;
esac
cc -std=c11 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=all \
    -I"$include" -Iplayback/native/ndmedia playback/native/ndmedia/ndmedia.c \
    playback/native/ndmedia/tests/null_stress.c -o "$work/null-stress" "${flags[@]}"
mkdir -p native-evidence
"$work/null-stress" > native-evidence/native-sanitizers.log 2>&1 || {
    cat native-evidence/native-sanitizers.log
    exit 1
}
cat native-evidence/native-sanitizers.log
