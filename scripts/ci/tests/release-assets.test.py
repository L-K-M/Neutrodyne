#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""verify-tag's expected asset set and its lock-driven placeholders (09 release.yml).

A tag cannot be created in a unit test, so this runs the real script in a
throwaway git repository with a fake `gh`. The expected set must expand the
lock-driven placeholders, leave out a component whose placeholder does not ship
(the PBS source before MD3, the server runtime tarball while it matches the
desktop runtime's source), and fail on an unknown product group.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
VERIFY_TAG = REPO / "scripts/ci/verify-tag.sh"
ASSETS = REPO / "release-assets.json"

VERSION = "0.1.0"
VERSION_CODE = 10095


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self._fixture_repo(self.root)

    def _fixture_repo(self, root: Path):
        (root / "changelogs").mkdir(parents=True)
        (root / "desktopApp").mkdir(parents=True)
        (root / "playback/native").mkdir(parents=True)
        (root / "youtube/ytdlp-desktop").mkdir(parents=True)
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
        for relative in (
            "desktopApp/runtime.lock",
            "desktopApp/wix.lock",
            "playback/native/native-components.lock",
            "youtube/ytdlp-desktop/python-components.lock",
            "sync/server/deploy/image-sources.lock.json",
        ):
            shutil.copy2(REPO / relative, root / relative)
        shutil.copy2(VERIFY_TAG, root / "scripts/ci/verify-tag.sh")
        shutil.copy2(REPO / "scripts/ci/release_assets.py", root / "scripts/ci/release_assets.py")
        shutil.copy2(ASSETS, root / "release-assets.json")
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

    def verify(self, products, tag=f"v{VERSION}", github_output=None):
        env = {
            **{k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "GH_"))},
            "PATH": str(self.root / "fakebin") + os.pathsep + os.environ["PATH"],
        }
        if github_output is not None:
            env["GITHUB_OUTPUT"] = str(github_output)
        return subprocess.run(
            ["bash", str(self.root / "scripts/ci/verify-tag.sh"), tag, f"--products={products}"],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )

    def test_full_product_set_expands_the_locks(self):
        result = self.verify("android,desktop,sources,ffmpeg-sources,server,server-sources")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        expected = [
            "neutrodyne-0.1.0-arm64-v8a.apk",
            "neutrodyne-0.1.0-x86_64.apk",
            "neutrodyne-0.1.0-armeabi-v7a.apk",
            "neutrodyne-0.1.0-r8-mapping.zip",
            "neutrodyne-0.1.0-windows-x64.msi",
            "neutrodyne-0.1.0-windows-x64.zip",
            "neutrodyne-0.1.0-macos-arm64.zip",
            "neutrodyne-0.1.0-linux-x64.deb",
            "neutrodyne-0.1.0-linux-x64.rpm",
            "neutrodyne-0.1.0-linux-x64.tar.gz",
            "neutrodyne-0.1.0-linux-arm64.deb",
            "neutrodyne-0.1.0-linux-arm64.rpm",
            "neutrodyne-0.1.0-linux-arm64.tar.gz",
            "openjdk-25.0.4.1+1-temurin-sources.tar.gz",
            "RUNTIME-SOURCES.md",
            "wix-3.11.2-src.tar.gz",
            "ffmpeg-9.0.2-neutrodyne-src.tar.xz",
            "neutrodyne-server-0.1.0.jar",
            "neutrodyne-server-image-sources-0.1.0.tar.xz",
            "SERVER-RUNTIME-SOURCES.md",
        ]
        for asset in expected:
            with self.subTest(asset=asset):
                self.assertIn(asset, result.stdout)
        # The PBS source does not ship (no [pbsSource] in the lock), and the
        # server runtime tarball matches the desktop one, so neither is expected.
        self.assertNotIn("python-build-standalone-", result.stdout)
        self.assertNotIn("openjdk-25.0.4.1+1-temurin-sources.tar.gz\nopenjdk-", result.stdout)
        self.assertIn("ffmpeg_version=9.0.2", result.stdout)

    def test_android_only(self):
        result = self.verify("android")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for asset in (
            "neutrodyne-0.1.0-arm64-v8a.apk",
            "neutrodyne-0.1.0-x86_64.apk",
            "neutrodyne-0.1.0-armeabi-v7a.apk",
            "neutrodyne-0.1.0-r8-mapping.zip",
        ):
            self.assertIn(asset, result.stdout)
        self.assertNotIn("neutrodyne-0.1.0-windows-x64.msi", result.stdout)
        self.assertNotIn("openjdk-", result.stdout)
        self.assertNotIn("neutrodyne-server-0.1.0.jar", result.stdout)

    def test_unknown_group_fails(self):
        result = self.verify("android,does-not-exist")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no group", result.stderr)

    def test_tag_version_mismatch_fails(self):
        result = self.verify("android", tag="v9.9.9")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("versionName", result.stderr)

    def test_expected_assets_output_is_a_heredoc(self):
        output = self.root / "github-output.txt"
        result = self.verify("android", github_output=output)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        text = output.read_text(encoding="utf-8")
        self.assertIn("expected_assets<<__NL_EOF__", text)
        self.assertIn("neutrodyne-0.1.0-arm64-v8a.apk", text)
        self.assertIn("ffmpeg_version=9.0.2", text)


if __name__ == "__main__":
    unittest.main()
