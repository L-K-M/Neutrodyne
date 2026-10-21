#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# bmgr-check.sh <api> <evidence-dir>
#
# The M3 AC6 positive local-transport gate — the black-box release harness
# (owner decision 2026-10-21): one full host-driven cycle on a rooted `default`
# x86_64 emulator of the given API (31 or 36) against the *untouched* published
# release APK — no debuggable target, no -t/-r on it, no test class loaded in
# the target process:
#
#   install -> real launch -> driver `seed` UI import -> SnapshotNowReceiver
#   -> driver `flip` settings-only import -> driver `encrypted` rejection
#   -> host root canaries + payload pull -> expectation record
#   -> encryption-off control -> encryption-on backup -> real TAR verification
#   -> pm uninstall -> identical-APK reinstall -> install-time transport restore
#   -> pre-launch gate (restricted default agent permitted and recorded, never a
#      normal bind) -> force-stop -> normal launch -> adoption/worker observables
#   -> force-stop -> coherent read-only pull -> strict host validation
#   -> empty-transport control leg
#
# The driver (`:tools:bmgrdriver`, self-instrumenting, own package/process) only
# acts and records; the host owns every assertion. The script never repairs the
# positive leg: no `bmgr restore`, no `pm clear`, no file copies into restored
# domains, no manual worker invocation, no sleep-as-proof — every wait is a
# bounded poll for a real observable. On any failure the trap still writes every
# collected artifact. `check-first-launch-restore-results.py --bmgr` is the
# strict aggregate over both legs' evidence.
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
DRIVER_CLASS="ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverTest"
DRIVER_RUNNER="ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverRunner"
DRIVER_PKG=""                     # from the driver APK badging
TRANSPORT="${TRANSPORT:-}"        # the actual transport name, from `bmgr list transports`
DATA_DIR="/data/data/$PKG"
DRIVER_DATA_DIR=""                # /data/data/$DRIVER_PKG, once discovered
TRANSPORT_DIR="/data/data/com.android.localtransport/files"
PARAMS_ON="is_encrypted=true,is_device_transfer=false,fake_encryption_flag=false"
PARAMS_OFF="is_encrypted=false,is_device_transfer=false,fake_encryption_flag=false"
NONCE="run-$(date +%s)-$RANDOM"

# Ownership ledger. A flag arms before its mutating call so even a failed
# mutation is reverted; unarmed flags mean the trap must not touch that state.
ARMED=""
OWNED_TRANSPORT=""; OWNED_ENABLED=""; OWNED_AUTORESTORE=""; OWNED_PARAMS=""
INSTALLED_PKG=""; INSTALLED_DRIVER=""
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
    "schema": "bmgr-evidence/v2",
    "device": facts.get("device", ""),
    "api": int(facts.get("api", "0")),
    "fingerprint": facts.get("fingerprint", ""),
    "abi": facts.get("abi", ""),
    "nonce": facts.get("nonce", ""),
    "sourceSha": facts.get("sourceSha", ""),
    "apkSha256": facts.get("apkSha256", ""),
    "apkBytes": int(facts.get("apkBytes", "0")),
    "driverApkSha256": facts.get("driverApkSha256", ""),
    "driverPackage": facts.get("driverPackage", ""),
    "driverRunner": facts.get("driverRunner", ""),
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
        if [ -n "$INSTALLED_DRIVER" ]; then
            timeout 60 "$ADB" shell pm uninstall --user 0 "$DRIVER_PKG" || true
            [ -z "$(timeout 60 "$ADB" shell pm path "$DRIVER_PKG" 2>/dev/null | tr -d '\r')" ] \
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

tool() {  # build-tools binary: PATH first, then the newest SDK build-tools dir
    command -v "$1" 2>/dev/null && return
    ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/ 2>/dev/null \
        | sort -V | tail -1 | xargs -I{} echo "{}$1"
}

# --- black-box helpers ---------------------------------------------------------

run_driver() {  # <phase> <method>: armed driver call; the raw stream is kept verbatim
    local phase="$1" method="$2"
    local log_file="$EV/instrument-$phase.log"
    log "driver $phase: $DRIVER_CLASS#$method"
    timeout 600 "$ADB" shell am instrument -w -r \
        -e bmgrPhase "$phase" -e bmgrNonce "$NONCE" \
        -e class "$DRIVER_CLASS#$method" \
        "$DRIVER_PKG/$DRIVER_RUNNER" 2>&1 | tee "$log_file" || true
    # Keep failure evidence before marker rejection triggers uninstall cleanup.
    local hierarchy_exit=0
    timeout 30 "$ADB" exec-out cat "$DRIVER_DATA_DIR/files/bmgr/ui-$phase.xml" \
        > "$EV/ui-$phase.xml" 2> "$EV/ui-$phase.err" || hierarchy_exit=$?
    printf '%s\n' "$hierarchy_exit" > "$EV/ui-$phase.exit"
    timeout 30 "$ADB" exec-out cat \
        "$DRIVER_DATA_DIR/files/bmgr/bmgr-status-$phase.json" > "$EV/driver-status-$phase.json" \
        2>/dev/null || fail "driver $phase left no status record"
    [ -s "$EV/driver-status-$phase.json" ] || fail "driver $phase status record empty"
    grep -q "^INSTRUMENTATION_CODE: -1" "$log_file" \
        || fail "driver $phase instrumentation did not complete"
    grep -q "INSTRUMENTATION_STATUS: bmgrDriver=$phase:$NONCE:ok" "$log_file" \
        || fail "driver $phase did not report ok for nonce $NONCE"
}

shadow_query() {  # <sql>: torn sequential copy of the live DB — LIVENESS HINT ONLY
    mkdir -p "$EV/shadow"
    for ext in "" "-wal" "-shm"; do
        timeout 30 "$ADB" exec-out cat "$DATA_DIR/databases/neutrodyne.db$ext" \
            > "$EV/shadow/neutrodyne.db$ext" 2>/dev/null \
            || rm -f "$EV/shadow/neutrodyne.db$ext"
    done
    python3 - "$EV/shadow/neutrodyne.db" "$1" <<'PY'
import sqlite3, sys
try:
    con = sqlite3.connect(sys.argv[1])
    print(con.execute(sys.argv[2]).fetchall())
except Exception as e:
    print(f"SHADOW-QUERY-ERROR {e}")
PY
}

wait_import_terminal() {  # <expected DONE count> <seconds>: bounded poll, liveness only
    local want="$1" budget="$2" seen="" answer
    for _ in $(seq 1 "$budget"); do
        answer="$(shadow_query "SELECT CASE WHEN (SELECT COUNT(*) FROM import_session \
            WHERE state='CANCELLED')>0 THEN 'CANCELLED' WHEN (SELECT COUNT(*) \
            FROM import_session WHERE state='DONE' AND finishedAt IS NOT NULL)>=$want \
            THEN 'DONE' ELSE 'WAIT' END")"
        case "$answer" in
            *DONE*) seen=1; break ;;
            *CANCELLED*) fail "an import session cancelled while waiting ($want DONE)" ;;
        esac
        sleep 2
    done
    [ -n "$seen" ] || fail "no terminal import session inside ${budget}s (wanted $want DONE)"
}

pull_tree() {  # <remote dir> <local dir>: recursive read-only pull (root exec-out)
    local remote="$1" local_dir="$2"
    mkdir -p "$local_dir"
    timeout 60 "$ADB" shell "find '$remote' -mindepth 1 2>/dev/null | sort" \
        | tr -d '\r' > "$local_dir/.listing" || true
    while IFS= read -r dev_path; do
        [ -n "$dev_path" ] || continue
        local rel="${dev_path#$remote/}"
        if timeout 30 "$ADB" shell test -d "$dev_path" 2>/dev/null; then
            mkdir -p "$local_dir/$rel"
        elif timeout 30 "$ADB" shell test -L "$dev_path" 2>/dev/null; then
            printf 'SYMLINK -> %s\n' "$(timeout 30 "$ADB" shell readlink "$dev_path" | tr -d '\r')" \
                > "$local_dir/$rel.symlink"
        else
            mkdir -p "$local_dir/$(dirname "$rel")"
            timeout 60 "$ADB" exec-out cat "$dev_path" > "$local_dir/$rel" 2>/dev/null \
                || log "pull miss: $dev_path"
        fi
    done < "$local_dir/.listing"
}

snapshot_now() {  # the DUMP-protected production receiver; bounded pending retries
    local line data
    for _ in $(seq 1 5); do
        timeout 90 "$ADB" shell am broadcast --include-stopped-packages \
            -n "$PKG/ch.lkmc.neutrodyne.SnapshotNowReceiver" > "$EV/snapshot.txt" 2>&1 || true
        data="$(grep -o 'data="[^"]*"' "$EV/snapshot.txt" | tail -1 | sed 's/^data="//; s/"$//')"
        case "$data" in
            ok\ *) line="$data"; break ;;
            pending) sleep 5 ;;
            *) fail "SnapshotNowReceiver answered '$data'" ;;
        esac
    done
    [ -n "${line:-}" ] || fail "SnapshotNowReceiver never answered 'ok'"
    printf '%s\n' "$line"
}

write_canaries() {  # root writes into excluded domains only — never DB/PB/snapshot
    local canaries=()
    local blob="canary-$NONCE"
    for rel in \
        "databases/bmgr-canary-$NONCE.db" \
        "files/downloads/bmgr-canary-$NONCE.dat" \
        "files/artwork/bmgr-canary-$NONCE.dat" \
        "files/other/bmgr-canary-$NONCE.dat" \
        "cache/bmgr-canary-$NONCE.dat" \
        "no_backup/bmgr-canary-$NONCE.dat"; do
        timeout 60 "$ADB" shell \
            "mkdir -p '$DATA_DIR/$(dirname "$rel")' && printf '%s' '$blob-$rel' > '$DATA_DIR/$rel'" \
            || fail "canary write failed: $rel"
        canaries+=("$(printf '%s' "$blob-$rel" | sha256sum | cut -d' ' -f1)|$rel")
    done
    timeout 60 "$ADB" shell \
        "mkdir -p '/sdcard/Android/data/$PKG/files' && printf '%s' '$blob-external' \
         > '/sdcard/Android/data/$PKG/files/bmgr-canary-$NONCE.dat'" \
        || fail "external canary write failed"
    canaries+=("$(printf '%s' "$blob-external" | sha256sum | cut -d' ' -f1)|external/files/bmgr-canary-$NONCE.dat")
    printf '%s\n' "${canaries[@]}"
}

pb_key() {  # <local pb> <key>: value or empty — the checker's own wire parser
    python3 scripts/ci/check-first-launch-restore-results.py \
        --bmgr-pb "$1" "$2" 2>/dev/null || true
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

    # A disposable emulator carries neither our app nor the driver already.
    [ -z "$(timeout 60 "$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r')" ] \
        || fail "refusing a device with a preexisting $PKG install"
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

    # --- APK gates: published-artifact checks own debuggable/signer facts ---
    require_disk
    APK="$(ls app/build/outputs/apk/release/app-x86_64-release.apk 2>/dev/null || true)"
    [ -n "$APK" ] || APK="$(find app/build/outputs/apk/release -name 'app-*-release.apk' | head -1)"
    DRIVER_APK="${BMGR_DRIVER_APK:-}"
    [ -n "$DRIVER_APK" ] || DRIVER_APK="$(find tools/bmgrdriver/build/outputs -name '*.apk' | head -1)"
    [ -f "$APK" ] || fail "release APK missing — build before boot"
    [ -f "$DRIVER_APK" ] || fail "driver APK missing — build :tools:bmgrdriver deviceTest"
    scripts/ci/check-apk.sh --published "$APK" | tee "$EV/check-apk.txt"
    timeout 60 "$(tool aapt2)" dump badging "$APK" > "$EV/apk-badging.txt" 2>&1 || true
    ! grep -q "application-debuggable" "$EV/apk-badging.txt" || fail "release APK is debuggable"
    selector apkNonDebuggable
    fact apkSha256 "$(sha256sum "$APK" | cut -d' ' -f1)"
    fact apkBytes "$(stat -c%s "$APK")"
    fact driverApkSha256 "$(sha256sum "$DRIVER_APK" | cut -d' ' -f1)"
    fact certSha256 "$(timeout 120 "$(tool apksigner)" verify --print-certs "$APK" | grep -o 'SHA-256 digest: .*' | head -1 | cut -d' ' -f3-)"

    # --- driver identity: self-instrumenting package discovered from its badging ---
    timeout 60 "$(tool aapt2)" dump badging "$DRIVER_APK" > "$EV/driver-badging.txt" 2>&1 \
        || fail "driver APK badging unreadable"
    DRIVER_PKG="$(grep -o "package: name='[^']*'" "$EV/driver-badging.txt" | head -1 | cut -d"'" -f2)"
    [ -n "$DRIVER_PKG" ] || fail "driver package unknown: $(head -3 "$EV/driver-badging.txt")"
    [ "$DRIVER_PKG" != "$PKG" ] || fail "driver APK shares the target package"
    DRIVER_DATA_DIR="/data/data/$DRIVER_PKG"
    fact driverPackage "$DRIVER_PKG"
    fact driverRunner "$DRIVER_RUNNER"
    INSTALLED_PKG=1
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-initial.txt"
    INSTALLED_DRIVER=1
    timeout 300 "$ADB" install -t --user 0 "$DRIVER_APK" | tee "$EV/install-driver.txt"
    timeout 60 "$ADB" shell pm list instrumentation > "$EV/instrumentations.txt" 2>&1 || true
    grep -q "instrumentation:$DRIVER_PKG/$DRIVER_RUNNER (target=$DRIVER_PKG)" \
        "$EV/instrumentations.txt" \
        || fail "driver instrumentation not self-targeting: $(tr '\n' ' ' < "$EV/instrumentations.txt")"
    ! grep -q "(target=$PKG)" "$EV/instrumentations.txt" \
        || fail "an instrumentation targets $PKG — the release build stays instrument-free"
    selector driverInstrumentationAvailable
    log "driver component: $DRIVER_PKG/$DRIVER_RUNNER"

    # Notifications are how the app reports import completion; grant so dumpsys shows them.
    if [ "$SDK" -ge 33 ]; then
        timeout 60 "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    fi

    # --- real first launch: makes the package backup-eligible and opens the graph ---
    timeout 60 "$ADB" shell am start -n "$PKG/.MainActivity" | tee "$EV/launch.txt"
    for _ in $(seq 1 30); do
        timeout 30 "$ADB" shell test -f "$DATA_DIR/databases/neutrodyne.db" 2>/dev/null && break
        sleep 2
    done
    timeout 30 "$ADB" shell test -f "$DATA_DIR/databases/neutrodyne.db" \
        || fail "the first launch never opened the database"

    # --- driver seed import through the production ExternalImportActivity ---
    run_driver seed seedImport
    timeout 30 "$ADB" exec-out cat "$DRIVER_DATA_DIR/files/bmgr/seed.expected.json" \
        > "$EV/seed.expected.json" || fail "driver wrote no seed.expected.json"
    [ -s "$EV/seed.expected.json" ] || fail "seed.expected.json empty"
    grep -q '"nonce": *"'"$NONCE"'"' "$EV/seed.expected.json" || fail "sidecar nonce mismatch"
    wait_import_terminal 1 90

    # --- production snapshot trigger (shell-only DUMP-protected receiver) ---
    RECEIVER_LINE="$(snapshot_now)"
    log "snapshot receiver: $RECEIVER_LINE"

    # --- driver settings-only flip + encrypted rejection, same production path ---
    run_driver flip settingsFlip
    wait_import_terminal 2 90
    run_driver encrypted encryptedImportRejected
    timeout 60 "$ADB" shell dumpsys notification > "$EV/notifications.txt" 2>&1 || true

    # --- host-owned prebackup capture: canaries, payload pair, device PB, expectation ---
    write_canaries > "$EV/canaries.txt"
    PULL_PRE="$EV/pulls/pre"
    mkdir -p "$PULL_PRE"
    for rel in \
        files/backup/auto-snapshot.zip \
        files/datastore/settings.preferences_pb \
        files/datastore/device_settings.preferences_pb; do
        mkdir -p "$PULL_PRE/$(dirname "$rel")"
        timeout 60 "$ADB" exec-out cat "$DATA_DIR/$rel" > "$PULL_PRE/$rel" \
            || fail "prebackup pull miss: $rel"
        [ -s "$PULL_PRE/$rel" ] || fail "prebackup pull empty: $rel"
    done
    timeout 60 "$ADB" shell "find '$DATA_DIR' -type f | sort" \
        | tr -d '\r' > "$PULL_PRE/prebackup-files.txt" || true
    python3 - "$EV" "$PULL_PRE" <<'PY' || fail "expectation assembly failed"
import json, sys
from pathlib import Path
ev, pre = Path(sys.argv[1]), Path(sys.argv[2])
sidecar = json.loads((ev / "seed.expected.json").read_text())
facts = {}
for line in (ev / "facts.txt").read_text().splitlines():
    k, _, v = line.partition("=")
    facts[k] = v
exp = {
    "v": 2,
    "nonce": facts["nonce"],
    "package": "ch.lkmc.neutrodyne",
    "api": int(facts["api"]),
    "installationId": None,  # filled by the caller from the decoded device PB
    "snapshotSha256": "", "snapshotBytes": 0,
    "portableSha256": "", "portableBytes": 0,
    "portableKey": "backup.opml_layout",
    "portableInSnapshot": "GROUPED",
    "portableAfter": "FLAT",
    "seedSha256": sidecar["seedSha256"],
    "seedBytes": sidecar["seedBytes"],
    "syncIds": {p["key"]: p["syncId"] for p in sidecar["fixture"]["podcasts"]},
    "groupUuids": {g["name"]: g["uuid"] for g in sidecar["fixture"]["groups"]},
    "fixture": sidecar["fixture"],
    "canaries": [line.rsplit("|", 1)[1] for line in (ev / "canaries.txt").read_text().splitlines()],
    "receiverLine": None,  # filled by the caller
    "preparedAt": int(__import__("time").time() * 1000),
}
(ev / "expectation-part.json").write_text(json.dumps(exp) + "\n")
PY
    INSTALL_ID="$(pb_key "$PULL_PRE/files/datastore/device_settings.preferences_pb" backup.installation_id)"
    [ -n "$INSTALL_ID" ] || fail "device PB lacks backup.installation_id"
    SNAP_SHA="$(sha256sum "$PULL_PRE/files/backup/auto-snapshot.zip" | cut -d' ' -f1)"
    SNAP_BYTES="$(stat -c%s "$PULL_PRE/files/backup/auto-snapshot.zip")"
    PORT_SHA="$(sha256sum "$PULL_PRE/files/datastore/settings.preferences_pb" | cut -d' ' -f1)"
    PORT_BYTES="$(stat -c%s "$PULL_PRE/files/datastore/settings.preferences_pb")"
    python3 - "$EV" "$INSTALL_ID" "$SNAP_SHA" "$SNAP_BYTES" "$PORT_SHA" "$PORT_BYTES" \
        "$RECEIVER_LINE" <<'PY' || fail "expectation completion failed"
import json, sys
from pathlib import Path
ev = Path(sys.argv[1])
exp = json.loads((ev / "expectation-part.json").read_text())
exp["installationId"] = sys.argv[2]
exp["snapshotSha256"], exp["snapshotBytes"] = sys.argv[3], int(sys.argv[4])
exp["portableSha256"], exp["portableBytes"] = sys.argv[5], int(sys.argv[6])
exp["receiverLine"] = sys.argv[7]
(ev / "expectation.json").write_text(json.dumps(exp, indent=2) + "\n")
PY
    grep -q '"nonce": "'"$NONCE"'"' "$EV/expectation.json" || fail "expectation nonce mismatch"
    selector expectationCaptured

    # --- encryption-off control: fresh dataset, same flow must produce no payload ---
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

    # --- source-side ledger pull: the manual seed/flip import sessions live only
    #     in the pre-uninstall DB; the reinstalled app starts with an empty ledger ---
    pull_tree "$DATA_DIR/databases" "$EV/pulls/source/databases"

    # --- real lifecycle: uninstall, verify absence, identical reinstall, restore ---
    timeout 60 "$ADB" shell pm uninstall --user 0 "$PKG" | tee "$EV/uninstall.txt"
    [ -z "$(timeout 60 "$ADB" shell pm path "$PKG" | tr -d '\r')" ] || fail "package still installed"
    timeout 60 "$ADB" shell ls "$DATA_DIR" > /dev/null 2>&1 \
        && fail "app data survived pm uninstall" || log "app data gone"
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-restore.txt"
    [ "$(sha256sum "$APK" | cut -d' ' -f1)" = "$(grep '^apkSha256=' "$EV/facts.txt" | cut -d= -f2)" ] \
        || fail "reinstalled APK differs"
    selector apkSignatureStable
    out dumpsys-restore.txt "$ADB" shell dumpsys backup

    # --- install-time restore: bounded poll for the delivered payload pair ---
    log "waiting for install-time restore (<=180s)..."
    restored=""
    for _ in $(seq 1 90); do
        if timeout 30 "$ADB" shell test -f "$DATA_DIR/files/backup/auto-snapshot.zip" \
           -a -f "$DATA_DIR/files/datastore/settings.preferences_pb" 2>/dev/null; then
            restored=1; break
        fi
        sleep 2
    done
    [ -n "$restored" ] || fail "install-time restore did not deliver the payloads in 180s"

    # --- pre-launch gate: the restricted default-agent window is recorded, and the
    #     only tolerated process evidence; no normal bind may precede our launch ---
    timeout 60 "$ADB" shell "find '$DATA_DIR' -mindepth 1 | sort" \
        | tr -d '\r' > "$EV/prelaunch-files.txt" || true
    timeout 30 "$ADB" shell "ps -A 2>/dev/null | grep '$PKG'" \
        | tr -d '\r' > "$EV/prelaunch-ps.txt" || true
    if [ -s "$EV/prelaunch-ps.txt" ]; then
        fact prelaunchAgentObserved true
        log "restricted restore-agent process recorded: $(tr '\n' ' ' < "$EV/prelaunch-ps.txt")"
    else
        fact prelaunchAgentObserved false
    fi
    grep -E "$DATA_DIR/databases" "$EV/prelaunch-files.txt" \
        && fail "a databases/ entry was delivered before the first launch" || true
    timeout 60 "$ADB" shell "tail -200 '$EV/logcat.txt'" 2>/dev/null > /dev/null 2>&1 || true
    cp "$EV/logcat.txt" "$EV/prelaunch-logcat.txt" 2>/dev/null || true
    timeout 60 "$ADB" shell am force-stop "$PKG"

    # --- controlled normal cold launch -> adoption + restore worker observables ---
    timeout 60 "$ADB" shell am start -n "$PKG/.MainActivity" | tee "$EV/launch-restored.txt"
    log "waiting for the adopted restore to finish (liveness poll, <=240s)..."
    restored_done=""
    for _ in $(seq 1 120); do
        answer="$(shadow_query "SELECT CASE WHEN (SELECT COUNT(*) FROM import_session \
            WHERE optionsJson LIKE '%\"fromAndroidBackup\":true%' AND state='CANCELLED')>0 \
            THEN 'CANCELLED' WHEN (SELECT COUNT(*) FROM import_session \
            WHERE optionsJson LIKE '%\"fromAndroidBackup\":true%' AND state='DONE' \
            AND finishedAt IS NOT NULL)>0 THEN 'DONE' ELSE 'WAIT' END")"
        case "$answer" in
            *DONE*) restored_done=1; break ;;
            *CANCELLED*) fail "the adopted restore session cancelled" ;;
        esac
        sleep 2
    done
    [ -n "$restored_done" ] || fail "the adopted restore never reached DONE in 240s"
    selector restoreAdoptionObserved

    # --- terminal capture: force-stop, then a coherent read-only pull ---
    timeout 60 "$ADB" shell am force-stop "$PKG"
    sleep 3   # quiesce journal writers; acceptance is the pull, not this pause
    PULL_POST="$EV/pulls/post"
    mkdir -p "$PULL_POST"
    timeout 60 "$ADB" shell "find '$DATA_DIR' -type f | sort" \
        | tr -d '\r' > "$EV/postlaunch-files.txt" || true
    pull_tree "$DATA_DIR/files" "$PULL_POST/files"
    pull_tree "$DATA_DIR/databases" "$PULL_POST/databases"
    pull_tree "$DATA_DIR/shared_prefs" "$PULL_POST/shared_prefs"
    pull_tree "$DATA_DIR/cache" "$PULL_POST/cache"
    pull_tree "$DATA_DIR/no_backup" "$PULL_POST/no_backup"
    timeout 60 "$ADB" shell dumpsys notification > "$PULL_POST/notifications.txt" 2>&1 || true
    python3 scripts/ci/check-first-launch-restore-results.py --bmgr-pull \
        "$EV" | tee "$EV/bmgr-pull.txt" || fail "the final quiescent capture failed validation"
    selector restoredLibraryVerified
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
    timeout 60 "$ADB" shell am start -n "$PKG/.MainActivity" | tee "$EV/launch-empty.txt"
    for _ in $(seq 1 30); do
        timeout 30 "$ADB" shell test -f "$DATA_DIR/databases/neutrodyne.db" 2>/dev/null && break
        sleep 2
    done
    timeout 30 "$ADB" shell test -f "$DATA_DIR/databases/neutrodyne.db" \
        || fail "the empty-leg launch never opened the database"
    timeout 60 "$ADB" shell am force-stop "$PKG"
    sleep 3
    PULL_EMPTY="$EV/pulls/empty"
    mkdir -p "$PULL_EMPTY"
    pull_tree "$DATA_DIR/files" "$PULL_EMPTY/files"
    pull_tree "$DATA_DIR/databases" "$PULL_EMPTY/databases"
    selector emptyLegExcluded
    log "api$API leg complete"
}

if [ "${BMGR_CHECK_LIB_ONLY:-}" != "1" ]; then
    main "$@"
fi
