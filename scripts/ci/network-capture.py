#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Host extraction and PRIVACY.md inventory checking for the v1.0 gate network capture.

`hosts` reads a pcap (through tshark) and a logging-proxy CONNECT log and writes the
normalized host list. `check` maps every host to a network-inventory ID of PRIVACY.md and
fails on an unmapped host, so a capture that contacted a destination the inventory does not
disclose cannot pass.

The inventory's fixed destinations are matched by host pattern. The destinations the user
chose during the session (feed, enclosure, artwork, chapter, show-notes and add-input hosts,
and the linked sync server) cannot be derived from the host name, so the caller declares them
with --expect host=id or --sync-server host; an undeclared host is unmapped.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

REPO_ROOT = Path(__file__).resolve().parents[2]

# Fixed inventory destinations (09 Network inventory). Patterns are lower-case; a leading
# dot matches a suffix (*.googlevideo.com). A host may map to several IDs: github.com is the
# destination of the app-update, YouTube-engine, server-update, link and issue-tracker rows.
FIXED_HOST_PATTERNS = (
    ("itunes.apple.com", ("apple",)),
    ("rss.marketingtools.apple.com", ("apple",)),
    ("api.fyyd.de", ("fyyd",)),
    ("api.podcastindex.org", ("podcastindex",)),
    ("www.youtube.com", ("youtube-subscriptions", "youtube-streams")),
    ("i.ytimg.com", ("youtube-subscriptions",)),
    ("yt3.googleusercontent.com", ("youtube-subscriptions",)),
    ("yt3.ggpht.com", ("youtube-subscriptions",)),
    (".googlevideo.com", ("youtube-streams",)),
    (
        "github.com",
        ("youtube-engine", "app-updates", "links", "issue-tracker", "server-updates"),
    ),
    ("release-assets.githubusercontent.com", ("youtube-engine", "app-updates", "server-updates")),
)

# The inventory's "never" row: no build may contact the rate-limited REST API.
FORBIDDEN_HOSTS = {
    "api.github.com": "the inventory forbids api.github.com; the update check uses the release asset",
}

INVENTORY_ROW = re.compile(r"^\| `([a-z-]+)` \|", re.MULTILINE)


def normalize_host(value: str) -> str:
    """Lower-case a captured host and drop a trailing dot and a CONNECT port."""
    host = value.strip().lower().rstrip(".")
    if host.startswith("[") and "]:" in host:
        return host[1 : host.index("]")]
    if host.count(":") == 1:
        return host.split(":", 1)[0]
    return host


def inventory_ids(privacy_path: Path) -> list[str]:
    ids = INVENTORY_ROW.findall(privacy_path.read_text(encoding="utf-8"))
    if not ids:
        raise SystemExit(f"no inventory IDs found in {privacy_path}")
    return ids


def default_engine_host() -> str:
    properties = REPO_ROOT / "gradle.properties"
    if properties.is_file():
        match = re.search(r"^neutrodyne\.engineManifestUrl=(\S+)", properties.read_text(encoding="utf-8"), re.MULTILINE)
        if match:
            return urlsplit(match.group(1)).hostname or "l-k-m.github.io"
    return "l-k-m.github.io"


def fixed_ids(host: str, engine_host: str) -> set[str]:
    ids: set[str] = set()
    for pattern, pattern_ids in FIXED_HOST_PATTERNS:
        if pattern.startswith("."):
            if host.endswith(pattern):
                ids.update(pattern_ids)
        elif host == pattern:
            ids.update(pattern_ids)
    if engine_host and host == engine_host.lower():
        ids.add("youtube-engine")
    return ids


def read_hosts(path: Path) -> list[str]:
    hosts = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        hosts.add(normalize_host(line))
    return sorted(host for host in hosts if host)


def command_hosts(args: argparse.Namespace) -> int:
    hosts: set[str] = set()
    if args.proxy_log:
        for line in Path(args.proxy_log).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                hosts.add(normalize_host(line))
    if args.pcap:
        pcap = Path(args.pcap)
        if not pcap.is_file():
            raise SystemExit(f"capture not found: {pcap}")
        for field in ("dns.qry.name", "tls.handshake.extensions_server_name"):
            result = subprocess.run(
                ["tshark", "-r", str(pcap), "-Y", field, "-T", "fields", "-e", field],
                capture_output=True,
                text=True,
                check=False,
            )
            if result.returncode != 0:
                raise SystemExit(f"tshark failed on {pcap}: {result.stderr.strip()}")
            for line in result.stdout.splitlines():
                for value in line.split("\t"):
                    if value.strip():
                        hosts.add(normalize_host(value))
    ordered = sorted(hosts)
    text = "".join(f"{host}\n" for host in ordered)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


def command_check(args: argparse.Namespace) -> int:
    inventory = inventory_ids(args.privacy)
    known = set(inventory)
    engine_host = args.engine_host or default_engine_host()

    declared: dict[str, set[str]] = {}

    def declare(host: str, ids: list[str], source: str) -> None:
        unknown = [identifier for identifier in ids if identifier not in known]
        if unknown:
            raise SystemExit(f"{source}: not an inventory ID of {args.privacy}: {', '.join(unknown)}")
        declared.setdefault(normalize_host(host), set()).update(ids)

    for value in args.expect:
        host, separator, ids = value.partition("=")
        if not separator or not host.strip() or not ids.strip():
            raise SystemExit(f"--expect needs host=id: {value!r}")
        declare(host, [item.strip() for item in ids.split(",") if item.strip()], f"--expect {value!r}")
    for host in args.sync_server:
        declare(host, ["sync-server"], "--sync-server")

    mapping: dict[str, list[str]] = {}
    unmapped: list[tuple[str, str]] = []
    for host in read_hosts(args.hosts):
        if host in FORBIDDEN_HOSTS:
            unmapped.append((host, FORBIDDEN_HOSTS[host]))
            continue
        ids = fixed_ids(host, engine_host) | declared.get(host, set())
        if ids:
            mapping[host] = sorted(ids)
        else:
            unmapped.append((host, "no inventory ID; declare it with --expect host=id"))

    for host in sorted(mapping):
        print(f"{host}\t{','.join(mapping[host])}")
    print(
        f"{len(mapping)} hosts mapped, {len(unmapped)} unmapped "
        f"({len(known)} inventory IDs)",
        file=sys.stderr,
    )

    for host, reason in unmapped:
        print(f"unmapped host: {host}: {reason}", file=sys.stderr)

    missing = [identifier for identifier in args.require if identifier not in known]
    if missing:
        raise SystemExit(f"--require: not an inventory ID of {args.privacy}: {', '.join(missing)}")
    absent = [
        identifier
        for identifier in args.require
        if identifier not in {item for ids in mapping.values() for item in ids}
    ]
    for identifier in absent:
        print(f"required inventory ID not observed: {identifier}", file=sys.stderr)

    return 1 if unmapped or absent else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    hosts = subparsers.add_parser("hosts", help="extract a normalized host list")
    hosts.add_argument("--pcap", help="capture file read through tshark")
    hosts.add_argument("--proxy-log", help="logging-proxy CONNECT log")
    hosts.add_argument("--output", help="host-list file (default: stdout)")
    hosts.set_defaults(handler=command_hosts)

    check = subparsers.add_parser("check", help="map every host to an inventory ID")
    check.add_argument("--hosts", required=True, type=Path, help="host list from 'hosts'")
    check.add_argument("--privacy", type=Path, default=REPO_ROOT / "PRIVACY.md")
    check.add_argument("--expect", action="append", default=[], metavar="HOST=ID[,ID]")
    check.add_argument("--sync-server", action="append", default=[], metavar="HOST")
    check.add_argument("--engine-host", help="engine manifest host (default: gradle.properties)")
    check.add_argument("--require", action="append", default=[], metavar="ID")
    check.set_defaults(handler=command_check)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
