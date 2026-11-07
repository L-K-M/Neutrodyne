#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Run the reference read-only deployment with internal test certificates and both proxies.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
export NEUTRODYNE_SERVER_IMAGE="${1:?local image required}" SYNC_HOST=sync.local UPDATE_CHECK=false
work="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="nd-smoke-${GITHUB_RUN_ID:-$$}"
compose=(docker compose -f sync/server/deploy/compose.yaml -f "$work/override.yaml")
capture=''
cleanup() {
    [[ -z "$capture" ]] || sudo kill -INT "$capture" 2>/dev/null || true
    "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
# No DNS or Internet connection may originate from the server with update
# checking disabled. Reverse-proxy responses originate from 8787 and are
# excluded; Caddy has its own namespace. The capture runs inside the server
# container's network namespace because Docker's embedded resolver would hide
# DNS queries from a host-side capture.
start_capture() {
    # $1 names the phase; uses the global $server_pid and $server_ip. It runs
    # right after each server start so startup traffic is covered too: the JVM
    # cannot dial out before the app runs, and the update check is disabled
    # here (its earliest attempt is the first 60 s tick anyway).
    # shellcheck disable=SC2024 # the fds are opened by this shell into $work; tee would merge stderr into the capture
    sudo nsenter -t "$server_pid" -n tcpdump -nn -l -i any \
        "(tcp or udp) and ((src host $server_ip and not src port 8787) or dst port 53)" \
        > "$work/egress.$1.txt" 2> "$work/capture.$1.log" &
    capture=$!
    # tcpdump must attach before an empty file can count as proof of silence.
    for _ in $(seq 1 50); do
        grep -q 'listening on' "$work/capture.$1.log" 2>/dev/null && return 0
        sudo kill -0 "$capture" 2>/dev/null || break
        sleep 0.1
    done
    echo 'egress capture did not attach' >&2
    cat "$work/capture.$1.log" >&2 || true
    exit 1
}
stop_capture() {
    sudo kill -0 "$capture" # an early capture failure must not count as zero egress
    sudo kill -INT "$capture"
    wait "$capture" || true
    capture=''
    # tcpdump must have actually attached; an empty file without a capture is not proof.
    grep -q 'listening on' "$work/capture.$1.log"
    [[ ! -s "$work/egress.$1.txt" ]] || { cat "$work/egress.$1.txt"; echo 'Unexpected server egress' >&2; exit 1; }
}
cat > "$work/Caddyfile" <<'CADDY'
sync.local {
    tls internal
    header Strict-Transport-Security "max-age=31536000"
    reverse_proxy neutrodyne-server:8787
}
CADDY
# nginx uses the same test CA as Caddy, so clients exercise certificate validation.
cat > "$work/override.yaml" <<YAML
services:
  caddy:
    ports: !override ["127.0.0.1:9443:443"]
    volumes:
      - $work/Caddyfile:/etc/caddy/Caddyfile:ro
  nginx:
    image: nginx:stable
    ports: ["127.0.0.1:9444:443"]
    volumes:
      - $work/nginx.conf:/etc/nginx/nginx.conf:ro
      - $work/certs:/certs:ro
    networks: [sync]
YAML
mkdir "$work/certs"
# Initial file exists before compose parses all mounts; nginx starts only after certificates arrive.
: > "$work/nginx.conf"
"${compose[@]}" up -d neutrodyne-server caddy
server="$("${compose[@]}" ps -q neutrodyne-server)"
caddy="$("${compose[@]}" ps -q caddy)"
server_ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$server")"
server_pid="$(docker inspect -f '{{.State.Pid}}' "$server")"
# Exec'd JVMs are bounded: they share the compose mem_limit (320m) with serve.
probe_jvm=(/usr/bin/java -Xmx32m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k)
cli_jvm=(/usr/bin/java -Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k)
start_capture startup
ready=false
for _ in $(seq 1 90); do
    if docker exec "$server" "${probe_jvm[@]}" -cp /app/neutrodyne-server.jar ch.lkmc.neutrodyne.sync.server.HealthCheck; then ready=true; break; fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker inspect -f 'server container: status={{.State.Status}} exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}}' "$server" || true
    docker logs --tail 50 "$server" 2>&1 || true
    exit 1
fi
docker exec "$server" "${cli_jvm[@]}" -jar /app/neutrodyne-server.jar doctor
ready=false
for _ in $(seq 1 60); do
    if docker cp "$caddy:/data/caddy/pki/authorities/local/root.crt" "$work/certs/ca.crt" 2>/dev/null; then ready=true; break; fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo 'Caddy never wrote its local CA certificate' >&2
    docker logs --tail 50 "$caddy" 2>&1 || true
    exit 1
fi
# Issue nginx's leaf from Caddy's test CA, confined to the temporary smoke directory.
docker cp "$caddy:/data/caddy/pki/authorities/local/root.key" "$work/certs/ca.key"
openssl req -new -newkey rsa:2048 -nodes -keyout "$work/certs/key.pem" -out "$work/certs/request.pem" -subj '/CN=sync.local' >/dev/null 2>&1
printf 'subjectAltName=DNS:sync.local\n' > "$work/certs/extensions"
openssl x509 -req -in "$work/certs/request.pem" -CA "$work/certs/ca.crt" -CAkey "$work/certs/ca.key" \
    -CAcreateserial -out "$work/certs/cert.pem" -days 1 -extfile "$work/certs/extensions" >/dev/null 2>&1
# Keep the example's SSE settings, with only its upstream replaced for the compose network.
{
    echo 'events {}'
    echo 'http { server { listen 443 ssl; server_name sync.local; ssl_certificate /certs/cert.pem; ssl_certificate_key /certs/key.pem;'
    sed 's|http://127.0.0.1:8787|http://neutrodyne-server:8787|g' sync/server/deploy/nginx.conf.example
    echo '} }'
} > "$work/nginx.conf"
"${compose[@]}" up -d nginx
invite_output="$(docker exec "$server" "${cli_jvm[@]}" -jar /app/neutrodyne-server.jar admin create smoke)"
export SMOKE_INVITE="${invite_output##*shown once): }"
python3 scripts/ci/server-smoke.py exercise --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json"
# Both five-minute streams run concurrently; this job never substitutes the MS3 two-hour gate.
python3 scripts/ci/server-smoke.py events --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json" &
caddy_stream=$!
python3 scripts/ci/server-smoke.py events --url https://sync.local:9444 --ca "$work/certs/ca.crt" --state "$work/state.json" &
nginx_stream=$!
wait "$caddy_stream"
wait "$nginx_stream"
docker exec "$server" "${cli_jvm[@]}" -jar /app/neutrodyne-server.jar backup /data/smoke.db
python3 scripts/ci/server-smoke.py mutate --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json"
# Revoke AFTER the backup; restore must carry this revocation forward.
python3 - "$work/state.json" "$work/certs/ca.crt" <<'PY'
import json, ssl, sys, urllib.request
state = json.load(open(sys.argv[1]))
req = urllib.request.Request('https://sync.local:9443/api/v1/auth/logout', data=b'',
    headers={'Authorization': 'Bearer ' + state['token'], 'Neutrodyne-Sync-Protocol': '1'})
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=sys.argv[2])))
with opener.open(req) as response:
    if response.status != 204: raise RuntimeError("Logout did not complete")
PY
stop_capture startup
"${compose[@]}" stop neutrodyne-server
"${compose[@]}" run --rm --no-deps neutrodyne-server restore /data/smoke.db
"${compose[@]}" up -d neutrodyne-server
server="$("${compose[@]}" ps -q neutrodyne-server)"
server_ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$server")"
server_pid="$(docker inspect -f '{{.State.Pid}}' "$server")"
start_capture restart
ready=false
for _ in $(seq 1 60); do
    if curl --max-time 5 --cacert "$work/certs/ca.crt" -fsS https://sync.local:9443/readyz >/dev/null; then ready=true; break; fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo 'server did not become ready after restore' >&2
    docker logs --tail 50 "$server" 2>&1 || true
    exit 1
fi
invite_output="$(docker exec "$server" "${cli_jvm[@]}" -jar /app/neutrodyne-server.jar user invite smoke)"
export SMOKE_INVITE="${invite_output##*shown once): }"
python3 scripts/ci/server-smoke.py after-restore --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json"
stop_capture restart
echo 'Reference compose, Caddy/nginx SSE, noexec native loading, backup/restore carried forward, and no server egress (DNS included) from startup through the post-restore run'
