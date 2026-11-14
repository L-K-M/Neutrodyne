#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "scripts/ci/verify-published-release.py"


class VerifyPublishedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.dist = self.root / "dist"
        self.remote = self.root / "remote"
        self.bin = self.root / "bin"
        self.log = self.root / "attest.log"
        self.dist.mkdir()
        self.remote.mkdir()
        self.bin.mkdir()
        self.write_dist({"a.bin": b"aaa", "b.bin": b"bbb"})

    def write_dist(self, files):
        for name in list(self.dist.iterdir()):
            if name.is_file():
                name.unlink()
        for name, data in files.items():
            (self.dist / name).write_bytes(data)

    def write_remote(self, files):
        for name in list(self.remote.iterdir()):
            if name.is_file():
                name.unlink()
        for name, data in files.items():
            (self.remote / name).write_bytes(data)

    def install_gh(self, fail_attest_for=None):
        gh = self.bin / "gh"
        template = """#!/bin/sh
set -eu
log="__LOG__"
remote="__REMOTE__"
fail="__FAIL__"
if [ "$1" = "release" ] && [ "$2" = "view" ]; then
  python3 - "$remote" <<'PY'
import json, os, sys
root=sys.argv[1]
assets=[{"name":n} for n in sorted(os.listdir(root)) if os.path.isfile(os.path.join(root,n))]
print(json.dumps({"assets":assets}))
PY
  exit 0
fi
if [ "$1" = "release" ] && [ "$2" = "download" ]; then
  dir=""
  while [ $# -gt 0 ]; do
    if [ "$1" = "-D" ]; then shift; dir="$1"; fi
    shift || true
  done
  cp "$remote"/* "$dir"/
  exit 0
fi
if [ "$1" = "attestation" ] && [ "$2" = "verify" ]; then
  shift 2
  target="$1"; shift
  printf '%s|%s\n' "$(basename "$target")" "$*" >> "$log"
  case "$*" in
    *"--signer-workflow L-K-M/Neutrodyne/.github/workflows/release.yml"*) ;;
    *) echo "signer workflow must include owner and repository" >&2; exit 2 ;;
  esac
  if [ -n "$fail" ] && [ "$(basename "$target")" = "$fail" ]; then
    echo "attestation failed for $(basename "$target")" >&2
    exit 1
  fi
  exit 0
fi
echo "unexpected gh invocation: $*" >&2
exit 90
"""
        script = (template.replace("__LOG__", str(self.log))
                  .replace("__REMOTE__", str(self.remote))
                  .replace("__FAIL__", fail_attest_for or ""))
        gh.write_text(script)
        gh.chmod(gh.stat().st_mode | stat.S_IEXEC)

    def run_verify(self):
        env = os.environ.copy()
        env["PATH"] = str(self.bin) + os.pathsep + env.get("PATH", "")
        return subprocess.run(
            ["python3", str(SCRIPT), "--tag", "v1.2.3", "--dist", str(self.dist),
             "--repo", "L-K-M/Neutrodyne", "--source-digest", "abc123"],
            capture_output=True, text=True, env=env,
        )

    def test_valid_case_passes(self):
        self.write_remote({"a.bin": b"aaa", "b.bin": b"bbb"})
        self.install_gh()
        r = self.run_verify()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("verified published release v1.2.3: 2 assets", r.stdout)
        lines = self.log.read_text().strip().splitlines()
        self.assertEqual([l.split('|', 1)[0] for l in lines], ["a.bin", "b.bin"])
        for line in lines:
            self.assertIn("-R L-K-M/Neutrodyne", line)
            self.assertIn("--signer-workflow L-K-M/Neutrodyne/.github/workflows/release.yml", line)
            self.assertIn("--source-ref refs/tags/v1.2.3", line)
            self.assertIn("--source-digest abc123", line)

    def test_missing_downloaded_asset_fails(self):
        self.write_remote({"a.bin": b"aaa"})
        self.install_gh()
        r = self.run_verify()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("missing published assets: b.bin", r.stderr + r.stdout)

    def test_extra_downloaded_asset_fails(self):
        self.write_remote({"a.bin": b"aaa", "b.bin": b"bbb", "c.bin": b"ccc"})
        self.install_gh()
        r = self.run_verify()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("extra published assets: c.bin", r.stderr + r.stdout)

    def test_tampered_downloaded_asset_fails(self):
        self.write_remote({"a.bin": b"aaa", "b.bin": b"TAMPERED"})
        self.install_gh()
        r = self.run_verify()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("downloaded asset bytes differ: b.bin", r.stderr + r.stdout)

    def test_attestation_failure_fails(self):
        self.write_remote({"a.bin": b"aaa", "b.bin": b"bbb"})
        self.install_gh(fail_attest_for="b.bin")
        r = self.run_verify()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("attestation verify failed for b.bin", r.stderr + r.stdout)


if __name__ == "__main__":
    unittest.main()
