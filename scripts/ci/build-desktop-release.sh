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

smoke() { # <image dir> — append the SMOKE line for check-runtime-sources
    scripts/desktop/smoke-start.sh "$1" | tee -a smoke.txt
}
xsmoke() { # smoke() under xvfb + a private session bus (Linux)
    xvfb-run -a dbus-run-session scripts/desktop/smoke-start.sh "$1" | tee -a smoke.txt
}

case "$TARGET" in
    windows-x64)
        ./gradlew createDistributable packageMsi -Pneutrodyne.installKind=msi
        ./gradlew createDistributable packageZip -Pneutrodyne.installKind=zip
        cp desktopApp/build/compose/binaries/main/msi/*.msi "$DIST/neutrodyne-$V-windows-x64.msi"
        cp desktopApp/build/desktop-packaging/zip/*.zip "$DIST/neutrodyne-$V-windows-x64.zip"
        # Same install gate as nightly.yml: Start-Process -Wait alone hides a
        # failed msiexec, and bash's -d test needs the cygpath -u POSIX form of
        # the per-user install root (nightly 37858231006).
        msi="$(cygpath -w "$DIST/neutrodyne-$V-windows-x64.msi")"
        rc="$(powershell -NoProfile -Command \
            "\$p = Start-Process msiexec -Wait -PassThru -ArgumentList '/i','$msi','/qn','/norestart','/l*v','msiexec-install.log'; \$p.ExitCode" | tr -d '\r')"
        echo "msiexec ExitCode=$rc"
        case "$rc" in
            0|3010) ;;
            *) tail -50 msiexec-install.log 2>/dev/null; echo "::error::msiexec exited $rc"; exit 1 ;;
        esac
        inst="$(cygpath -u "${LOCALAPPDATA:?LOCALAPPDATA unset}")/Programs/Neutrodyne"
        [ -d "$inst" ] || { echo "::error::MSI succeeded but $inst is missing"; exit 1; }
        smoke "$inst"
        python -m zipfile -e "$DIST/neutrodyne-$V-windows-x64.zip" "$SMOKE_IMG"
        smoke "$SMOKE_IMG/Neutrodyne" ;;
    macos-arm64)
        if [ "$MAC_KIND" = "mac-zip" ]; then
            ./gradlew createDistributable -Pneutrodyne.installKind=mac-zip
            scripts/desktop/mac-zip.sh --app "$IMG" --version "$V" \
                --out "$DIST/neutrodyne-$V-macos-arm64.zip"
            ditto -x -k "$DIST/neutrodyne-$V-macos-arm64.zip" "$SMOKE_IMG"
        else
            ./gradlew createDistributable packageDmg -Pneutrodyne.installKind=dmg
            cp desktopApp/build/compose/binaries/main/dmg/*.dmg "$DIST/neutrodyne-$V-macos-arm64.dmg"
            mkdir -p /tmp/neutrodyne-dmg-mnt
            hdiutil attach -nobrowse -mountpoint /tmp/neutrodyne-dmg-mnt \
                "$DIST/neutrodyne-$V-macos-arm64.dmg"
            cp -R /tmp/neutrodyne-dmg-mnt/Neutrodyne.app "$SMOKE_IMG/"
            hdiutil detach /tmp/neutrodyne-dmg-mnt
        fi
        smoke "$SMOKE_IMG/Neutrodyne.app" ;;
    linux-*)
        arch="${TARGET#linux-}"
        ./gradlew createDistributable packageDeb -Pneutrodyne.installKind=deb
        ./gradlew createDistributable packageRpm -Pneutrodyne.installKind=rpm
        ./gradlew createDistributable packageTarGz -Pneutrodyne.installKind=tar.gz
        cp desktopApp/build/desktop-packaging/deb/*.deb "$DIST/neutrodyne-$V-linux-$arch.deb"
        cp desktopApp/build/desktop-packaging/rpm/*.rpm "$DIST/neutrodyne-$V-linux-$arch.rpm"
        cp "desktopApp/build/desktop-packaging/tar-gz/neutrodyne-$V-linux-$arch.tar.gz" "$DIST/"
        sudo dpkg -i "$DIST/neutrodyne-$V-linux-$arch.deb"
        # The installed tree is root-owned: smoke-start.sh appends its
        # java-option to the image .cfg, so the DEB leg smokes the extracted
        # payload (the same installKind=deb bits) — no chmod of /opt
        # (nightly 37831500506).
        dpkg-deb -x "$DIST/neutrodyne-$V-linux-$arch.deb" "$SMOKE_IMG/deb-payload"
        xsmoke "$SMOKE_IMG/deb-payload/opt/neutrodyne"
        rpm2cpio "$DIST/neutrodyne-$V-linux-$arch.rpm" | (cd "$SMOKE_IMG" && cpio -idm --quiet)
        xsmoke "$SMOKE_IMG/opt/neutrodyne"
        tar -xzf "$DIST/neutrodyne-$V-linux-$arch.tar.gz" -C "$SMOKE_IMG"
        xsmoke "$SMOKE_IMG/Neutrodyne" ;;
    *) echo "unknown target: $TARGET" >&2; exit 2 ;;
esac

# The image scan plus the runtime exception's image checks (11 Image scan rules,
# Runtime exception obligations and checks).
inputs=("$IMG")
while IFS= read -r f; do
    inputs+=("$f")
done < <(find "$DIST" -maxdepth 1 -type f \
    \( -name '*.msi' -o -name '*.deb' -o -name '*.rpm' -o -name '*.tar.gz' -o -name '*.zip' -o -name '*.dmg' \))
scripts/ci/check-desktop-image.sh "${inputs[@]}"
scripts/ci/check-runtime-sources.sh --image "$IMG" --smoke smoke.txt
if [ "$TARGET" = "macos-arm64" ]; then
    codesign --verify --deep --strict "$IMG"
fi
