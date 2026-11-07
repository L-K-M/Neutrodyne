#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Logging proxy for the desktop network capture (09 v1.0 gate row 8).

The desktop JVM is started with -Dhttps.proxyHost/-Dhttps.proxyPort, so OkHttp's default
ProxySelector sends every HTTPS request as a CONNECT and every plain HTTP request with an
absolute URI. The proxy logs the destination host of both and tunnels the bytes, so the
capture sees the JVM's traffic without decrypting it. The YouTube-engine child never uses a
proxy (11 clears its proxy variables), which is why the desktop capture also runs tshark.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import select
import socket
import threading
from urllib.parse import urlsplit


def log_host(host: str) -> None:
    print(host.lower().rstrip("."), flush=True)


def parse_target(target: str, default_port: int) -> tuple[str, int]:
    if target.startswith("["):
        closing = target.index("]")
        host = target[1:closing]
        port = int(target[closing + 2 :]) if target[closing + 1 :].startswith(":") else default_port
        return host, port
    host, separator, port = target.partition(":")
    return host, int(port) if separator and port else default_port


def tunnel(client: socket.socket, upstream: socket.socket, pending: bytes) -> None:
    """Relay bytes in both directions until one side closes."""
    if pending:
        upstream.sendall(pending)
    sockets = [client, upstream]
    while True:
        readable, _, _ = select.select(sockets, [], [], 60.0)
        if not readable:
            return
        for source in readable:
            data = source.recv(65536)
            if not data:
                return
            (upstream if source is client else client).sendall(data)


def handle(client: socket.socket) -> None:
    upstream: socket.socket | None = None
    try:
        client.settimeout(60.0)
        request = b""
        while b"\r\n\r\n" not in request:
            chunk = client.recv(4096)
            if not chunk:
                return
            request += chunk
            if len(request) > 65536:
                return
        head, _, rest = request.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        method, target, _ = lines[0].split(" ", 2)

        if method == "CONNECT":
            host, port = parse_target(target, 443)
            log_host(host)
            upstream = socket.create_connection((host, port), timeout=60.0)
            client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            tunnel(client, upstream, rest)
            return

        parsed = urlsplit(target)
        host = parsed.hostname
        if not host:
            client.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return
        port = parsed.port or 80
        log_host(host)
        upstream = socket.create_connection((host, port), timeout=60.0)
        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query
        lines[0] = f"{method} {path} HTTP/1.1"
        upstream.sendall(("\r\n".join(lines)).encode("latin-1") + b"\r\n\r\n" + rest)
        tunnel(client, upstream, b"")
    except OSError:
        pass
    finally:
        client.close()
        if upstream is not None:
            upstream.close()


def serve(listen_host: str, listen_port: int, port_file: Path | None) -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((listen_host, listen_port))
    server.listen(64)
    if port_file:
        port_file.write_text(f"{server.getsockname()[1]}\n", encoding="utf-8")
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", type=Path)
    args = parser.parse_args()
    serve(args.listen, args.port, args.port_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
