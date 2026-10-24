#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# bmgr-check.sh <api> <evidence-dir>
#
# The M3 AC6 positive local-transport gate: one full host-driven cycle on a rooted
# `default` x86_64 emulator of the given API (31 or 36, owner matrix 2026-10-20).
# Runs the published release APK — no debuggable target, no -t/-r on it — through:
#
#   install -> prepare (real first start, receiver-published snapshot, canaries)
#   -> encryption-off control -> encryption-on backup -> real TAR verification
#   -> pm uninstall -> identical-APK reinstall -> install-time transport restore
#   -> verify (real production first-launch logical restore)
#   -> empty-transport control leg
#
# The script never repairs the positive leg: no `bmgr restore 1`, no `pm clear`,
# no file copies, no reseed, no app launch after reinstall. On any failure the
# trap still writes every collected artifact. `check-first-launch-restore-results.py
# --bmgr` is the strict aggregate over both legs' evidence.

set -euo pipefail

API="${1:?usage: bmgr-check.sh <api> <evidence-dir>}"
EV="$(cd "${2:?usage: bmgr-check.sh <api> <evidence-dir>}" >/dev/null 2>&1 && pwd || { mkdir -p "$2"; cd "$2" && pwd; })"
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd -P)"
cd "$REPO_ROOT"
mkdir -p "$EV"
exec > >(tee -a "$EV/script.log") 2>&1

ADB="${ADB:-adb}"
PKG="ch.lkmc.neutrodyne"
CLASS="ch.lkmc.neutrodyne.BmgrTransportDeviceTest"
RUNNER_PKG=""                     # filled from `pm list instrumentation`
TRANSPORT=""                      # the actual transport name, from `bmgr list transports`
DATA_DIR="/data/data/$PKG"
TRANSPORT_DIR="/data/data/com.android.localtransport/files"
PARAMS_ON="is_encrypted=true,is_device_transfer=false,fake_encryption_flag=false"
PARAMS_OFF="is_encrypted=false,is_device_transfer=false,fake_encryption_flag=false"
NONCE="run-$(date +%s)-$RANDOM"

log() { echo "[bmgr-check $(date +%H:%M:%S)] $*"; }
fail() { echo "[bmgr-check] FAIL: $*" >&2; exit 1; }
fact() { echo "$1=$2" >> "$EV/facts.txt"; }
selector() { echo "$1=pass" >> "$EV/selectors.txt"; fact "selector.$1" pass; }
out() {  # bounded device capture
    local file="$1"; shift
    timeout 120 "$@" > "$EV/$file" 2>&1 || log "capture $file failed ($?)"
}

ORIG_TRANSPORT=""; ORIG_ENABLED=""; ORIG_AUTORESTORE=""; ORIG_PARAMS="null"
LOGCAT_PID=""
finish_run_json() {
    python3 - "$EV" <<'PY' || true
import json, sys
from pathlib import Path
ev = Path(sys.argv[1])
facts = {}
f = ev / "facts.txt"
if f.is_file():
    for line in f.read_text().splitlines():
        k, _, v = line.partition("=")
        facts[k] = v
selectors = {}
s = ev / "selectors.txt"
if s.is_file():
    for line in s.read_text().splitlines():
        k, _, v = line.partition("=")
        selectors[k] = v
run = {
    "schema": "bmgr-evidence/v1",
    "device": facts.get("device", ""),
    "api": int(facts.get("api", "0")),
    "fingerprint": facts.get("fingerprint", ""),
    "abi": facts.get("abi", ""),
    "nonce": facts.get("nonce", ""),
    "sourceSha": facts.get("sourceSha", ""),
    "apkSha256": facts.get("apkSha256", ""),
    "apkBytes": int(facts.get("apkBytes", "0")),
    "testApkSha256": facts.get("testApkSha256", ""),
    "certSha256": facts.get("certSha256", ""),
    "transport": facts.get("transport", ""),
    "tarSha256": facts.get("tarSha256", ""),
    "unencryptedDiagnosis": facts.get("unencryptedDiagnosis", ""),
    "startedAt": facts.get("startedAt", ""),
    "finishedAt": facts.get("finishedAt", ""),
    "selectors": selectors,
}
(ev / "run.json").write_text(json.dumps(run, indent=2) + "\n")
PY
}
cleanup() {
    status=$?
    fact finishedAt "$(date -u +%FT%TZ)"
    finish_run_json
    [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null || true
    timeout 60 "$ADB" shell dumpsys backup > "$EV/dumpsys-final.txt" 2>&1 || true
    timeout 60 "$ADB" shell bmgr list transports > "$EV/transports-final.txt" 2>&1 || true
    # Restore every transport/backup setting this run touched; only our packages leave.
    if [ -n "$ORIG_TRANSPORT" ] && [ "$ORIG_TRANSPORT" != "$TRANSPORT" ]; then
        timeout 60 "$ADB" shell bmgr transport "$ORIG_TRANSPORT" || true
    fi
    [ -n "$ORIG_ENABLED" ] && timeout 60 "$ADB" shell bmgr enable "$ORIG_ENABLED" || true
    [ -n "$ORIG_AUTORESTORE" ] && timeout 60 "$ADB" shell bmgr autorestore "$ORIG_AUTORESTORE" || true
    if [ "$ORIG_PARAMS" = "null" ]; then
        timeout 60 "$ADB" shell settings delete secure backup_local_transport_parameters || true
    elif [ -n "$ORIG_PARAMS" ]; then
        timeout 60 "$ADB" shell settings put secure backup_local_transport_parameters "$ORIG_PARAMS" || true
    fi
    timeout 60 "$ADB" shell pm uninstall --user 0 "$PKG" || true
    [ -n "$RUNNER_PKG" ] && timeout 60 "$ADB" shell pm uninstall --user 0 "$RUNNER_PKG" || true
    log "evidence left in $EV (exit $status)"
}
trap cleanup EXIT

require_disk() {  # >= 10 GiB before every heavy step
    local free
    free=$(df -k "$REPO_ROOT" | awk 'NR==2 {print $4}')
    [ "${free:-0}" -ge 10485760 ] || fail "disk headroom under 10 GiB ($free KiB)"
}

current_token() {  # newest restore-set token — `bmgr init` rotates it, never hardcode one
    timeout 60 "$ADB" shell bmgr list sets | tr -d '\r' \
        | awk '$1 ~ /^[0-9]+$/ {if ($1+0 > m) m = $1+0} END {if (m) print m}'
}

instrument() {  # <phase> <method> <extra args...>; strict raw stream kept
    local phase="$1" method="$2"
    local log_file="$EV/instrument-$phase.log"
    shift 2
    log "instrument $phase: $CLASS#$method"
    timeout 420 "$ADB" shell am instrument -w -r \
        -e bmgrPhase "$phase" -e class "$CLASS#$method" "$@" \
        "$RUNNER_PKG/ch.lkmc.neutrodyne.NeutrodyneTestRunner" 2>&1 | tee "$log_file" || true
    grep -q "^INSTRUMENTATION_CODE: -1" "$log_file" \
        || fail "instrument $phase did not complete successfully"
}

tool() {  # build-tools binary: PATH first, then the newest SDK build-tools dir
    command -v "$1" 2>/dev/null && return
    ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/ 2>/dev/null \
        | sort -V | tail -1 | xargs -I{} echo "{}$1"
}

main() {
    require_disk
    fact device "api$API"; fact api "$API"; fact nonce "$NONCE"
    fact startedAt "$(date -u +%FT%TZ)"
    fact sourceSha "$(git rev-parse HEAD)"

    # --- device: the disposable rooted default-image emulator, already booted ---
    timeout 660 "$ADB" wait-for-device
    for _ in $(seq 1 60); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 10
    done
    timeout 120 "$ADB" root && sleep 2 && timeout 120 "$ADB" wait-for-device
    [ "$("$ADB" shell id -u | tr -d '\r')" = "0" ] || fail "adbd is not root"
    SDK="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
    [ "$SDK" = "$API" ] || fail "emulator runs API $SDK, not $API"
    fact abi "$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
    fact fingerprint "$("$ADB" shell getprop ro.build.fingerprint | tr -d '\r')"
    grep -q '^abi=x86_64$' "$EV/facts.txt" || fail "the transport leg needs an x86_64 emulator"
    out dumpsys-before.txt "$ADB" shell dumpsys backup
    "$ADB" logcat -v threadtime > "$EV/logcat.txt" 2>/dev/null &
    LOGCAT_PID=$!

    # --- original backup state (restored in the trap) ---
    out transports-before.txt "$ADB" shell bmgr list transports
    TRANSPORT="$(grep -o 'com\.android\.localtransport/\.LocalTransport' "$EV/transports-before.txt" | head -1)"
    [ -n "$TRANSPORT" ] || fail "local transport not in: $(cat "$EV/transports-before.txt")"
    ORIG_TRANSPORT="$(grep '^\*' "$EV/transports-before.txt" | sed 's/^[* ]*//' | head -1)"
    ORIG_ENABLED="$(timeout 60 "$ADB" shell bmgr enabled | tr -d '\r' | grep -o 'enabled\|disabled' | head -1)"
    ORIG_AUTORESTORE="$(timeout 60 "$ADB" shell bmgr autorestore | tr -d '\r' | grep -o 'enabled\|disabled' | head -1)"
    ORIG_PARAMS="$(timeout 60 "$ADB" shell settings get secure backup_local_transport_parameters | tr -d '\r' || true)"
    log "originals: transport=$ORIG_TRANSPORT enabled=$ORIG_ENABLED autorestore=$ORIG_AUTORESTORE params=$ORIG_PARAMS"

    timeout 60 "$ADB" shell bmgr enable true
    timeout 60 "$ADB" shell bmgr autorestore true
    timeout 60 "$ADB" shell bmgr transport "$TRANSPORT"
    timeout 60 "$ADB" shell bmgr list transports | grep -E "^\* +$TRANSPORT" \
        || fail "local transport not selected"
    fact transport "$TRANSPORT"

    # --- APK gates: the published-artifact checks already own debuggable/signer facts ---
    require_disk
    APK="$(ls app/build/outputs/apk/release/app-x86_64-release.apk 2>/dev/null || true)"
    [ -n "$APK" ] || APK="$(find app/build/outputs/apk/release -name 'app-*-release.apk' | head -1)"
    TESTAPK=app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
    [ -f "$APK" ] || fail "release APK missing — build before boot"
    [ -f "$TESTAPK" ] || fail "releaseAndroidTest APK missing — build before boot"
    scripts/ci/check-apk.sh --published "$APK" | tee "$EV/check-apk.txt"
    timeout 60 "$(tool aapt2)" dump badging "$APK" > "$EV/apk-badging.txt" 2>&1 || true
    ! grep -q "application-debuggable" "$EV/apk-badging.txt" || fail "release APK is debuggable"
    selector apkNonDebuggable
    fact apkSha256 "$(sha256sum "$APK" | cut -d' ' -f1)"
    fact apkBytes "$(stat -c%s "$APK")"
    fact testApkSha256 "$(sha256sum "$TESTAPK" | cut -d' ' -f1)"
    fact certSha256 "$(timeout 120 "$(tool apksigner)" verify --print-certs "$APK" | grep -o 'SHA-256 digest: .*' | head -1 | cut -d' ' -f3-)"
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-initial.txt"
    timeout 300 "$ADB" install -t --user 0 "$TESTAPK" | tee "$EV/install-test.txt"
    RUNNER_PKG="$(timeout 60 "$ADB" shell pm list instrumentation | grep "(target=$PKG)" \
        | sed 's/instrumentation://; s|/.*||' | tr -d '\r')"
    [ -n "$RUNNER_PKG" ] || fail "no instrumentation targets $PKG"
    selector testInstrumentationAvailable
    log "runner component: $RUNNER_PKG"

    # --- prepare: the ordinary first start seeds, publishes, records expectations ---
    instrument prepare prepareLocalTransportBackup -e bmgrNonce "$NONCE"
    grep -o 'INSTRUMENTATION_STATUS: bmgrExpected=[^ ]*' "$EV/instrument-prepare.log" \
        | sed 's/.*=//' > "$EV/expected.b64"
    [ -s "$EV/expected.b64" ] || fail "prepare did not emit bmgrExpected"
    python3 -c 'import base64,sys,json; print(json.dumps(json.loads(base64.urlsafe_b64decode(sys.stdin.read()+"===")),indent=2))' \
        < "$EV/expected.b64" > "$EV/expectation.json"
    grep -q '"nonce": "'"$NONCE"'"' "$EV/expectation.json" || fail "expectation nonce mismatch"
    selector expectationCaptured

    # --- eligibility: an instrumentation-finished package may sit stopped; a normal
    #     user launch before backup clears it (never a force-stop, never a clear) ---
    timeout 60 "$ADB" shell dumpsys package "$PKG" > "$EV/dumpsys-package.txt" || true
    if grep -q 'stopped=true' "$EV/dumpsys-package.txt"; then
        log "package is stopped — one normal launcher run restores backup eligibility"
        timeout 60 "$ADB" shell am start -n "$PKG/.MainActivity" | tee "$EV/launch.txt"
        for _ in $(seq 1 15); do
            timeout 60 "$ADB" shell dumpsys package "$PKG" > "$EV/dumpsys-package.txt" || true
            ! grep -q 'stopped=true' "$EV/dumpsys-package.txt" && break
            sleep 2
        done
        ! grep -q 'stopped=true' "$EV/dumpsys-package.txt" || fail "package stayed stopped"
        timeout 30 "$ADB" shell input keyevent KEYCODE_HOME || true
        sleep 5
    else
        log "package is not stopped; instrumentation launch already made it backup-eligible"
    fi

    # --- encryption-off control: fresh dataset, same flow must produce no payload ---
    timeout 60 "$ADB" shell bmgr init || fail "bmgr init (enc-off) failed"
    timeout 60 "$ADB" shell settings put secure backup_local_transport_parameters "$PARAMS_OFF"
    timeout 60 "$ADB" shell bmgr init || log "params re-init skipped"
    timeout 300 "$ADB" shell bmgr backupnow "$PKG" 2>&1 | tee "$EV/backupnow-encoff.log" || true
    TOKEN="$(current_token)"
    [ -n "$TOKEN" ] || fail "no current restore set after enc-off init"
    if timeout 60 "$ADB" shell ls "$TRANSPORT_DIR/$TOKEN/_full/$PKG" 2>/dev/null | grep -q .; then
        fail "unencrypted transport still received an app payload"
    fi
    fact unencryptedDiagnosis "encoff:$(grep -o 'Package .* with result: [A-Za-z ]*' "$EV/backupnow-encoff.log" | tail -1 | tr -d ' ')"
    selector unencryptedTransportExcluded
    log "encryption-off control: $(tail -3 "$EV/backupnow-encoff.log" | tr '\n' ' ')"

    # --- positive leg: fresh dataset so a stale archive cannot satisfy the TAR ---
    timeout 60 "$ADB" shell settings put secure backup_local_transport_parameters "$PARAMS_ON"
    timeout 60 "$ADB" shell bmgr init || log "params re-init skipped"
    timeout 300 "$ADB" shell bmgr backupnow "$PKG" 2>&1 | tee "$EV/backupnow-positive.log" || true
    grep -q "Package $PKG with result: Success" "$EV/backupnow-positive.log" \
        || fail "package backup did not report Success"
    grep -q "Backup finished with result: Success" "$EV/backupnow-positive.log" \
        || fail "overall backup did not report Success"
    TOKEN="$(current_token)"
    [ -n "$TOKEN" ] || fail "no current restore set for the positive leg"
    TAR_DEV="$TRANSPORT_DIR/$TOKEN/_full/$PKG"
    timeout 60 "$ADB" shell ls -l "$TAR_DEV" | tee "$EV/transport-ls.txt"
    timeout 120 "$ADB" exec-out cat "$TAR_DEV" > "$EV/transport.tar"
    [ -s "$EV/transport.tar" ] || fail "transport archive absent or empty at $TAR_DEV"
    fact tarSha256 "$(sha256sum "$EV/transport.tar" | cut -d' ' -f1)"
    python3 scripts/ci/check-first-launch-restore-results.py --bmgr-tar \
        "$EV/transport.tar" "$EV/expectation.json" | tee "$EV/bmgr-tar.txt"
    selector positivePayloadVerified

    # --- real lifecycle: uninstall, verify absence, identical reinstall, wait restore ---
    timeout 60 "$ADB" shell pm uninstall --user 0 "$PKG" | tee "$EV/uninstall.txt"
    [ -z "$(timeout 60 "$ADB" shell pm path "$PKG" | tr -d '\r')" ] || fail "package still installed"
    timeout 60 "$ADB" shell ls "$DATA_DIR" > /dev/null 2>&1 \
        && fail "app data survived pm uninstall" || log "app data gone"
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-restore.txt"
    [ "$(sha256sum "$APK" | cut -d' ' -f1)" = "$(grep '^apkSha256=' "$EV/facts.txt" | cut -d= -f2)" ] \
        || fail "reinstalled APK differs"
    selector apkSignatureStable
    out dumpsys-restore.txt "$ADB" shell dumpsys backup

    log "waiting for install-time restore (<=120s)..."
    restored=""
    for _ in $(seq 1 60); do
        if timeout 30 "$ADB" shell test -f "$DATA_DIR/files/backup/auto-snapshot.zip" \
           -a -f "$DATA_DIR/files/datastore/settings.preferences_pb" 2>/dev/null; then
            restored=1; break
        fi
        sleep 2
    done
    [ -n "$restored" ] || fail "install-time restore did not deliver the payloads in 120s"
    {
        for rel in files/backup/auto-snapshot.zip files/datastore/settings.preferences_pb; do
            printf '%s  %s\n' \
                "$(timeout 30 "$ADB" exec-out cat "$DATA_DIR/$rel" | sha256sum | cut -d' ' -f1)" "$rel"
        done
        timeout 30 "$ADB" shell "find $DATA_DIR -type f 2>/dev/null | sort" || true
    } > "$EV/restored-files.txt"
    grep -c '^[0-9a-f]\\{64\\}  files/' "$EV/restored-files.txt" | grep -q '^2$' \
        || fail "restored-files.txt lacks both payload hashes"
    timeout 30 "$ADB" shell test -f "$DATA_DIR/databases/neutrodyne.db" 2>/dev/null \
        && fail "a database file was restored" || true
    selector restoredFilesMatched

    # Reinstall the test APK only if uninstall of the target took it down (same cert keeps it).
    timeout 60 "$ADB" shell pm path "$RUNNER_PKG" | grep -q . \
        || timeout 300 "$ADB" install -t --user 0 "$TESTAPK" | tee "$EV/install-test-2.txt"
    instrument verify firstLaunchRestoresTransportedSnapshot \
        -e bmgrNonce "$NONCE" -e bmgrExpected "$(cat "$EV/expected.b64")"
    out dumpsys-after.txt "$ADB" shell dumpsys backup
    timeout 60 "$ADB" shell bmgr list sets > "$EV/sets.txt" 2>&1 || true

    # --- empty-dataset control: fresh init, reinstall, nothing may come back ---
    timeout 60 "$ADB" shell bmgr init
    timeout 60 "$ADB" shell pm uninstall --user 0 "$PKG" | tee "$EV/uninstall-empty.txt"
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-empty.txt"
    # A transport restore would create files/ early; poll so a late delivery cannot hide.
    for _ in $(seq 1 15); do
        timeout 30 "$ADB" shell "test -z \"\$(find $DATA_DIR/files -type f 2>/dev/null)\"" \
            || fail "unexpected file arrived on the empty leg"
        sleep 2
    done
    timeout 60 "$ADB" shell pm path "$RUNNER_PKG" | grep -q . \
        || timeout 60 "$ADB" install -t --user 0 "$TESTAPK" | tee "$EV/install-test-3.txt"
    instrument empty firstLaunchWithoutTransportDataStaysEmpty -e bmgrNonce "$NONCE"
    selector emptyLegExcluded
    log "api$API leg complete"
}

main "$@"
