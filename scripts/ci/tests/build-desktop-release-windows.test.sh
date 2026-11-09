#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# build-desktop-release-windows.test.sh — the MSI-install boundary of the
# windows-x64 release leg (scripts/ci/build-desktop-release-windows.sh). The
# real leg file runs in a stub repo: fake gradlew, cygpath, powershell and
# python stand in for the Windows tools and fake smoke-start records reaching
# the smoke step. The powershell stub emulates the -Command contract
# (MSIEXEC_EXIT on stdout, process exit = the child code) — no pwsh exists
# here to parse the script itself, so its body is asserted as fixed text.
#
# Run: bash scripts/ci/tests/build-desktop-release-windows.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.
set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
LEG="$REPO_ROOT/scripts/ci/build-desktop-release-windows.sh"
WIN_PATH="C:\\ci d's pkg.msi" # a space and an apostrophe: interpolation breakers

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }
chk() { local m="$1"; shift; if "$@"; then t_ok "$m"; else t_fail "$m"; fi; }
[ -f "$LEG" ] || { echo "not ok: $LEG is missing" >&2; exit 1; }

# The leg resolves REPO_ROOT from its own location, so the real file is copied
# beside a fake gradlew, smoke-start and bin tools.
repo="$(mktemp -d)"; trap 'rm -rf "$repo"' EXIT
mkdir -p "$repo/scripts/ci" "$repo/scripts/desktop" "$repo/bin"
cp "$LEG" "$repo/scripts/ci/"
cat > "$repo/scripts/desktop/smoke-start.sh" <<'EOF'
#!/bin/sh
printf '%s\n' "$@" >> smoke-args.txt
echo "SMOKE $1"
EOF
cat > "$repo/gradlew" <<'EOF'
#!/bin/sh
for arg; do
    case "$arg" in -Pneutrodyne.installKind=*) printf '%s\n' "${arg#*=}" > current-kind ;; esac
done
mkdir -p desktopApp/build/compose/binaries/main/msi desktopApp/build/desktop-packaging/zip
: > desktopApp/build/compose/binaries/main/msi/p.msi
: > desktopApp/build/desktop-packaging/zip/p.zip
EOF
cat > "$repo/scripts/ci/check-desktop-image.sh" <<'EOF'
#!/bin/sh
expected=zip
case "$*" in *Neutrodyne-App*) expected=msi ;; esac
[ "$(cat current-kind)" = "$expected" ] || { echo 'wrong manifest variant' >&2; exit 1; }
printf '%s\n' "$expected" >> checked-kinds
[ -z "${STUB_IMAGE_CHECK_FAIL-}" ]
EOF
cat > "$repo/scripts/ci/check-runtime-sources.sh" <<'EOF'
#!/bin/sh
exit 0
EOF
printf '#!/bin/sh\nprintf "%%s\\n" "%s"\n' "$WIN_PATH" > "$repo/bin/cygpath"
cat > "$repo/bin/powershell" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$STUB_LOG.argv"
printf 'MSI_PATH=%s\n' "${MSI_PATH-<unset>}" > "$STUB_LOG.env"
[ -z "${STUB_PS_NOMARKER-}" ] || exit "${STUB_PS_EXIT:-0}"
printf 'MSIEXEC_EXIT=%s\n' "${STUB_MSIEXEC_EXIT:-0}"
exit "${STUB_MSIEXEC_EXIT:-0}"
EOF
cat > "$repo/bin/python" <<'EOF'
#!/bin/sh
# The shipped extractor is invoked as: python extract-zip.py <zip> <dest>.
mkdir -p "$3/Neutrodyne"
EOF
chmod +x "$repo/gradlew" "$repo/scripts/desktop/smoke-start.sh" "$repo/scripts/ci/"*.sh "$repo/bin/"*

run_leg() { # [ENV=x ...] — one leg run; clears records, captures leg.log
    rm -f "$repo/smoke.txt" "$repo/smoke-args.txt" "$repo/stub.argv" "$repo/stub.env" "$repo/checked-kinds"
    env PATH="$repo/bin:$PATH" LOCALAPPDATA="$repo" STUB_LOG="$repo/stub" "$@" \
        bash "$repo/scripts/ci/build-desktop-release-windows.sh" 9.9.9 \
        "$repo/dist" "$repo/img" >"$repo/leg.log" 2>&1
}
smokes() { if [ -f "$repo/smoke.txt" ]; then grep -c '^SMOKE ' "$repo/smoke.txt"; else echo 0; fi; }

chk "the leg resolves the native path once" test "$(grep -c 'cygpath -w' "$LEG")" -eq 1

# The ZIP smoke extraction must restore the artifact's recorded mtimes: the AOT
# cache binds the trained JARs' timestamps, and zipfile -e drops them.
chk "the ZIP smoke uses the timestamp-restoring extractor" grep -q 'extract-zip.py' "$LEG"
chk "the ZIP smoke no longer uses python -m zipfile -e" test -z "$(grep -F 'python -m zipfile -e' "$LEG")"

# --- msiexec 0: clean install -------------------------------------------------
chk "msiexec 0 lets the leg finish" run_leg STUB_MSIEXEC_EXIT=0
chk "both smokes ran after msiexec 0" test "$(smokes)" -eq 2
chk "installed MSI and ZIP use their own manifest variants" \
    test "$(tr '\n' ' ' < "$repo/checked-kinds")" = "msi zip "
chk "the installed app's smoke ran" grep -q 'Neutrodyne-App' "$repo/smoke.txt"
# MD3 AC2: the leg must arm the packaged recorded-media proof on both smokes.
# The leg canonicalises REPO_ROOT (pwd -P), so compare against the resolved repo.
chk "both smokes armed the recorded-media replay" \
    test "$(grep -cF -- "--engine-replay=$(cd -- "$repo" && pwd -P)/youtube/engine/src/test/resources/recorded/media" "$repo/smoke-args.txt")" -eq 2
chk "powershell got -NoProfile -Command" \
    test "$(head -2 "$repo/stub.argv" | tr '\n' ' ')" = "-NoProfile -Command "
ps="$(tail -n +3 "$repo/stub.argv")"
# shellcheck disable=SC2016 # literal tokens in the PowerShell text, not shell
for tok in '-Wait' '-PassThru' '`"$env:MSI_PATH`"' 'MSIEXEC_EXIT' '/qn' '/norestart'; do
    chk "-Command carries $tok" grep -qF -- "$tok" <<<"$ps"
done
chk "the -Command text carries no path (data, not code)" \
    test -z "$(printf '%s' "$ps" | grep -E 'cygpath|C:[\\/]')"
chk "MSI_PATH arrives as data (space + apostrophe)" \
    test "$(cat "$repo/stub.env")" = "MSI_PATH=$WIN_PATH"

# --- 3010 reboot required: accepted under /norestart --------------------------
chk "msiexec 3010 is accepted" run_leg STUB_MSIEXEC_EXIT=3010
chk "3010 is logged and both smokes ran" \
    test "$(smokes):$(grep -c '3010' "$repo/leg.log")" = "2:1"

# --- 1603 install failure: stop before the smoke ------------------------------
run_leg STUB_MSIEXEC_EXIT=1603; rc=$?
chk "msiexec 1603 fails the leg" test "$rc" -ne 0
chk "the full 1603 reaches the log" grep -q 'exit 1603' "$repo/leg.log"
chk "no smoke ran after msiexec 1603" test "$(smokes)" -eq 0

run_leg STUB_MSIEXEC_EXIT=256; rc=$?
chk "a child failure cannot wrap to success" test "$rc:$(smokes)" = "1:0"

# --- powershell succeeds without a marker: fail closed ------------------------
run_leg STUB_PS_NOMARKER=1; rc=$?
chk "a marker-less powershell success fails the leg" test "$rc" -ne 0
chk "no smoke ran without a marker" test "$(smokes)" -eq 0

run_leg STUB_MSIEXEC_EXIT=0 STUB_IMAGE_CHECK_FAIL=1; rc=$?
chk "a rejected installed MSI fails the leg" test "$rc" -ne 0
chk "the ZIP is not built after an installed payload failure" \
    test "$(cat "$repo/current-kind"):$(smokes)" = "msi:1"

echo
if [ "$FAILED" -gt 0 ]; then
    echo "build-desktop-release-windows.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "build-desktop-release-windows.test: all cases pass"
