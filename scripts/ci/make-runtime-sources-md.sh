#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# make-runtime-sources-md.sh --image <dir> --out <file>
#
# Writes RUNTIME-SOURCES.md for the release (11 Runtime exception obligations and
# checks; release.yml's sources job): vendor and version, the release URL, the
# per-target archive SHA-256s, the source tarball's name and SHA-256 — all from
# desktopApp/runtime.lock — the jlink module list and plugins (the module list is
# read from the given image's jlink'd runtime/release, so the document always
# matches what actually ships), the components the tarball covers, the other
# attached sources and why, and the unmodified-runtime statement including the
# macOS ad-hoc re-signing note.
#
# Exit 2 = usage error.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
RUNTIME_LOCK="$REPO_ROOT/desktopApp/runtime.lock"
WIX_LOCK="$REPO_ROOT/desktopApp/wix.lock"

IMG="" OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --image) IMG="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ -z "$OUT" ]; then
    echo "usage: $0 [--image <dir>] --out <file>" >&2
    exit 2
fi

lock_prop() { grep -E "^$2=" "$1" | tail -1 | cut -d= -f2-; }

vendor="$(lock_prop "$RUNTIME_LOCK" vendor)"
vendor_version="$(lock_prop "$RUNTIME_LOCK" vendorVersion)"
version="$(lock_prop "$RUNTIME_LOCK" version)"
java_version="$(lock_prop "$RUNTIME_LOCK" javaVersion)"
release_url="$(lock_prop "$RUNTIME_LOCK" releaseUrl)"
src_name="$(lock_prop "$RUNTIME_LOCK" sourceTarball.name)"
src_url="$(lock_prop "$RUNTIME_LOCK" sourceTarball.url)"
src_sha="$(lock_prop "$RUNTIME_LOCK" sourceTarball.sha256)"
wix_version="$(lock_prop "$WIX_LOCK" version)"
wix_src_name="$(lock_prop "$WIX_LOCK" source.name)"
wix_src_sha="$(lock_prop "$WIX_LOCK" source.sha256)"

# The jlink module list: prefer the image's own runtime/release (what actually
# shipped); without an image, take JLINK_MODULES from the convention plugin — the
# same source the build uses (DesktopApplicationConventionPlugin.kt).
modules=""
if [ -n "$IMG" ]; then
    RT=""
    for cand in "$IMG/lib/runtime" "$IMG/Contents/runtime" "$IMG/runtime"; do
        if [ -d "$cand" ]; then
            RT="$cand"
            break
        fi
    done
    [ -n "$RT" ] || { echo "no runtime directory under $IMG" >&2; exit 2; }
    modules="$(grep -E '^MODULES=' "$RT/release" | cut -d'"' -f2)"
else
    modules="$(
        awk '/JLINK_MODULES *=/{in_list=1} in_list{print} in_list&&/\)/{exit}' \
            "$REPO_ROOT/build-logic/convention/src/main/kotlin/DesktopApplicationConventionPlugin.kt" \
            | grep -oE '"[a-z0-9.]+"' | tr -d '"' | tr '\n' ' '
    )"
fi
[ -n "$modules" ] || { echo "could not determine the jlink module list" >&2; exit 2; }

{
    cat <<EOF
# Runtime sources — Neutrodyne desktop

The desktop installers bundle an unmodified OpenJDK runtime distributed under
GPL-2.0 with the Classpath Exception (the runtime exception, D3). This file is the
corresponding-source record that GPL-2.0 §3 requires: the exact source of the
exact runtime shipped, available from the same place as the packages themselves.

## Runtime

- Vendor: $vendor
- Vendor version: $vendor_version
- Version: $version
- JAVA_VERSION: $java_version
- Release: $release_url

## Binary archives (SHA-256, per target)

EOF
    for target in windows-x64 macos-arm64 linux-x64 linux-arm64; do
        url="$(lock_prop "$RUNTIME_LOCK" "$target.archiveUrl")"
        sha="$(lock_prop "$RUNTIME_LOCK" "$target.sha256")"
        echo "- $target: $(basename "$url")"
        echo "  - $url"
        echo "  - $sha"
    done

    cat <<EOF

## Corresponding source

- $src_name (attached to this release as openjdk-$version-temurin-sources.tar.gz)
- $src_url
- SHA-256: $src_sha

## jlink

The bundled runtime is produced by jlink from the archives above; it only selects
modules and strips debug data, headers and man pages. The plugins applied:
--add-modules, --strip-debug (which on Linux also strips native debug symbols,
equivalent to objcopy -g, via jlink's strip-native-debug-symbols plugin),
--strip-native-commands, --no-header-files, --no-man-pages. One list serves every
target:

    $modules

## What the source tarball covers

The OpenJDK source tree of $src_name covers every GPL component in the bundle:
the class library and HotSpot, the jpackage launcher, and wixhelper.dll (all under
src/jdk.jpackage). It also covers the GCC runtime (GPL-3.0 with GCC-exception-3.1)
whose notes ship in runtime/legal/. Microsoft's VC++ redistributables on Windows
are proprietary and redistributable; they are not GPL-covered.

## Unmodified statement and the macOS signature

The runtime is unmodified: every native file under runtime/ is byte-identical to
the pinned archive's copy — verified by scripts/ci/check-runtime-sources.sh on
every target (on Linux after applying the same objcopy -g jlink applies; Temurin's
files are already stripped). Exception by construction: on macOS jpackage replaces
Adoptium's signatures on the runtime's Mach-O files with the bundle's ad-hoc
signature when it seals the .app — the code is unchanged; the check compares both
files with signatures removed (PO note, 11 Open questions).

The runtime's licence texts ship inside every image at runtime/legal/ (LICENSE,
ASSEMBLY_EXCEPTION, ADDITIONAL_LICENSE_INFO and the module licence files) and are
shown in the app's Licences screen.

## Other attached sources and why

- wix-$wix_version-src.tar.gz ($wix_src_name, SHA-256 $wix_src_sha): the MS-RL
  components the Windows MSI embeds (RemoveFolderEx's custom action, WixUI), from
  the pinned WiX release of desktopApp/wix.lock — attached whenever an MSI ships.
- ffmpeg-<ver>-neutrodyne-src.tar.xz: the LGPL-2.1 FFmpeg build's pristine source,
  attached from MD1b when the playback engine ships.
- python-build-standalone-<tag>-src.tar.gz: the PBS build of the bundled CPython,
  including its MPL-2.0 build patches, attached from MD3 when the desktop engine
  ships.
- neutrodyne-server-<v>-image-sources.tar.xz: the GPL/LGPL source packages of the
  pinned server base image, attached from MS1 when the server image ships.
EOF
} >"$OUT"

echo "wrote $OUT"
