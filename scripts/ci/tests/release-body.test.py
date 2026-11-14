#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The release body template and its renderer (09 Release body, PLAN M11 AC13).

A tag cannot be created in a unit test, so this builds a throwaway repository
with the real locks and changelog and renders the body for a sample tag. The
rendered body must carry every section AC13 names: the Android public-key
statement and the download-only warning, the unsigned desktop builds with each
OS's open step, the bundled OpenJDK runtime and LGPL FFmpeg sources, the server
run instructions, SHA256SUMS, the `gh release verify-asset` and
`gh attestation verify` commands and the Obtainium filter. A release without the
desktop or server products must leave those blocks out.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
RENDERER = REPO / "scripts/ci/make-release-body.py"

VERSION = "1.0.0"
VERSION_CODE = 1000095
DIGEST = "sha256:" + "a" * 64


class ReleaseBodyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self._fixture()

    def _fixture(self):
        """A repository root carrying the real locks and a sample changelog."""
        for relative in (
            "scripts/ci/make-release-body.py",
            "scripts/ci/release_assets.py",
            "scripts/ci/release-body-template.md",
            "release-assets.json",
            "desktopApp/runtime.lock",
            "desktopApp/wix.lock",
            "playback/native/native-components.lock",
            "youtube/ytdlp-desktop/python-components.lock",
            "sync/server/deploy/image-sources.lock.json",
        ):
            source = REPO / relative
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        (self.root / "gradle.properties").write_text(
            f"neutrodyne.versionName={VERSION}\n"
            f"neutrodyne.versionCode={VERSION_CODE}\n"
            "neutrodyne.repoUrl=https://github.com/L-K-M/Neutrodyne\n",
            encoding="utf-8",
        )
        changelogs = self.root / "changelogs"
        changelogs.mkdir()
        (changelogs / f"{VERSION_CODE}.txt").write_text(
            "First stable release: Android, desktop and the sync server.\n",
            encoding="utf-8",
        )

    def render(self, products, digest=DIGEST, ffmpeg_version="9.0.2"):
        out = self.root / "body.md"
        command = [
            "python3",
            str(self.root / "scripts/ci/make-release-body.py"),
            "--repo-root",
            str(self.root),
            "--tag",
            f"v{VERSION}",
            "--products",
            products,
            "--ffmpeg-version",
            ffmpeg_version,
            "--out",
            str(out),
        ]
        if digest is not None:
            command += ["--digest", digest]
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return out.read_text(encoding="utf-8")

    def test_full_release_carries_every_ac13_section(self):
        body = self.render("android,desktop,sources,ffmpeg-sources,server,server-sources")
        required = [
            "release builds signed with a key that is public in this repository",
            "Download only from this page",
            "not signed by a registered developer",
            "Open Anyway",
            "Run anyway",
            "Smart App Control must be off",
            "openjdk-25.0.4.1+1-temurin-sources.tar.gz",
            "LGPLv2.1",
            "ffmpeg-9.0.2-neutrodyne-src.tar.xz",
            "wix-3.11.2-src.tar.gz",
            "Sync server (optional, self-hosted)",
            "java -jar neutrodyne-server-1.0.0.jar serve",
            "ghcr.io/l-k-m/neutrodyne-server:1.0.0 (digest " + DIGEST + ")",
            "sha256sum --check --ignore-missing SHA256SUMS",
            "gh attestation verify <file> -R L-K-M/Neutrodyne --signer-workflow L-K-M/Neutrodyne/.github/workflows/release.yml --source-ref refs/tags/v1.0.0 --source-digest <commit-sha>",
            "gh attestation verify oci://ghcr.io/l-k-m/neutrodyne-server@" + DIGEST,
            "Obtainium (Android)",
            r"neutrodyne-.*-arm64-v8a\.apk$",
            "Guides: https://github.com/L-K-M/Neutrodyne#install-and-update",
        ]
        for needle in required:
            with self.subTest(needle=needle):
                self.assertIn(needle, body)
        self.assertNotIn("{{", body)
        self.assertNotIn("{%", body)
        self.assertNotIn("gh release verify-asset", body)
        self.assertNotIn("immutable", body.lower())

    def test_android_only_leaves_out_desktop_and_server(self):
        body = self.render("android", digest=None)
        self.assertIn("Android — pick the APK for your device:", body)
        self.assertNotIn("Desktop — pick the file for your computer:", body)
        self.assertNotIn("Sync server (optional, self-hosted)", body)
        self.assertNotIn("gh attestation verify oci://", body)
        self.assertIn("Obtainium (Android)", body)
        self.assertNotIn("gh release verify-asset", body)
        self.assertNotIn("immutable", body.lower())

    def test_ffmpeg_link_names_the_lock_version(self):
        body = self.render("android,desktop,sources,ffmpeg-sources,server,server-sources")
        self.assertIn(
            "https://github.com/L-K-M/Neutrodyne/releases/download/v1.0.0/"
            "ffmpeg-9.0.2-neutrodyne-src.tar.xz",
            body,
        )

    def test_a_stale_ffmpeg_version_is_rejected(self):
        out = self.root / "body.md"
        result = subprocess.run(
            [
                "python3",
                str(self.root / "scripts/ci/make-release-body.py"),
                "--repo-root",
                str(self.root),
                "--tag",
                f"v{VERSION}",
                "--products",
                "android",
                "--ffmpeg-version",
                "0.0.0",
                "--out",
                str(out),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("FFmpeg", result.stderr)

    def test_server_without_digest_is_rejected(self):
        result = subprocess.run(
            [
                "python3",
                str(self.root / "scripts/ci/make-release-body.py"),
                "--repo-root",
                str(self.root),
                "--tag",
                f"v{VERSION}",
                "--products",
                "android,server",
                "--out",
                str(self.root / "body.md"),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("digest", result.stderr)


if __name__ == "__main__":
    unittest.main()
