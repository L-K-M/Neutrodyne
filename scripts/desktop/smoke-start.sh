#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# smoke-start.sh <image dir> [--timeout seconds] [--engine-replay=<dir>]
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
# --engine-replay=<dir> arms MD3 AC2's packaged engine proof: <dir> is a recorded
# scenario directory (the shared corpus' `recorded/resolve/`, http.json included)
# injected as `-Dneutrodyne.smoke.engineReplay`; the image's engine child replays
# the recorded exchanges, never the network. The SMOKE line must then carry an
# `engine` object proving the resolve through the image's own packaged runtime —
# the interpreter and engine resources inside the image, the pinned interpreter
# and yt-dlp versions, the resolved audio's recorded identity and a dead child —
# and the step's budget grows to 300 s for the cold bundled compile. A .cfg or
# `_JAVA_OPTIONS` declaration of the replay property fails before launch, and a
# missing or contradicting `engine` object fails the verdict.
#
# An image whose .cfg reads a trained AOT cache (-XX:AOTCache=) gets a stricter
# contract: the run appends -XX:AOTMode=on to the child's JAVA_TOOL_OPTIONS and
# the final SMOKE line must report "aotCache":"in-use" — a fallback, a missing
# archive or an unverified cache is a failure, never an unverified pass. A
# conflicting -XX:AOT* option in the .cfg, _JAVA_OPTIONS or JAVA_TOOL_OPTIONS
# (a mode override or a different archive) fails before launch. Images without
# the flag keep the ordinary smoke contract.
#
# <image dir> is a jpackage app image: `Neutrodyne/` (Windows and Linux), or
# `Neutrodyne.app` (macOS). Exit 0 = the launcher exited 0 and its final SMOKE
# line is a JSON object with no `failed` field (plus the `engine` evidence when
# --engine-replay armed it); 1 = the launcher exited nonzero, printed no SMOKE
# line, printed a malformed or non-object SMOKE line, reported a failed step,
# hit the watchdog (60 s by default, 300 s armed; Smoke mode's budget), or the
# .cfg changed during the run. The final SMOKE line is the verdict: smoke mode
# prints it and then exits 1 when a step failed, so a SMOKE line alone is not
# success. Diagnostics go to stderr; stdout carries only the SMOKE line on
# success.
#
# The watchdog is a Python 3 stdlib helper (present on every runner) so the bound
# holds on Windows too, where GNU timeout and Windows timeout.exe are different
# tools. On timeout or cancel it kills only the launched process tree.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"

IMG=""
TIMEOUT_S=""
REPLAY_SEEN=""
REPLAY=""
for arg in "$@"; do
    case "$arg" in
        --timeout=*) TIMEOUT_S="${arg#--timeout=}" ;;
        --engine-replay=*) REPLAY="${arg#--engine-replay=}"; REPLAY_SEEN=1 ;;
        -h|--help) sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d'; exit 0 ;;
        *) IMG="$arg" ;;
    esac
done
if [ -n "$REPLAY_SEEN" ]; then
    if [ -z "$REPLAY" ] || [ ! -d "$REPLAY" ] || [ ! -f "$REPLAY/http.json" ]; then
        echo "smoke-start: --engine-replay needs a recorded scenario directory with http.json" >&2
        exit 2
    fi
    REPLAY="$(cd -- "$REPLAY" >/dev/null 2>&1 && pwd -P)"
    # The JVM property needs the interpreter's own path form: on Windows the
    # launcher's File() cannot read an MSYS /d/... path, so hand it the
    # drive-letter form (cygpath -m keeps forward slashes, which Java accepts).
    if command -v cygpath >/dev/null 2>&1; then
        REPLAY="$(cygpath -m "$REPLAY")"
    fi
fi
if [ -z "$TIMEOUT_S" ]; then
    # The armed run's budget covers a cold bundled compile plus the resolve.
    if [ -n "$REPLAY" ]; then TIMEOUT_S=300; else TIMEOUT_S=60; fi
fi
case "$TIMEOUT_S" in
    ''|*[!0-9]*|0)
        echo "smoke-start: --timeout needs a positive number of seconds" >&2
        exit 2
        ;;
esac
if [ -z "$IMG" ] || [ ! -d "$IMG" ]; then
    echo "usage: $0 <image dir> [--timeout=seconds] [--engine-replay=<dir>]" >&2
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
python3 - "$CFG" "$SNAPSHOT" "${JAVA_TOOL_OPTIONS-}" "${_JAVA_OPTIONS-}" "$REPLAY" "$RUN_DIR/aot-strict" >"$RUN_DIR/java-tool-options" <<'PY' || exit 1
import hashlib
import os
import stat
import sys

cfg_path, snapshot_path, jto, underscore, replay, aot_marker = sys.argv[1:7]
SMOKE_PROP = "neutrodyne.smoke"
SMOKE_OPT = "-Dneutrodyne.smoke=true"
REPLAY_PROP = "neutrodyne.smoke.engineReplay"
WHITESPACE = " \t\r\n"
PREFIX = "-D" + SMOKE_PROP
REPLAY_PREFIX = "-D" + REPLAY_PROP


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
    # The .cfg may never declare the replay property: it would override the
    # injected value below and replay a fixture the gate did not choose.
    if option == REPLAY_PREFIX or option.startswith(REPLAY_PREFIX + "="):
        fail(f"{cfg_path} declares -D{REPLAY_PROP}; the launcher chooses the fixture")
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
if any(token == REPLAY_PREFIX or token.startswith(REPLAY_PREFIX + "=")
       for token in underscore_tokens):
    fail(f"_JAVA_OPTIONS sets -D{REPLAY_PROP}; the launcher chooses the fixture")

# The launcher's effective AOT options come from the .cfg's [JavaOptions]
# java-options lines (jpackage's cfg parser reads keys inside their [Section];
# the section name runs to the last ] on the line). A cfg that reads a trained
# archive gets the strict contract: the run requests -XX:AOTMode=on and the
# verdict requires aotCache=in-use, so no source may override the mode or
# redirect the archive. The .cfg wins over JAVA_TOOL_OPTIONS and _JAVA_OPTIONS
# wins over both, so the strict request only survives when none of the three
# fight it.
cfg_options = []
in_java_options = False
for line in text.split("\n"):
    if os.name == "nt" and line.endswith("\r"):
        line = line[:-1]
    if line.startswith("["):
        end = line.rfind("]")
        in_java_options = end > 0 and line[1:end] == "JavaOptions"
        continue
    if not in_java_options:
        continue
    key, separator, option = line.partition("=")
    if separator and key.strip().lower() == "java-options":
        cfg_options.append(option.lstrip())
aot_requested = any(o.startswith("-XX:AOTCache=") for o in cfg_options)


def aot_conflicts(tokens, allow_mode=False, allow_cache=False):
    """-XX:AOT* tokens that would override the strict request or redirect the
    archive; an allowed flag value still leaves the in-use proof intact."""
    bad = []
    for token in tokens:
        if not token.startswith("-XX:"):
            continue
        name = token[4:]
        if name[:1] in "+-":
            name = name[1:]
        name = name.split("=", 1)[0]
        if not name.startswith("AOT"):
            continue
        if token == "-XX:AOTMode=on":
            continue
        if allow_mode and name == "AOTMode":
            continue
        if allow_cache and name == "AOTCache":
            continue
        bad.append(token)
    return bad


if aot_requested:
    bad = aot_conflicts(underscore_tokens)
    if bad:
        fail(f"_JAVA_OPTIONS sets {bad[0]!r}, which would override the strict AOT launch")
    # JAVA_TOOL_OPTIONS' own AOTMode is safe: our appended =on is last and wins
    # inside the same variable, and the cfg wins over it anyway.
    bad = aot_conflicts(jto_tokens, allow_mode=True)
    if bad:
        fail(f"JAVA_TOOL_OPTIONS sets {bad[0]!r}, which would override the strict AOT launch")
    cfg_tokens = []
    for option in cfg_options:
        try:
            cfg_tokens.extend(tokenize(option))
        except ValueError as error:
            fail(f"invalid quoting in {cfg_path} java-options: {error}")
    bad = aot_conflicts(cfg_tokens, allow_cache=True)
    if bad:
        fail(f"{cfg_path} declares {bad[0]!r}, which would override the strict AOT launch")

# The last -D wins within JAVA_TOOL_OPTIONS, so our true overrides an earlier
# false there; unrelated options are preserved. The replay property rides quoted:
# the JVM honours double quotes inside JAVA_TOOL_OPTIONS, so a fixture path with
# spaces survives as one option.
options = f"{jto} {SMOKE_OPT}".strip() if jto.strip() else SMOKE_OPT
if replay:
    options = f'{options} {REPLAY_PREFIX}="{replay}"'
if aot_requested:
    options += " -XX:AOTMode=on"
    with open(aot_marker, "w", encoding="ascii") as handle:
        handle.write("1\n")
print(options)
PY
# A heredoc inside $(...) is misparsed by macOS's system Bash 3.2. Keep the
# Python input outside command substitution, then read its successful result.
JAVA_TOOL_OPTIONS="$(cat "$RUN_DIR/java-tool-options")"
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
# carries exactly that line on success. An AOT-configured image must also
# report the trained archive in use — the strict request was already applied,
# so a fallback or unverified cache is a real failure, not a pass.
if ! python3 - "$OUT" "$RUN_DIR/aot-strict" >"$VERDICT" <<'PY'
import json
import os
import sys

out_path = sys.argv[1]
aot_strict = os.path.exists(sys.argv[2])
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
if aot_strict and report.get("aotCache") != "in-use":
    print(
        "smoke-start: aotCache reported "
        f"{report.get('aotCache')!r}, not 'in-use': the image reads -XX:AOTCache "
        "but the trained archive did not load under the strict -XX:AOTMode=on request",
        file=sys.stderr,
    )
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

# The armed run's extra verdict (MD3 AC2): the engine object must prove the
# recorded resolve ran through the image's own packaged runtime — interpreter and
# resources inside the checked image, the lock-pinned interpreter version, the
# manifest-pinned yt-dlp, the recorded answer and a dead child. Anything missing
# or contradicting fails the gate; the SMOKE line alone is never enough.
if [ -n "$REPLAY" ]; then
    PY_LOCK="$REPO_ROOT/youtube/ytdlp-desktop/python-components.lock"
    BUNDLED="$REPO_ROOT/youtube/ytdlp/engine/bundled.json"
    for pin in "$PY_LOCK" "$BUNDLED"; do
        if [ ! -f "$pin" ]; then
            echo "smoke-start: expected pin file is missing: $pin" >&2
            exit 1
        fi
    done
    # The inside-the-image comparison needs the image's canonical path in the
    # interpreter's own form — a Windows caller may hand an MSYS path over.
    IMG_CHECK="$(cd -- "$IMG" >/dev/null 2>&1 && pwd -P)"
    if command -v cygpath >/dev/null 2>&1; then
        IMG_CHECK="$(cygpath -m "$IMG_CHECK")"
    fi
    if ! python3 - "$IMG_CHECK" "$VERDICT" "$PY_LOCK" "$BUNDLED" <<'PY'
import json
import os
import re
import sys

img_arg, verdict_path, lock_path, bundled_path = sys.argv[1:5]


def fail(message):
    print(f"smoke-start: engine evidence: {message}", file=sys.stderr)
    sys.exit(1)


with open(verdict_path, "r", encoding="utf-8") as handle:
    line = handle.read().strip()
report = json.loads(line[len("SMOKE "):])
engine = report.get("engine")
if not isinstance(engine, dict):
    fail("the SMOKE line carries no engine evidence for the armed replay run")
if engine.get("resolve") != "ok":
    fail(f"resolve is {engine.get('resolve')!r}, not ok")
for key, wanted in (("videoId", "aaaaaaaaaaA"), ("formatId", "140"),
                    ("urlHost", "media.example.invalid"), ("host", "stdio"),
                    ("child", "exited"), ("cacert", True)):
    if engine.get(key) != wanted:
        fail(f"{key} is {engine.get(key)!r}, not {wanted!r}")

# The reported interpreter and resource paths must canonicalise inside the very
# image the gate launched, and the interpreter inside the packaged engine's
# python/ — a host or dev-tree interpreter can never satisfy this.
img = os.path.normcase(os.path.realpath(img_arg))
def real(field):
    value = engine.get(field)
    if not isinstance(value, str) or not value:
        fail(f"{field} is missing or not a path")
    return os.path.normcase(os.path.realpath(value))

resources = real("resources")
if not resources.startswith(img + os.sep):
    fail("resources is outside the checked image")
if os.path.basename(resources) != "engine":
    fail("resources is not the image's engine/ directory")
interpreter = real("interpreter")
if not interpreter.startswith(resources + os.sep + "python" + os.sep):
    fail("interpreter is not inside the packaged engine/python/")
if not os.path.isfile(interpreter):
    fail("interpreter is not a file in the checked image")

with open(lock_path, "r", encoding="utf-8") as handle:
    lock = handle.read()
match = re.search(r'^python\s*=\s*"([^"]+)"', lock, re.M)
expected_python = match.group(1) if match else None
with open(bundled_path, "r", encoding="utf-8") as handle:
    bundled = json.load(handle)
expected_ytdlp = bundled["version"]
expected_ejs = bundled.get("ejsVersion")

if engine.get("python") != expected_python:
    fail(f"python is {engine.get('python')!r}, not the lock's {expected_python!r}")
for key in ("ytDlp", "engineVersion", "expectedYtdlp"):
    if engine.get(key) != expected_ytdlp:
        fail(f"{key} is {engine.get(key)!r}, not the manifest's {expected_ytdlp!r}")
if expected_ejs is not None and engine.get("ejs") != expected_ejs:
    fail(f"ejs is {engine.get('ejs')!r}, not the manifest's {expected_ejs!r}")
if engine.get("shimApi") != 1:
    fail(f"shimApi is {engine.get('shimApi')!r}, not 1")
PY
    then
        echo "smoke-start: engine evidence check failed" >&2
        dump_output
        exit 1
    fi
fi

printf '%s\n' "$(cat "$VERDICT")"
exit 0
