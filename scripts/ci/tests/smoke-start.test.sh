#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# smoke-start.test.sh — the packaged-launcher contract of
# scripts/desktop/smoke-start.sh (11 Smoke mode): exit 0 only when the launcher
# exits 0 AND its `SMOKE {json}` line carries no `failed` field. Smoke mode
# prints the SMOKE line and then exits 1 when a step failed, so a SMOKE line
# alone is not success — and a launcher may also die after printing it.
#
# The launcher is a fixture: a shell script cat'ing a canned stdout file with a
# chosen exit code, inside a minimal jpackage layout (bin/Neutrodyne +
# lib/app/Neutrodyne.cfg). smoke-start.sh itself runs for real.
#
# Run: bash scripts/ci/tests/smoke-start.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
SMOKE_START="$REPO_ROOT/scripts/desktop/smoke-start.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

# --- fixture plumbing -----------------------------------------------------------

IMG="$WORK/img"
mkdir -p "$IMG/bin" "$IMG/lib/app"
printf '[JavaOptions]\njava-options=-Xmx512m\n' > "$IMG/lib/app/Neutrodyne.cfg"

OUT="$WORK/launcher.out" # what the fake launcher prints this case

# launcher <rc> — install a launcher that prints $OUT's content and exits <rc>.
launcher() {
    cat > "$IMG/bin/Neutrodyne" <<EOF
#!/usr/bin/env bash
cat "$OUT" 2>/dev/null || true
exit $1
EOF
    chmod +x "$IMG/bin/Neutrodyne"
}

run() { bash "$SMOKE_START" "$IMG" >"$WORK/sm.out" 2>"$WORK/sm.err"; }

expect_pass() { # <case name>
    if run && grep -q '^SMOKE {' "$WORK/sm.out"; then
        t_ok "$1"
    else
        t_fail "$1"
        sed 's/^/    /' "$WORK/sm.err" >&2
    fi
}

expect_fail() { # <case name>
    if run; then
        t_fail "$1"
        sed 's/^/    /' "$WORK/sm.out" >&2
    else
        t_ok "$1"
    fi
}

# The lines mirror smokeJson's shape (11 Smoke mode): a success line carries
# "window" but no "failed" key; a failed run's line carries "failed" (and no
# "window"), and the launcher exits 1 with it.
JSON_OK='SMOKE {"versionName":"0.1.0","versionCode":1,"installKind":"deb","javaVendor":"Eclipse Adoptium","javaVendorVersion":"Temurin-25+36","javaRuntimeVersion":"25+36","steps":{"appDirs":3,"buildInfo":1,"graph":40,"initializers":2,"window":510,"firstFrameMs":480},"window":"opened","pending":"database,destinations,ffmpeg,ndmedia,engine,dbus,aotCache,rss"}'
JSON_HEADLESS='SMOKE {"versionName":"0.1.0","versionCode":1,"installKind":"deb","javaVendor":"Eclipse Adoptium","javaVendorVersion":"Temurin-25+36","javaRuntimeVersion":"25+36","steps":{"appDirs":3,"buildInfo":1,"graph":40,"initializers":2,"window":1},"window":"skipped-headless","pending":"database,destinations,ffmpeg,ndmedia,engine,dbus,aotCache,rss"}'
JSON_FAIL='SMOKE {"versionName":"0.1.0","versionCode":1,"installKind":"deb","javaVendor":"Eclipse Adoptium","javaVendorVersion":"Temurin-25+36","javaRuntimeVersion":"25+36","steps":{"appDirs":3,"buildInfo":1,"graph":40},"pending":"database,destinations,ffmpeg,ndmedia,engine,dbus,aotCache,rss","failed":"initializers"}'

# --- cases ---------------------------------------------------------------------

# 1. Successful line, launcher exit 0: pass, echo the line, restore the .cfg.
printf '%s\n' "$JSON_OK" > "$OUT"
launcher 0
if run && grep -q '^SMOKE {' "$WORK/sm.out" \
    && ! grep -q 'neutrodyne.smoke' "$IMG/lib/app/Neutrodyne.cfg"; then
    t_ok "successful SMOKE line with launcher exit 0 passes and restores the .cfg"
else
    t_fail "successful SMOKE line with launcher exit 0 passes and restores the .cfg"
    sed 's/^/    /' "$WORK/sm.err" >&2
fi

# 2. The headless variant is a valid success.
printf '%s\n' "$JSON_HEADLESS" > "$OUT"
launcher 0
expect_pass "skipped-headless SMOKE line with launcher exit 0 passes"

# 3. A launcher that dies after printing its line is not a success.
printf '%s\n' "$JSON_OK" > "$OUT"
launcher 1
expect_fail "SMOKE line followed by launcher exit 1 fails"

# 4. Smoke mode's own failure shape: `failed` in the JSON, exit 1.
printf '%s\n' "$JSON_FAIL" > "$OUT"
launcher 1
expect_fail "SMOKE line carrying a failed step and exit 1 fails"

# 5. The printed verdict binds even if the launcher masks its own exit code.
printf '%s\n' "$JSON_FAIL" > "$OUT"
launcher 0
expect_fail "SMOKE line carrying a failed step at exit 0 still fails"

# 6. No SMOKE line at all stays a failure (unchanged contract).
printf 'launcher noise only\n' > "$OUT"
launcher 0
expect_fail "no SMOKE line fails"

# 7. An installed image's .cfg is root-owned: the append must fail with a clear
#    directive to smoke a writable copy, not an opaque 'Permission denied' from
#    bash (nightly 37831500506, the installed DEB at /opt/neutrodyne).
chmod a-w "$IMG/lib/app/Neutrodyne.cfg"
printf '%s\n' "$JSON_OK" > "$OUT"
launcher 0
if run; then
    t_fail "a read-only image .cfg fails with a clear message"
elif grep -qi 'not writable\|writable copy' "$WORK/sm.err"; then
    t_ok "a read-only image .cfg fails with a clear message"
else
    t_fail "a read-only image .cfg fails with a clear message"
    sed 's/^/    /' "$WORK/sm.err" >&2
fi
chmod +w "$IMG/lib/app/Neutrodyne.cfg"

echo
if [ "$FAILED" -gt 0 ]; then
    echo "smoke-start.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "smoke-start.test: all cases behave"
