#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# smoke-start.sh <image dir> [--timeout seconds]
#
# Starts a packaged Neutrodyne app image in smoke mode and prints its `SMOKE {json}`
# line (11 Smoke mode). Used by ci.yml's desktop-smoke, nightly.yml's desktop-matrix,
# release.yml's desktop job and check-runtime-sources.sh --image.
#
# A jpackage launcher takes JVM options only from its generated .cfg: the launcher's
# [JavaOptions] entries map to the `java-options=` lines, and -D arguments passed on
# the command line land in main()'s argv instead of the JVM. Smoke mode therefore
# rides in through a temporary `java-options=-Dneutrodyne.smoke=true` line appended
# to the image's .cfg, which this script always removes again (deviation recorded
# 2026-10-06 in 11 Smoke mode).
#
# <image dir> is a jpackage app image: `Neutrodyne/` (Windows and Linux), or
# `Neutrodyne.app` (macOS). Exit 0 = the SMOKE line printed; 1 = the launcher failed,
# printed no SMOKE line or hit the watchdog (60 s by default, Smoke mode's budget).

set -euo pipefail

IMG=""
TIMEOUT_S=60
for arg in "$@"; do
    case "$arg" in
        --timeout=*) TIMEOUT_S="${arg#--timeout=}" ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) IMG="$arg" ;;
    esac
done
if [ -z "$IMG" ] || [ ! -d "$IMG" ]; then
    echo "usage: $0 <image dir> [--timeout=seconds]" >&2
    exit 2
fi

# Locate the launcher and its .cfg for the image layouts: Linux keeps the launcher
# in bin/ and the .cfg in lib/app/, Windows puts both at the image root's
# Neutrodyne.exe and app/, macOS nests everything under Contents/.
LAUNCHER="" CFG=""
for cand in \
    "$IMG/bin/Neutrodyne" \
    "$IMG/bin/Neutrodyne.exe" \
    "$IMG/Neutrodyne.exe" \
    "$IMG/Contents/MacOS/Neutrodyne"; do
    if [ -f "$cand" ]; then
        LAUNCHER="$cand"
        break
    fi
done
for cand in \
    "$IMG/lib/app/Neutrodyne.cfg" \
    "$IMG/app/Neutrodyne.cfg" \
    "$IMG/Contents/app/Neutrodyne.cfg"; do
    if [ -f "$cand" ]; then
        CFG="$cand"
        break
    fi
done
if [ -z "$LAUNCHER" ] || [ -z "$CFG" ]; then
    echo "smoke-start: no launcher or .cfg under $IMG" >&2
    exit 1
fi

SMOKE_OPT='java-options=-Dneutrodyne.smoke=true'
# shellcheck disable=SC2329 # invoked indirectly as the EXIT trap
restore() {
    # Remove exactly the line we appended; an unchanged .cfg stays untouched.
    if grep -qxF "$SMOKE_OPT" "$CFG" 2>/dev/null; then
        grep -vxF "$SMOKE_OPT" "$CFG" >"$CFG.tmp" && mv "$CFG.tmp" "$CFG"
    fi
}
trap restore EXIT

if ! grep -qxF "$SMOKE_OPT" "$CFG"; then
    printf '%s\n' "$SMOKE_OPT" >>"$CFG"
fi

# The watchdog is part of smoke mode's contract: a hung step must not hang the job.
OUT="$(mktemp)"
rc=0
if command -v timeout >/dev/null 2>&1; then
    timeout "$TIMEOUT_S" "$LAUNCHER" >"$OUT" 2>&1 || rc=$?
else
    "$LAUNCHER" >"$OUT" 2>&1 || rc=$?
fi

if ! grep -q '^SMOKE {' "$OUT"; then
    echo "smoke-start: no SMOKE line (launcher exit $rc)" >&2
    sed 's/^/  | /' "$OUT" >&2 | head -40
    rm -f "$OUT"
    exit 1
fi
grep '^SMOKE {' "$OUT" | tail -1
rm -f "$OUT"
exit 0
