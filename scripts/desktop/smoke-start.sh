#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# smoke-start.sh <image dir> [--timeout seconds]
#
# Starts a packaged Neutrodyne app image in smoke mode and prints its `SMOKE {json}`
# line (11 Smoke mode). Used by check-runtime-sources.sh --image and the desktop
# release gates.
#
# A jpackage launcher takes JVM options from its generated .cfg and from the
# environment. Smoke mode rides in through JAVA_TOOL_OPTIONS: the script appends
# `-Dneutrodyne.smoke=true` to the child's JAVA_TOOL_OPTIONS, preserving any
# existing options, and never writes the .cfg. It fails before launch when the
# image's .cfg or `_JAVA_OPTIONS` would override the property with a non-true
# value. The .cfg is snapshotted read-only before the run and compared after it;
# any byte, mode or type change fails the gate without a SMOKE line, and the
# tool never repairs another process's writes. A read-only .cfg and image are
# valid because nothing here writes them.
#
# <image dir> is a jpackage app image: `Neutrodyne/` (Windows and Linux), or
# `Neutrodyne.app` (macOS). Exit 0 = the launcher exited 0 and its final SMOKE
# line is a JSON object with no `failed` field; 1 = the launcher exited nonzero,
# printed no SMOKE line, printed a malformed or non-object SMOKE line, reported a
# failed step, hit the watchdog (60 s by default, Smoke mode's budget), or the
# .cfg changed during the run. The final SMOKE line is the verdict: smoke mode
# prints it and then exits 1 when a step failed, so a SMOKE line alone is not
# success. Diagnostics go to stderr; stdout carries only the SMOKE line on
# success.
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

RUN_DIR="$(mktemp -d)" || exit 1
CANCEL_FILE="$RUN_DIR/cancel"
OUT="$RUN_DIR/launcher.out"
SNAPSHOT="$RUN_DIR/cfg.before"
VERDICT="$RUN_DIR/verdict"
WATCHDOG_PID=""

# shellcheck disable=SC2329 # runs as the EXIT trap
cleanup() {
    if [ -n "$RUN_DIR" ]; then
        rm -rf "$RUN_DIR" || true
    fi
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

# Snapshot the .cfg read-only and compute the child's JAVA_TOOL_OPTIONS. The
# .cfg's java-options are command-line options and override JAVA_TOOL_OPTIONS;
# `_JAVA_OPTIONS` overrides both. A conflicting value fails before launch.
JAVA_TOOL_OPTIONS="$(python3 - "$CFG" "$SNAPSHOT" "${JAVA_TOOL_OPTIONS-}" "${_JAVA_OPTIONS-}" <<'PY'
import hashlib
import os
import stat
import sys

cfg_path, snapshot_path, jto, underscore = sys.argv[1:5]
SMOKE_PROP = "neutrodyne.smoke"
SMOKE_OPT = "-Dneutrodyne.smoke=true"
WHITESPACE = " \t\r\n"
PREFIX = "-D" + SMOKE_PROP


def fail(message):
    print(f"smoke-start: {message}", file=sys.stderr)
    sys.exit(1)


def tokenize(value):
    """Split a JVM environment variable into options the way the JVM does:
    single and double quotes group text and are removed, backslash is literal,
    and an unmatched quote is an error."""
    tokens = []
    index = 0
    length = len(value)
    while index < length:
        while index < length and value[index] in WHITESPACE:
            index += 1
        if index >= length:
            break
        token = []
        quote = None
        while index < length:
            char = value[index]
            if quote is not None:
                if char == quote:
                    quote = None
                else:
                    token.append(char)
                index += 1
            elif char in "'\"":
                quote = char
                index += 1
            elif char in WHITESPACE:
                break
            else:
                token.append(char)
                index += 1
        if quote is not None:
            raise ValueError("unmatched quote")
        tokens.append("".join(token))
    return tokens


def smoke_value(tokens):
    """The effective smoke property value (last -D wins), or None."""
    value = None
    for token in tokens:
        if token == PREFIX:
            value = ""
        elif token.startswith(PREFIX + "="):
            value = token[len(PREFIX) + 1:]
    return value


try:
    info = os.lstat(cfg_path)
except OSError as error:
    fail(f"cannot inspect {cfg_path}: {error}")
if not stat.S_ISREG(info.st_mode):
    fail(f"configuration {cfg_path} is not a regular file")
try:
    with open(cfg_path, "rb") as handle:
        data = handle.read()
except OSError as error:
    fail(f"cannot read {cfg_path}: {error}")
mode = stat.S_IMODE(info.st_mode)
digest = hashlib.sha256(data).hexdigest()
with open(snapshot_path, "w", encoding="ascii") as handle:
    handle.write(f"{stat.S_IFMT(info.st_mode):o} {mode:o} {digest}\n")

try:
    jto_tokens = tokenize(jto)
    underscore_tokens = tokenize(underscore)
except ValueError as error:
    fail(f"invalid quoting in JAVA_TOOL_OPTIONS or _JAVA_OPTIONS: {error}")

# Reject every conflicting declaration, including in an unknown section. A
# later, ignored section must not mask an earlier active false value. Preserve
# option-value whitespace: POSIX jpackage passes a trailing CR/space to the JVM,
# where "true\r" and "true " do not enable the application's smoke mode.
text = data.decode("utf-8", errors="replace")
for line in text.split("\n"):
    if os.name == "nt":
        line = line.removesuffix("\r")  # Windows text-mode CRLF translation
    key, separator, option = line.partition("=")
    if not separator or key.strip().lower() != "java-options":
        continue
    option = option.lstrip()
    if option == PREFIX:
        cfg_value = ""
    elif option.startswith(PREFIX + "="):
        cfg_value = option[len(PREFIX) + 1:]
    else:
        continue
    if cfg_value != "true":
        fail(f"{cfg_path} declares -D{SMOKE_PROP}={cfg_value!r}, not true")

underscore_value = smoke_value(underscore_tokens)
if underscore_value is not None and underscore_value != "true":
    fail(f"_JAVA_OPTIONS sets -D{SMOKE_PROP}={underscore_value!r}, not true")

# The last -D wins within JAVA_TOOL_OPTIONS, so our true overrides an earlier
# false there; unrelated options are preserved.
print(f"{jto} {SMOKE_OPT}".strip() if jto.strip() else SMOKE_OPT)
PY
)" || exit 1
export JAVA_TOOL_OPTIONS

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

# The .cfg must be exactly as it was before the run: the tool never writes it,
# so any change is another process's mutation and no verdict is trusted.
if ! python3 - "$CFG" "$SNAPSHOT" <<'PY'
import hashlib
import os
import stat
import sys

cfg_path, snapshot_path = sys.argv[1:3]
try:
    with open(snapshot_path, "r", encoding="ascii") as handle:
        expected_type, expected_mode, expected_digest = handle.read().split()
except (OSError, ValueError):
    print("smoke-start: missing configuration snapshot", file=sys.stderr)
    sys.exit(1)
try:
    info = os.lstat(cfg_path)
except OSError as error:
    print(f"smoke-start: configuration {cfg_path} is gone: {error}", file=sys.stderr)
    sys.exit(1)
if not stat.S_ISREG(info.st_mode):
    print(f"smoke-start: configuration {cfg_path} is no longer a regular file", file=sys.stderr)
    sys.exit(1)
try:
    with open(cfg_path, "rb") as handle:
        data = handle.read()
except OSError as error:
    print(f"smoke-start: configuration {cfg_path} is unreadable: {error}", file=sys.stderr)
    sys.exit(1)
actual_type = f"{stat.S_IFMT(info.st_mode):o}"
actual_mode = f"{stat.S_IMODE(info.st_mode):o}"
actual_digest = hashlib.sha256(data).hexdigest()
if (actual_type, actual_mode, actual_digest) != (expected_type, expected_mode, expected_digest):
    print(
        "smoke-start: configuration changed during the run "
        f"(type {expected_type}->{actual_type}, mode {expected_mode}->{actual_mode}, "
        f"sha256 {expected_digest[:12]}->{actual_digest[:12]}); no verdict is trusted",
        file=sys.stderr,
    )
    sys.exit(1)
PY
then
    echo "smoke-start: configuration integrity check failed" >&2
    dump_output
    exit 1
fi

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
if ! python3 - "$OUT" >"$VERDICT" <<'PY'
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
printf '%s\n' "$(cat "$VERDICT")"
exit 0
