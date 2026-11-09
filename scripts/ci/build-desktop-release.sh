#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# build-desktop-release.sh <target> <version> <mac_kind>
#
# One attempt of release.yml's desktop job (09 release.yml step 3): package every
# format of the target, smoke-start each package, run the image scan and the
# runtime checks, and leave the renamed release assets in dist/. release.yml calls
# it twice — a runner or toolchain flake gets a second chance, a deterministic
# failure fails twice and fails the job.
#
#   <target>:  windows-x64 | macos-arm64 | linux-x64 | linux-arm64
#   <version>: the tag's X.Y.Z (verify-tag's version output)
#   <mac_kind>: mac-zip (0.x, PO-39) | dmg (1.0.0+)
#
# Expects the runner's own tools: WiX via Compose's downloadWix on Windows,
# plutil/codesign/ditto/hdiutil on macOS, dpkg-deb/fakeroot/rpmbuild/binutils/
# xvfb/dbus-x11 on Linux.

set -euo pipefail

if [ $# -ne 3 ]; then
    echo "usage: $0 <target> <version> <mac_kind>" >&2
    exit 2
fi
TARGET="$1" V="$2" MAC_KIND="$3"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
cd "$REPO_ROOT"

IMG="desktopApp/build/compose/binaries/main/app/Neutrodyne"
[ "$TARGET" = "macos-arm64" ] && IMG="$IMG.app"
DIST="$REPO_ROOT/dist"
SMOKE_IMG=/tmp/neutrodyne-smoke-img
mkdir -p "$DIST" "$SMOKE_IMG"
: >smoke.txt

# MD3 AC2: every packaged smoke this script drives must also prove the engine —
# the image's own bundled interpreter, shim and yt-dlp resolving the shared
# corpus' recorded exchanges under ReplayRH, then the media scenario's real
# loopback playback and chunked download. smoke-start.sh injects the replay
# property and gates the SMOKE line's engine evidence. This script only ever
# packages engine builds; the emergency -Pneutrodyne.youtubeEngine=false leg
# builds APKs only, no packaged desktop image.
RECORDED_MEDIA="$REPO_ROOT/youtube/engine/src/test/resources/recorded/media"

smoke() { # <image dir> — append the SMOKE line for check-runtime-sources
    scripts/desktop/smoke-start.sh "$1" --engine-replay="$RECORDED_MEDIA" | tee -a smoke.txt
    scripts/ci/check-runtime-sources.sh --image "$1" --smoke smoke.txt
}
xsmoke() { # smoke() under xvfb + a private session bus (Linux)
    xvfb-run -a dbus-run-session scripts/desktop/smoke-start.sh "$1" --engine-replay="$RECORDED_MEDIA" | tee -a smoke.txt
    scripts/ci/check-runtime-sources.sh --image "$1" --smoke smoke.txt
}
check_package() {
    # installKind changes the app JAR and its generated manifest. Check this
    # variant before the next Gradle invocation replaces those source identities.
    scripts/ci/check-desktop-image.sh "$IMG" "$1"
}

case "$TARGET" in
    windows-x64)
        scripts/ci/build-desktop-release-windows.sh "$V" "$DIST" "$SMOKE_IMG" ;;
    macos-arm64)
        if [ "$MAC_KIND" = "mac-zip" ]; then
            ./gradlew createDistributable -Pneutrodyne.installKind=mac-zip
            scripts/desktop/mac-zip.sh --app "$IMG" --version "$V" \
                --out "$DIST/neutrodyne-$V-macos-arm64.zip"
            MAC_PACKAGE="$DIST/neutrodyne-$V-macos-arm64.zip"
            ditto -x -k "$DIST/neutrodyne-$V-macos-arm64.zip" "$SMOKE_IMG"
        else
            ./gradlew createDistributable packageDmg -Pneutrodyne.installKind=dmg
            cp desktopApp/build/compose/binaries/main/dmg/*.dmg "$DIST/neutrodyne-$V-macos-arm64.dmg"
            MAC_PACKAGE="$DIST/neutrodyne-$V-macos-arm64.dmg"
            mkdir -p /tmp/neutrodyne-dmg-mnt
            hdiutil attach -nobrowse -mountpoint /tmp/neutrodyne-dmg-mnt \
                "$DIST/neutrodyne-$V-macos-arm64.dmg"
            # ditto, not cp -R: it carries the artifact's timestamps, modes and
            # symlinks, so the smoke image keeps the trained JARs' recorded
            # mtimes and a drifted DMG payload stays drifted for the strict
            # check instead of being silently re-stamped at extraction time.
            ditto /tmp/neutrodyne-dmg-mnt/Neutrodyne.app "$SMOKE_IMG/"
            hdiutil detach /tmp/neutrodyne-dmg-mnt
        fi
        smoke "$SMOKE_IMG/Neutrodyne.app"
        check_package "$MAC_PACKAGE" ;;
    linux-*)
        arch="${TARGET#linux-}"
        ./gradlew createDistributable packageDeb -Pneutrodyne.installKind=deb
        cp desktopApp/build/desktop-packaging/deb/*.deb "$DIST/neutrodyne-$V-linux-$arch.deb"
        sudo dpkg -i "$DIST/neutrodyne-$V-linux-$arch.deb"
        xsmoke /opt/neutrodyne
        check_package "$DIST/neutrodyne-$V-linux-$arch.deb"
        ./gradlew createDistributable packageRpm -Pneutrodyne.installKind=rpm
        cp desktopApp/build/desktop-packaging/rpm/*.rpm "$DIST/neutrodyne-$V-linux-$arch.rpm"
        rpm2cpio "$DIST/neutrodyne-$V-linux-$arch.rpm" | (cd "$SMOKE_IMG" && cpio -idm --quiet)
        xsmoke "$SMOKE_IMG/opt/neutrodyne"
        check_package "$DIST/neutrodyne-$V-linux-$arch.rpm"
        ./gradlew createDistributable packageTarGz -Pneutrodyne.installKind=tar.gz
        cp "desktopApp/build/desktop-packaging/tar-gz/neutrodyne-$V-linux-$arch.tar.gz" "$DIST/"
        tar -xzf "$DIST/neutrodyne-$V-linux-$arch.tar.gz" -C "$SMOKE_IMG"
        xsmoke "$SMOKE_IMG/Neutrodyne"
        check_package "$DIST/neutrodyne-$V-linux-$arch.tar.gz" ;;
    *) echo "unknown target: $TARGET" >&2; exit 2 ;;
esac

# Package image scans ran with each variant's matching classpath manifest.
scripts/ci/check-runtime-sources.sh --image "$IMG" --smoke smoke.txt
if [ "$TARGET" = "macos-arm64" ]; then
    codesign --verify --deep --strict "$IMG"
fi
