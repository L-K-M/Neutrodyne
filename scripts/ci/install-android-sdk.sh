#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# install-android-sdk.sh [sdk-dir]
#
# Installs the Android SDK pieces the builds need, pinned and checksum-verified
# (09 CI scripts, 01 Toolchain and versions): the command-line tools build below,
# `platforms;android-37` and `build-tools;36.0.0`. Used inside the release/repro
# container (repro-build.sh) and on bare runners. Existing installations are
# reused; the script is idempotent.
#
# sdk-dir defaults to $ANDROID_HOME (or $ANDROID_SDK_ROOT), then to
# $HOME/android-sdk.

set -euo pipefail

CMDLINE_TOOLS_BUILD="16111833"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
CMDLINE_TOOLS_SHA256="0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"

SDK_PACKAGES=(                                     # 01 Toolchain and versions
    "platforms;android-37.0"                       # minor-versioned since the 36.1 repackaging
    "build-tools;36.0.0"
)

SDK_DIR="${1:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}}"

command -v curl >/dev/null 2>&1 || { echo "install-android-sdk: curl not found" >&2; exit 2; }
# unzip first; bare hosts (and the slim Python container before its apt line)
# fall back to python3's zipfile.
if ! command -v unzip >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1; then
    echo "install-android-sdk: need unzip or python3" >&2; exit 2
fi
command -v java  >/dev/null 2>&1 || { echo "install-android-sdk: java not found (set JAVA_HOME)" >&2; exit 2; }

unzip_dir() { # unzip_dir <zip> <dest>
    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$1" -d "$2"
    else
        python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$1" "$2"
    fi
}

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    echo "install-android-sdk: installing cmdline-tools $CMDLINE_TOOLS_BUILD into $SDK_DIR"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/$CMDLINE_TOOLS_ZIP"
    echo "$CMDLINE_TOOLS_SHA256  $tmp/$CMDLINE_TOOLS_ZIP" | sha256sum -c - >/dev/null
    mkdir -p "$SDK_DIR/cmdline-tools"
    unzip_dir "$tmp/$CMDLINE_TOOLS_ZIP" "$tmp/tools"
    # google's zip contains a top-level cmdline-tools/; sdkmanager wants <sdk>/cmdline-tools/latest/
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$tmp/tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    # python's zipfile path drops the zip's unix mode bits — restore them
    chmod +x "$SDK_DIR/cmdline-tools/latest/bin/"* 2>/dev/null || true
    rm -rf "$tmp"
    trap - EXIT
else
    echo "install-android-sdk: cmdline-tools already present"
fi

export PATH="$SDK_DIR/cmdline-tools/latest/bin:$PATH"

if [ -d "$SDK_DIR/platforms/android-37.0" ] && [ -d "$SDK_DIR/build-tools/36.0.0" ]; then
    echo "install-android-sdk: required packages already installed"
    exit 0
fi

# Accept the licences once (build machines are throwaway; the file lands in the SDK dir).
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --install "${SDK_PACKAGES[@]}"

echo "install-android-sdk: done — $SDK_DIR"
