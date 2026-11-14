#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Safety and portability checks for the desktop Python archive extractor."""

import io
import os
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import unittest

EXTRACTOR = pathlib.Path(__file__).resolve().parents[1] / "extract-python-archive.py"
TRIM = EXTRACTOR.with_name("trim-python.sh")


class ArchiveExtractionTest(unittest.TestCase):
    def run_archive(self, members):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            archive = root / "input.tar.gz"
            output = root / "out"
            output.mkdir()
            with tarfile.open(archive, "w:gz") as tar:
                for kind, name, value in members:
                    info = tarfile.TarInfo(name)
                    if kind == "file":
                        content = value.encode()
                        info.size = len(content)
                        tar.addfile(info, io.BytesIO(content))
                    else:
                        info.type = tarfile.SYMTYPE if kind == "symlink" else tarfile.LNKTYPE
                        info.linkname = value
                        tar.addfile(info)
            result = subprocess.run(
                [sys.executable, str(EXTRACTOR), str(archive), str(output)],
                capture_output=True,
                text=True,
            )
            outside = (root / "escape").exists()
            linked = (output / "python" / "bin" / "python").is_symlink()
            shared = (output / "python" / "share").exists()
            return result, outside, linked, shared

    def test_relative_link_within_root_is_allowed(self):
        result, _, linked, _ = self.run_archive([
            ("file", "python/lib/engine", "ok"),
            ("symlink", "python/bin/python", "../lib/engine"),
        ])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(linked)

    def test_escaping_paths_and_links_are_rejected(self):
        for members in (
            [("file", "../escape", "bad")],
            [("file", "/escape", "bad")],
            [("file", "C:/escape", "bad")],
            [("symlink", "python/bin/python", "../../../escape")],
            [("symlink", "python/bin/python", "/escape")],
            [("hardlink", "python/bin/python", "../escape")],
            [("file", "python/file", "ok"), ("hardlink", "python/link", "python/file")],
            [("symlink", "python/link", "../../escape"), ("file", "python/link/file", "bad")],
            [("file", "python/../escape", "bad")],
            [("symlink", "python/bin/python", "C:\\escape")],
        ):
            with self.subTest(members=members):
                result, outside, _, _ = self.run_archive(members)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(outside)

    def test_unneeded_share_tree_is_not_extracted(self):
        result, _, _, shared = self.run_archive([
            ("file", "python/bin/python3", "ok"),
            ("symlink", "python/share/terminfo/a/loop", "loop"),
        ])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(shared)

    def test_escape_after_many_entries_is_rejected(self):
        members = [("file", f"python/data/{i}", "ok") for i in range(1_000)]
        members.append(("symlink", "python/data/escape", "../../../escape"))
        result, outside, _, _ = self.run_archive(members)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(outside)

    def test_git_bash_uses_windows_python(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for name, body in (("uname", "echo MINGW64_NT"), ("python", "exit 42")):
                executable = root / name
                executable.write_text(f"#!/bin/sh\n{body}\n")
                executable.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(
                ["bash", str(TRIM), str(root / "archive"), str(root / "output")],
                env=env,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 42, result.stderr)


if __name__ == "__main__":
    unittest.main()
