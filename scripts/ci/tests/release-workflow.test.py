#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The release workflow's structure (09 release.yml, D79).

The workflow only runs on a tag, so its guarantees are checked here as text:
every action is pinned by a full commit SHA, the publish job verifies the image
and its sources before it creates the draft, attests every asset with
actions/attest-build-provenance, and image-tags moves the GHCR tags only after
publish. The release body comes from the template file, not from an inline heredoc.
"""

from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[3]
WORKFLOW = REPO / ".github/workflows/release.yml"
ATTEST_BUILD_PROVENANCE = "actions/attest-build-provenance@4d101475d8b20a2381f78447822ac1eab6504dd8"

SHA_PIN = re.compile(r"uses:\s+[^\s@]+@[0-9a-f]{40}\s+#\s+v\S+")


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_every_action_is_pinned_by_full_sha(self):
        uses = re.findall(r"^\s*(?:-\s+)?uses:\s+(\S+)", self.text, re.MULTILINE)
        self.assertGreater(len(uses), 10)
        for use in uses:
            with self.subTest(use=use):
                self.assertRegex(use, r"^[^@]+@[0-9a-f]{40}$")
        # Every pin carries the tag in a trailing comment (Renovate's digest pin
        # convention; the repo's own check is the comment, not a separate tool).
        pinned_lines = [line for line in self.text.splitlines() if "uses:" in line]
        for line in pinned_lines:
            with self.subTest(line=line):
                self.assertRegex(line, SHA_PIN)

    def test_jobs_and_order(self):
        for job in (
            "verify-tag",
            "android",
            "desktop",
            "sources",
            "server-jar",
            "server-sources",
            "server-image",
            "server-image-manifest",
            "publish",
            "image-tags",
        ):
            with self.subTest(job=job):
                self.assertIsNotNone(
                    re.search(rf"^  {job}:$", self.text, re.MULTILINE), job
                )
        publish = self.text.split("  publish:", 1)[1].split("  image-tags:", 1)[0]
        for need in (
            "verify-tag",
            "android",
            "desktop",
            "sources",
            "server-jar",
            "server-sources",
            "server-image-manifest",
        ):
            with self.subTest(need=need):
                self.assertIn(need, publish.split("steps:", 1)[0])
        image_tags = self.text.split("  image-tags:", 1)[1]
        self.assertIn("needs: [verify-tag, publish, server-image-manifest]", image_tags)

    def test_publish_verifies_before_it_creates_the_draft(self):
        publish = self.text.split("  publish:", 1)[1].split("  image-tags:", 1)[0]
        verify = publish.index("Verify the image and exact sources before publication")
        runtime = publish.index("Runtime exception checks")
        assets = publish.index("Compare the asset set with verify-tag's expected list")
        draft = publish.index('gh release create "$TAG"')
        self.assertLess(verify, draft)
        self.assertLess(runtime, draft)
        self.assertLess(assets, draft)

    def test_every_asset_is_attested_with_build_provenance(self):
        self.assertIn(ATTEST_BUILD_PROVENANCE, self.text)
        publish = self.text.split("  publish:", 1)[1].split("  image-tags:", 1)[0]
        self.assertIn("subject-path: dist/**", publish)
        # The image is attested by digest in its own job.
        manifest = self.text.split("  server-image-manifest:", 1)[1].split("  publish:", 1)[0]
        self.assertIn("subject-name: ${{ steps.index.outputs.repository }}", manifest)
        self.assertIn("subject-digest: ${{ steps.index.outputs.digest }}", manifest)
        self.assertIn("push-to-registry: true", manifest)

    def test_publish_downloads_every_build_artifact(self):
        publish = self.text.split("  publish:", 1)[1].split("  image-tags:", 1)[0]
        # main's artifact names: release-android, release-server,
        # release-server-sources, desktop-<target> and sources.
        for pattern in ("release-*", "desktop-*", "sources"):
            with self.subTest(pattern=pattern):
                self.assertIn(f"pattern: {pattern}", publish.split("steps:", 1)[1])
        for artifact in (
            "name: release-android\n",
            "name: release-server\n",
            "name: release-server-sources\n",
            "name: desktop-${{ matrix.target }}\n",
            "name: sources\n",
        ):
            with self.subTest(artifact=artifact):
                self.assertIn(artifact, self.text)
        manifest = self.text.split("  server-image-manifest:", 1)[1].split("  publish:", 1)[0]
        self.assertIn("server-digest-*", manifest)

    def test_release_body_comes_from_the_template_file(self):
        publish = self.text.split("  publish:", 1)[1].split("  image-tags:", 1)[0]
        self.assertIn("scripts/ci/make-release-body.py", publish)
        self.assertNotIn("cat >> body.md", publish)
        self.assertTrue((REPO / "scripts/ci/release-body-template.md").is_file())

    def test_products_cover_every_release_assets_group(self):
        import json

        groups = json.loads((REPO / "release-assets.json").read_text(encoding="utf-8"))["groups"]
        for group in groups:
            with self.subTest(group=group):
                self.assertIn(group, self.text)

    def test_image_tags_share_the_publish_concurrency_group(self):
        publish = self.text.split("  publish:", 1)[1].split("  image-tags:", 1)[0]
        image_tags = self.text.split("  image-tags:", 1)[1]
        self.assertIn("group: release-publish", publish)
        self.assertIn("group: release-publish", image_tags)


if __name__ == "__main__":
    unittest.main()
