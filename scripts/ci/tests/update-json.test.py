#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Run the real producer/validator in isolated roots, never rewrite checkout versions."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]


class UpdateJsonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts/ci").mkdir(parents=True)
        for name in ("make-update-json.sh", "check-update-json.sh", "update_manifest_assets.py"):
            shutil.copy2(REPO / "scripts/ci" / name, self.root / "scripts/ci" / name)
        shutil.copy2(REPO / "release-assets.json", self.root)
        (self.root / "changelogs").mkdir()
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.configure("0.12.3", 120395)

    def configure(self, version, code):
        self.version = version
        (self.root / "gradle.properties").write_text(
            f"neutrodyne.versionName={version}\nneutrodyne.versionCode={code}\n"
            "neutrodyne.repoUrl=https://github.com/L-K-M/Neutrodyne\n")
        (self.root / f"changelogs/{code}.txt").write_text("Fixture notes, not HTML.\n")
        for path in self.assets.iterdir():
            path.unlink()
        for abi in ("arm64-v8a", "x86_64", "armeabi-v7a"):
            (self.assets / f"neutrodyne-{version}-{abi}.apk").write_bytes(b"synthetic apk\n")

    def run_script(self, name, *args, succeeds=True, image=""):
        result = subprocess.run(["bash", str(self.root / "scripts/ci" / name), *map(str, args)],
                                env={**os.environ, "NEUTRODYNE_SERVER_IMAGE": image}, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)

    def generate(self, **kwargs):
        self.run_script("make-update-json.sh", "v" + self.version, self.assets, **kwargs)
        if kwargs.get("succeeds", True):
            return json.loads((self.assets / "neutrodyne-update.json").read_text())

    def validate(self, doc, succeeds=True):
        path = self.assets / "neutrodyne-update.json"
        path.write_text(json.dumps(doc))
        self.run_script("check-update-json.sh", path, self.assets, "v" + self.version, succeeds=succeeds)

    def desktop(self):
        inventory = json.loads((self.root / "release-assets.json").read_text())
        ext = "dmg" if int(self.version.split(".")[0]) >= 1 else "zip"
        for pattern in inventory["groups"]["desktop"]:
            (self.assets / pattern.replace("{v}", self.version).replace("{mac_ext}", ext)).write_bytes(b"synthetic desktop\n")

    def test_android_only_and_version_examples(self):
        for version, code in (("0.1.0", 10095), ("1.0.0", 1000095), ("1.2.3", 1020395), ("0.99.99", 999995)):
            with self.subTest(version=version):
                self.configure(version, code)
                doc = self.generate()
                self.assertNotIn("desktop", doc)
                self.assertNotIn("server", doc)
                self.validate(doc)

    def test_full_desktop_before_and_after_one(self):
        for version, code, kind in (("0.12.3", 120395, "mac-zip"), ("1.0.0", 1000095, "dmg")):
            self.configure(version, code)
            self.desktop()
            doc = self.generate()
            self.assertEqual(len(doc["desktop"]), 9)
            self.assertEqual(next(a["kind"] for a in doc["desktop"] if a["os"] == "macos"), kind)
            self.validate(doc)

    def test_partial_desktop_rejected_in_producer_and_validator(self):
        doc = self.generate()
        (self.assets / f"neutrodyne-{self.version}-windows-x64.zip").write_bytes(b"partial")
        self.generate(succeeds=False)
        self.validate(doc, succeeds=False)

    def test_unknown_duplicate_missing_and_extra_targets(self):
        self.desktop()
        doc = self.generate()
        for change in (lambda d: d["desktop"].pop(),
                       lambda d: d["desktop"].append(copy.deepcopy(d["desktop"][0])),
                       lambda d: d["desktop"][0].update(arch="future"),
                       lambda d: d["desktop"].clear(),
                       lambda d: d["desktop"][0].update(file="other.zip")):
            bad = copy.deepcopy(doc)
            change(bad)
            self.validate(bad, succeeds=False)

    def test_corrupt_missing_and_empty_assets(self):
        doc = self.generate()
        path = self.assets / doc["apks"][0]["file"]
        path.write_bytes(b"corrupted same length or different")
        self.validate(doc, succeeds=False)
        path.unlink()
        self.validate(doc, succeeds=False)
        self.generate(succeeds=False)
        path.write_bytes(b"")
        self.validate(self.generate(), succeeds=False)

    def test_hash_size_url_and_strict_numeric_boundaries(self):
        doc = self.generate()
        for field, value in (("file", "unknown.apk"), ("size", True), ("size", 1), ("sha256", "0" * 64),
                             ("url", doc["apks"][0]["url"].replace("/releases/", "//releases/")),
                             ("url", doc["apks"][0]["url"] + "?token=x"),
                             ("url", doc["apks"][0]["url"].replace("github.com", "github.com.evil")),
                             ("url", doc["apks"][0]["url"].replace("/download/", "/%2e%2e/download/"))):
            bad = copy.deepcopy(doc)
            bad["apks"][0][field] = value
            self.validate(bad, succeeds=False)
        explicit_port = copy.deepcopy(doc)
        explicit_port["releaseUrl"] = doc["releaseUrl"].replace("github.com", "github.com:443")
        self.validate(explicit_port)

    def test_notes_schema_date_and_additive_fields(self):
        doc = self.generate()
        additive = copy.deepcopy(doc)
        additive["future"] = {"allowed": True}
        self.validate(additive)
        for field, value in (("schema", True), ("minSdk", 26.0),
                             ("published", "not a date"), ("notes", "swapped")):
            bad = copy.deepcopy(doc)
            bad[field] = value
            self.validate(bad, succeeds=False)
        for notes in ("", "x" * 501):
            (self.root / "changelogs/120395.txt").write_text(notes)
            self.generate(succeeds=False)

    def test_server_digest_owner_and_missing_digest(self):
        (self.assets / f"neutrodyne-server-{self.version}.jar").write_bytes(b"synthetic jar")
        for image in ("", "ghcr.io/other/neutrodyne-server@sha256:" + "a" * 64,
                      "ghcr.io/l-k-m/neutrodyne-server:latest", "ghcr.io/l-k-m/neutrodyne-server@sha256:bad"):
            self.generate(succeeds=False, image=image)
        doc = self.generate(image="ghcr.io/l-k-m/neutrodyne-server@sha256:" + "a" * 64)
        self.validate(doc)
        doc["server"]["image"] = "ghcr.io/other/neutrodyne-server@sha256:" + "a" * 64
        self.validate(doc, succeeds=False)


if __name__ == "__main__":
    unittest.main()
