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
for _ in $(seq 1 90); do
    if docker exec "$server" /usr/bin/java -cp /app/neutrodyne-server.jar ch.lkmc.neutrodyne.sync.server.HealthCheck; then break; fi
    sleep 1
done
docker exec "$server" /usr/bin/java -jar /app/neutrodyne-server.jar doctor
for _ in $(seq 1 60); do
    if docker cp "$caddy:/data/caddy/pki/authorities/local/root.crt" "$work/certs/ca.crt" 2>/dev/null; then break; fi
    sleep 1
done
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
# No DNS or Internet connection may originate from the server with update checking disabled.
# Reverse-proxy responses originate from 8787 and are excluded; Caddy has its own namespace.
server_ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$server")"
server_pid="$(docker inspect -f '{{.State.Pid}}' "$server")"
sudo nsenter -t "$server_pid" -n tcpdump -nn -l -i any \
    "(tcp or udp) and ((src host $server_ip and not src port 8787) or dst port 53)" > "$work/egress.txt" 2> "$work/capture.log" &
capture=$!
invite_output="$(docker exec "$server" /usr/bin/java -jar /app/neutrodyne-server.jar admin create smoke)"
export SMOKE_INVITE="${invite_output##*shown once): }"
python3 scripts/ci/server-smoke.py exercise --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json"
# Both five-minute streams run concurrently; this job never substitutes the MS3 two-hour gate.
python3 scripts/ci/server-smoke.py events --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json" &
caddy_stream=$!
python3 scripts/ci/server-smoke.py events --url https://sync.local:9444 --ca "$work/certs/ca.crt" --state "$work/state.json" &
nginx_stream=$!
wait "$caddy_stream"
wait "$nginx_stream"
docker exec "$server" /usr/bin/java -jar /app/neutrodyne-server.jar backup /data/smoke.db
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
sudo kill -0 "$capture" # an early capture failure must not count as zero egress
sudo kill -INT "$capture"
wait "$capture" || true
capture=''
# tcpdump must have actually attached; an empty file without a capture is not proof.
grep -q 'listening on' "$work/capture.log"
[[ ! -s "$work/egress.txt" ]] || { cat "$work/egress.txt"; echo 'Unexpected server egress' >&2; exit 1; }
"${compose[@]}" stop neutrodyne-server
"${compose[@]}" run --rm --no-deps neutrodyne-server restore /data/smoke.db
"${compose[@]}" up -d neutrodyne-server
for _ in $(seq 1 60); do
    if curl --cacert "$work/certs/ca.crt" -fsS https://sync.local:9443/readyz >/dev/null; then break; fi
    sleep 1
done
server="$("${compose[@]}" ps -q neutrodyne-server)"
invite_output="$(docker exec "$server" /usr/bin/java -jar /app/neutrodyne-server.jar user invite smoke)"
export SMOKE_INVITE="${invite_output##*shown once): }"
python3 scripts/ci/server-smoke.py after-restore --url https://sync.local:9443 --ca "$work/certs/ca.crt" --state "$work/state.json"
echo 'Reference compose, Caddy/nginx SSE, noexec native loading, backup/restore and egress checks passed'
