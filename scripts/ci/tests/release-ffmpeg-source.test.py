#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The MD1b FFmpeg source offer in the release pipeline (11 FFmpeg LGPL obligations).

A tag cannot be created in a unit test, so this exercises the three parts that must
agree without one:

  * release-assets.json's `ffmpeg-sources` group names the bundle, and its `{ffmpeg}`
    placeholder expands to the version in playback/native/native-components.lock;
  * release.yml's `sources` job builds the bundle with `:playback:native:assembleFfmpegSource`
    (and re-checks the licence envelope) and `publish` needs and downloads it;
  * verify-tag.sh expands `{ffmpeg}` from the same lock, so the asset-set comparison in
    `publish` expects the exact file the bundle step produces.

The verify-tag half runs the real script in a throwaway git repository with a fake `gh`,
so the expansion is the shipped code path, not a copy of it.
"""

import json
import re
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
ASSETS = REPO / "release-assets.json"
RELEASE = REPO / ".github/workflows/release.yml"
VERIFY_TAG = REPO / "scripts/ci/verify-tag.sh"
NATIVE_LOCK = REPO / "playback/native/native-components.lock"
SERVER_SOURCES_LOCK = REPO / "sync/server/deploy/image-sources.lock.json"

VERSION = "0.1.0"
VERSION_CODE = 10095


def ffmpeg_version():
    section = False
    for line in NATIVE_LOCK.read_text(encoding="utf-8").splitlines():
        if line.startswith("["):
            section = line == "[ffmpeg]"
            continue
        if section and line.startswith("version"):
            return line.split("=", 1)[1].strip().strip('"')
    raise AssertionError("native-components.lock has no [ffmpeg] version")


def bundle_name():
    return f"ffmpeg-{ffmpeg_version()}-neutrodyne-src.tar.xz"


class ReleaseFfmpegSourceTest(unittest.TestCase):
    def test_group_names_the_bundle_with_the_lock_version(self):
        doc = json.loads(ASSETS.read_text(encoding="utf-8"))
        group = doc["groups"]["ffmpeg-sources"]
        self.assertEqual(["ffmpeg-{ffmpeg}-neutrodyne-src.tar.xz"], group)
        self.assertEqual(bundle_name(), group[0].replace("{ffmpeg}", ffmpeg_version()))
        self.assertEqual("MD1b", doc["milestones"]["ffmpeg-sources"])

    def test_workflow_builds_and_publishes_the_bundle(self):
        text = RELEASE.read_text(encoding="utf-8")
        self.assertIn("assembleFfmpegSource", text)
        self.assertIn("checkNativeLicences", text)
        # The publish job must depend on the sources job and download its artifact.
        publish = text.split("  publish:", 1)[1]
        needs = re.search(r"^    needs: \[([^\]]*)\]", publish, re.MULTILINE).group(1)
        self.assertIn("sources", [n.strip() for n in needs.split(",")])
        self.assertIn("pattern: sources", publish)
        # The bundle job must produce the asset the group names.
        sources = text.split("  sources:", 1)[1].split("  # TODO (M0b)", 1)[0]
        self.assertIn(":playback:native:assembleFfmpegSource", sources)
        self.assertIn("ffmpeg-*-neutrodyne-src.tar.xz", sources)

    def test_every_consumed_verify_tag_output_is_declared(self):
        # A `needs.verify-tag.outputs.X` that the job does not declare is an empty string,
        # which would turn the release body's FFmpeg source link into `ffmpeg--...`.
        text = RELEASE.read_text(encoding="utf-8")
        job = text.split("  verify-tag:", 1)[1]
        declared_block = job.split("    outputs:", 1)[1].split("    permissions:", 1)[0]
        declared = set(re.findall(r"^\s+([A-Za-z_]+):", declared_block, re.MULTILINE))
        consumed = set(re.findall(r"needs\.verify-tag\.outputs\.([A-Za-z_]+)", text))
        self.assertIn("ffmpeg_version", consumed)
        self.assertEqual(set(), consumed - declared)

    def test_verify_tag_expands_the_bundle_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture_repo(root)
            env = {
                **{k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "GH_"))},
                "PATH": str(root / "fakebin") + os.pathsep + os.environ["PATH"],
            }
            result = subprocess.run(
                ["bash", str(root / "scripts/ci/verify-tag.sh"), f"v{VERSION}",
                 "--products=android,ffmpeg-sources"],
                cwd=root, env=env, capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(bundle_name(), result.stdout)
            self.assertIn(f"ffmpeg_version={ffmpeg_version()}", result.stdout)

    def _fixture_repo(self, root: Path):
        (root / "changelogs").mkdir(parents=True)
        (root / "playback/native").mkdir(parents=True)
        (root / "sync/server/deploy").mkdir(parents=True)
        (root / "scripts/ci").mkdir(parents=True)
        (root / "fakebin").mkdir()
        (root / "gradle.properties").write_text(
            f"neutrodyne.versionName={VERSION}\n"
            f"neutrodyne.versionCode={VERSION_CODE}\n"
            "neutrodyne.repoUrl=https://github.com/L-K-M/Neutrodyne\n"
            "neutrodyne.youtubeEngine=false\n",
            encoding="utf-8",
        )
        (root / f"changelogs/{VERSION_CODE}.txt").write_text("Fixture notes.\n", encoding="utf-8")
        shutil.copy2(NATIVE_LOCK, root / "playback/native/native-components.lock")
        shutil.copy2(VERIFY_TAG, root / "scripts/ci/verify-tag.sh")
        shutil.copy2(ASSETS, root / "release-assets.json")
        shutil.copy2(SERVER_SOURCES_LOCK, root / "sync/server/deploy/image-sources.lock.json")
        (root / "fakebin/gh").write_text(
            "#!/usr/bin/env python3\n"
            "import sys\n"
            "args = sys.argv[1:]\n"
            "if any('releases/latest' in a for a in args):\n"
            "    print('Not Found', file=sys.stderr); sys.exit(1)\n"
            "if any('workflows/ci.yml/runs' in a for a in args):\n"
            "    print('1'); sys.exit(0)\n"
            "print('{}'); sys.exit(0)\n",
            encoding="utf-8",
        )
        (root / "fakebin/gh").chmod(0o755)
        run = lambda *args: subprocess.run(args, cwd=root, check=True, capture_output=True)
        run("git", "init", "-q", "-b", "main", ".")
        run("git", "config", "user.email", "test@example.com")
        run("git", "config", "user.name", "Test")
        run("git", "add", "-A")
        run("git", "commit", "-q", "-m", "fixture")
        run("git", "tag", f"v{VERSION}")
        # verify-tag fetches origin's main; pointing origin at the fixture satisfies it.
        run("git", "remote", "add", "origin", str(root))


if __name__ == "__main__":
    unittest.main()
