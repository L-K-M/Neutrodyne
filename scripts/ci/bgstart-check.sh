#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# bgstart-check.sh <31|36> <evidence-dir>: M4 P6b external background-start gate.
# A self-instrumented driver first restores a durable paused session through the
# published release app's own import UI (phase bgseed). The host then kills the
# target process (a stopped package would be invisible to the actor's
# resolveService) and the driver's bgstart leg — on an UNINSTRUMENTED release
# APK —
# BINDS the playback service from the standalone `:tools:bgactor` APK (a separate
# demoted uid — the external equivalent of MediaController.connect()), proves the
# uid sits at or below SERVICE non-foreground for a continuous >=15s settle, taps
# the SystemUI media card's Play button (a trusted STATUS_BAR_SERVICE controller),
# observes the genuine ForegroundServiceStartNotAllowedException surfaces (service stays
# non-foreground, DENIED verdicts on every allow record, no temp allowlist, no
# BFSL self record, uid at or below SERVICE, recovery notification 4001), then
# taps the real notification "Resume" action and proves playback resumed
# (foreground 4000, APC audio for the target uid, position advance >200 ms).
# Every wait is bounded and every saved dump is re-verified by
# check-first-launch-restore-results.py --bgstart for both images.
#
# Require the workflow's named rooted disposable x86_64 AVD under GitHub Actions;
# cleanup removes only the packages this run installed. Device runs exist only in
# CI; never run against a provisioned device.

set -euo pipefail

ADB="${ADB:-adb}"
API=""                            # 31 or 36 only, set inside main
EV=""                             # evidence dir, set inside main
REPO_ROOT=""
PKG="ch.lkmc.neutrodyne"
DRIVER_CLASS="ch.lkmc.neutrodyne.tools.bmgrdriver.BgStartTest"
DRIVER_RUNNER="ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverRunner"
DRIVER_PKG=""                     # from the driver APK badging
DATA_DIR="/data/data/$PKG"
DRIVER_DATA_DIR=""                # /data/data/$DRIVER_PKG, once discovered
ACTOR_PKG="ch.lkmc.neutrodyne.tools.bgactor"
NONCE="run-$(date +%s)-$RANDOM"
APP_UID=""
PROCSTATE_SERVICE=10

# Ownership ledger. A flag arms before its mutating call; unarmed flags mean the
# trap must not touch that state.
ARMED=""; INSTALLED_PKG=""; INSTALLED_DRIVER=""; INSTALLED_ACTOR=""; LOGCAT_PID=""

log() { echo "[bgstart-check $(date +%H:%M:%S)] $*"; }
fail() { echo "[bgstart-check] FAIL: $*" >&2; exit 1; }
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
    "schema": "bgstart-evidence/v1",
    "device": facts.get("device", ""),
    "api": int(facts.get("api", "0")),
    "fingerprint": facts.get("fingerprint", ""),
    "abi": facts.get("abi", ""),
    "nonce": facts.get("nonce", ""),
    "sourceSha": facts.get("sourceSha", ""),
    "apkSha256": facts.get("apkSha256", ""),
    "apkBytes": int(facts.get("apkBytes", "0")),
    "driverApkSha256": facts.get("driverApkSha256", ""),
    "actorApkSha256": facts.get("actorApkSha256", ""),
    "driverPackage": facts.get("driverPackage", ""),
    "driverRunner": facts.get("driverRunner", ""),
    "certSha256": facts.get("certSha256", ""),
    "avd": facts.get("avd", ""),
    "appUid": int(facts.get("appUid", "0")),
    "cleanupStatus": facts.get("cleanupStatus", ""),
    "startedAt": facts.get("startedAt", ""),
    "finishedAt": facts.get("finishedAt", ""),
    "selectors": selectors,
}
(ev / "run.json").write_text(json.dumps(run, indent=2) + "\n")
PY
}

cleanup() {
    status=$?
    [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null || true
    # Diagnostics are read-only and run even when nothing was ever owned.
    out services-final.txt "$ADB" shell dumpsys activity services "$PKG"
    out processes-final.txt "$ADB" shell dumpsys activity processes
    failed=0
    if [ -n "$ARMED" ]; then
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
        if [ -n "$INSTALLED_ACTOR" ]; then
            timeout 60 "$ADB" shell pm uninstall --user 0 "$ACTOR_PKG" || true
            [ -z "$(timeout 60 "$ADB" shell pm path "$ACTOR_PKG" 2>/dev/null | tr -d '\r')" ] \
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

tool() {  # build-tools binary: PATH first, then the newest SDK build-tools dir
    command -v "$1" 2>/dev/null && return
    ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/ 2>/dev/null \
        | sort -V | tail -1 | xargs -I{} echo "{}$1"
}

# --- driver helpers ------------------------------------------------------------

run_driver() {  # <phase> <method>: armed driver call; the raw stream is kept verbatim
    local phase="$1" method="$2"
    local log_file="$EV/instrument-$phase.log"
    log "driver $phase: $DRIVER_CLASS#$method"
    timeout 600 "$ADB" shell am instrument -w -r \
        -e bmgrPhase "$phase" -e bmgrNonce "$NONCE" \
        -e class "$DRIVER_CLASS#$method" \
        "$DRIVER_PKG/$DRIVER_RUNNER" 2>&1 | tee "$log_file" || true
    # Keep failure evidence before marker rejection triggers uninstall cleanup.
    pull_vault "ui-$phase.xml" || true
    timeout 30 "$ADB" exec-out cat \
        "$DRIVER_DATA_DIR/files/bmgr/bmgr-status-$phase.json" > "$EV/driver-status-$phase.json" \
        2>/dev/null || fail "driver $phase left no status record"
    [ -s "$EV/driver-status-$phase.json" ] || fail "driver $phase status record empty"
    # A failed bgstart leg still leaves every captured stage dump in the vault —
    # pull all of it before the marker greps can abort the leg.
    if [ "$phase" = "bgstart" ]; then pull_bg_evidence; fi
    grep -q "^INSTRUMENTATION_CODE: -1" "$log_file" \
        || fail "driver $phase instrumentation did not complete"
    grep -q "INSTRUMENTATION_STATUS: bmgrDriver=$phase:$NONCE:ok" "$log_file" \
        || fail "driver $phase did not report ok for nonce $NONCE"
}

pull_vault() {  # <name>: one named file out of the driver's evidence vault
    local name="$1"
    timeout 30 "$ADB" exec-out cat "$DRIVER_DATA_DIR/files/bmgr/$name" > "$EV/$name" 2>/dev/null
}

pull_bg_evidence() {  # every bgstart-*/ui-bgstart* file the driver left behind
    local listing="$EV/vault-listing.txt"
    timeout 60 "$ADB" shell \
        "find '$DRIVER_DATA_DIR/files/bmgr' -mindepth 1 -maxdepth 1 -type f \
         \( -name 'bgstart-*' -o -name 'ui-bgstart*' \) 2>/dev/null | sort" \
        | tr -d '\r' > "$listing" || true
    while IFS= read -r dev_path <&3; do
        [ -n "$dev_path" ] || continue
        pull_vault "$(basename "$dev_path")" || log "vault pull miss: $dev_path"
    done 3< "$listing"
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

uid_state() {  # <uid>: raw `get-uid-state` text, unparsed
    timeout 60 "$ADB" shell cmd activity get-uid-state "$1" | tr -d '\r'
}

require_below_bfgs() {  # <stage>: the target uid must be SERVICE(10) or worse
    local stage="$1" state
    state="$(uid_state "$APP_UID")"
    printf '%s\n' "$state" > "$EV/uid-state-$stage.txt"
    local num
    num="$(printf '%s' "$state" | sed -n 's/^ *\(-\?[0-9][0-9]*\).*/\1/p' | head -1)"
    [ -n "$num" ] || fail "get-uid-state unparsable at $stage: $state"
    [ "$num" -ge "$PROCSTATE_SERVICE" ] \
        || fail "target uid state $num above SERVICE at $stage — FGS denial unreachable"
}

main() {
    API="${1:?usage: bgstart-check.sh <api> <evidence-dir>}"
    EV="$(cd "${2:?usage: bgstart-check.sh <api> <evidence-dir>}" >/dev/null 2>&1 && pwd \
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
        || fail "bgstart-check.sh only runs inside a GitHub Actions job"
    DISPOSABLE="BGSTART_DISPOSABLE_API$API"
    [ "${!DISPOSABLE:-}" = "1" ] \
        || fail "set $DISPOSABLE=1 to acknowledge a disposable emulator"
    [ -n "${BGSTART_EXPECT_AVD:-}" ] \
        || fail "BGSTART_EXPECT_AVD must name the workflow-created AVD"

    # --- device: the named disposable qemu emulator the job booted ---
    timeout 660 "$ADB" wait-for-device
    for _ in $(seq 1 60); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 10
    done
    AVD="$(timeout 60 "$ADB" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
    [ "$AVD" = "$BGSTART_EXPECT_AVD" ] \
        || fail "connected AVD '$AVD' is not the expected '$BGSTART_EXPECT_AVD'"
    fact avd "$AVD"
    [ "$(timeout 60 "$ADB" shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] \
        || fail "the device is not a qemu emulator"
    timeout 120 "$ADB" root && sleep 2 && timeout 120 "$ADB" wait-for-device
    [ "$("$ADB" shell id -u | tr -d '\r')" = "0" ] || fail "adbd is not root"
    SDK="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
    [ "$SDK" = "$API" ] || fail "emulator runs API $SDK, not $API"
    fact abi "$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
    fact fingerprint "$("$ADB" shell getprop ro.build.fingerprint | tr -d '\r')"
    grep -q '^abi=x86_64$' "$EV/facts.txt" || fail "the bgstart lane needs an x86_64 emulator"
    "$ADB" logcat -v threadtime > "$EV/logcat.txt" 2>/dev/null &
    LOGCAT_PID=$!

    # A disposable emulator carries neither our app nor the driver already.
    [ -z "$(timeout 60 "$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r')" ] \
        || fail "refusing a device with a preexisting $PKG install"
    ARMED=1

    # --- APK gates: published-artifact checks own debuggable/signer facts ---
    require_disk
    APK="$(ls app/build/outputs/apk/release/app-x86_64-release.apk 2>/dev/null || true)"
    [ -n "$APK" ] || APK="$(find app/build/outputs/apk/release -name 'app-*-release.apk' | head -1)"
    DRIVER_APK="${BGSTART_DRIVER_APK:-}"
    [ -n "$DRIVER_APK" ] || DRIVER_APK="$(find tools/bmgrdriver/build/outputs -name '*.apk' | head -1)"
    ACTOR_APK="${BGSTART_ACTOR_APK:-}"
    [ -n "$ACTOR_APK" ] || ACTOR_APK="$(find tools/bgactor/build/outputs -name '*-release.apk' -o -name '*-debug.apk' | head -1)"
    [ -f "$APK" ] || fail "release APK missing — build before boot"
    [ -f "$DRIVER_APK" ] || fail "driver APK missing — build :tools:bmgrdriver deviceTest"
    [ -f "$ACTOR_APK" ] || fail "actor APK missing — build :tools:bgactor release"
    scripts/ci/check-apk.sh --published "$APK" | tee "$EV/check-apk.txt"
    timeout 60 "$(tool aapt2)" dump badging "$APK" > "$EV/apk-badging.txt" 2>&1 || true
    ! grep -q "application-debuggable" "$EV/apk-badging.txt" || fail "release APK is debuggable"
    selector apkNonDebuggable
    fact apkSha256 "$(sha256sum "$APK" | cut -d' ' -f1)"
    fact apkBytes "$(stat -c%s "$APK")"
    fact driverApkSha256 "$(sha256sum "$DRIVER_APK" | cut -d' ' -f1)"
    fact actorApkSha256 "$(sha256sum "$ACTOR_APK" | cut -d' ' -f1)"
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
    timeout 60 "$(tool aapt2)" dump badging "$ACTOR_APK" > "$EV/actor-badging.txt" 2>&1 \
        || fail "actor APK badging unreadable"
    grep -q "package: name='$ACTOR_PKG'" "$EV/actor-badging.txt" \
        || fail "actor APK package is not $ACTOR_PKG: $(head -3 "$EV/actor-badging.txt")"
    INSTALLED_PKG=1
    timeout 300 "$ADB" install --user 0 "$APK" | tee "$EV/install-target.txt"
    INSTALLED_DRIVER=1
    timeout 300 "$ADB" install -t --user 0 "$DRIVER_APK" | tee "$EV/install-driver.txt"
    INSTALLED_ACTOR=1
    timeout 300 "$ADB" install --user 0 "$ACTOR_APK" | tee "$EV/install-actor.txt"
    timeout 60 "$ADB" shell pm list instrumentation > "$EV/instrumentations.txt" 2>&1 || true
    grep -q "instrumentation:$DRIVER_PKG/$DRIVER_RUNNER (target=$DRIVER_PKG)" \
        "$EV/instrumentations.txt" \
        || fail "driver instrumentation not self-targeting: $(tr '\n' ' ' < "$EV/instrumentations.txt")"
    ! grep -q "(target=$PKG)" "$EV/instrumentations.txt" \
        || fail "an instrumentation targets $PKG — the release build stays instrument-free"
    selector driverInstrumentationAvailable
    log "driver component: $DRIVER_PKG/$DRIVER_RUNNER"

    # The recovery/playback notifications are the leg's observable surface.
    if [ "$SDK" -ge 33 ]; then
        timeout 60 "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    fi

    # `pm list packages -U` substring-matches the filter — the driver package
    # `ch.lkmc.neutrodyne.tools.bmgrdriver.test` also matches `ch.lkmc.neutrodyne` —
    # and row order is not stable; anchor the exact package line.
    APP_UID="$(timeout 60 "$ADB" shell cmd package list packages -U "$PKG" \
        | tr -d '\r' | sed -n "s/^package:$PKG uid:\([0-9][0-9]*\).*/\1/p" | head -1)"
    [ -n "$APP_UID" ] || fail "target uid not resolvable"
    fact appUid "$APP_UID"

    # --- bgseed: durable paused session through the real import UI ---
    run_driver bgseed seedPausedSession
    pull_vault bgstart.expected.json || fail "driver wrote no bgstart.expected.json"
    [ -s "$EV/bgstart.expected.json" ] || fail "bgstart.expected.json empty"
    grep -q '"nonce": *"'"$NONCE"'"' "$EV/bgstart.expected.json" || fail "sidecar nonce mismatch"
    grep -q '"api": *'"$API" "$EV/bgstart.expected.json" || fail "sidecar api mismatch"
    wait_import_terminal 1 90
    selector seedImportDone

    # The durable state the bgstart leg relies on: a paused restored session and a
    # nonempty Up next — a torn read-only liveness check, never the assertion itself.
    answer="$(shadow_query "SELECT COUNT(*) FROM play_session WHERE currentEpisodeId IS NOT NULL")"
    case "$answer" in *\(*[1-9]*) ;; *) fail "no durable play_session after the seed: $answer" ;; esac
    answer="$(shadow_query "SELECT COUNT(*) FROM queue_entry")"
    case "$answer" in *\(*[1-9]*) ;; *) fail "no durable queue_entry after the seed: $answer" ;; esac
    selector durableQueueSeeded

    # --- cold state: kill the process, then prove the uid sits below the FGS
    # threshold. NOT force-stop: a stopped package is invisible to a normal
    # caller's resolveService ("Unable to start service ... not found"), so the
    # external bind could never reach it — a real cold start is a dead process,
    # not a stopped package.
    TARGET_PID="$(timeout 30 "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
    [ -z "$TARGET_PID" ] || timeout 60 "$ADB" shell kill -9 "$TARGET_PID"
    for _ in $(seq 1 10); do
        [ -z "$(timeout 30 "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && break
        sleep 1
    done
    [ -z "$(timeout 30 "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] \
        || fail "target still running after the kill"
    require_below_bfgs cold
    selector targetColdBelowBfgs

    # --- bgstart: the external denial + resume leg, fully evidence-captured ---
    run_driver bgstart deniedBackgroundStartOffersWorkingResume
    pull_bg_evidence
    for name in \
        bgstart-uid-state-before.txt bgstart-uid-state-card.txt \
        bgstart-uid-state-denied.txt bgstart-uid-state-resumed.txt \
        bgstart-services-before.txt bgstart-services-card.txt \
        bgstart-services-denied.txt bgstart-services-resumed.txt \
        bgstart-processes-before.txt bgstart-processes-card.txt \
        bgstart-processes-denied.txt bgstart-processes-resumed.txt \
        bgstart-notification-denied.txt bgstart-notification-resumed.txt \
        bgstart-media-session-card.txt \
        bgstart-media-session-denied.txt bgstart-media-session-resumed.txt \
        bgstart-audio-denied.txt bgstart-audio-resumed.txt \
        bgstart-settle.txt bgstart-positions.txt; do
        [ -s "$EV/$name" ] || fail "driver evidence missing or empty: $name"
    done
    selector bgstartCompleted
    log "api$API leg complete"
}

if [ "${BGSTART_CHECK_LIB_ONLY:-}" != "1" ]; then
    main "$@"
fi
