#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Verify a published release against local dist assets and build provenance.

Downloads every published asset into an owned temporary directory, compares the
exact downloaded filename set and bytes to the local dist files, and verifies
each downloaded artifact with `gh attestation verify` bound to the repository,
release.yml signer workflow, the exact tag ref and the source commit digest.
"""
from __future__ import annotations

import argparse
import filecmp
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=False, text=True, **kwargs)


def release_asset_names(tag: str, repo: str) -> list[str]:
    r = run(["gh", "release", "view", tag, "-R", repo, "--json", "assets"],
            capture_output=True)
    if r.returncode != 0:
        raise SystemExit(r.stderr.strip() or f"gh release view failed for {tag}")
    data = json.loads(r.stdout or "{}")
    names = [a["name"] for a in data.get("assets", [])]
    return sorted(names)


def local_asset_names(dist: Path) -> list[str]:
    return sorted(p.name for p in dist.iterdir() if p.is_file())


def compare_sets(local: list[str], remote: list[str]) -> None:
    missing = sorted(set(local) - set(remote))
    extra = sorted(set(remote) - set(local))
    if missing or extra:
        msgs = []
        if missing:
            msgs.append("missing published assets: " + ", ".join(missing))
        if extra:
            msgs.append("extra published assets: " + ", ".join(extra))
        raise SystemExit("; ".join(msgs))


def compare_bytes(dist: Path, downloaded: Path, names: list[str]) -> None:
    for name in names:
        a, b = dist / name, downloaded / name
        if not filecmp.cmp(a, b, shallow=False):
            raise SystemExit(f"downloaded asset bytes differ: {name}")


def verify_attestations(downloaded: Path, names: list[str], repo: str, signer_workflow: str,
                        source_ref: str, source_digest: str) -> None:
    for name in names:
        path = str(downloaded / name)
        cmd = [
            "gh", "attestation", "verify", path,
            "-R", repo,
            "--signer-workflow", signer_workflow,
            "--source-ref", source_ref,
            "--source-digest", source_digest,
        ]
        r = run(cmd, capture_output=True)
        if r.returncode != 0:
            err = (r.stderr or r.stdout or "").strip()
            raise SystemExit(f"attestation verify failed for {name}: {err}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--dist", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--source-digest", required=True)
    ap.add_argument("--signer-workflow")
    args = ap.parse_args()

    dist = Path(args.dist)
    if not dist.is_dir():
        raise SystemExit(f"dist is not a directory: {dist}")
    source_ref = "refs/tags/" + args.tag
    signer_workflow = args.signer_workflow or args.repo + "/.github/workflows/release.yml"

    local = local_asset_names(dist)
    if not local:
        raise SystemExit("dist contains no release assets")
    remote = release_asset_names(args.tag, args.repo)
    compare_sets(local, remote)

    with tempfile.TemporaryDirectory(prefix="verify-published-release.") as td:
        dl = Path(td)
        # Download every published asset into owned temp dir.
        r = run(["gh", "release", "download", args.tag, "-R", args.repo, "-D", str(dl)],
                capture_output=True)
        if r.returncode != 0:
            raise SystemExit(r.stderr.strip() or f"gh release download failed for {args.tag}")
        downloaded = local_asset_names(dl)
        compare_sets(local, downloaded)
        compare_bytes(dist, dl, local)
        verify_attestations(dl, local, args.repo, signer_workflow, source_ref,
                            args.source_digest)
    print(f"verified published release {args.tag}: {len(local)} assets")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1)
