#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Boundary tests for scripts/ci/apply-screenshots.sh (09 CI scripts).

Each case runs the real script in a temporary git checkout that carries one
module with a build.gradle.kts and a screenshots tree; a fake `gh` on PATH
answers `run view`, `repo view` and `run download` with fixture JSON and a
locally built screenshots-<sha>.zip. Nothing touches the network or Gradle.
Every rejection case asserts the checkout is byte-identical afterwards:
validation must finish before the first write.
"""

import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "apply-screenshots.sh"
PNG = b"\x89PNG\r\n\x1a\n"
RUN_ID = "424242"
REPO_SLUG = "owner/repo"

FAKE_GH = """#!/usr/bin/env python3
import json, os, shutil, sys
args = sys.argv[1:]
log = os.environ.get("FAKE_GH_LOG")
if log:
    with open(log, "a") as fh:
        fh.write(json.dumps(args) + "\\n")
if args[:2] == ["run", "view"]:
    print(os.environ["FAKE_GH_RUN"])
elif args[:2] == ["repo", "view"]:
    print(json.dumps({"nameWithOwner": os.environ["FAKE_GH_REPO"]}))
elif args[:2] == ["run", "download"]:
    dest = args[args.index("--dir") + 1]
    target = os.path.join(dest, os.environ["FAKE_GH_ARTIFACT"])
    os.makedirs(target, exist_ok=True)
    shutil.copy(os.environ["FAKE_GH_ZIP"],
                os.path.join(target, os.environ["FAKE_GH_ZIPNAME"]))
else:
    sys.exit("fake gh: unexpected " + " ".join(args))
"""


def git(root, *args):
    return subprocess.run(["git", "-C", str(root), *args], check=True,
                          capture_output=True, text=True)


def symlink_member(name):
    info = zipfile.ZipInfo(name)
    info.create_system = 3
    info.external_attr = (stat.S_IFLNK | 0o777) << 16
    return info


class ApplyScreenshotsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.repo = base / "repo"
        (self.repo / "mod/app/src").mkdir(parents=True)
        (self.repo / "mod/app/screenshots/android").mkdir(parents=True)
        (self.repo / "mod/app/build.gradle.kts").write_text("// fixture\n")
        (self.repo / "mod/app/src/main.kt").write_text("fun fixture() = 1\n")
        (self.repo / "mod/app/screenshots/android/old.png").write_bytes(PNG + b"OLD")
        (self.repo / "mod/app/screenshots/android/stale.png").write_bytes(PNG + b"STALE")
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@example.com")
        git(self.repo, "config", "user.name", "Fixture")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "init")
        self.head = git(self.repo, "rev-parse", "HEAD").stdout.strip()

        bindir = base / "bin"
        bindir.mkdir()
        gh = bindir / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        self.gh_log = base / "gh.log"
        self.zips = base / "zips"
        self.zips.mkdir()

    def tree(self):
        return {
            str(p.relative_to(self.repo)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(self.repo.rglob("*"))
            if p.is_file() and ".git" not in p.parts
        }

    def run_apply(self, members, manifest_sha=None,
                  manifest_updates=None, artifact=None, zip_name=None,
                  run_updates=None, mutate_zip=None):
        artifact = artifact or f"screenshots-{self.head}"
        zip_name = zip_name or artifact + ".zip"
        zpath = self.zips / zip_name
        manifest = {
            "schema": 1,
            "repository": REPO_SLUG,
            "tier": "pr",
            "sourceSha": manifest_sha or self.head,
            "sourceRef": "fixture",
            "requestedRef": "fixture",
            "workflowRef": "refs/heads/main",
            "workflowSha": "0" * 40,
            "runId": RUN_ID,
            "runUrl": "https://example.invalid/run/1",
        }
        manifest.update(manifest_updates or {})
        with zipfile.ZipFile(zpath, "w") as zf:
            zf.writestr("manifest.json", json.dumps(manifest))
            for member in members:
                if isinstance(member, zipfile.ZipInfo):
                    zf.writestr(member, b"target")
                else:
                    zf.writestr(member[0], member[1])
        if mutate_zip:
            mutate_zip(zpath)
        run = {
            "conclusion": "success",
            "status": "completed",
            "event": "workflow_dispatch",
            "workflowName": "record-screenshots",
            "headBranch": "fixture",
            "headSha": self.head,
            "url": "https://example.invalid/run/1",
        }
        run.update(run_updates or {})
        env = {k: v for k, v in os.environ.items()
               if not k.startswith(("FAKE_GH_", "GH_", "GITHUB_"))}
        env.update(
            PATH=str(self.zips.parent / "bin") + os.pathsep + os.environ["PATH"],
            FAKE_GH_LOG=str(self.gh_log),
            FAKE_GH_RUN=json.dumps(run),
            FAKE_GH_REPO=REPO_SLUG,
            FAKE_GH_ZIP=str(zpath),
            FAKE_GH_ARTIFACT=artifact,
            FAKE_GH_ZIPNAME=zip_name,
        )
        return subprocess.run(["bash", str(SCRIPT), RUN_ID], cwd=self.repo,
                              env=env, capture_output=True, text=True,
                              timeout=60)

    def assert_rejected(self, result, before):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(before, self.tree(),
                         "rejected apply still modified the checkout")

    def test_valid_artifact_applies_exact_png_bytes(self):
        before = self.tree()
        result = self.run_apply([
            ("mod/app/screenshots/android/new.png", PNG + b"NEW"),
            ("mod/app/screenshots/android/old.png", PNG + b"REPLACED"),
            ("mod/app/screenshots/desktop/d1.png", PNG + b"D1"),
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        shot = self.repo / "mod/app/screenshots"
        self.assertEqual(PNG + b"NEW", (shot / "android/new.png").read_bytes())
        self.assertEqual(PNG + b"REPLACED", (shot / "android/old.png").read_bytes())
        self.assertEqual(PNG + b"D1", (shot / "desktop/d1.png").read_bytes())
        # the script never stages or commits
        self.assertEqual("", git(self.repo, "diff", "--cached",
                                 "--name-only").stdout)
        self.assertEqual(self.head,
                         git(self.repo, "rev-parse", "HEAD").stdout.strip())
        self.assertIn("1 replaced", result.stdout)
        # a local golden absent from the artifact stays in place and is listed
        self.assertIn("mod/app/screenshots/android/stale.png", result.stdout)
        self.assertEqual(PNG + b"STALE", (shot / "android/stale.png").read_bytes())

    def test_workflow_ref_can_differ_from_recorded_source(self):
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")],
            run_updates={"headSha": "e" * 40, "headBranch": "main"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_foreign_manifest_run_rejects_before_copy(self):
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")],
            manifest_updates={"runId": "999999"})
        self.assert_rejected(result, before)

    def test_declared_size_over_budget_rejects_before_read(self):
        name = "mod/app/screenshots/android/new.png"
        over_budget = 30 * 1024 * 1024 + 1
        local_size_offset, central_size_offset = 22, 24

        def inflate_header(path):
            with zipfile.ZipFile(path) as archive:
                info = archive.getinfo(name)
                central_start = archive.start_dir
            data = bytearray(path.read_bytes())
            central = data.index(name.encode(), central_start) - zipfile.sizeCentralDir
            struct.pack_into("<I", data, info.header_offset + local_size_offset, over_budget)
            struct.pack_into("<I", data, central + central_size_offset, over_budget)
            path.write_bytes(data)

        before = self.tree()
        result = self.run_apply([(name, PNG + b"NEW")], mutate_zip=inflate_header)
        self.assert_rejected(result, before)
        self.assertIn("budget", result.stderr)

    def test_manifest_source_sha_mismatch_rejects_before_copy(self):
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")],
            manifest_sha="f" * 40)
        self.assert_rejected(result, before)

    def test_failed_run_rejects_before_download(self):
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")],
            run_updates={"conclusion": "failure"})
        self.assert_rejected(result, before)
        calls = self.gh_log.read_text() if self.gh_log.exists() else ""
        self.assertNotIn("download", calls)

    def test_wrong_workflow_rejects(self):
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")],
            run_updates={"workflowName": "ci"})
        self.assert_rejected(result, before)

    def test_zip_name_manifest_mismatch_rejects(self):
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")],
            zip_name="screenshots-" + "a" * 40 + ".zip")
        self.assert_rejected(result, before)

    def test_traversal_member_rejects_before_copy(self):
        before = self.tree()
        result = self.run_apply([
            ("mod/app/screenshots/android/../../evil.png", PNG + b"EVIL"),
            ("/abs/path.png", PNG + b"ABS"),
            ("mod\\app\\win.png", PNG + b"WIN"),
        ])
        self.assert_rejected(result, before)
        self.assertFalse((self.repo / "mod/app/evil.png").exists())

    def test_outside_screenshots_member_rejects(self):
        before = self.tree()
        result = self.run_apply([
            ("mod/app/src/evil.png", PNG + b"SRC"),
            ("screenshots/android/nomodule.png", PNG + b"TOP"),
            ("docs/screenshots/android/x.png", PNG + b"DOC"),
        ])
        self.assert_rejected(result, before)

    def test_symlink_member_rejects_before_copy(self):
        before = self.tree()
        result = self.run_apply(
            [symlink_member("mod/app/screenshots/android/link.png")])
        self.assert_rejected(result, before)

    def test_duplicate_member_rejects_before_copy(self):
        before = self.tree()
        result = self.run_apply([
            ("mod/app/screenshots/android/dup.png", PNG + b"ONE"),
            ("mod/app/screenshots/android/dup.png", PNG + b"TWO"),
        ])
        self.assert_rejected(result, before)

    def test_symlinked_destination_rejects_before_copy(self):
        outside = Path(self.tmp.name) / "outside"
        outside.mkdir()
        (self.repo / "mod/bad").mkdir()
        (self.repo / "mod/bad/build.gradle.kts").write_text("// fixture\n")
        os.symlink(outside, self.repo / "mod/bad/screenshots")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "add symlinked module")
        self.head = git(self.repo, "rev-parse", "HEAD").stdout.strip()
        before = self.tree()
        result = self.run_apply(
            [("mod/bad/screenshots/android/e.png", PNG + b"EVIL")])
        self.assert_rejected(result, before)
        self.assertFalse((outside / "android/e.png").exists())

    def test_dirty_source_tree_rejects_before_copy(self):
        with open(self.repo / "mod/app/src/main.kt", "a") as fh:
            fh.write("fun dirty() = 2\n")
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")])
        self.assert_rejected(result, before)
        self.assertFalse((self.repo / "mod/app/screenshots/android/new.png").exists())

    def test_dirty_golden_png_is_allowed(self):
        (self.repo / "mod/app/screenshots/android/old.png").write_bytes(PNG + b"LOCAL")
        result = self.run_apply(
            [("mod/app/screenshots/android/new.png", PNG + b"NEW")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(PNG + b"NEW",
                         (self.repo / "mod/app/screenshots/android/new.png").read_bytes())

    def test_over_budget_set_rejects_before_copy(self):
        members = [(f"mod/app/screenshots/android/f{i}.png", PNG + bytes([i % 256]))
                   for i in range(801)]
        before = self.tree()
        result = self.run_apply(members)
        self.assert_rejected(result, before)

    def test_non_png_member_rejects_before_copy(self):
        before = self.tree()
        result = self.run_apply(
            [("mod/app/screenshots/android/fake.png", b"#!/bin/sh\necho hi")])
        self.assert_rejected(result, before)


if __name__ == "__main__":
    unittest.main()
