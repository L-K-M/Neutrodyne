#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The ZIP extraction boundary of the packaged smoke (11 AOT cache).

`python -m zipfile -e` (and `zipfile.extractall`) writes entries at the
extraction time, which silently loses the trained AOT cache: the JVM falls
back to the non-AOT path when a classpath JAR's recorded timestamp no longer
matches. The shipped extractor restores the artifact's own recorded times, so
a drifted producer stays drifted and a strict -XX:AOTMode=on launch still
fails. These fixtures pin that contract: the Info-ZIP extended timestamp
(0x5455, UTC) is authoritative, the MS-DOS fallback is local-time, a drifted
entry is preserved, and a corrupt extra field or a path traversal fails closed.
"""

import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[3]
EXTRACT = REPO / "scripts/ci/extract-zip.py"

# 1980-02-01T00:00:00Z — AOT_IMAGE_TIMESTAMP_MS, the trained image's fixed epoch.
EPOCH_S = 318211200


def extended_timestamp(mtime_s: int) -> bytes:
    """The Info-ZIP 0x5455 extra field: flags=1 (mtime), 4-byte little-endian UTC."""
    return struct.pack("<HHB", 0x5455, 5, 0x01) + struct.pack("<I", mtime_s)


def make_zip(path: Path, entries: list[tuple[str, bytes, bytes | None, tuple | None]]):
    """Write a ZIP; each entry is (name, data, extra, date_time). date_time=None
    lets zipfile derive the MS-DOS time from the current time."""
    with zipfile.ZipFile(path, "w") as archive:
        for name, data, extra, date_time in entries:
            info = zipfile.ZipInfo(name, date_time=date_time or time.localtime()[:6])
            info.extra = extra or b""
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, data)


class ExtractZipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def extract(self, archive: Path, destination: Path | None = None):
        destination = destination or (self.root / "out")
        return subprocess.run(
            [sys.executable, str(EXTRACT), str(archive), str(destination)],
            capture_output=True, text=True, timeout=30,
        )

    def test_extended_timestamp_is_restored_in_utc(self):
        """GREEN: the 0x5455 extra field's UTC seconds become the file's mtime."""
        archive = self.root / "extended.zip"
        make_zip(archive, [("Neutrodyne/app/tiny.jar", b"JAR", extended_timestamp(EPOCH_S), None)])
        result = self.extract(archive)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(EPOCH_S, int((self.root / "out/Neutrodyne/app/tiny.jar").stat().st_mtime))

    def test_dos_time_fallback_uses_local_time(self):
        """The MS-DOS time (no extra field) is interpreted in the local timezone,
        matching the build runner because the release builds and smokes on one host."""
        archive = self.root / "dos.zip"
        date_time = (2026, 11, 8, 12, 0, 0)
        make_zip(archive, [("Neutrodyne/app/tiny.jar", b"JAR", None, date_time)])
        result = self.extract(archive)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        expected = int(time.mktime(date_time + (0, 0, -1)))
        self.assertEqual(expected, int((self.root / "out/Neutrodyne/app/tiny.jar").stat().st_mtime))

    def test_drifted_timestamp_is_preserved_not_stamped(self):
        """RED negative: a one-second drift in the artifact survives extraction, so
        the strict cache check sees the drift instead of an expected epoch."""
        archive = self.root / "drifted.zip"
        make_zip(
            archive,
            [("Neutrodyne/app/tiny.jar", b"JAR", extended_timestamp(EPOCH_S + 1), None)],
        )
        result = self.extract(archive)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(EPOCH_S + 1, int((self.root / "out/Neutrodyne/app/tiny.jar").stat().st_mtime))

    def test_corrupt_extended_timestamp_fails_closed(self):
        """A 0x5455 extra field too small to carry the mtime is a corrupt artifact:
        the extraction refuses it rather than silently writing a wrong time."""
        archive = self.root / "corrupt.zip"
        make_zip(
            archive,
            [("Neutrodyne/app/tiny.jar", b"JAR", struct.pack("<HHB", 0x5455, 4, 0x01), None)],
        )
        result = self.extract(archive)
        self.assertNotEqual(0, result.returncode)
        # Either zipfile's own reader or the extractor's parser refuses it; the
        # point is that no entry is written with a silently wrong time.
        self.assertIn("5455", result.stderr)
        self.assertFalse((self.root / "out/Neutrodyne/app/tiny.jar").exists())

    def test_extended_timestamp_without_mtime_flag_fails_closed(self):
        archive = self.root / "noflag.zip"
        make_zip(
            archive,
            [
                (
                    "Neutrodyne/app/tiny.jar",
                    b"JAR",
                    struct.pack("<HHB", 0x5455, 5, 0x00) + struct.pack("<I", EPOCH_S),
                    None,
                )
            ],
        )
        result = self.extract(archive)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no mtime", result.stderr)

    def test_path_traversal_fails_closed(self):
        archive = self.root / "traversal.zip"
        make_zip(archive, [("../evil.txt", b"EVIL", extended_timestamp(EPOCH_S), None)])
        result = self.extract(archive)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("escapes the destination", result.stderr)
        self.assertFalse((self.root / "evil.txt").exists())

    def test_directory_entries_restore_their_own_times(self):
        """Creating files updates a directory's mtime; the extractor restores the
        directories' recorded times afterwards."""
        archive = self.root / "dirs.zip"
        with zipfile.ZipFile(archive, "w") as out:
            directory = zipfile.ZipInfo("Neutrodyne/app/", date_time=time.localtime()[:6])
            directory.extra = extended_timestamp(EPOCH_S)
            out.writestr(directory, b"")
            file_info = zipfile.ZipInfo("Neutrodyne/app/tiny.jar", date_time=time.localtime()[:6])
            file_info.extra = extended_timestamp(EPOCH_S)
            out.writestr(file_info, b"JAR")
        result = self.extract(archive)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(EPOCH_S, int((self.root / "out/Neutrodyne/app").stat().st_mtime))
        self.assertEqual(EPOCH_S, int((self.root / "out/Neutrodyne/app/tiny.jar").stat().st_mtime))

    def test_unix_mode_is_restored(self):
        archive = self.root / "mode.zip"
        with zipfile.ZipFile(archive, "w") as out:
            info = zipfile.ZipInfo("Neutrodyne/Neutrodyne", date_time=time.localtime()[:6])
            info.external_attr = (0o100755 << 16)
            info.extra = extended_timestamp(EPOCH_S)
            out.writestr(info, b"LAUNCHER")
        result = self.extract(archive)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(0o755, stat_mode(self.root / "out/Neutrodyne/Neutrodyne"))


def stat_mode(path: Path) -> int:
    return os.stat(path).st_mode & 0o777


if __name__ == "__main__":
    unittest.main()
