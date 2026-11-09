#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# build-desktop-release-windows.sh <version> <dist> <smoke_img>
#
# The windows-x64 leg of build-desktop-release.sh, extracted so
# tests/build-desktop-release-windows.test.sh can run it against fake tools:
# package the MSI and ZIP, install the MSI, smoke the installed app and the
# unpacked zip.
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: $0 <version> <dist> <smoke_img>" >&2; exit 2; }
V="$1" DIST="$2" SMOKE_IMG="$3"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
cd "$REPO_ROOT"
# MD3 AC2: the same recorded-media proof the POSIX leg arms; smoke-start.sh
# owns the path-form translation the JVM property needs on Windows.
RECORDED_MEDIA="$REPO_ROOT/youtube/engine/src/test/resources/recorded/media"
smoke() { scripts/desktop/smoke-start.sh "$1" --engine-replay="$RECORDED_MEDIA" | tee -a smoke.txt; }
mkdir -p "$DIST" "$SMOKE_IMG"

./gradlew createDistributable packageMsi -Pneutrodyne.installKind=msi
cp desktopApp/build/compose/binaries/main/msi/*.msi "$DIST/neutrodyne-$V-windows-x64.msi"

# The MSI path is data ($env:MSI_PATH), never -Command text: a path is not
# code, so spaces or apostrophes cannot break the fixed script. -Wait
# -PassThru keeps msiexec's exit code — without it a failed install still
# exits powershell 0 and the smoke below would pass a stale installed image.
# The child code returns on a stdout marker line. 3010
# (ERROR_SUCCESS_REBOOT_REQUIRED) is accepted: /norestart defers the reboot
# and the installed files smoke verifies are final.
rc_ps=0
# shellcheck disable=SC2016 # the single-quoted command is PowerShell, not shell
out="$(MSI_PATH="$(cygpath -w "$DIST/neutrodyne-$V-windows-x64.msi")" \
    powershell -NoProfile -Command '
$ErrorActionPreference = "Stop"
$p = Start-Process msiexec -Wait -PassThru -ArgumentList "/i `"$env:MSI_PATH`" /qn /norestart"
Write-Output "MSIEXEC_EXIT=$($p.ExitCode)"
exit $p.ExitCode')" || rc_ps=$?
rc="$(printf '%s\n' "$out" | tr -d '\r' | sed -n 's/^MSIEXEC_EXIT=\([0-9][0-9]*\)$/\1/p' | head -1)"
case "$rc" in
    0) ;;
    3010) echo "msiexec: 3010 (success, reboot required); /norestart defers it" ;;
    '') echo "msiexec gave no exit marker (powershell exit $rc_ps)" >&2; exit 1 ;;
    *) echo "msiexec failed: exit $rc" >&2; exit 1 ;;
esac

smoke "$LOCALAPPDATA/Neutrodyne-App"
# Validate the actual installed payload while the generated classpath still
# describes the MSI variant. The MSI table query alone does not scan its JARs.
scripts/ci/check-desktop-image.sh "$LOCALAPPDATA/Neutrodyne-App" "$DIST/neutrodyne-$V-windows-x64.msi"
scripts/ci/check-runtime-sources.sh --image "$LOCALAPPDATA/Neutrodyne-App" --smoke smoke.txt
./gradlew createDistributable packageZip -Pneutrodyne.installKind=zip
cp desktopApp/build/desktop-packaging/zip/*.zip "$DIST/neutrodyne-$V-windows-x64.zip"
# Restore each entry's recorded mtime from the artifact instead of the extraction
# time: the AOT cache binds the trained JARs' timestamps, and a drifted producer
# must stay drifted for the strict check to catch it. (The stdlib zipfile CLI
# drops them, which would silently lose the cache and hide the drift.)
python "$SCRIPT_DIR/extract-zip.py" "$DIST/neutrodyne-$V-windows-x64.zip" "$SMOKE_IMG"
smoke "$SMOKE_IMG/Neutrodyne"
scripts/ci/check-desktop-image.sh "$SMOKE_IMG/Neutrodyne" "$DIST/neutrodyne-$V-windows-x64.zip"
scripts/ci/check-runtime-sources.sh --image "$SMOKE_IMG/Neutrodyne" --smoke smoke.txt
