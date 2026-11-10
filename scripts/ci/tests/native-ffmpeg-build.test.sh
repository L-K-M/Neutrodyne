#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# native-ffmpeg-build.test.sh — boundary cases for playback/native/ffmpeg/build.sh
# (11 FFmpeg build). The real build runs on the four desktop runners, where it
# downloads the pinned tarball, verifies its signature and compiles FFmpeg; this
# suite covers the parts that must fail loudly and the ones a shell script can
# get wrong without any network:
#
#   - the script parses (bash -n), so a syntax error never reaches a runner;
#   - the target guard rejects an unknown or missing target with exit 2;
#   - every shell function is defined before its first call, which is what a
#     straight `name() { ... }` definition placed after the block that calls it
#     breaks (the download block called verify_signature before its definition,
#     so the build died with "command not found" on the first runner);
#   - the lock's configure line and library list stay in step with the design
#     document, so a flag that would enable GPL, version3 or nonfree code fails
#     here instead of in checkNativeLicences on four runners;
#   - the bounded package-manager bootstrap (package-bootstrap.sh) installs with
#     the first working manager, bounds a hung manager inside its budget, retains
#     every manager's log, and fails a required tool instead of disabling x86
#     assembly silently.
#
# Run: bash scripts/ci/tests/native-ffmpeg-build.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
SCRIPT="$REPO_ROOT/playback/native/ffmpeg/build.sh"
LOCK="$REPO_ROOT/playback/native/native-components.lock"
DESIGN="$REPO_ROOT/docs/design/11-desktop.md"

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

[ -f "$SCRIPT" ] || { echo "not ok: $SCRIPT is missing" >&2; exit 1; }

# --- syntax ---------------------------------------------------------------------

if bash -n "$SCRIPT" 2>/dev/null; then
    t_ok "build.sh parses"
else
    t_fail "build.sh does not parse: $(bash -n "$SCRIPT" 2>&1 | head -1)"
fi

# --- target guard ---------------------------------------------------------------

out="$(bash "$SCRIPT" bogus-target 2>&1)"
status=$?
if [ "$status" -eq 2 ] && printf '%s' "$out" | grep -q 'usage: build.sh'; then
    t_ok "an unknown target exits 2 with the usage line"
else
    t_fail "an unknown target exited $status: $out"
fi

out="$(bash "$SCRIPT" 2>&1)"
status=$?
if [ "$status" -eq 2 ] && printf '%s' "$out" | grep -q 'usage: build.sh'; then
    t_ok "a missing target exits 2 with the usage line"
else
    t_fail "a missing target exited $status: $out"
fi

# --- function definitions precede their first call ------------------------------
#
# Bash resolves a function name when the call runs, not when the script is read,
# so `bash -n` cannot see a definition that sits below its caller. Walking the
# definition and call sites of every function catches it.

python3 - "$SCRIPT" <<'PY' || FAILED=$((FAILED + 1))
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()

definition = {}
for number, line in enumerate(lines, start=1):
    match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{", line)
    if match:
        definition.setdefault(match.group(1), number)

problems = []
for name, defined_at in sorted(definition.items()):
    for number, line in enumerate(lines, start=1):
        if number >= defined_at:
            continue
        # A call site: the name at a word boundary, not in a comment, and not the
        # definition itself (which `number < defined_at` already excludes).
        if re.search(rf"(^|[;&|]|\bthen\b|\belse\b|\bdo\b|\{{)\s*{name}\b", line) and not line.lstrip().startswith("#"):
            problems.append(f"{name} is called at line {number} but defined at line {defined_at}")
            break

for problem in problems:
    print(f"not ok: {problem}", file=sys.stderr)
if not problems:
    print(f"ok: all {len(definition)} shell functions are defined before their first call")
sys.exit(1 if problems else 0)
PY

# --- licence envelope in the lock and the configure line ------------------------
#
# The configure line is duplicated in the design document; a drift between them
# is what lets a GPL flag in. Compare the two, ignoring the target flags.

configure="$(sed -n 's/^configure = "\(.*\)"$/\1/p' "$LOCK")"
if [ -n "$configure" ]; then
    t_ok "the lock records a configure line"
else
    t_fail "the lock records no configure line"
    configure=""
fi

for banned in --enable-gpl --enable-version3 --enable-nonfree; do
    if printf '%s' "$configure" | grep -q -- "$banned"; then
        t_fail "the lock's configure line carries $banned"
    else
        t_ok "the lock's configure line has no $banned"
    fi
done

for required in --disable-everything --disable-programs --disable-doc --disable-network \
    --disable-autodetect --enable-shared --disable-static --disable-avdevice \
    --disable-avfilter --disable-swscale --enable-swresample; do
    if printf '%s' "$configure" | grep -q -- "$required"; then
        t_ok "the lock's configure line carries $required"
    else
        t_fail "the lock's configure line is missing $required"
    fi
done

# The design document's own copy of the shared line must name the same flags.
for required in --disable-everything --disable-programs --disable-doc --disable-network \
    --disable-autodetect --enable-shared --disable-static --disable-avdevice \
    --disable-avfilter --disable-swscale --enable-swresample; do
    if grep -q -- "$required" "$DESIGN"; then
        t_ok "11-desktop.md names $required"
    else
        t_fail "11-desktop.md no longer names $required"
    fi
done

# --- the four libraries of D86 --------------------------------------------------

libraries="$(sed -n 's/^libraries = \[\(.*\)\]$/\1/p' "$LOCK" | tr -d '"' | tr -d ' ')"
if [ "$libraries" = "avcodec,avformat,avutil,swresample" ]; then
    t_ok "the lock lists the four libraries of D86"
else
    t_fail "the lock lists '$libraries', expected the four of D86"
fi

# --- the lock's own paths resolve ------------------------------------------------
#
# The lock names its files the way the repository does, so build.sh resolves
# them from the repository root. A path that does not resolve is only discovered
# when the build has already downloaded the tarball (the first runner died at
# `cp` after both downloads, having verified neither).

signing_key="$(sed -n 's/^signingKey = "\(.*\)"$/\1/p' "$LOCK")"
if [ -n "$signing_key" ] && [ -f "$REPO_ROOT/$signing_key" ]; then
    t_ok "the lock's signing key resolves from the repository root"
else
    t_fail "the lock's signingKey '$signing_key' does not resolve from $REPO_ROOT"
fi

# The signing key must carry the fingerprint the lock records. The armour header
# spaces it in groups of four, so compare with the spaces removed.
fingerprint="$(sed -n 's/^signingKeyFingerprint = "\(.*\)"$/\1/p' "$LOCK")"
if [ -n "$fingerprint" ] &&
    grep '^Comment:' "$REPO_ROOT/$signing_key" 2>/dev/null | tr -d ' \t\r' |
        grep -q "$fingerprint"; then
    t_ok "the signing key names its fingerprint in its armour header"
else
    t_fail "the signing key does not name $fingerprint in its armour header"
fi

for library in avcodec avformat avutil swresample; do
    if grep -q "\"$library" "$LOCK" || grep -q ",$library" "$LOCK" || grep -q "$library\"" "$LOCK"; then
        t_ok "the lock records $library"
    else
        t_fail "the lock does not record $library"
    fi
done

# --- bounded package-manager bootstrap ------------------------------------------
#
# The Linux build installs NASM (required on x64) and patchelf (optional) with
# the container's package manager. These cases drive the actual bootstrap_package
# and bounded helpers from package-bootstrap.sh with fake managers, so a stall,
# a fallback and a required-tool failure are exercised without a container.

BOOTSTRAP_LIB="$REPO_ROOT/playback/native/ffmpeg/package-bootstrap.sh"
if [ -f "$BOOTSTRAP_LIB" ]; then
    t_ok "package-bootstrap.sh is present"
else
    t_fail "package-bootstrap.sh is missing"
fi
if bash -n "$BOOTSTRAP_LIB" 2>/dev/null; then
    t_ok "package-bootstrap.sh parses"
else
    t_fail "package-bootstrap.sh does not parse: $(bash -n "$BOOTSTRAP_LIB" 2>&1 | head -1)"
fi

if [ -f "$BOOTSTRAP_LIB" ]; then
    # shellcheck source=/dev/null
    source "$BOOTSTRAP_LIB"

    bootstrap_fixture() {
        local dir manager
        dir="$(mktemp -d)"
        mkdir -p "$dir/bin" "$dir/logs"
        for manager in microdnf dnf yum; do
            cat > "$dir/bin/$manager" <<'FAKE'
#!/usr/bin/env bash
name="$(basename "$0")"
var="FAKE_MODE_$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')"
mode="${!var:-ok}"
case "$mode" in
    ok|fail-created)
        cat > "$(dirname "$0")/$FAKE_TOOL" <<'TOOL'
#!/usr/bin/env bash
echo fake
TOOL
        chmod +x "$(dirname "$0")/$FAKE_TOOL"
        echo "$name: $mode $FAKE_TOOL"
        [ "$mode" = ok ] || exit 1
        ;;
    fail)
        echo "$name: package not found" >&2
        exit 1
        ;;
    hang)
        echo $$ > "$FAKE_PID_FILE"
        echo "$name: starting"
        sleep 300 &
        child=$!
        echo $child > "$FAKE_CHILD_FILE"
        # GNU timeout signals the process group; the Perl fallback signals only
        # this shell, so the child must be reaped here on every path.
        trap 'kill "$child" 2>/dev/null || true; exit 143' EXIT TERM ALRM HUP
        wait
        ;;
esac
FAKE
            chmod +x "$dir/bin/$manager"
        done
        echo "$dir"
    }

    # A unique tool name keeps an ambient nasm on the host from satisfying the
    # bootstrap pre-check; the package is still nasm, the tool is the probe.
    FAKE_TOOL=nasm-bootstrap-probe

    run_bootstrap() {
        (
            export PATH="$FIXTURE/bin:$PATH"
            export FAKE_TOOL FAKE_PID_FILE="$FIXTURE/pid" FAKE_CHILD_FILE="$FIXTURE/child"
            export FAKE_MODE_MICRODNF FAKE_MODE_DNF FAKE_MODE_YUM
            bootstrap_package "$@"
        )
    }

    # GNU timeout kills the manager's process group; the Perl fallback signals
    # only the exec'd manager. The orphan assertion is meaningful only on the
    # GNU path, so it is guarded rather than assumed.
    if command -v timeout >/dev/null 2>&1 && timeout --version >/dev/null 2>&1; then
        BOOTSTRAP_GNU_TIMEOUT=1
    else
        BOOTSTRAP_GNU_TIMEOUT=0
    fi

    # shellcheck disable=SC2329 # runs as the EXIT/INT/TERM trap
    bootstrap_cleanup() {
        if [ -n "${FIXTURE:-}" ]; then
            [ -f "$FIXTURE/pid" ] && kill "$(cat "$FIXTURE/pid")" 2>/dev/null || true
            [ -f "$FIXTURE/child" ] && kill "$(cat "$FIXTURE/child")" 2>/dev/null || true
            rm -rf "$FIXTURE"
        fi
    }
    trap bootstrap_cleanup EXIT INT TERM

    # The first working manager installs the tool and retains its output.
    FIXTURE="$(bootstrap_fixture)"
    FAKE_MODE_MICRODNF=ok FAKE_MODE_DNF=ok FAKE_MODE_YUM=ok
    rm -f "$FIXTURE/bin/dnf" "$FIXTURE/bin/yum"
    out="$(run_bootstrap optional 5 nasm "$FIXTURE/logs" "$FAKE_TOOL" microdnf dnf yum 2>&1)"; rc=$?
    if [ "$rc" -eq 0 ] && [ -x "$FIXTURE/bin/$FAKE_TOOL" ] &&
        [ -f "$FIXTURE/logs/$FAKE_TOOL-microdnf.log" ] &&
        grep -q "microdnf: ok $FAKE_TOOL" "$FIXTURE/logs/$FAKE_TOOL-microdnf.log" &&
        printf '%s' "$out" | grep -q 'installed nasm with microdnf' &&
        [ ! -f "$FIXTURE/logs/$FAKE_TOOL-dnf.log" ]; then
        t_ok "the first working manager installs the tool and retains its log"
    else
        t_fail "first working manager: rc=$rc out=$out"
    fi
    rm -rf "$FIXTURE"

    # A failing manager falls through to the next one, keeping both logs.
    FIXTURE="$(bootstrap_fixture)"
    FAKE_MODE_MICRODNF=ok FAKE_MODE_DNF=fail FAKE_MODE_YUM=ok
    rm -f "$FIXTURE/bin/microdnf"
    out="$(run_bootstrap optional 5 nasm "$FIXTURE/logs" "$FAKE_TOOL" microdnf dnf yum 2>&1)"; rc=$?
    if [ "$rc" -eq 0 ] && [ -x "$FIXTURE/bin/$FAKE_TOOL" ] &&
        [ -f "$FIXTURE/logs/$FAKE_TOOL-dnf.log" ] && grep -q 'package not found' "$FIXTURE/logs/$FAKE_TOOL-dnf.log" &&
        [ -f "$FIXTURE/logs/$FAKE_TOOL-yum.log" ] && grep -q "yum: ok $FAKE_TOOL" "$FIXTURE/logs/$FAKE_TOOL-yum.log" &&
        printf '%s' "$out" | grep -q 'installed nasm with yum'; then
        t_ok "a failing manager falls through to the next one"
    else
        t_fail "fallback: rc=$rc out=$out"
    fi
    rm -rf "$FIXTURE"

    # A required tool no manager provides fails with the retained log tails.
    FIXTURE="$(bootstrap_fixture)"
    FAKE_MODE_MICRODNF=fail FAKE_MODE_DNF=fail FAKE_MODE_YUM=fail
    out="$(run_bootstrap required 5 nasm "$FIXTURE/logs" "$FAKE_TOOL" microdnf dnf yum 2>&1)"; rc=$?
    if [ "$rc" -eq 1 ] && [ ! -x "$FIXTURE/bin/$FAKE_TOOL" ] &&
        printf '%s' "$out" | grep -q 'could not install nasm' &&
        printf '%s' "$out" | grep -q "$FAKE_TOOL-microdnf.log" &&
        printf '%s' "$out" | grep -q 'package not found'; then
        t_ok "a required tool no manager provides fails with the log tails"
    else
        t_fail "required failure: rc=$rc out=$out"
    fi
    rm -rf "$FIXTURE"

    # A hung manager is killed inside the budget, which is then exhausted so the
    # next manager is never attempted.
    FIXTURE="$(bootstrap_fixture)"
    FAKE_MODE_MICRODNF=hang FAKE_MODE_DNF=ok FAKE_MODE_YUM=ok
    start="$(date +%s)"
    out="$(run_bootstrap optional 2 nasm "$FIXTURE/logs" "$FAKE_TOOL" microdnf dnf yum 2>&1)"; rc=$?
    elapsed=$(( $(date +%s) - start ))
    if [ "$rc" -eq 1 ] && [ "$elapsed" -lt 5 ] && [ ! -x "$FIXTURE/bin/$FAKE_TOOL" ] &&
        [ -f "$FIXTURE/logs/$FAKE_TOOL-microdnf.log" ] && [ ! -f "$FIXTURE/logs/$FAKE_TOOL-dnf.log" ]; then
        t_ok "a hung manager is bounded by the budget (elapsed ${elapsed}s)"
    else
        t_fail "hung manager: rc=$rc elapsed=${elapsed}s out=$out"
    fi
    sleep 0.2
    if [ -f "$FIXTURE/pid" ] && [ -f "$FIXTURE/child" ]; then
        mgr_pid="$(cat "$FIXTURE/pid")"; child_pid="$(cat "$FIXTURE/child")"
        if [ "$BOOTSTRAP_GNU_TIMEOUT" -eq 1 ]; then
            if kill -0 "$mgr_pid" 2>/dev/null || kill -0 "$child_pid" 2>/dev/null; then
                t_fail "the timed-out manager left an orphan (mgr=$mgr_pid child=$child_pid)"
                kill "$mgr_pid" "$child_pid" 2>/dev/null || true
            else
                t_ok "the timed-out manager left no orphan child"
            fi
        else
            # The Perl fallback only signals the manager. Clean its fixture
            # child without weakening the GNU-path orphan assertion above.
            kill "$child_pid" 2>/dev/null || true
            t_ok "the child fixture was reaped after the timeout (Perl path)"
        fi
    else
        t_fail "the hung manager wrote no pid files"
    fi
    rm -rf "$FIXTURE"

    # A manager that exits non-zero is not success even when it leaves the tool.
    FIXTURE="$(bootstrap_fixture)"
    FAKE_MODE_MICRODNF=fail-created FAKE_MODE_DNF=ok FAKE_MODE_YUM=ok
    rm -f "$FIXTURE/bin/dnf" "$FIXTURE/bin/yum"
    out="$(run_bootstrap required 5 nasm "$FIXTURE/logs" "$FAKE_TOOL" microdnf dnf yum 2>&1)"; rc=$?
    if [ "$rc" -eq 1 ] && [ -x "$FIXTURE/bin/$FAKE_TOOL" ] &&
        printf '%s' "$out" | grep -q 'could not install nasm'; then
        t_ok "a manager that errors is not treated as success even when it leaves the tool"
    else
        t_fail "command error: rc=$rc out=$out"
    fi
    rm -rf "$FIXTURE"

    # An already-present tool is not reinstalled.
    FIXTURE="$(bootstrap_fixture)"
    printf '#!/usr/bin/env bash\n' > "$FIXTURE/bin/$FAKE_TOOL"; chmod +x "$FIXTURE/bin/$FAKE_TOOL"
    rm -f "$FIXTURE/bin/microdnf" "$FIXTURE/bin/dnf" "$FIXTURE/bin/yum"
    out="$(run_bootstrap required 5 nasm "$FIXTURE/logs" "$FAKE_TOOL" microdnf dnf yum 2>&1)"; rc=$?
    if [ "$rc" -eq 0 ] && [ -z "$out" ]; then
        t_ok "an already-present tool is not reinstalled"
    else
        t_fail "already present: rc=$rc out=$out"
    fi
    rm -rf "$FIXTURE"
fi

if [ "$FAILED" -eq 0 ]; then
    echo "native-ffmpeg-build: all cases behave"
    exit 0
fi
echo "native-ffmpeg-build: $FAILED case(s) failed" >&2
exit 1
