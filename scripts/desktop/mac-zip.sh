#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# mac-zip.sh --app <Neutrodyne.app> --version <0.Y.Z> --out <neutrodyne-{v}-macos-arm64.zip>
#
# The 0.x macOS ZIP pipeline (11 macOS DMG, ad-hoc signing and the 0.x ZIP; PO-39).
# jpackage refuses an app version whose first number is 0, so before 1.0.0 the macOS
# release ships a ZIP built this way instead of a DMG:
#
#   1. the app image is built with the placeholder version 1.0.0 (the release job
#      passes -Pneutrodyne.installKind=mac-zip so build-info records mac-zip);
#   2. CFBundleShortVersionString and CFBundleVersion in Contents/Info.plist are
#      replaced with the real 0.Y.Z;
#   3. the nested Mach-O code and the bundle are re-signed ad hoc (the plist change
#      breaks the seal) and verified with codesign --verify --deep --strict;
#   4. ditto packs the .app into the release ZIP;
#   5. the unzipped app gets a smoke start (scripts/desktop/smoke-start.sh).
#
# Runs only on the macos runner: needs plutil, codesign and ditto.
# Exit 0 = the ZIP was written and its unzipped app printed a SMOKE line.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
SMOKE_START="$SCRIPT_DIR/smoke-start.sh"

APP="" VERSION="" OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --app) APP="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$APP" ] || [ -z "$VERSION" ] || [ -z "$OUT" ]; then
    echo "usage: $0 --app <Neutrodyne.app> --version <0.Y.Z> --out <zip>" >&2
    exit 2
fi
case "$VERSION" in
    0.*) ;;
    *) echo "mac-zip: --version must be a 0.x version (the DMG takes over at 1.0.0)" >&2; exit 1 ;;
esac
[ -d "$APP" ] || { echo "mac-zip: no .app at $APP" >&2; exit 1; }
PLIST="$APP/Contents/Info.plist"
[ -f "$PLIST" ] || { echo "mac-zip: no Info.plist in $APP" >&2; exit 1; }
for tool in plutil codesign ditto; do
    command -v "$tool" >/dev/null 2>&1 || { echo "mac-zip: $tool is required (macOS runner)" >&2; exit 1; }
done

# The image must have been packaged for the mac-zip install kind (install-kind
# property → build-info.properties, 11 install-kind mechanism note 2026-10-06).
BUILD_INFO="$APP/Contents/app/resources/build-info.properties"
if [ -f "$BUILD_INFO" ] && ! grep -q '^installKind=mac-zip' "$BUILD_INFO"; then
    echo "mac-zip: build-info.properties is not installKind=mac-zip — repackage with -Pneutrodyne.installKind=mac-zip" >&2
    exit 1
fi

is_macho() {
    local magic
    magic="$(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d ' ')"
    case "$magic" in
        feedface|feedfacf|cefaedfe|cffaedfe|cafebabe) return 0 ;;
        *) return 1 ;;
    esac
}

echo "mac-zip: version $VERSION into Info.plist"
plutil -replace CFBundleShortVersionString -string "$VERSION" "$PLIST"
plutil -replace CFBundleVersion -string "$VERSION" "$PLIST"

# Nested code first, inside out, the bundle last (11 Ad-hoc signatures).
while IFS= read -r -d '' f; do
    if is_macho "$f"; then
        codesign --force -s - "$f" >/dev/null
    fi
done < <(find "$APP/Contents" -type f -depth 1 -print0 2>/dev/null || find "$APP/Contents" -type f -print0)
while IFS= read -r -d '' d; do
    codesign --force -s - "$d" >/dev/null
done < <(find "$APP/Contents" -type d \( -name '*.framework' -o -name '*.app' \) -depth 1 -print0 2>/dev/null || true)
codesign --force -s - "$APP" >/dev/null
codesign --verify --deep --strict "$APP"

# ditto keeps the .app as the archive's top level and resource forks out of it.
rm -f "$OUT"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$OUT"

# Smoke start the unzipped app — the shipped artifact is what we check.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
ditto -x -k "$OUT" "$TMP"
"$SMOKE_START" "$TMP/Neutrodyne.app"

echo "mac-zip: wrote $OUT"
