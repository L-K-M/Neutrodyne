#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# smoke-start.sh <image dir> [--timeout seconds]
#
# Starts a packaged Neutrodyne app image in smoke mode and prints its `SMOKE {json}`
# line (11 Smoke mode). Used by check-runtime-sources.sh --image and the desktop
# release gates.
#
# A jpackage launcher takes JVM options only from its generated .cfg: the launcher's
# [JavaOptions] entries map to the `java-options=` lines, and -D arguments passed on
# the command line land in main()'s argv instead of the JVM. Smoke mode therefore
# rides in through a temporary `java-options=-Dneutrodyne.smoke=true` line appended
# to the image's .cfg. The .cfg is restored byte-for-byte afterwards — original
# bytes and mode, including a pre-existing smoke option and an absent final newline.
#
# <image dir> is a jpackage app image: `Neutrodyne/` (Windows and Linux), or
# `Neutrodyne.app` (macOS). Exit 0 = the launcher exited 0 and its final SMOKE
# line is a JSON object with no `failed` field; 1 = the launcher exited nonzero,
# printed no SMOKE line, printed a malformed or non-object SMOKE line, reported a
# failed step, or hit the watchdog (60 s by default, Smoke mode's budget). The
# final SMOKE line is the verdict: smoke mode prints it and then exits 1 when a
# step failed, so a SMOKE line alone is not success. Diagnostics go to stderr;
# stdout carries only the SMOKE line on success.
#
# The watchdog is a Python 3 stdlib helper (present on every runner) so the bound
# holds on Windows too, where GNU timeout and Windows timeout.exe are different
# tools. On timeout or cancel it kills only the launched process tree.

set -euo pipefail

IMG=""
TIMEOUT_S=60
for arg in "$@"; do
    case "$arg" in
        --timeout=*) TIMEOUT_S="${arg#--timeout=}" ;;
        -h|--help) sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d'; exit 0 ;;
        *) IMG="$arg" ;;
    esac
done
case "$TIMEOUT_S" in
    ''|*[!0-9]*|0)
        echo "smoke-start: --timeout needs a positive number of seconds" >&2
        exit 2
        ;;
esac
if [ -z "$IMG" ] || [ ! -d "$IMG" ]; then
    echo "usage: $0 <image dir> [--timeout=seconds]" >&2
    exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "smoke-start: python3 is required for the portable watchdog" >&2
    exit 1
fi

# Locate the launcher and its .cfg for the image layouts: Linux keeps the launcher
# in bin/ and the .cfg in lib/app/, Windows puts both at the image root's
# Neutrodyne.exe and app/, macOS nests everything under Contents/.
LAUNCHER="" CFG=""
for cand in \
    "$IMG/bin/Neutrodyne" \
    "$IMG/bin/Neutrodyne.exe" \
    "$IMG/Neutrodyne.exe" \
    "$IMG/Contents/MacOS/Neutrodyne"; do
    if [ -f "$cand" ]; then
        LAUNCHER="$cand"
        break
    fi
done
for cand in \
    "$IMG/lib/app/Neutrodyne.cfg" \
    "$IMG/app/Neutrodyne.cfg" \
    "$IMG/Contents/app/Neutrodyne.cfg"; do
    if [ -f "$cand" ]; then
        CFG="$cand"
        break
    fi
done
if [ -z "$LAUNCHER" ] || [ -z "$CFG" ]; then
    echo "smoke-start: no launcher or .cfg under $IMG" >&2
    exit 1
fi

SMOKE_OPT='java-options=-Dneutrodyne.smoke=true'

RUN_DIR="$(mktemp -d)" || exit 1
CANCEL_FILE="$RUN_DIR/cancel"
OUT="$RUN_DIR/launcher.out"
CFG_BACKUP="$RUN_DIR/Neutrodyne.cfg.orig"
WATCHDOG_PID=""

# shellcheck disable=SC2329 # runs as the EXIT trap
restore_cfg() {
    # Restore the original bytes in place: the inode (and with it the mode) stays,
    # a pre-existing smoke option and an absent final newline survive untouched.
    if [ -f "$CFG_BACKUP" ]; then
        cat "$CFG_BACKUP" >"$CFG" || echo "smoke-start: could not restore $CFG" >&2
    fi
}
# shellcheck disable=SC2329 # runs as the EXIT trap
cleanup() {
    restore_cfg
    rm -rf "$RUN_DIR"
}
trap cleanup EXIT

# shellcheck disable=SC2329 # runs as the INT/TERM trap
request_cancel() {
    # The watchdog polls this file; it then kills only the launched process tree.
    : >"$CANCEL_FILE" 2>/dev/null || true
    if [ -n "$WATCHDOG_PID" ]; then
        wait "$WATCHDOG_PID" 2>/dev/null || true
    fi
}
trap request_cancel INT TERM

# Back up the .cfg before touching it, so the restore is byte-for-byte.
cat "$CFG" >"$CFG_BACKUP"
if ! grep -qxF "$SMOKE_OPT" "$CFG"; then
    # Appending to a file without a final newline would merge the option into the
    # last line; put it on its own line first. The backup undoes both writes.
    if [ -s "$CFG" ] && [ -n "$(tail -c 1 "$CFG")" ]; then
        printf '\n' >>"$CFG"
    fi
    printf '%s\n' "$SMOKE_OPT" >>"$CFG"
fi

# The watchdog runs the launcher in its own session/process group so a timeout or
# cancel can kill exactly that tree. Paths travel as argv, never as code.
rc=0
python3 - "$LAUNCHER" "$OUT" "$TIMEOUT_S" "$CANCEL_FILE" "$$" <<'PY' &
import os
import signal
import subprocess
import sys
import time

launcher, out_path, timeout_s, cancel_path, parent_s = sys.argv[1:6]
timeout_s = float(timeout_s)
parent_pid = int(parent_s)

kwargs = {}
if os.name == "posix":
    kwargs["start_new_session"] = True
elif os.name == "nt":
    kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP

try:
    with open(out_path, "wb") as out:
        proc = subprocess.Popen([launcher], stdout=out, stderr=subprocess.STDOUT, **kwargs)
except OSError as exc:
    print(f"smoke-start: cannot launch {launcher}: {exc}", file=sys.stderr)
    sys.exit(125)

# The launcher leads its own session/process group; record the group now so a
# later kill cannot address a reused pid.
if os.name == "posix":
    try:
        pgid = os.getpgid(proc.pid)
    except OSError:
        pgid = None
else:
    pgid = None

def kill_tree():
    if os.name == "posix" and pgid is not None:
        try:
            os.killpg(pgid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
    else:
        try:
            subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except OSError:
            pass

def parent_gone():
    if os.name != "posix":
        return False
    try:
        os.kill(parent_pid, 0)
    except ProcessLookupError:
        return True
    except PermissionError:
        return False
    return False

deadline = time.monotonic() + timeout_s
outcome = "exit"
try:
    while True:
        try:
            proc.wait(timeout=0.1)
            break
        except subprocess.TimeoutExpired:
            if os.path.exists(cancel_path) or parent_gone():
                outcome = "cancel"
                break
            if time.monotonic() >= deadline:
                outcome = "timeout"
                break
except KeyboardInterrupt:
    outcome = "cancel"

if outcome != "exit":
    kill_tree()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    sys.exit(124 if outcome == "timeout" else 130)

sys.exit(proc.returncode)
PY
WATCHDOG_PID=$!
wait "$WATCHDOG_PID" || rc=$?
if [ -f "$CANCEL_FILE" ]; then
    rc=130
fi

dump_output() {
    if [ -f "$OUT" ]; then
        awk 'NR<=40 {print "  | " $0}' "$OUT" >&2
    fi
}

# The launcher's own output is diagnostics, never the script's stdout contract.
if [ "$rc" -eq 124 ]; then
    echo "smoke-start: launcher exceeded the ${TIMEOUT_S}s watchdog; killed its process tree" >&2
    dump_output
    exit 1
fi
if [ "$rc" -eq 130 ]; then
    echo "smoke-start: cancelled; killed the launcher's process tree" >&2
    dump_output
    exit 1
fi
if [ "$rc" -eq 125 ]; then
    echo "smoke-start: could not start the launcher" >&2
    dump_output
    exit 1
fi
if [ "$rc" -ne 0 ]; then
    echo "smoke-start: launcher exited $rc without a successful smoke report" >&2
    dump_output
    exit 1
fi

# The final SMOKE line is the verdict: a JSON object, no `failed` field. stdout
# carries exactly that line on success.
if ! python3 - "$OUT" <<'PY'
import json
import sys

out_path = sys.argv[1]
smoke_lines = []
with open(out_path, "r", encoding="utf-8", errors="replace") as handle:
    for line in handle:
        if line.startswith("SMOKE "):
            smoke_lines.append(line.rstrip("\r\n"))
if not smoke_lines:
    print("smoke-start: no SMOKE line in the launcher output", file=sys.stderr)
    sys.exit(1)
line = smoke_lines[-1]
payload = line[len("SMOKE "):]
try:
    report = json.loads(payload)
except json.JSONDecodeError as exc:
    print(f"smoke-start: final SMOKE line is not valid JSON: {exc}: {payload}", file=sys.stderr)
    sys.exit(1)
if not isinstance(report, dict):
    print(f"smoke-start: final SMOKE line is not a JSON object: {payload}", file=sys.stderr)
    sys.exit(1)
if "failed" in report:
    print("smoke-start: smoke run reported a failed step:", file=sys.stderr)
    print(payload, file=sys.stderr)
    sys.exit(1)
print(line)
PY
then
    echo "smoke-start: launcher output context:" >&2
    dump_output
    exit 1
fi

if [ -f "$CANCEL_FILE" ]; then
    echo "smoke-start: cancelled" >&2
    exit 1
fi
exit 0
