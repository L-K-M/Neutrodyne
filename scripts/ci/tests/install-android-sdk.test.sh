#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# install-android-sdk.test.sh — boundary cases for scripts/ci/install-android-sdk.sh
# (09 CI scripts). The script under test keeps two mechanisms, and this suite
# covers both:
#
#   - revision pinning (merged from #47): cmdline-tools/latest is reused only
#     when its source.properties reports the lock's pinned Pkg.Revision,
#     otherwise the pinned archive is downloaded, SHA-256-verified, staged as
#     latest.new and renamed over latest;
#   - host matrix (from #34): the archive comes from android-sdk.lock per host
#     and the package install runs through the shipped bin/sdkmanager on
#     linux-x64 (where cmdline-tools 23.x's native bin/android actually runs) or
#     the pure-Java SdkManagerCli inside lib/ on linux-arm64 (Google ships no
#     linux/arm64 archive), macos-arm64 (the archive's launcher is
#     x86_64-only) and windows-x64.
#
# The fixtures are tiny zips named like the upstream archives; a stub curl
# records the requested URL and serves the matching fixture, stub java records
# and performs the SdkManagerCli calls, and the fixture bin/android stands in
# for the real native launcher: it installs only on the simulated linux-x64 and
# dies with the real "Exec format error" (exit 126) anywhere else — the literal
# failure nightly 2026-10-08 hit on ubuntu-24.04-arm and macos-15.
#
# The script resolves android-sdk.lock beside itself, so each run copies both
# into a fixture root — no repository state is touched. A copy of a pre-lock
# script carries its linux pin inline; rewriting the CMDLINE_TOOLS_SHA256 line
# (no-op on the lockfile script) plus writing the lock beside it lets a
# historical copy get past verification and expose its own bug.
#
# Also asserted: every `run:` step of the cross-platform desktop jobs sets
# `shell: bash` (the default pwsh on windows-2025 parses `-P…` Gradle arguments
# into task names and cannot run .sh steps natively), and every step that calls
# install-android-sdk.sh exports ANDROID_HOME and ANDROID_SDK_ROOT to the same
# directory (AGP fails when the inherited ANDROID_SDK_ROOT points at the runner
# image's SDK while ANDROID_HOME points at ours).
#
# SCRIPT_UNDER_TEST overrides the script path so the suite can be run against a
# historical copy (e.g. `git show 2233b01:scripts/ci/install-android-sdk.sh`
# fails the latest.new case; the pre-pin revision check fails the stale and
# missing-source.properties cases).
#
# Run: bash scripts/ci/tests/install-android-sdk.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

# --- fixture plumbing -----------------------------------------------------------

FIX_ROOT="$WORK/repo"
STUBBIN="$WORK/bin"
STUB_LOG="$WORK/logs"
FIXTURE_ZIPS="$WORK/zips"
BASEPATH="$PATH"
REAL_CURL="$(command -v curl)"
STUBBIN_CASE_EXTRA=""

mkdir -p "$FIX_ROOT/scripts/ci" "$STUBBIN" "$STUB_LOG" "$FIXTURE_ZIPS" "$WORK/home"
cp "${SCRIPT_UNDER_TEST:-$REPO_ROOT/scripts/ci/install-android-sdk.sh}" \
    "$FIX_ROOT/scripts/ci/install-android-sdk.sh"
SCRIPT_FILE="$FIX_ROOT/scripts/ci/install-android-sdk.sh"

# Fixture archives: the upstream file names, content reduced to the pinned
# source.properties, the markers each suite reads (host-marker,
# from-pinned-archive), two lib jars (so the Java classpath has a separator to
# convert on windows-x64) and the launchers.
python3 - "$FIXTURE_ZIPS" <<'PY'
import sys, zipfile, os.path

out = sys.argv[1]

# linux-x64's shipped shape: bin/sdkmanager delegates to the native bin/android.
SDKMANAGER_SH = '''#!/bin/sh
echo "sdkmanager $*" >> "$STUB_LOG/sdkmanager.log"
DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="$(cd "$DIR/../../.." && pwd)"
mode="" pkgs=""
for a in "$@"; do
  case "$a" in
    --licenses) mode=licenses ;;
    *) pkgs="$pkgs $a" ;;
  esac
done
[ -n "$mode" ] || mode=install
exec "$DIR/android" --sdk="$SDK_ROOT" sdk $mode$pkgs
'''

ANDROID_SH = '''#!/bin/sh
echo "android $*" >> "$STUB_LOG/android.log"
if [ "${SIM_HOST:-}" != "linux-x64" ]; then
  echo "$0: cannot execute binary file: Exec format error" >&2
  exit 126
fi
sdk=""
while [ $# -gt 0 ] && [ "$1" != "sdk" ]; do
  case "$1" in --sdk=*) sdk="${1#*=}" ;; esac
  shift
done
[ "${1:-}" = "sdk" ] && shift
sub="${1:-}"
[ $# -gt 0 ] && shift
case "$sub" in
  licenses)
    mkdir -p "$sdk/licenses"
    printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\\n' > "$sdk/licenses/android-sdk-license" ;;
  install)
    for p in "$@"; do mkdir -p "$sdk/$(printf '%s' "$p" | tr ';' '/')"; done ;;
esac
'''

SPECS = {
    'commandlinetools-linux-16111833_latest.zip': 'linux-x64',
    'commandlinetools-mac_x86_64-16111833_latest.zip': 'mac_x86_64',
    'commandlinetools-win-16111833_latest.zip': 'windows-x64',
}
for name, marker in SPECS.items():
    win = marker == 'windows-x64'
    entries = {
        'cmdline-tools/host-marker': marker + '\n',
        'cmdline-tools/from-pinned-archive': 'built by fixture\n',
        'cmdline-tools/source.properties': 'Pkg.Revision=23.0\nPkg.Path=cmdline-tools;latest\n',
        'cmdline-tools/lib/sdklib/tools.sdklib.jar': 'fixture jar\n',
        'cmdline-tools/lib/repository/tools.repository.jar': 'fixture jar\n',
    }
    if win:
        entries['cmdline-tools/bin/sdkmanager.bat'] = 'rem fixture\n'
        entries['cmdline-tools/bin/android.exe'] = 'MZ fixture\n'
    else:
        entries['cmdline-tools/bin/sdkmanager'] = SDKMANAGER_SH
        entries['cmdline-tools/bin/android'] = ANDROID_SH
    with zipfile.ZipFile(os.path.join(out, name), 'w') as z:
        for n, c in entries.items():
            z.writestr(n, c)
PY

sha_of() { sha256sum "$1" | cut -d' ' -f1; }
LINUX_SHA="$(sha_of "$FIXTURE_ZIPS/commandlinetools-linux-16111833_latest.zip")"
MAC_SHA="$(sha_of "$FIXTURE_ZIPS/commandlinetools-mac_x86_64-16111833_latest.zip")"
WIN_SHA="$(sha_of "$FIXTURE_ZIPS/commandlinetools-win-16111833_latest.zip")"

write_lock() { # write_lock <linux-sha> — fixture pin table beside the copy
    local lsha="$1"
    cat > "$FIX_ROOT/scripts/ci/android-sdk.lock" <<EOF
cmdline-tools.revision=23.0
cmdline-tools.linux-x64.archiveUrl=https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
cmdline-tools.linux-x64.sha256=$lsha
cmdline-tools.linux-arm64.archiveUrl=https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
cmdline-tools.linux-arm64.sha256=$lsha
cmdline-tools.macos-arm64.archiveUrl=https://dl.google.com/android/repository/commandlinetools-mac_x86_64-16111833_latest.zip
cmdline-tools.macos-arm64.sha256=$MAC_SHA
cmdline-tools.windows-x64.archiveUrl=https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip
cmdline-tools.windows-x64.sha256=$WIN_SHA
EOF
}
write_lock "$LINUX_SHA"

# A pre-lock historical script carries its linux pin inline; repoint it at the
# fixture's real hash so the run gets past verification and exposes the bug the
# copy exists to exercise. The lockfile script has no such line — sed is a no-op.
sed -i "s/^CMDLINE_TOOLS_SHA256=.*/CMDLINE_TOOLS_SHA256=\"$LINUX_SHA\"/" "$SCRIPT_FILE"

# --- stubs ----------------------------------------------------------------------

cat > "$STUBBIN/uname" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
    -s) echo "$SIM_UNAME_S" ;;
    -m) echo "$SIM_UNAME_M" ;;
    *)  echo "$SIM_UNAME_S $SIM_UNAME_M" ;;
esac
EOF
cat > "$STUBBIN/curl" <<'EOF'
#!/usr/bin/env bash
url="" out=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out="$2"; shift 2 ;;
        http*) url="$1"; shift ;;
        *) shift ;;
    esac
done
echo "$(basename "$url")" >> "$STUB_LOG/curl.log"
exec "$REAL_CURL" -fsSL "file://$FIXTURE_ZIPS/$(basename "$url")" -o "$out"
EOF
cat > "$STUBBIN/java" <<'EOF'
#!/usr/bin/env bash
{ echo "== invocation =="; printf '%s\n' "$@"; } >> "$STUB_LOG/java.log"
sdk="" mode="" pkgs=()
for a in "$@"; do
    case "$a" in
        --sdk_root=*) sdk="${a#*=}" ;;
        --licenses) mode=licenses ;;
        --install) mode=install ;;
        platforms\;*|build-tools\;*) [ "$mode" = install ] && pkgs+=("$a") ;;
    esac
done
case "$mode" in
    licenses)
        mkdir -p "$sdk/licenses"
        printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$sdk/licenses/android-sdk-license" ;;
    install)
        for p in ${pkgs[@]+"${pkgs[@]}"}; do
            mkdir -p "$sdk/$(printf '%s' "$p" | tr ';' '/')"
        done ;;
esac
exit 0
EOF
chmod +x "$STUBBIN/"*

# run_install <uname -s> <uname -m> <sim-host> <sdk-dir> — fresh logs each run.
run_install() {
    local s="$1" m="$2" host="$3" dir="$4"
    rm -f "$STUB_LOG"/*.log
    env -i PATH="$STUBBIN:$STUBBIN_CASE_EXTRA$BASEPATH" HOME="$WORK/home" \
        LANG=C.UTF-8 STUB_LOG="$STUB_LOG" FIXTURE_ZIPS="$FIXTURE_ZIPS" \
        REAL_CURL="$REAL_CURL" SIM_UNAME_S="$s" SIM_UNAME_M="$m" SIM_HOST="$host" \
        bash "$SCRIPT_FILE" "$dir"
}

has_pkg_dirs() {
    [ -d "$1/platforms/android-37.0" ] && [ -d "$1/build-tools/36.0.0" ]
}

# seed_tools <sdk-dir> <revision|none> — an installed cmdline-tools/latest.
# The seeded sdkmanager is a self-contained stub for the revision cases' package
# calls; the archive's own bin/sdkmanager keeps delegating to bin/android.
seed_tools() {
    mkdir -p "$1/cmdline-tools/latest/bin"
    if [ "$2" != "none" ]; then
        printf 'Pkg.Revision=%s\n' "$2" > "$1/cmdline-tools/latest/source.properties"
    fi
    cat > "$1/cmdline-tools/latest/bin/sdkmanager" <<'EOF'
#!/bin/sh
echo "sdkmanager $*" >> "$STUB_LOG/sdkmanager.log"
SDK_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
install=""
for a in "$@"; do
  case "$a" in
    --licenses) mkdir -p "$SDK_ROOT/licenses" ;;
    --install) install=yes ;;
    *) [ -n "$install" ] && mkdir -p "$SDK_ROOT/$(printf '%s' "$a" | tr ';' '/')" ;;
  esac
done
exit 0
EOF
    chmod +x "$1/cmdline-tools/latest/bin/sdkmanager"
}

seed_packages() {
    mkdir -p "$1/platforms/android-37.0" "$1/build-tools/36.0.0"
}

latest_revision() {
    sed -n 's/^Pkg\.Revision=//p' "$1/cmdline-tools/latest/source.properties" | tr -d '\r'
}

# pinned_layout_ok <sdk-dir> — the fixture tree landed at cmdline-tools/latest.
pinned_layout_ok() {
    [ -x "$1/cmdline-tools/latest/bin/sdkmanager" ] \
        && [ "$(latest_revision "$1")" = "23.0" ] \
        && [ -f "$1/cmdline-tools/latest/from-pinned-archive" ] \
        && [ ! -d "$1/cmdline-tools/latest/cmdline-tools" ]
}

# --- revision pinning and staging (from #47, adapted to the lockfile installer) --

# 1. A pinned install is reused: no download, no package calls, no staging.
SDK="$WORK/sdk-reuse"
seed_tools "$SDK" 23.0
seed_packages "$SDK"
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && grep -q 'already present' "$WORK/out" \
    && [ ! -f "$STUB_LOG/curl.log" ] && [ ! -f "$STUB_LOG/sdkmanager.log" ] \
    && [ ! -e "$SDK/cmdline-tools/latest/from-pinned-archive" ]; then
    t_ok "pinned cmdline-tools 23.0 is reused without a download"
else
    t_fail "pinned cmdline-tools 23.0 is reused without a download"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 2. A stale-revision install is replaced by the pinned archive.
SDK="$WORK/sdk-stale"
seed_tools "$SDK" 12.0
seed_packages "$SDK"
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && [ "$(cat "$STUB_LOG/curl.log")" = "commandlinetools-linux-16111833_latest.zip" ] \
    && pinned_layout_ok "$SDK" \
    && grep -q 'replacing cmdline-tools 12.0' "$WORK/out"; then
    t_ok "stale cmdline-tools 12.0 is replaced by the pinned archive"
else
    t_fail "stale cmdline-tools 12.0 is replaced by the pinned archive"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 3. A leftover latest.new from an interrupted run cannot corrupt the layout:
#    the staging dir is removed before the verified tree is staged, so latest
#    ends up as the archive root rather than a dir containing a nested
#    cmdline-tools/. The previous version exited 0 here with
#    latest/cmdline-tools/bin/sdkmanager and no latest/bin/sdkmanager.
SDK="$WORK/sdk-staging"
seed_tools "$SDK" 12.0
seed_packages "$SDK"
mkdir -p "$SDK/cmdline-tools/latest.new/leftover"
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && pinned_layout_ok "$SDK" \
    && [ ! -e "$SDK/cmdline-tools/latest.new" ] \
    && [ ! -e "$SDK/cmdline-tools/latest/leftover" ]; then
    t_ok "stale latest.new staging dir cannot nest the new tree"
else
    t_fail "stale latest.new staging dir cannot nest the new tree"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 4. An install without source.properties cannot prove its revision, so it is
#    replaced rather than trusted.
SDK="$WORK/sdk-noprops"
seed_tools "$SDK" none
seed_packages "$SDK"
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && [ -f "$STUB_LOG/curl.log" ] && pinned_layout_ok "$SDK"; then
    t_ok "missing source.properties forces a pinned reinstall"
else
    t_fail "missing source.properties forces a pinned reinstall"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 5. Missing packages go through the (already pinned) sdkmanager: licenses,
#    then install of the pinned package set.
SDK="$WORK/sdk-pkgs"
seed_tools "$SDK" 23.0
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && [ ! -f "$STUB_LOG/curl.log" ] \
    && grep -q 'sdkmanager --licenses' "$STUB_LOG/sdkmanager.log" \
    && grep -q 'sdkmanager --install platforms;android-37.0 build-tools;36.0.0' \
        "$STUB_LOG/sdkmanager.log" \
    && has_pkg_dirs "$SDK"; then
    t_ok "missing packages are installed through the pinned sdkmanager"
else
    t_fail "missing packages are installed through the pinned sdkmanager"
    sed 's/^/    /' "$WORK/out" >&2
fi

# --- host selection and launchers -------------------------------------------------

# 6. linux-x64: the linux archive lands and the shipped bin/sdkmanager (which
#    delegates to the native bin/android) performs the install — no Java
#    SdkManagerCli. This is the path the release/repro container pins.
SDK="$WORK/sdk-linux-x64"
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && has_pkg_dirs "$SDK" \
    && [ "$(cat "$STUB_LOG/curl.log")" = "commandlinetools-linux-16111833_latest.zip" ] \
    && [ "$(cat "$SDK/cmdline-tools/latest/host-marker")" = "linux-x64" ] \
    && [ -f "$STUB_LOG/sdkmanager.log" ] && [ -f "$STUB_LOG/android.log" ] \
    && [ ! -f "$STUB_LOG/java.log" ] \
    && [ -f "$SDK/licenses/android-sdk-license" ]; then
    t_ok "linux-x64 installs via the shipped sdkmanager/native android"
else
    t_fail "linux-x64 installs via the shipped sdkmanager/native android"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 7. linux-arm64: no upstream linux/arm64 archive exists, so the linux archive
#    lands but the packages must be installed through Java's SdkManagerCli —
#    the shipped launcher would die with "Exec format error".
SDK="$WORK/sdk-linux-arm64"
if run_install Linux aarch64 linux-arm64 "$SDK" > "$WORK/out" 2>&1 \
    && has_pkg_dirs "$SDK" \
    && [ "$(cat "$STUB_LOG/curl.log")" = "commandlinetools-linux-16111833_latest.zip" ] \
    && grep -q 'com\.android\.sdklib\.tool\.sdkmanager\.SdkManagerCli' "$STUB_LOG/java.log" 2>/dev/null \
    && grep -q 'platforms;android-37\.0' "$STUB_LOG/java.log" \
    && grep -q 'build-tools;36\.0\.0' "$STUB_LOG/java.log" \
    && [ ! -f "$STUB_LOG/android.log" ] && [ ! -f "$STUB_LOG/sdkmanager.log" ]; then
    t_ok "linux-arm64 installs via the Java SdkManagerCli, never the native launcher"
else
    t_fail "linux-arm64 installs via the Java SdkManagerCli, never the native launcher"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 8. macos-arm64: the macOS (x86_64) archive lands, but its native launcher is
#    x86_64-only — the install must again go through the Java SdkManagerCli.
SDK="$WORK/sdk-macos-arm64"
if run_install Darwin arm64 macos-arm64 "$SDK" > "$WORK/out" 2>&1 \
    && has_pkg_dirs "$SDK" \
    && [ "$(cat "$STUB_LOG/curl.log")" = "commandlinetools-mac_x86_64-16111833_latest.zip" ] \
    && [ "$(cat "$SDK/cmdline-tools/latest/host-marker")" = "mac_x86_64" ] \
    && grep -q 'SdkManagerCli' "$STUB_LOG/java.log" 2>/dev/null \
    && [ ! -f "$STUB_LOG/android.log" ] && [ ! -f "$STUB_LOG/sdkmanager.log" ]; then
    t_ok "macos-arm64 fetches the macOS archive and installs via SdkManagerCli"
else
    t_fail "macos-arm64 fetches the macOS archive and installs via SdkManagerCli"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 9. windows-x64: the windows archive lands; the Java launcher gets a
#    semicolon-separated classpath through cygpath (-pw) and Windows-style
#    paths for toolsdir/sdk_root (-w).
STUBBIN_CASE_EXTRA="$WORK/bin-case"
mkdir -p "$STUBBIN_CASE_EXTRA"
cat > "$STUBBIN_CASE_EXTRA/cygpath" <<'EOF'
#!/usr/bin/env bash
echo "cygpath $*" >> "$STUB_LOG/cygpath.log"
case "$1" in
    -pw) shift; printf '%s' "$1" | tr ':' ';' ;;
    *)   shift; printf '%s' "$1" ;;
esac
EOF
chmod +x "$STUBBIN_CASE_EXTRA/cygpath"
STUBBIN_CASE_EXTRA="$STUBBIN_CASE_EXTRA:"
SDK="$WORK/sdk-windows-x64"
if run_install MINGW64_NT-10.0 x86_64 windows-x64 "$SDK" > "$WORK/out" 2>&1 \
    && has_pkg_dirs "$SDK" \
    && [ "$(cat "$STUB_LOG/curl.log")" = "commandlinetools-win-16111833_latest.zip" ] \
    && [ "$(cat "$SDK/cmdline-tools/latest/host-marker")" = "windows-x64" ] \
    && grep -q 'SdkManagerCli' "$STUB_LOG/java.log" 2>/dev/null \
    && awk 'prev=="-cp" && /;/{found=1} {prev=$0} END{exit !found}' "$STUB_LOG/java.log" \
    && grep -q '\-pw' "$STUB_LOG/cygpath.log" 2>/dev/null; then
    t_ok "windows-x64 fetches the windows archive and uses a ; classpath"
else
    t_fail "windows-x64 fetches the windows archive and uses a ; classpath"
    sed 's/^/    /' "$WORK/out" >&2
fi
STUBBIN_CASE_EXTRA=""

# 10. Unsupported host: explicit failure before anything is downloaded — no
#     silent fallthrough onto the linux archive.
SDK="$WORK/sdk-unsupported"
if ! run_install FreeBSD amd64 freebsd-x64 "$SDK" > "$WORK/out" 2>&1 \
    && grep -qi 'unsupported' "$WORK/out" \
    && [ ! -f "$STUB_LOG/curl.log" ]; then
    t_ok "unsupported host fails explicitly before any download"
else
    t_fail "unsupported host fails explicitly before any download"
    sed 's/^/    /' "$WORK/out" >&2
fi

# --- integrity and idempotence ---------------------------------------------------

# 11. Checksum rejection: pin the macOS fixture's hash for the linux archive —
#     sha256sum must refuse it and nothing may be installed.
SDK="$WORK/sdk-badsum"
write_lock "$MAC_SHA"
if ! run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && grep -qiE 'sha|checksum|mismatch' "$WORK/out" \
    && [ ! -d "$SDK/cmdline-tools/latest" ]; then
    t_ok "a checksum mismatch aborts before install"
else
    t_fail "a checksum mismatch aborts before install"
    sed 's/^/    /' "$WORK/out" >&2
fi
write_lock "$LINUX_SHA"

# 12. Idempotence: a second run on a complete SDK downloads nothing and calls
#     no tool.
SDK="$WORK/sdk-linux-x64"
if run_install Linux x86_64 linux-x64 "$SDK" > "$WORK/out" 2>&1 \
    && [ ! -f "$STUB_LOG/curl.log" ] && [ ! -f "$STUB_LOG/java.log" ] \
    && [ ! -f "$STUB_LOG/android.log" ] \
    && grep -q 'already installed' "$WORK/out"; then
    t_ok "a complete SDK is reused without downloads"
else
    t_fail "a complete SDK is reused without downloads"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 13. Cached tools but missing packages: no archive download, the install still
#     runs through the host's launcher.
SDK="$WORK/sdk-partial"
mkdir -p "$SDK/cmdline-tools"
python3 - "$FIXTURE_ZIPS/commandlinetools-linux-16111833_latest.zip" "$SDK" <<'PY'
import sys, zipfile, os
z = zipfile.ZipFile(sys.argv[1])
dest = os.path.join(sys.argv[2], 'cmdline-tools')
z.extractall(dest)
os.rename(os.path.join(dest, 'cmdline-tools'), os.path.join(dest, 'latest'))
PY
chmod +x "$SDK/cmdline-tools/latest/bin/"* 2>/dev/null || true
if run_install Linux aarch64 linux-arm64 "$SDK" > "$WORK/out" 2>&1 \
    && has_pkg_dirs "$SDK" \
    && [ ! -f "$STUB_LOG/curl.log" ] \
    && grep -q 'SdkManagerCli' "$STUB_LOG/java.log" 2>/dev/null; then
    t_ok "cached cmdline-tools are reused; packages still install"
else
    t_fail "cached cmdline-tools are reused; packages still install"
    sed 's/^/    /' "$WORK/out" >&2
fi

# --- workflow invariants ----------------------------------------------------------

# 14. Every run: step of the cross-platform desktop jobs sets shell: bash, and
#     every step calling install-android-sdk.sh exports ANDROID_HOME and
#     ANDROID_SDK_ROOT with the same value to $GITHUB_ENV.
if ! python3 - "$REPO_ROOT" <<'PY'
import re, sys

root = sys.argv[1]
indent = lambda s: len(s) - len(s.lstrip(' '))
ok = True

def job_block(lines, name):
    i = next(i for i, l in enumerate(lines) if l.strip() == name + ':')
    ind = indent(lines[i])
    j = next((k for k in range(i + 1, len(lines))
              if lines[k].strip() and indent(lines[k]) <= ind), len(lines))
    return lines[i:j]

def steps(block):
    out, cur = [], None
    for l in block:
        if re.match(r'\s+- ', l):
            if cur is not None:
                out.append(cur)
            cur = [l]
        elif cur is not None:
            cur.append(l)
    if cur is not None:
        out.append(cur)
    return out

def run_text(step):
    for i, l in enumerate(step):
        if re.match(r'\s+run:\s*\|', l):
            ind = indent(l)
            body = []
            for l in step[i + 1:]:
                if l.strip() and indent(l) <= ind:
                    break
                body.append(l)
            return body
    return None

for wf, jobs in (('.github/workflows/nightly.yml', ('desktop-matrix',)),
                 ('.github/workflows/release.yml', ('desktop',))):
    lines = open(root + '/' + wf).read().splitlines()
    for job in jobs:
        for s in steps(job_block(lines, job)):
            if run_text(s) is None:
                continue
            name = next((l.strip() for l in s if l.strip().startswith('- name:')),
                        s[0].strip())
            if not any(re.match(r'\s+shell:\s*bash\b', l) for l in s):
                print('missing shell: bash — %s %s: %s' % (wf, job, name))
                ok = False

for wf in ('.github/workflows/nightly.yml', '.github/workflows/release.yml'):
    lines = open(root + '/' + wf).read().splitlines()
    for s in steps(lines):
        body = run_text(s)
        if body is None or not any('install-android-sdk.sh' in l for l in body):
            continue
        name = next((l.strip() for l in s if l.strip().startswith('- name:')),
                    s[0].strip())
        vals = {}
        for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
            m = next((re.search(r'\b%s=([^"\'\s]+)' % var, l) for l in body
                      if 'GITHUB_ENV' in l and ('%s=' % var) in l), None)
            vals[var] = m.group(1) if m else None
        if vals['ANDROID_HOME'] is None or vals['ANDROID_HOME'] != vals['ANDROID_SDK_ROOT']:
            print('SDK step does not export equal ANDROID_HOME/ANDROID_SDK_ROOT — %s: %s'
                  % (wf, name))
            ok = False

sys.exit(0 if ok else 1)
PY
then
    t_fail "desktop run steps use bash and SDK steps export one SDK location"
else
    t_ok "desktop run steps use bash and SDK steps export one SDK location"
fi

# --- summary -----------------------------------------------------------------------

if [ "$FAILED" -eq 0 ]; then
    echo "all install-android-sdk cases pass"
else
    echo "$FAILED install-android-sdk case(s) failed" >&2
fi
exit "$([ "$FAILED" -eq 0 ] && echo 0 || echo 1)"
