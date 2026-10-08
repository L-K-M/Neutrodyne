#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# install-android-sdk.test.sh — boundary cases for scripts/ci/install-android-sdk.sh
# (09 CI scripts). The script under test reuses cmdline-tools/latest only when
# its source.properties reports the pinned Pkg.Revision, otherwise downloads the
# pinned archive, verifies its SHA-256, stages the tree as latest.new and renames
# it over latest.
#
# A tiny fixture zip named like the upstream archive stands in for the network:
# a stub curl records the requested URL and copies the fixture, and a stub java
# satisfies the script's presence check (the fixture's bin/sdkmanager is a shell
# stub, so no real JVM runs). The script is copied into the fixture root and its
# CMDLINE_TOOLS_SHA256 line repointed at the fixture's real hash, so the checksum
# path under test is unchanged.
#
# Cases cover: reuse of a pinned install, replacement of a stale-revision
# install, a leftover latest.new staging dir (an interrupted run used to nest
# the new tree inside it, leaving latest/bin/sdkmanager missing while exiting
# 0), missing source.properties, a checksum mismatch, and the sdkmanager
# package-install invocation.
#
# SCRIPT_UNDER_TEST overrides the script path so the suite can be run against a
# historical copy (e.g. `git show 2233b01:scripts/ci/install-android-sdk.sh`
# fails the latest.new case; the pre-pin revision check fails the stale and
# missing-source.properties cases).
#
# Note for merge resolution: the #34 branch adds a same-named file covering the
# cross-platform host-matrix cases of its lockfile installer — merge keeps both
# scenario sets in one file.
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

mkdir -p "$FIX_ROOT/scripts/ci" "$STUBBIN" "$STUB_LOG" "$FIXTURE_ZIPS" "$WORK/home"
cp "${SCRIPT_UNDER_TEST:-$REPO_ROOT/scripts/ci/install-android-sdk.sh}" \
    "$FIX_ROOT/scripts/ci/install-android-sdk.sh"
SCRIPT_FILE="$FIX_ROOT/scripts/ci/install-android-sdk.sh"

# Fixture archive: the upstream file name, content reduced to the pinned
# source.properties, a stub bin/sdkmanager, and a marker proving provenance.
python3 - "$FIXTURE_ZIPS" <<'PY'
import sys, zipfile, os.path

SDKMANAGER_SH = '''#!/bin/sh
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
'''

with zipfile.ZipFile(
    os.path.join(sys.argv[1], 'commandlinetools-linux-16111833_latest.zip'), 'w'
) as z:
    z.writestr('cmdline-tools/source.properties',
               'Pkg.Revision=23.0\nPkg.Path=cmdline-tools;latest\n')
    z.writestr('cmdline-tools/bin/sdkmanager', SDKMANAGER_SH)
    z.writestr('cmdline-tools/from-pinned-archive', 'built by fixture\n')
PY

# Keep the checksum gate real: pin the copied script to the fixture's own hash.
LINUX_SHA="$(sha256sum "$FIXTURE_ZIPS/commandlinetools-linux-16111833_latest.zip" | cut -d' ' -f1)"
sed -i "s/^CMDLINE_TOOLS_SHA256=.*/CMDLINE_TOOLS_SHA256=\"$LINUX_SHA\"/" "$SCRIPT_FILE"

# --- stubs ----------------------------------------------------------------------

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
cp "$FIXTURE_ZIPS/$(basename "$url")" "$out"
EOF
cat > "$STUBBIN/java" <<'EOF'
#!/usr/bin/env bash
# the script only probes `command -v java`; the fixture sdkmanager is a shell stub
exit 0
EOF
chmod +x "$STUBBIN/"*

# run_install <sdk-dir> — fresh stub logs each run.
run_install() {
    rm -f "$STUB_LOG"/*.log
    env -i PATH="$STUBBIN:$BASEPATH" HOME="$WORK/home" LANG=C.UTF-8 \
        STUB_LOG="$STUB_LOG" FIXTURE_ZIPS="$FIXTURE_ZIPS" \
        bash "$SCRIPT_FILE" "$1"
}

# seed_tools <sdk-dir> <revision|none> — an installed cmdline-tools/latest.
# The seeded sdkmanager is the same logging stub the fixture archive carries.
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

# --- cases -----------------------------------------------------------------------

# 1. A pinned install is reused: no download, no package calls, no staging.
SDK="$WORK/sdk-reuse"
seed_tools "$SDK" 23.0
seed_packages "$SDK"
if run_install "$SDK" > "$WORK/out" 2>&1 \
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
if run_install "$SDK" > "$WORK/out" 2>&1 \
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
if run_install "$SDK" > "$WORK/out" 2>&1 \
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
if run_install "$SDK" > "$WORK/out" 2>&1 \
    && [ -f "$STUB_LOG/curl.log" ] && pinned_layout_ok "$SDK"; then
    t_ok "missing source.properties forces a pinned reinstall"
else
    t_fail "missing source.properties forces a pinned reinstall"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 5. A download that fails the checksum aborts without leaving a partial
#    cmdline-tools/latest behind.
BADSHA_SCRIPT="$WORK/install-badsha.sh"
sed "s/^CMDLINE_TOOLS_SHA256=.*/CMDLINE_TOOLS_SHA256=\"0000000000000000000000000000000000000000000000000000000000000000\"/" \
    "$FIX_ROOT/scripts/ci/install-android-sdk.sh" > "$BADSHA_SCRIPT"
SCRIPT_FILE="$BADSHA_SCRIPT"
SDK="$WORK/sdk-badsha"
if run_install "$SDK" > "$WORK/out" 2>&1; then
    t_fail "checksum mismatch aborts the install"
    sed 's/^/    /' "$WORK/out" >&2
elif [ ! -e "$SDK/cmdline-tools/latest" ]; then
    t_ok "checksum mismatch aborts the install"
else
    t_fail "checksum mismatch aborts the install (partial latest left behind)"
fi
SCRIPT_FILE="$FIX_ROOT/scripts/ci/install-android-sdk.sh"

# 6. Missing packages go through the (already pinned) sdkmanager: licenses,
#    then install of the pinned package set.
SDK="$WORK/sdk-pkgs"
seed_tools "$SDK" 23.0
if run_install "$SDK" > "$WORK/out" 2>&1 \
    && [ ! -f "$STUB_LOG/curl.log" ] \
    && grep -q 'sdkmanager --licenses' "$STUB_LOG/sdkmanager.log" \
    && grep -q 'sdkmanager --install platforms;android-37.0 build-tools;36.0.0' \
        "$STUB_LOG/sdkmanager.log" \
    && [ -d "$SDK/platforms/android-37.0" ] && [ -d "$SDK/build-tools/36.0.0" ]; then
    t_ok "missing packages are installed through the pinned sdkmanager"
else
    t_fail "missing packages are installed through the pinned sdkmanager"
    sed 's/^/    /' "$WORK/out" >&2
fi

if [ "$FAILED" -eq 0 ]; then
    echo "all install-android-sdk cases pass"
else
    echo "$FAILED install-android-sdk case(s) failed" >&2
fi
exit "$([ "$FAILED" -eq 0 ] && echo 0 || echo 1)"
