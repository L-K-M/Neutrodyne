#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# install-android-sdk.test.sh — boundary cases for scripts/ci/install-android-sdk.sh
# (09 CI scripts). The script under test picks a pinned, checksum-verified
# cmdline-tools archive per host and then drives the package install either
# through the shipped bin/sdkmanager (linux-x64, where cmdline-tools 23.x's
# native bin/android actually runs) or through the pure-Java SdkManagerCli still
# inside lib/ (linux-arm64 — Google ships no linux/arm64 archive —, macos-arm64 —
# the macOS archive's launcher is x86_64-only —, windows-x64).
#
# The fixtures are tiny zips named like the upstream archives; a stub curl
# records the requested URL and serves the matching fixture, stub java records
# and performs the SdkManagerCli calls, and the fixture bin/android stands in
# for the real native launcher: it installs only on the simulated linux-x64 and
# dies with the real "Exec format error" (exit 126) anywhere else — the literal
# failure nightly 2026-10-08 hit on ubuntu-24.04-arm and macos-15.
#
# The script resolves android-sdk.lock beside itself, so each run copies it and
# the lock into a fixture root — no repository state is touched. A copy of the
# pre-fix script carries its linux pin inline; rewriting that line to the
# fixture's real hash is what lets a regression run proceed far enough to
# expose the launcher bug.
#
# Also asserted: every `run:` step of the cross-platform desktop jobs sets
# `shell: bash` (the default pwsh on windows-2025 parses `-P…` Gradle arguments
# into task names and cannot run .sh steps natively), and every step that calls
# install-android-sdk.sh exports ANDROID_HOME and ANDROID_SDK_ROOT to the same
# directory (AGP fails when the inherited ANDROID_SDK_ROOT points at the runner
# image's SDK while ANDROID_HOME points at ours).
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
cp "$REPO_ROOT/scripts/ci/install-android-sdk.sh" "$FIX_ROOT/scripts/ci/"

# Fixture archives: the upstream file names, but content reduced to a marker
# naming the host archive, the launchers, and two lib jars (so the Java
# classpath has a separator to convert on windows-x64).
python3 - "$FIXTURE_ZIPS" <<'PY'
import sys, zipfile, os.path

out = sys.argv[1]

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
        'cmdline-tools/source.properties': 'Pkg.Revision=23.0\nPkg.Path=cmdline-tools;23.0\n',
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

# The pre-fix script carries its linux pin inline; repoint it at the fixture's
# real hash so the run gets past verification and exposes the launcher bug.
# The fixed script reads android-sdk.lock, where this line does not exist.
sed -i "s/^CMDLINE_TOOLS_SHA256=.*/CMDLINE_TOOLS_SHA256=\"$LINUX_SHA\"/" \
    "$FIX_ROOT/scripts/ci/install-android-sdk.sh"

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
        bash "$FIX_ROOT/scripts/ci/install-android-sdk.sh" "$dir"
}

has_pkg_dirs() {
    [ -d "$1/platforms/android-37.0" ] && [ -d "$1/build-tools/36.0.0" ]
}

# --- host selection and launcher cases ------------------------------------------

# 1. linux-x64: the linux archive lands and the shipped bin/sdkmanager (which
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

# 2. linux-arm64: no upstream linux/arm64 archive exists, so the linux archive
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

# 3. macos-arm64: the macOS (x86_64) archive lands, but its native launcher is
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

# 4. windows-x64: the windows archive lands; the Java launcher gets a
#    semicolon-separated classpath through cygpath (-pw) and Windows-style
#    paths for toolsdir/sdk_root (-w).
mkdir -p "$STUBBIN-case"
cat > "$STUBBIN-case/cygpath" <<'EOF'
#!/usr/bin/env bash
echo "cygpath $*" >> "$STUB_LOG/cygpath.log"
case "$1" in
    -pw) shift; printf '%s' "$1" | tr ':' ';' ;;
    *)   shift; printf '%s' "$1" ;;
esac
EOF
chmod +x "$STUBBIN-case/cygpath"
STUBBIN_CASE_EXTRA="$STUBBIN-case:"
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

# 5. Unsupported host: explicit failure before anything is downloaded — no
#    silent fallthrough onto the linux archive.
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

# 6. Checksum rejection: pin the macOS fixture's hash for the linux archive —
#    sha256sum must refuse it and nothing may be installed.
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

# 7. Idempotence: a second run on a complete SDK downloads nothing and calls no
#    tool.
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

# 8. Cached tools but missing packages: no archive download, the install still
#    runs through the host's launcher.
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

# 9. Every run: step of the cross-platform desktop jobs sets shell: bash, and
#    every step calling install-android-sdk.sh exports ANDROID_HOME and
#    ANDROID_SDK_ROOT with the same value to $GITHUB_ENV.
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
    i = next((i for i, l in enumerate(step) if re.match(r'\s+run:', l)), None)
    if i is None:
        return None
    if not re.match(r'\s+run:\s*[|>]', step[i]):
        return [step[i]]
    ind = indent(step[i])
    body = []
    for l in step[i + 1:]:
        if l.strip() and indent(l) <= ind:
            break
        body.append(l)
    return body

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

[ "$FAILED" -eq 0 ] || { echo "$FAILED case(s) failing" >&2; exit 1; }
echo "all cases pass"
