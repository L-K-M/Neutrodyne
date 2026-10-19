#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Synthetic chapter fixtures. Run via make-playback-fixtures.sh (FFMPEG overridable)."""
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "playback/impl/src/test/resources/media"


def atom(kind, data):
    return struct.pack(">I4s", len(data) + 8, kind) + data


def atoms(data):
    offset = 0
    while offset < len(data):
        size, kind = struct.unpack_from(">I4s", data, offset)
        assert size >= 8 and offset + size <= len(data)
        yield kind, data[offset + 8:offset + size]
        offset += size


def frame(kind, data):
    return kind + struct.pack(">I", len(data)) + b"\0\0" + data


def make():
    OUT.mkdir(parents=True, exist_ok=True)
    chap = b"".join(
        frame(b"CHAP", f"c{i}".encode() + b"\0" +
              struct.pack(">IIII", i * 1000, (i + 1) * 1000, 0xFFFFFFFF, 0xFFFFFFFF) +
              frame(b"TIT2", b"\0" + title.encode()))
        for i, title in enumerate(["Opening", "Closing"])
    )
    size = len(chap)
    syncsafe = bytes([(size >> shift) & 0x7F for shift in (21, 14, 7, 0)])
    (OUT / "chapters_id3.mp3").write_bytes(
        b"ID3\x03\x00\x00" + syncsafe + chap + (OUT / "cbr_128k_2s.mp3").read_bytes()
    )
    with tempfile.TemporaryDirectory() as temporary:
        metadata = Path(temporary) / "chapters.txt"
        metadata.write_text(";FFMETADATA1\n" + "".join(
            f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={i * 1000}\nEND={(i + 1) * 1000}\ntitle={title}\n"
            for i, title in enumerate(["Opening", "Closing"])
        ))
        both = OUT / "chapters_both.m4b"
        subprocess.run([
            os.environ.get("FFMPEG", "ffmpeg"), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2",
            "-i", str(metadata), "-map_metadata", "1", "-map_chapters", "1",
            "-c:a", "aac", "-b:a", "32k", "-fflags", "+bitexact", "-flags:a", "+bitexact",
            str(both),
        ], check=True)
        # mdat precedes moov, so changing moov sizes never shifts sample offsets.
        original = list(atoms(both.read_bytes()))
        assert [kind for kind, _ in original].index(b"mdat") < [kind for kind, _ in original].index(b"moov")
        for name in ("nero", "quicktime", "both"):
            top = []
            for kind, body in original:
                if kind != b"moov":
                    top.append(atom(kind, body))
                    continue
                children = []
                for child, value in atoms(body):
                    if child == b"trak" and name == "nero":
                        parts = list(atoms(value))
                        mdia = next(v for k, v in parts if k == b"mdia")
                        handler = next(v for k, v in atoms(mdia) if k == b"hdlr")[8:12]
                        if handler != b"soun":
                            continue
                        value = b"".join(atom(k, v) for k, v in parts if k != b"tref")
                    if child == b"udta":
                        value = b"".join(atom(k, v) for k, v in atoms(value) if name != "quicktime" or k != b"chpl")
                        if name == "both":
                            # Deliberately different Nero titles prove QuickTime wins.
                            value = value.replace(b"Opening", b"NeroOne").replace(b"Closing", b"NeroTwo")
                    children.append(atom(child, value))
                top.append(atom(kind, b"".join(children)))
            (OUT / f"chapters_{name}.m4b").write_bytes(b"".join(top))
    (OUT / "chapters.json").write_text(json.dumps({
        "version": "1.2.0", "chapters": [{"startTime": 0, "title": "JSON opening"}, {"startTime": 1, "title": "JSON closing"}]
    }) + "\n")


if __name__ == "__main__":
    make()
