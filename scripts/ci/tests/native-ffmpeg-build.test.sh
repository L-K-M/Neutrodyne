#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# native-ffmpeg-build.test.sh — boundary cases for playback/native/ffmpeg/build.sh
# (11 FFmpeg build). The real build runs on the four desktop runners, where it
# downloads the pinned tarball, verifies its signature and compiles FFmpeg; this
# suite covers the parts that must fail loudly and the ones a shell script can
# get wrong without any network:
#
#   - the script parses (bash -n), so a syntax error never reaches a runner;
#   - the target guard rejects an unknown or missing target with exit 2;
#   - every shell function is defined before its first call, which is what a
#     straight `name() { ... }` definition placed after the block that calls it
#     breaks (the download block called verify_signature before its definition,
#     so the build died with "command not found" on the first runner);
#   - the lock's configure line and library list stay in step with the design
#     document, so a flag that would enable GPL, version3 or nonfree code fails
#     here instead of in checkNativeLicences on four runners.
#
# Run: bash scripts/ci/tests/native-ffmpeg-build.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
SCRIPT="$REPO_ROOT/playback/native/ffmpeg/build.sh"
LOCK="$REPO_ROOT/playback/native/native-components.lock"
DESIGN="$REPO_ROOT/docs/design/11-desktop.md"

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

[ -f "$SCRIPT" ] || { echo "not ok: $SCRIPT is missing" >&2; exit 1; }

# --- syntax ---------------------------------------------------------------------

if bash -n "$SCRIPT" 2>/dev/null; then
    t_ok "build.sh parses"
else
    t_fail "build.sh does not parse: $(bash -n "$SCRIPT" 2>&1 | head -1)"
fi

# --- target guard ---------------------------------------------------------------

out="$(bash "$SCRIPT" bogus-target 2>&1)"
status=$?
if [ "$status" -eq 2 ] && printf '%s' "$out" | grep -q 'usage: build.sh'; then
    t_ok "an unknown target exits 2 with the usage line"
else
    t_fail "an unknown target exited $status: $out"
fi

out="$(bash "$SCRIPT" 2>&1)"
status=$?
if [ "$status" -eq 2 ] && printf '%s' "$out" | grep -q 'usage: build.sh'; then
    t_ok "a missing target exits 2 with the usage line"
else
    t_fail "a missing target exited $status: $out"
fi

# --- function definitions precede their first call ------------------------------
#
# Bash resolves a function name when the call runs, not when the script is read,
# so `bash -n` cannot see a definition that sits below its caller. Walking the
# definition and call sites of every function catches it.

python3 - "$SCRIPT" <<'PY' || FAILED=$((FAILED + 1))
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()

definition = {}
for number, line in enumerate(lines, start=1):
    match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{", line)
    if match:
        definition.setdefault(match.group(1), number)

problems = []
for name, defined_at in sorted(definition.items()):
    for number, line in enumerate(lines, start=1):
        if number >= defined_at:
            continue
        # A call site: the name at a word boundary, not in a comment, and not the
        # definition itself (which `number < defined_at` already excludes).
        if re.search(rf"(^|[;&|]|\bthen\b|\belse\b|\bdo\b|\{{)\s*{name}\b", line) and not line.lstrip().startswith("#"):
            problems.append(f"{name} is called at line {number} but defined at line {defined_at}")
            break

for problem in problems:
    print(f"not ok: {problem}", file=sys.stderr)
if not problems:
    print(f"ok: all {len(definition)} shell functions are defined before their first call")
sys.exit(1 if problems else 0)
PY

# --- licence envelope in the lock and the configure line ------------------------
#
# The configure line is duplicated in the design document; a drift between them
# is what lets a GPL flag in. Compare the two, ignoring the target flags.

configure="$(sed -n 's/^configure = "\(.*\)"$/\1/p' "$LOCK")"
if [ -n "$configure" ]; then
    t_ok "the lock records a configure line"
else
    t_fail "the lock records no configure line"
    configure=""
fi

for banned in --enable-gpl --enable-version3 --enable-nonfree; do
    if printf '%s' "$configure" | grep -q -- "$banned"; then
        t_fail "the lock's configure line carries $banned"
    else
        t_ok "the lock's configure line has no $banned"
    fi
done

for required in --disable-everything --disable-programs --disable-doc --disable-network \
    --disable-autodetect --enable-shared --disable-static --disable-avdevice \
    --disable-avfilter --disable-swscale --enable-swresample; do
    if printf '%s' "$configure" | grep -q -- "$required"; then
        t_ok "the lock's configure line carries $required"
    else
        t_fail "the lock's configure line is missing $required"
    fi
done

# The design document's own copy of the shared line must name the same flags.
for required in --disable-everything --disable-programs --disable-doc --disable-network \
    --disable-autodetect --enable-shared --disable-static --disable-avdevice \
    --disable-avfilter --disable-swscale --enable-swresample; do
    if grep -q -- "$required" "$DESIGN"; then
        t_ok "11-desktop.md names $required"
    else
        t_fail "11-desktop.md no longer names $required"
    fi
done

# --- the four libraries of D86 --------------------------------------------------

libraries="$(sed -n 's/^libraries = \[\(.*\)\]$/\1/p' "$LOCK" | tr -d '"' | tr -d ' ')"
if [ "$libraries" = "avcodec,avformat,avutil,swresample" ]; then
    t_ok "the lock lists the four libraries of D86"
else
    t_fail "the lock lists '$libraries', expected the four of D86"
fi

for library in avcodec avformat avutil swresample; do
    if grep -q "\"$library" "$LOCK" || grep -q ",$library" "$LOCK" || grep -q "$library\"" "$LOCK"; then
        t_ok "the lock records $library"
    else
        t_fail "the lock does not record $library"
    fi
done

if [ "$FAILED" -eq 0 ]; then
    echo "native-ffmpeg-build: all cases behave"
    exit 0
fi
echo "native-ffmpeg-build: $FAILED case(s) failed" >&2
exit 1
