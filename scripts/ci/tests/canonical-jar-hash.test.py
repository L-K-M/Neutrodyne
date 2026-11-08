#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Validate archive names before ZipInfo can truncate or normalize them."""
import contextlib
import importlib.util
import io
import struct
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).parents[1] / "canonical-jar-hash.py"
spec = importlib.util.spec_from_file_location("canonical_jar_hash", SCRIPT)
canonical = importlib.util.module_from_spec(spec)
spec.loader.exec_module(canonical)


def archive(name, content=b"CODE-A"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as jar:
        jar.writestr(name, content)
    return output.getvalue()


def signed_native():
    header = struct.pack("<IiiIIIII", 0xFEEDFACF, 0x01000007, 3, 6, 2, 88, 0, 0)
    segment = struct.pack(
        "<II16sQQQQIIII", 0x19, 72, b"__LINKEDIT", 0, 192, 256, 192, 5, 1, 0, 0,
    )
    command = struct.pack("<IIII", 0x1D, 16, 320, 128)
    return header + segment + command + bytes(256 - 120) + b"A" * 64 + b"S" * 128


class OriginalEntryNameTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "input.jar"

    def assert_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                canonical.canonical_sha256(self.path)

    def assert_native_change_bound(self, changed):
        approved = self.path.with_name("approved.jar")
        approved.write_bytes(archive("lib.dylib", signed_native()))
        self.path.write_bytes(archive("lib.dylib", bytes(changed)))
        self.assertNotEqual(
            canonical.canonical_sha256(approved),
            canonical.canonical_sha256(self.path),
            "non-signature data changes must change the fingerprint",
        )

    def test_unsupported_segment_layouts_stay_bound_as_raw_bytes(self):
        vectors = Path(__file__).with_name("fixtures") / "malformed-macho-vectors.txt"
        for line in vectors.read_text().splitlines():
            if not line or line.startswith("#"):
                continue
            label, value = line.split("=", 1)
            payload = bytes.fromhex(value)
            with self.subTest(layout=label):
                self.assertEqual(payload, canonical.normalize_thin_macho(payload)[0])

    def test_a_nonterminal_signature_cannot_hide_linkedit_data(self):
        changed = bytearray(signed_native())
        struct.pack_into("<I", changed, 112, 256)
        changed[256:320] = b"E" * 64
        changed[-64:] = b"A" * 64
        self.assert_native_change_bound(changed)

    def test_an_extended_signature_command_cannot_hide_header_padding(self):
        changed = bytearray(signed_native())
        struct.pack_into("<I", changed, 108, 32)
        struct.pack_into("<I", changed, 20, 104)
        changed[120:136] = b"E" * 16
        self.assert_native_change_bound(changed)

    def test_overlapping_signature_commands_cannot_splice_data(self):
        changed = bytearray(signed_native())
        struct.pack_into("<I", changed, 16, 3)
        struct.pack_into("<I", changed, 20, 104)
        struct.pack_into("<I", changed, 116, 64)
        struct.pack_into("<IIII", changed, 120, 0x1D, 16, 320, 64)
        changed[256:320] = b"E" * 64
        changed[-64:] = b"A" * 64
        self.assert_native_change_bound(changed)

    def test_nul_suffix_cannot_collide_with_an_approved_name(self):
        payload = archive("A.class_zzzz")
        self.assertEqual(2, payload.count(b"A.class_zzzz"))
        self.path.write_bytes(payload.replace(b"A.class_zzzz", b"A.class\0evil"))
        with zipfile.ZipFile(self.path) as jar:
            entry = jar.infolist()[0]
            self.assertEqual("A.class", entry.filename)
            self.assertEqual("A.class\0evil", entry.orig_filename)
        self.assert_rejected()

    def test_windows_separator_normalization_cannot_hide_a_backslash(self):
        self.path.write_bytes(archive("A\\B.class"))
        # Exercise the stdlib's Windows name transformation without claiming
        # a native Windows package run on this host.
        with patch.object(zipfile.os, "sep", "\\"):
            with zipfile.ZipFile(self.path) as jar:
                entry = jar.infolist()[0]
                self.assertEqual("A/B.class", entry.filename)
                self.assertEqual("A\\B.class", entry.orig_filename)
            self.assert_rejected()


if __name__ == "__main__":
    unittest.main()
