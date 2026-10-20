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
#
# Safety: the script only mutates the named disposable emulator the CI job
# created (BMGR_DISPOSABLE_API<api>=1 + BMGR_EXPECT_AVD under GITHUB_ACTIONS),
# and the trap reverts only the changes this invocation owns.

set -euo pipefail

ADB="${ADB:-adb}"
API="${API:-}"                    # 31 or 36 only, set inside main
EV="${EV:-}"                      # evidence dir, set inside main
REPO_ROOT=""
PKG="ch.lkmc.neutrodyne"
CLASS="ch.lkmc.neutrodyne.BmgrTransportDeviceTest"
RUNNER_PKG=""                     # filled from `pm list instrumentation`
TRANSPORT="${TRANSPORT:-}"        # the actual transport name, from `bmgr list transports`
DATA_DIR="/data/data/$PKG"
TRANSPORT_DIR="/data/data/com.android.localtransport/files"
PARAMS_ON="is_encrypted=true,is_device_transfer=false,fake_encryption_flag=false"
PARAMS_OFF="is_encrypted=false,is_device_transfer=false,fake_encryption_flag=false"
NONCE="run-$(date +%s)-$RANDOM"

# Ownership ledger. A flag arms before its mutating call so even a failed
# mutation is reverted; unarmed flags mean the trap must not touch that state.
ARMED=""
OWNED_TRANSPORT=""; OWNED_ENABLED=""; OWNED_AUTORESTORE=""; OWNED_PARAMS=""
INSTALLED_PKG=""; INSTALLED_RUNNER=""
ORIG_TRANSPORT=""; ORIG_ENABLED=""; ORIG_AUTORESTORE=""; ORIG_PARAMS="null"
ORIG_ENABLED_BOOL=""; ORIG_AUTORESTORE_BOOL=""
LOGCAT_PID=""

log() { echo "[bmgr-check $(date +%H:%M:%S)] $*"; }
fail() { echo "[bmgr-check] FAIL: $*" >&2; exit 1; }
fact() { echo "$1=$2" >> "$EV/facts.txt"; }
selector() { echo "$1=pass" >> "$EV/selectors.txt"; fact "selector.$1" pass; }
out() {  # bounded device capture
    local file="$1"; shift
    timeout 120 "$@" > "$EV/$file" 2>&1 || log "capture $file failed ($?)"
}

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
    "avd": facts.get("avd", ""),
    "tarSha256": facts.get("tarSha256", ""),
    "tarDevicePath": facts.get("tarDevicePath", ""),
    "unencryptedDiagnosis": facts.get("unencryptedDiagnosis", ""),
    "unencryptedBackupnowExit": int(facts.get("unencryptedBackupnowExit", "-1")),
    "positiveBackupnowExit": int(facts.get("positiveBackupnowExit", "-1")),
    "cleanupStatus": facts.get("cleanupStatus", ""),
    "startedAt": facts.get("startedAt", ""),
    "finishedAt": facts.get("finishedAt", ""),
    "selectors": selectors,
}
(ev / "run.json").write_text(json.dumps(run, indent=2) + "\n")
PY
}
restore_secure() {  # <key> <raw>: put the captured value back, or re-delete an absent one
    if [ "$2" = "null" ]; then
        timeout 60 "$ADB" shell settings delete secure "$1"
    else
        timeout 60 "$ADB" shell settings put secure "$1" "$2"
    fi
}

cleanup() {
    status=$?
    [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null || true
    # Diagnostics are read-only and run even when nothing was ever owned.
    timeout 60 "$ADB" shell dumpsys backup > "$EV/dumpsys-final.txt" 2>&1 || true
    timeout 60 "$ADB" shell bmgr list transports > "$EV/transports-final.txt" 2>&1 || true
    failed=0
    if [ -n "$ARMED" ]; then
        if [ -n "$OWNED_PARAMS" ]; then
            restore_secure backup_local_transport_parameters "$ORIG_PARAMS" || failed=1
        fi
        if [ -n "$OWNED_TRANSPORT" ]; then
            if [ -n "$ORIG_TRANSPORT" ]; then
                timeout 60 "$ADB" shell bmgr transport "$ORIG_TRANSPORT" || failed=1
            else
                log "no originally selected transport recorded — cannot restore it"
                failed=1
            fi
        fi
        if [ -n "$OWNED_AUTORESTORE" ]; then
            timeout 60 "$ADB" shell bmgr autorestore "$ORIG_AUTORESTORE_BOOL" || failed=1
            restore_secure backup_auto_restore "$ORIG_AUTORESTORE" || failed=1
        fi
        if [ -n "$OWNED_ENABLED" ]; then
            # BackupManager caches these flags; backup_enabled is file-persisted on API31.
            # Restoring only secure settings does not undo the bmgr calls above.
            timeout 60 "$ADB" shell bmgr enable "$ORIG_ENABLED_BOOL" || failed=1
            restore_secure backup_enabled "$ORIG_ENABLED" || failed=1
        fi
        if [ -n "$OWNED_ENABLED" ]; then
            timeout 60 "$ADB" shell bmgr enabled > "$EV/enabled-restored.txt" 2>&1 || failed=1
            [ "$(backup_flag "$EV/enabled-restored.txt" 'Backup Manager currently')" = "$ORIG_ENABLED_BOOL" ] \
                || failed=1
        fi
        if [ -n "$OWNED_AUTORESTORE" ]; then
            timeout 60 "$ADB" shell dumpsys backup > "$EV/dumpsys-restored.txt" 2>&1 || failed=1
            [ "$(backup_flag "$EV/dumpsys-restored.txt" 'Auto-restore is')" = "$ORIG_AUTORESTORE_BOOL" ] \
                || failed=1
        fi
        if [ -n "$INSTALLED_PKG" ]; then
            timeout 60 "$ADB" shell pm uninstall --user 0 "$PKG" || true
            [ -z "$(timeout 60 "$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r')" ] \
                || failed=1
        fi
        if [ -n "$INSTALLED_RUNNER" ]; then
            timeout 60 "$ADB" shell pm uninstall --user 0 "$RUNNER_PKG" || true
            [ -z "$(timeout 60 "$ADB" shell pm path "$RUNNER_PKG" 2>/dev/null | tr -d '\r')" ] \
                || failed=1
        fi
    fi
    if [ "$failed" -eq 0 ]; then fact cleanupStatus ok; else fact cleanupStatus failed; fi
    fact finishedAt "$(date -u +%FT%TZ)"
    finish_run_json
    log "evidence left in $EV (exit $status, cleanup failures $failed)"
    [ "$failed" -eq 0 ] || exit 1
    exit "$status"
}

require_disk() {  # >= 10 GiB before every heavy step
    local free
    free=$(df -k "$REPO_ROOT" | awk 'NR==2 {print $4}')
    [ "${free:-0}" -ge 10485760 ] || fail "disk headroom under 10 GiB ($free KiB)"
}

# `bmgr list transports` pads the selected line "  * name", others "    name".
selected_transport() { sed -n 's/^ *\* //p' "$1" | tr -d '\r' | head -1; }

raw_setting() {  # <secure key>: the raw 0/1/value or literal null — never a bmgr getter
    timeout 60 "$ADB" shell settings get secure "$1" 2>/dev/null | tr -d '\r'
}

backup_flag() {  # <receipt> <prefix>: require exactly one effective service-state line
    local value
    value="$(tr -d '\r' < "$1" | grep -E "^$2 (enabled|disabled)$" || true)"
    case "$value" in
        "$2 enabled") printf true ;;
        "$2 disabled") printf false ;;
        *) return 1 ;;
    esac
}

bmgr_init() {  # <label>: init only our transport and require its real result
    local label="$1" file="$EV/init-$1.txt" rc=0
    timeout 60 "$ADB" shell bmgr init "$TRANSPORT" > "$file" 2>&1 || rc=$?
    [ "$rc" -eq 0 ] \
        || fail "bmgr init $TRANSPORT ($label) failed rc=$rc: $(tr -d '\r' < "$file" | tr '\n' ' ')"
    tr -d '\r' < "$file" | grep -q '^Initialization result: 0$' \
        || fail "bmgr init $TRANSPORT ($label) gave no success: $(tr -d '\r' < "$file" | tr '\n' ' ')"
}

find_payload() {  # every _full/$PKG file under the transport store, whatever the token dir
    timeout 60 "$ADB" shell \
        "find '$TRANSPORT_DIR' -type f -path '*/_full/$PKG' 2>/dev/null" 2>/dev/null \
        | tr -d '\r' || true
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
    API="${1:?usage: bmgr-check.sh <api> <evidence-dir>}"
    EV="$(cd "${2:?usage: bmgr-check.sh <api> <evidence-dir>}" >/dev/null 2>&1 && pwd \
        || { mkdir -p "$2"; cd "$2" && pwd; })"
    REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd -P)"
    cd "$REPO_ROOT"
    mkdir -p "$EV"
    exec > >(tee -a "$EV/script.log") 2>&1
    trap cleanup EXIT

    require_disk
    fact device "api$API"; fact api "$API"; fact nonce "$NONCE"
    fact startedAt "$(date -u +%FT%TZ)"
    fact sourceSha "$(git rev-parse HEAD)"

    # --- disposable-job guard: no device contact outside the CI matrix ---
    [ "$API" = "31" ] || [ "$API" = "36" ] || fail "api must be 31 or 36, got '$API'"
    [ "${GITHUB_ACTIONS:-}" = "true" ] \
        || fail "bmgr-check.sh only runs inside a GitHub Actions job"
    DISPOSABLE="BMGR_DISPOSABLE_API$API"
    [ "${!DISPOSABLE:-}" = "1" ] \
        || fail "set $DISPOSABLE=1 to acknowledge a disposable emulator"
    [ -n "${BMGR_EXPECT_AVD:-}" ] \
        || fail "BMGR_EXPECT_AVD must name the workflow-created AVD"

    # --- device: the named disposable qemu emulator the job booted ---
    timeout 660 "$ADB" wait-for-device
    for _ in $(seq 1 60); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 10
    done
    AVD="$(timeout 60 "$ADB" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
    [ "$AVD" = "$BMGR_EXPECT_AVD" ] \
        || fail "connected AVD '$AVD' is not the expected '$BMGR_EXPECT_AVD'"
    fact avd "$AVD"
    [ "$(timeout 60 "$ADB" shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] \
        || fail "the device is not a qemu emulator"
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

    # --- original backup state: raw secure values captured before any change ---
    out transports-before.txt "$ADB" shell bmgr list transports
    TRANSPORT="$(grep -o 'com\.android\.localtransport/\.LocalTransport' "$EV/transports-before.txt" | head -1)"
    [ -n "$TRANSPORT" ] || fail "local transport not in: $(cat "$EV/transports-before.txt")"
    ORIG_TRANSPORT="$(selected_transport "$EV/transports-before.txt")"
    [ -n "$ORIG_TRANSPORT" ] || fail "the original selected transport is unknown"
    out enabled-before.txt "$ADB" shell bmgr enabled
    ORIG_ENABLED_BOOL="$(backup_flag "$EV/enabled-before.txt" 'Backup Manager currently')" \
        || fail "the original effective backup state is unknown"
    ORIG_AUTORESTORE_BOOL="$(backup_flag "$EV/dumpsys-before.txt" 'Auto-restore is')" \
        || fail "the original effective auto-restore state is unknown"
    ORIG_ENABLED="$(raw_setting backup_enabled)" || fail "cannot read original backup_enabled"
    ORIG_AUTORESTORE="$(raw_setting backup_auto_restore)" || fail "cannot read original backup_auto_restore"
    ORIG_PARAMS="$(raw_setting backup_local_transport_parameters)" || fail "cannot read original transport parameters"
    case "$ORIG_ENABLED" in 0|1|null) ;; *) fail "invalid original backup_enabled value" ;; esac
    case "$ORIG_AUTORESTORE" in 0|1|null) ;; *) fail "invalid original backup_auto_restore value" ;; esac
    {
        printf 'transport=%s\n' "$ORIG_TRANSPORT"
        printf 'backup_enabled=%s\n' "$ORIG_ENABLED"
        printf 'backup_auto_restore=%s\n' "$ORIG_AUTORESTORE"
        printf 'backup_enabled_effective=%s\n' "$ORIG_ENABLED_BOOL"
        printf 'backup_auto_restore_effective=%s\n' "$ORIG_AUTORESTORE_BOOL"
        printf 'backup_local_transport_parameters=%s\n' "$ORIG_PARAMS"
    } > "$EV/originals.txt"
    log "originals: transport=$ORIG_TRANSPORT enabled=$ORIG_ENABLED autorestore=$ORIG_AUTORESTORE params=$ORIG_PARAMS"

    # A disposable emulator carries neither our app nor its test package already.
    [ -z "$(timeout 60 "$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r')" ] \
        || fail "refusing a device with a preexisting $PKG install"
    ! timeout 60 "$ADB" shell pm list instrumentation | grep -qF "(target=$PKG)" \
        || fail "refusing a device with a preexisting test install"
    ARMED=1

    OWNED_ENABLED=1
    timeout 60 "$ADB" shell bmgr enable true
    OWNED_AUTORESTORE=1
    timeout 60 "$ADB" shell bmgr autorestore true
    OWNED_TRANSPORT=1
    timeout 60 "$ADB" shell bmgr transport "$TRANSPORT"
    timeout 60 "$ADB" shell bmgr list transports > "$EV/transports-selected.txt" 2>&1 || true
    [ "$(selected_transport "$EV/transports-selected.txt")" = "$TRANSPORT" ] \
        || fail "local transport not selected: $(tr '\n' ' ' < "$EV/transports-selected.txt")"
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
    INSTALLED_PKG=1
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-initial.txt"
    INSTALLED_RUNNER=1
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
    # `bmgr init` needs the transport named (bare init prints usage and returns);
    # `--monitor` is what emits the ERROR_PREFLIGHT evidence the parser binds.
    bmgr_init encoff-pre
    OWNED_PARAMS=1
    timeout 60 "$ADB" shell settings put secure backup_local_transport_parameters "$PARAMS_OFF"
    bmgr_init encoff
    rc=0
    timeout 300 "$ADB" shell bmgr backupnow --monitor "$PKG" \
        2>&1 | tee "$EV/backupnow-encoff.log" || rc=$?
    fact unencryptedBackupnowExit "$rc"
    VERDICT="$(python3 scripts/ci/check-first-launch-restore-results.py --bmgr-encoff \
        "$EV/backupnow-encoff.log" "$PKG")" \
        || fail "enc-off backupnow was not a diagnosed policy exclusion: $(tail -3 "$EV/backupnow-encoff.log" | tr '\n' ' ')"
    fact unencryptedDiagnosis "encoff:$VERDICT"
    find_payload > "$EV/encoff-payload.txt"
    ! grep -q . "$EV/encoff-payload.txt" \
        || fail "unencrypted transport still received an app payload: $(tr '\n' ' ' < "$EV/encoff-payload.txt")"
    selector unencryptedTransportExcluded
    log "encryption-off control: $VERDICT"

    # --- positive leg: fresh dataset so a stale archive cannot satisfy the TAR ---
    timeout 60 "$ADB" shell settings put secure backup_local_transport_parameters "$PARAMS_ON"
    bmgr_init positive
    rc=0
    timeout 300 "$ADB" shell bmgr backupnow "$PKG" \
        2>&1 | tee "$EV/backupnow-positive.log" || rc=$?
    fact positiveBackupnowExit "$rc"
    grep -q "Package $PKG with result: Success" "$EV/backupnow-positive.log" \
        || fail "package backup did not report Success"
    grep -q "Backup finished with result: Success" "$EV/backupnow-positive.log" \
        || fail "overall backup did not report Success"
    out sets-positive.txt "$ADB" shell bmgr list sets
    find_payload > "$EV/transport-find.txt"
    [ "$(wc -l < "$EV/transport-find.txt")" -eq 1 ] \
        || fail "expected exactly one _full/$PKG payload, got: $(tr '\n' ' ' < "$EV/transport-find.txt")"
    TAR_DEV="$(head -1 "$EV/transport-find.txt")"
    fact tarDevicePath "$TAR_DEV"
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
    bmgr_init empty
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

if [ "${BMGR_CHECK_LIB_ONLY:-}" != "1" ]; then
    main "$@"
fi
