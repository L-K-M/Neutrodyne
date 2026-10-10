#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# install-android-sdk.sh [sdk-dir]
#
# Installs the Android SDK pieces the builds need, pinned and checksum-verified
# (09 CI scripts, 01 Toolchain and versions): the host's cmdline-tools archive
# from android-sdk.lock beside this script, `platforms;android-37.0` and
# `build-tools;36.0.0`. Used inside the release/repro container (repro-build.sh)
# and on bare runners. An existing cmdline-tools is reused only when its
# source.properties reports the lock's pinned revision — the ubuntu-24.04
# runner image ships cmdline-tools 12.0, whose avdmanager cannot parse a dotted
# api level (exits 0, writes `target=android-0` in the AVD's .ini), so "exists"
# alone is not enough.
#
# sdk-dir defaults to $ANDROID_HOME (or $ANDROID_SDK_ROOT), then to
# $HOME/android-sdk.
#
# Hosts: cmdline-tools 23.x's bin/sdkmanager only delegates to the native
# bin/android launcher, which Google ships for linux-x64, macosx-x86_64 and
# windows-x64 — there is no linux/arm64 archive and the macOS archive's
# launcher is x86_64-only (nightly 2026-10-08: "Exec format error" on
# ubuntu-24.04-arm and macos-15). linux-x64 runs the shipped bin/sdkmanager
# unchanged; the other supported hosts run the pure-Java SdkManagerCli the
# archive still carries in lib/ — no native code executes. Any other host
# fails explicitly.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
LOCK_FILE="$SCRIPT_DIR/android-sdk.lock"

SDK_PACKAGES=(                                     # 01 Toolchain and versions
    "platforms;android-37.0"                       # minor-versioned since the 36.1 repackaging
    "build-tools;36.0.0"
)

SDK_DIR="${1:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}}"

case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)                        HOST=linux-x64 ;;
    Linux/aarch64|Linux/arm64)           HOST=linux-arm64 ;;
    Darwin/arm64)                        HOST=macos-arm64 ;;
    MINGW*/x86_64|MSYS*/x86_64|CYGWIN*/x86_64) HOST=windows-x64 ;;
    *) echo "install-android-sdk: unsupported host $(uname -s)/$(uname -m)" >&2; exit 2 ;;
esac

command -v curl >/dev/null 2>&1 || { echo "install-android-sdk: curl not found" >&2; exit 2; }
# unzip first; bare hosts (and the slim Python container before its apt line)
# fall back to python3's zipfile.
if ! command -v unzip >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1; then
    echo "install-android-sdk: need unzip or python3" >&2; exit 2
fi
command -v java  >/dev/null 2>&1 || { echo "install-android-sdk: java not found (set JAVA_HOME)" >&2; exit 2; }

lock_prop() { # lock_prop <key>
    local v
    v="$(grep -E "^$1=" "$LOCK_FILE" 2>/dev/null | tail -1 | cut -d= -f2-)"
    [ -n "$v" ] || { echo "install-android-sdk: $LOCK_FILE has no '$1'" >&2; exit 2; }
    printf '%s\n' "$v"
}
# Pkg.Revision of the locked archives; keep the lock's key in sync with the
# archives it names.
CMDLINE_TOOLS_REVISION="$(lock_prop "cmdline-tools.revision")"
ARCHIVE_URL="$(lock_prop "cmdline-tools.$HOST.archiveUrl")"
ARCHIVE_SHA256="$(lock_prop "cmdline-tools.$HOST.sha256")"

verify_sha256() { # verify_sha256 <hex> <file>
    if command -v sha256sum >/dev/null 2>&1; then
        echo "$1  $2" | sha256sum -c - >/dev/null
    elif command -v shasum >/dev/null 2>&1; then
        echo "$1  $2" | shasum -a 256 -c - >/dev/null
    else
        python3 - "$1" "$2" <<'PY'
import hashlib, sys
sys.exit(0 if hashlib.sha256(open(sys.argv[2], 'rb').read()).hexdigest() == sys.argv[1] else 1)
PY
    fi
}

unzip_dir() { # unzip_dir <zip> <dest>
    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$1" -d "$2"
    else
        python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$1" "$2"
    fi
}

TOOLS_DIR="$SDK_DIR/cmdline-tools/latest"

# sdkmanager <args> — the classic CLI on every host. linux-x64 execs the shipped
# bin/sdkmanager (which delegates to the native bin/android), keeping the
# release/repro container byte-identical. The other hosts run the pure-Java
# SdkManagerCli still inside lib/ instead: no linux/arm64 launcher exists and
# the macOS archive's bin/android is x86_64-only; windows-x64 could run
# android.exe but shares the Java path so every non-linux-x64 host is one code
# path. Windows java.exe needs native C:\ paths and a ';' classpath separator —
# cygpath converts the MSYS forms.
sdkmanager() {
    if [ "$HOST" = linux-x64 ]; then
        "$TOOLS_DIR/bin/sdkmanager" "$@"
        return
    fi
    local cp toolsdir="$TOOLS_DIR" sdkroot="$SDK_DIR"
    cp="$(find "$TOOLS_DIR/lib" -name '*.jar' | sort | paste -sd: -)"
    if [ "$HOST" = windows-x64 ]; then
        cp="$(cygpath -pw "$cp")"
        toolsdir="$(cygpath -w "$toolsdir")"
        sdkroot="$(cygpath -w "$sdkroot")"
    fi
    java -Dcom.android.sdkmanager.toolsdir="$toolsdir" -cp "$cp" \
        com.android.sdklib.tool.sdkmanager.SdkManagerCli --sdk_root="$sdkroot" "$@"
}

INSTALLED_REVISION=""
if [ -f "$TOOLS_DIR/source.properties" ]; then
    INSTALLED_REVISION="$(sed -n 's/^Pkg\.Revision=//p' "$TOOLS_DIR/source.properties" | head -n 1 | tr -d '\r')"
fi
if [ -x "$TOOLS_DIR/bin/sdkmanager" ] && [ "$INSTALLED_REVISION" = "$CMDLINE_TOOLS_REVISION" ]; then
    echo "install-android-sdk: cmdline-tools $INSTALLED_REVISION already present"
else
    if [ -n "$INSTALLED_REVISION" ]; then
        echo "install-android-sdk: replacing cmdline-tools $INSTALLED_REVISION with pinned $CMDLINE_TOOLS_REVISION"
    else
        echo "install-android-sdk: installing $(basename "$ARCHIVE_URL") (revision $CMDLINE_TOOLS_REVISION) into $SDK_DIR ($HOST)"
    fi
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL "$ARCHIVE_URL" -o "$tmp/cmdline-tools.zip"
    verify_sha256 "$ARCHIVE_SHA256" "$tmp/cmdline-tools.zip" \
        || { echo "install-android-sdk: SHA-256 mismatch for $ARCHIVE_URL" >&2; exit 1; }
    mkdir -p "$SDK_DIR/cmdline-tools"
    unzip_dir "$tmp/cmdline-tools.zip" "$tmp/tools"
    # google's zip contains a top-level cmdline-tools/; sdkmanager wants
    # <sdk>/cmdline-tools/latest/. Stage the verified tree inside cmdline-tools/
    # first so the rm/mv on latest stays same-filesystem — and never let
    # sdkmanager upgrade itself (it would land in a sibling latest-2 that
    # nothing puts on PATH). A leftover latest.new from an interrupted run must
    # go first: mv into an existing dir would nest the tree.
    rm -rf "$SDK_DIR/cmdline-tools/latest.new"
    mv "$tmp/tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest.new"
    rm -rf "$TOOLS_DIR"
    mv "$SDK_DIR/cmdline-tools/latest.new" "$TOOLS_DIR"
    # python's zipfile path drops the zip's unix mode bits — restore them
    chmod +x "$TOOLS_DIR/bin/"* 2>/dev/null || true
    rm -rf "$tmp"
    trap - EXIT
fi

export PATH="$TOOLS_DIR/bin:$PATH"

if [ -d "$SDK_DIR/platforms/android-37.0" ] && [ -d "$SDK_DIR/build-tools/36.0.0" ]; then
    echo "install-android-sdk: required packages already installed"
    exit 0
fi

# Accept the licences once (build machines are throwaway; the file lands in the SDK dir).
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "${SDK_PACKAGES[@]}"

echo "install-android-sdk: done — $SDK_DIR"
