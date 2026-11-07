#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# v1.0 gate network capture (09 CI scripts / v1.0 gate row 8).
#
# Maintainer-run and needs the real internet; it is never a CI job. It records the hosts a
# fresh-install session contacts, writes a host list, then checks every host against the
# PRIVACY.md network inventory.
#
#   --android   emulator -tcpdump capture, then tshark DNS names and TLS SNI
#   --desktop   logging CONNECT proxy for the JVM, plus tshark DNS/SNI for the engine child
#   --server    capture of the compose network namespace
#
# The inventory's fixed destinations are matched by host pattern. The destinations the user
# chose during the session (feed, enclosure, artwork, chapter, show-notes and add-input hosts,
# and the linked sync server) cannot be derived from the host name, so declare them with
# --expect host=id or --sync-server host. The check fails on any host the inventory does not
# cover; use --require id to assert that a destination was actually exercised.
#
# The interactive session is manual: the script starts the capture and waits for Enter.

set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"

usage() {
    cat <<'EOF'
usage: network-capture.sh --android | --desktop | --server [options]

  --output DIR        capture directory (default build/network-capture-<mode>)
  --apk PATH          Android: install this APK after boot
  --avd NAME          Android: AVD name (default neutrodyne-api36)
  --app-cmd PATH      desktop: the desktop launcher or 'java -jar <jar>'
  --app-arg ARG       desktop: repeated launcher argument
  --interface IFACE   desktop: tshark interface (default: any / the default route)
  --proxy-port PORT   desktop: proxy port (default: ephemeral)
  --server-compose F  server: compose file (default sync/server/deploy/compose.yaml)
  --expect HOST=ID    declare a user-chosen destination (repeatable)
  --sync-server HOST  declare the linked sync server (repeatable)
  --require ID        require an inventory ID was observed (repeatable)
  --no-check          write the host list without the inventory check
EOF
}

mode=""
output=""
apk=""
avd="neutrodyne-api36"
app_cmd=""
app_args=()
interface=""
proxy_port=""
server_compose="$root/sync/server/deploy/compose.yaml"
expect=()
sync_servers=()
requires=()
no_check=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --android) mode=android ;;
        --desktop) mode=desktop ;;
        --server) mode=server ;;
        --output) output="${2:?--output needs a directory}"; shift ;;
        --apk) apk="${2:?--apk needs a path}"; shift ;;
        --avd) avd="${2:?--avd needs a name}"; shift ;;
        --app-cmd) app_cmd="${2:?--app-cmd needs a path}"; shift ;;
        --app-arg) app_args+=("${2:?--app-arg needs a value}"); shift ;;
        --interface) interface="${2:?--interface needs a name}"; shift ;;
        --proxy-port) proxy_port="${2:?--proxy-port needs a number}"; shift ;;
        --server-compose) server_compose="${2:?--server-compose needs a path}"; shift ;;
        --expect) expect+=("${2:?--expect needs host=id}"); shift ;;
        --sync-server) sync_servers+=("${2:?--sync-server needs a host}"); shift ;;
        --require) requires+=("${2:?--require needs an inventory ID}"); shift ;;
        --no-check) no_check=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

case "$mode" in
    android|desktop|server) ;;
    "") echo "one of --android, --desktop or --server is required" >&2; usage >&2; exit 2 ;;
    *) echo "unknown mode: $mode" >&2; exit 2 ;;
esac

if [[ -z "$output" ]]; then
    output="$root/build/network-capture-$mode"
fi
mkdir -p "$output"
work="$(cd "$output" && pwd -P)"

pids=()
cleanup() {
    local pid
    for pid in ${pids[@]+"${pids[@]}"}; do
        kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT

require_tool() {
    local name=$1
    command -v "$name" >/dev/null 2>&1 || {
        echo "required tool not found: $name" >&2
        exit 2
    }
}

wait_for_enter() {
    # Keep the capture running while the maintainer performs the session.
    printf '\nCapture is running in %s.\n' "$work"
    printf 'Perform the session, then press Enter to stop the capture.\n'
    read -r -p '> ' _ || true
}

finish_hosts() {
    local args=()
    [[ -n "${1:-}" ]] && args+=(--pcap "$1")
    [[ -n "${2:-}" ]] && args+=(--proxy-log "$2")
    python3 "$root/scripts/ci/network-capture.py" hosts "${args[@]}" \
        --output "$work/hosts.txt"
    printf 'host list: %s\n' "$work/hosts.txt"
}

run_check() {
    [[ "$no_check" -eq 1 ]] && return 0
    local args=(--hosts "$work/hosts.txt" --privacy "$root/PRIVACY.md")
    local value
    for value in ${expect[@]+"${expect[@]}"}; do args+=(--expect "$value"); done
    for value in ${sync_servers[@]+"${sync_servers[@]}"}; do args+=(--sync-server "$value"); done
    for value in ${requires[@]+"${requires[@]}"}; do args+=(--require "$value"); done
    python3 "$root/scripts/ci/network-capture.py" check "${args[@]}"
}

run_android() {
    require_tool tshark
    local adb=${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb
    local emulator=${ANDROID_HOME:-$HOME/Library/Android/sdk}/emulator/emulator
    [[ -x "$adb" ]] || { echo "adb not found at $adb (set ANDROID_HOME)" >&2; exit 2; }
    [[ -x "$emulator" ]] || { echo "emulator not found at $emulator (set ANDROID_HOME)" >&2; exit 2; }

    local pcap="$work/android.pcap"
    "$emulator" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect -tcpdump "$pcap" >"$work/emulator.log" 2>&1 &
    pids+=("$!")
    "$adb" wait-for-device
    local booted=0
    for _ in $(seq 1 180); do
        if [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
            booted=1
            break
        fi
        sleep 5
    done
    [[ "$booted" -eq 1 ]] || { echo "emulator $avd did not finish booting" >&2; exit 1; }

    if [[ -n "$apk" ]]; then
        "$adb" install -r "$apk"
    fi
    wait_for_enter
    "$adb" emu kill 2>/dev/null || true
    wait_for_enter_drain
    finish_hosts "$pcap"
    run_check
}

wait_for_enter_drain() {
    # The capture tools flush their files on SIGTERM; a GUI app may take longer, so give
    # everything a moment before forcing it and then reap the PIDs without hanging.
    local pid
    for pid in ${pids[@]+"${pids[@]}"}; do
        kill "$pid" 2>/dev/null || true
    done
    sleep 1
    for pid in ${pids[@]+"${pids[@]}"}; do
        kill -9 "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    done
    # The PIDs are reaped: clear them so the EXIT trap cannot signal a recycled PID.
    pids=()
}

run_desktop() {
    require_tool tshark
    require_tool python3
    [[ -n "$app_cmd" ]] || {
        echo "--app-cmd is required: the desktop launcher or 'java -jar <jar>'" >&2
        exit 2
    }

    local port_file="$work/proxy.port"
    python3 "$root/scripts/ci/network-capture-proxy.py" --listen 127.0.0.1 \
        --port "${proxy_port:-0}" --port-file "$port_file" >"$work/proxy-connects.txt" 2>"$work/proxy.log" &
    pids+=("$!")
    for _ in $(seq 1 100); do
        [[ -s "$port_file" ]] && break
        sleep 0.1
    done
    [[ -s "$port_file" ]] || { echo "the capture proxy did not start" >&2; exit 1; }
    local port
    port="$(cat "$port_file")"

    if [[ -z "$interface" ]]; then
        case "$(uname -s)" in
            Linux) interface=any ;;
            Darwin) interface="$(route -n get default 2>/dev/null | awk '/interface:/ {print $2}' | head -1)" ;;
        esac
    fi
    [[ -n "$interface" ]] || {
        echo "could not determine the capture interface; pass --interface" >&2
        exit 2
    }
    local pcap="$work/desktop.pcap"
    tshark -i "$interface" -f "udp port 53 or tcp port 443" -w "$pcap" \
        >"$work/tshark.log" 2>&1 &
    pids+=("$!")

    # JAVA_TOOL_OPTIONS reaches the JVM through any launcher; the engine child clears its
    # environment (11), so its traffic appears only in the tshark capture.
    JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$port -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$port" \
        "$app_cmd" ${app_args[@]+"${app_args[@]}"} >"$work/app.log" 2>&1 &
    pids+=("$!")

    wait_for_enter
    wait_for_enter_drain
    finish_hosts "$pcap" "$work/proxy-connects.txt"
    run_check
}

run_server() {
    require_tool docker
    require_tool sudo
    require_tool tshark
    local container
    container="$(docker compose -f "$server_compose" ps -q neutrodyne-server)"
    [[ -n "$container" ]] || { echo "the compose server is not running" >&2; exit 2; }
    local pid
    pid="$(docker inspect -f '{{.State.Pid}}' "$container")"
    local pcap="$work/server.pcap"
    sudo nsenter -t "$pid" -n tcpdump -nn -l -i any "not port 8787" -w "$pcap" \
        >"$work/tcpdump.log" 2>&1 &
    pids+=("$!")
    wait_for_enter
    wait_for_enter_drain
    finish_hosts "$pcap"
    run_check
}

case "$mode" in
    android) run_android ;;
    desktop) run_desktop ;;
    server) run_server ;;
esac

printf 'capture complete: %s\n' "$work"
