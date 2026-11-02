#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""corpus_reference.py — the MD0 decode corpus manifest (scripts/fixtures).

Writes `playback/engine/src/test/resources/media/corpus.json`: the SHA-256,
sample rate, channel count, expected decoded sample count and chapter
expectations of every file of `:playback:engine`'s MD0 corpus.

The expected sample counts are computed from the *container structure* by this
script — MP3 frame headers and the Xing/LAME tag, MP4 `stts`/`elst`/`sgpd`
boxes and `moof`/`trun` runs, Ogg granule positions and the Opus pre-skip,
Matroska `CodecDelay` and block timestamps, the FLAC `STREAMINFO` total and the
WAV data chunk — never by decoding the file. That keeps them independent of the
code under test (`FfDemuxer`/`FfDecoder`), which is what makes the corpus test
in `:playback:engine` a check rather than a restatement.

While the corpus was prepared the same numbers were measured with an ffmpeg 9.0
LGPL build (`-f s16le -` piped into `wc -c`); every entry agreed. That build is
the second, independent witness, and `--verify` re-runs it when `ffmpeg` is on
PATH.

Usage:
    python3 scripts/fixtures/corpus_reference.py [--write PATH] [--verify]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys
from typing import Any

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORPUS_DIR = os.path.join(REPO, "playback", "engine", "src", "test", "resources", "media")
MANIFEST = os.path.join(CORPUS_DIR, "corpus.json")

# Chapter expectations, kept beside the parsers that read them: the ID3 CHAP
# frames of the MP3 and the Nero chpl box of the M4A carry exactly these.
MP3_SAMPLES_PER_FRAME = 1152


class CorpusError(Exception):
    """Raised when a file's structure cannot be read; the manifest must never guess."""


def read(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


# --------------------------------------------------------------------------- WAV


def parse_wav(data: bytes) -> dict[str, Any]:
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise CorpusError("not a RIFF/WAVE file")
    pos = 12
    fmt = None
    payload = None
    while pos + 8 <= len(data):
        tag, size = struct.unpack_from("<4sI", data, pos)
        body = data[pos + 8 : pos + 8 + size]
        if tag == b"fmt ":
            audio_format, channels, rate, _, _, bits = struct.unpack_from("<HHIIHH", body, 0)
            fmt = (channels, rate, bits, audio_format)
        elif tag == b"data":
            payload = len(body)
        pos += 8 + size + (size & 1)
    if fmt is None or payload is None:
        raise CorpusError("WAV without fmt or data chunk")
    channels, rate, bits, _ = fmt
    block_align = channels * bits // 8
    return {
        "container": "wav",
        "codec": f"pcm_s{bits}le",
        "sampleRate": rate,
        "channels": channels,
        "expectedSamples": payload // block_align,
        "decodedSamples": payload // block_align,
        "trimRule": None,
    }


# -------------------------------------------------------------------------- FLAC


def parse_flac(data: bytes) -> dict[str, Any]:
    if data[:4] != b"fLaC":
        raise CorpusError("not a FLAC file")
    pos = 4
    while pos + 4 <= len(data):
        header = data[pos]
        last = header & 0x80
        block_type = header & 0x7F
        size = struct.unpack_from(">I", data, pos)[0] & 0xFFFFFF
        body = data[pos + 4 : pos + 4 + size]
        if block_type == 0:  # STREAMINFO
            if len(body) < 18:
                raise CorpusError("short STREAMINFO")
            packed = int.from_bytes(body[10:18], "big")
            rate = packed >> 44
            channels = ((packed >> 41) & 0x7) + 1
            total_samples = packed & ((1 << 36) - 1)
            return {
                "container": "flac",
                "codec": "flac",
                "sampleRate": rate,
                "channels": channels,
                "expectedSamples": total_samples,
                "decodedSamples": total_samples,
                "trimRule": None,
            }
        pos += 4 + size
        if last:
            break
    raise CorpusError("FLAC without STREAMINFO")


# --------------------------------------------------------------------------- MP3

MPEG_VERSIONS = {3: 1, 2: 0, 0: 2}  # MPEG 1 / 2 / 2.5
BITRATES = {
    # (version, layer): table indexed by the 4-bit bitrate field
    (1, 3): [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0],
    (1, 2): [0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0],
    (2, 3): [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0],
    (2, 2): [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0],
}
RATES = {1: (44100, 48000, 32000), 2: (22050, 24000, 16000), 0: (11025, 12000, 8000)}


def mp3_frames(data: bytes, start: int) -> list[tuple[int, int, int, int]]:
    """Walk the frame headers: (offset, samples, bitrate, rate)."""
    frames = []
    pos = start
    while pos + 4 <= len(data):
        if data[pos] != 0xFF or (data[pos + 1] & 0xE0) != 0xE0:
            break
        version_bits = (data[pos + 1] >> 3) & 0x03
        layer_bits = (data[pos + 1] >> 1) & 0x03
        if version_bits == 1 or layer_bits == 0:
            break
        version = MPEG_VERSIONS[version_bits]
        layer = 4 - layer_bits  # 01 = Layer III, 10 = Layer II, 11 = Layer I
        bitrate_index = (data[pos + 2] >> 4) & 0x0F
        rate_index = (data[pos + 2] >> 2) & 0x03
        padding = (data[pos + 2] >> 1) & 0x01
        if rate_index == 3 or bitrate_index in (0, 15):
            break
        bitrate = BITRATES.get((version, layer), [0] * 16)[bitrate_index] * 1000
        rate = RATES[version][rate_index]
        if bitrate <= 0:
            break
        samples = 1152 if layer == 3 else 576
        if version != 1:  # MPEG 2/2.5 halves the samples per frame
            samples //= 3 if layer == 3 else 1
            samples = 576
        length = (samples // 8 * bitrate // rate) + (padding if layer == 3 else 0)
        if length <= 0 or pos + length > len(data):
            break
        frames.append((pos, samples, bitrate, rate))
        pos += length
    return frames


def id3_size(data: bytes) -> int:
    if data[:3] != b"ID3":
        return 0
    size = 0
    for byte in data[6:10]:
        size = (size << 7) | (byte & 0x7F)
    return 10 + size


def parse_id3_chapters(data: bytes) -> list[dict[str, Any]]:
    """ID3v2.3/2.4 CHAP frames with their embedded TIT2 titles."""
    end = id3_size(data)
    chapters: list[dict[str, Any]] = []
    pos = 10
    while pos + 10 <= end:
        frame_id = data[pos : pos + 4]
        if frame_id.strip(b"\x00") == b"":
            break
        size = struct.unpack_from(">I", data, pos + 4)[0]
        flags = struct.unpack_from(">H", data, pos + 8)[0]
        body = data[pos + 10 : pos + 10 + size]
        pos += 10 + size
        if frame_id != b"CHAP":
            continue
        split = body.index(b"\x00")
        _element_id = body[:split]
        rest = body[split + 1 :]
        start_ms, end_ms = struct.unpack_from(">II", rest, 0)
        title = None
        inner = rest[16:]
        inner_pos = 0
        while inner_pos + 10 <= len(inner):
            inner_id = inner[inner_pos : inner_pos + 4]
            inner_size = struct.unpack_from(">I", inner, inner_pos + 4)[0]
            inner_body = inner[inner_pos + 10 : inner_pos + 10 + inner_size]
            inner_pos += 10 + inner_size
            if inner_id == b"TIT2" and inner_body:
                encoding = inner_body[0]
                text = inner_body[1:]
                if encoding in (1, 2):
                    text = text[2:]
                elif encoding == 3:
                    text = text
                title = text.split(b"\x00")[0].decode("utf-8", "replace")
                break
        chapters.append({"startMs": start_ms, "endMs": end_ms, "title": title})
        if flags & 0x0040:  # compressed or encrypted frames are not used here
            raise CorpusError("compressed/encrypted ID3 frame")
    return chapters


def parse_mp3(data: bytes) -> dict[str, Any]:
    start = id3_size(data)
    if start + 4 > len(data) or data[start] != 0xFF:
        raise CorpusError("no MPEG frame after the ID3 tag")
    frames = mp3_frames(data, start)
    if not frames:
        raise CorpusError("no MPEG audio frames")
    rate = frames[0][3]
    channels = 2  # the corpus's MP3s are joint stereo
    total = len(frames) * frames[0][1]

    # A Xing/Info tag in the first frame's side information carries the frame
    # count and, for LAME, the encoder delay and padding its decoder trims. The
    # tag sits at a fixed offset in the frame (libavformat/mp3dec.c's
    # xing_offtbl: 32 bytes for MPEG 1 stereo, 17 for MPEG 1 mono, 9 for MPEG 2
    # mono), counted from the frame header.
    first = frames[0][0]
    version = MPEG_VERSIONS[(data[first + 1] >> 3) & 0x03]
    tag_offset = {1: {2: 32, 1: 17}, 2: {2: 17, 1: 9}, 0: {2: 17, 1: 9}}[version][2]
    found = -1
    for tag in (b"Xing", b"Info"):
        found = data.find(tag, first, first + tag_offset + 8)
        if found >= 0:
            break
    start_pad = end_pad = 0
    lame_tag = False
    xing_frames = None
    if found >= 0:
        flags = struct.unpack_from(">I", data, found + 4)[0]
        cursor = found + 8
        if flags & 0x01:
            xing_frames = struct.unpack_from(">I", data, cursor)[0]
            cursor += 4
        if flags & 0x02:  # stream size in bytes
            cursor += 4
        if flags & 0x04:  # the 100-entry seek table
            cursor += 100
        if flags & 0x08:  # VBR quality
            cursor += 4
        # The LAME tag follows: a 9-byte encoder string, then 12 bytes of
        # revision, lowpass, replay gain, flags and bitrate, then a 3-byte
        # field holding the delay (top 12 bits) and the padding (low 12).
        if cursor + 24 <= len(data) and data[cursor : cursor + 4] in (b"LAME", b"Lavc", b"Lavf"):
            lame_tag = True
            delays = int.from_bytes(data[cursor + 21 : cursor + 24], "big")
            start_pad = delays >> 12
            end_pad = delays & 0xFFF
    # ffmpeg's demuxer skips start_pad + 529 samples at the front and discards
    # end_pad - 529 at the back, both anchored to the Xing frame count
    # (libavformat/mp3dec.c mp3_parse_vbr_tags), which is what the decoder emits.
    decoded = (xing_frames or len(frames)) * frames[0][1]
    # The LAME trim happens inside libavformat/libavcodec, so an independent
    # decoder reports the trimmed count: both fields hold it.
    trimmed = decoded - (start_pad + 529) - max(0, end_pad - 529) if lame_tag else decoded
    rule = "lame-tag-delay-and-padding" if lame_tag else None
    return {
        "container": "mp3",
        "codec": "mp3",
        "sampleRate": rate,
        "channels": channels,
        "expectedSamples": trimmed,
        "decodedSamples": trimmed,
        "trimRule": rule,
        "chapters": parse_id3_chapters(data) if data[:3] == b"ID3" else [],
    }


# --------------------------------------------------------------------------- MP4


def mp4_boxes(
    data: bytes, start: int, end: int, path: tuple[str, ...] = ()
) -> list[tuple[tuple[str, ...], int, int]]:
    """(path, body offset, body size) of every box directly under [start, end)."""
    out = []
    pos = start
    while pos + 8 <= end:
        tag = data[pos + 4 : pos + 8].decode("latin1")
        size = struct.unpack_from(">I", data, pos)[0]
        header = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, pos + 8)[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            raise CorpusError(f"box {tag} overruns its parent")
        out.append((path + (tag,), pos + header, size - header))
        pos += size
    return out


# Only these boxes hold other boxes; `meta` carries a 4-byte version/flags
# header before its children, the rest start theirs immediately.
MP4_CONTAINERS = {
    "moov",
    "trak",
    "mdia",
    "minf",
    "stbl",
    "edts",
    "udta",
    "mvex",
    "moof",
    "traf",
    "dinf",
    "gmhd",
}


def mp4_walk(data: bytes) -> list[tuple[tuple[str, ...], int, int]]:
    """Every box of the file, depth first and in file order: (path, offset, size)."""
    found: list[tuple[tuple[str, ...], int, int]] = []
    queue: list[tuple[tuple[str, ...], int, int]] = [((), 0, len(data))]
    while queue:
        path, start, end = queue.pop(0)
        for box_path, offset, size in mp4_boxes(data, start, end, path):
            found.append((box_path, offset, size))
            tag = box_path[-1]
            if tag in MP4_CONTAINERS:
                queue.append((box_path, offset, offset + size))
            elif tag == "meta":
                queue.append((box_path, offset + 4, offset + size))
    found.sort(key=lambda box: box[1])
    return found


def mp4_find(data: bytes, path: tuple[str, ...]) -> list[tuple[tuple[str, ...], int, int]]:
    return [box for box in mp4_walk(data) if box[0] == path]


def parse_mp4(data: bytes) -> dict[str, Any]:
    moov = mp4_find(data, ("moov",))
    if not moov:
        raise CorpusError("no moov box")
    traks = mp4_find(data, ("moov", "trak"))
    if not traks:
        raise CorpusError("no trak box")
    handlers = mp4_find(data, ("moov", "trak", "mdia", "hdlr"))
    audio_trak = None
    chapter_trak = None
    for index, trak in enumerate(traks):
        # The handlers are in trak order, so the index pairs them up.
        kind = data[handlers[index][1] + 8 : handlers[index][1] + 12]
        if kind == b"soun":
            audio_trak = trak
        elif kind == b"text":
            chapter_trak = trak
    if audio_trak is None:
        raise CorpusError("no audio trak")

    def boxes_under(trak: tuple[tuple[str, ...], int, int], suffix: tuple[str, ...]) -> list[tuple[tuple[str, ...], int, int]]:
        return [box for box in mp4_walk(data) if box[0][:2] == ("moov", "trak") and box[0][2:] == suffix and box[1] >= trak[1] and box[1] < trak[1] + trak[2]]

    mdhd = boxes_under(audio_trak, ("mdia", "mdhd"))
    if not mdhd:
        raise CorpusError("no mdhd")
    version = data[mdhd[0][1]]
    timescale = struct.unpack_from(">I", data, mdhd[0][1] + (12 if version == 0 else 20))[0]

    stts = boxes_under(audio_trak, ("mdia", "minf", "stbl", "stts"))
    stts_total = 0
    if stts:
        _, offset, size = stts[0]
        entries = struct.unpack_from(">I", data, offset + 4)[0]
        for index in range(entries):
            count, delta = struct.unpack_from(">II", data, offset + 8 + 8 * index)
            stts_total += count * delta

    # Fragmented files carry their samples in moof/trun runs instead of stts.
    trun_total = 0
    fragmented = not stts_total
    for _moof_path, moof_offset, moof_size in mp4_find(data, ("moof",)):
        for traf in mp4_boxes(data, moof_offset, moof_offset + moof_size, ("moof",)):
            if traf[0][-1] != "traf":
                continue
            for box in mp4_boxes(data, traf[1], traf[1] + traf[2], traf[0]):
                if box[0][-1] != "trun":
                    continue
                _, offset, size = box
                flags = struct.unpack_from(">I", data, offset)[0] & 0xFFFFFF
                sample_count = struct.unpack_from(">I", data, offset + 4)[0]
                cursor = offset + 8
                if flags & 0x000001:  # data-offset-present
                    cursor += 4
                if flags & 0x000004:  # first-sample-flags
                    cursor += 4
                for index in range(sample_count):
                    if flags & 0x000100:  # sample-duration-present
                        trun_total += struct.unpack_from(">I", data, cursor)[0]
                        cursor += 4
                    if flags & 0x000200:
                        cursor += 4
                    if flags & 0x000400:
                        cursor += 4
                    if flags & 0x000800:
                        cursor += 4
                    if flags & 0x001000:
                        cursor += 4
                break

    # The edit list trims the priming (media_time) and, when the edit is shorter
    # than the media, the trailing padding as well.
    segment_duration = None
    media_time = 0
    elst = boxes_under(audio_trak, ("edts", "elst"))
    if elst:
        _, offset, size = elst[0]
        version = data[offset]
        entries = struct.unpack_from(">I", data, offset + 4)[0]
        if entries:
            if version == 1:
                segment_duration, media_time = struct.unpack_from(">QQ", data, offset + 8)
            else:
                segment_duration, media_time = struct.unpack_from(">II", data, offset + 8)

    stsd = boxes_under(audio_trak, ("mdia", "minf", "stbl", "stsd"))
    codec = "aac"
    if stsd:
        _, offset, size = stsd[0]
        entry_format = data[offset + 12 : offset + 16]
        if entry_format == b"mp4a":
            codec = "aac"
        elif entry_format == b"fLaC":
            codec = "flac"
        elif entry_format == b"alac":
            codec = "alac"

    packet_count = 0
    stsz = boxes_under(audio_trak, ("mdia", "minf", "stbl", "stsz"))
    if stsz:
        _, offset, size = stsz[0]
        sample_size = struct.unpack_from(">I", data, offset + 4)[0]
        packet_count = struct.unpack_from(">I", data, offset + 8)[0] if sample_size == 0 else size // 4
    trun_packets = 0
    for _moof_path, moof_offset, moof_size in mp4_find(data, ("moof",)):
        for traf in mp4_boxes(data, moof_offset, moof_offset + moof_size, ("moof",)):
            if traf[0][-1] != "traf":
                continue
            for box in mp4_boxes(data, traf[1], traf[1] + traf[2], traf[0]):
                if box[0][-1] != "trun":
                    continue
                _, offset, size = box
                trun_packets += struct.unpack_from(">I", data, offset + 4)[0]
    packet_count = packet_count or trun_packets

    decoded = packet_count * 1024 if codec == "aac" else (stts_total or trun_total)
    expected = decoded
    rule = None
    if segment_duration is not None:
        # The demuxer drops the packets outside the edit list, so an independent
        # decoder already reports the trimmed count.
        expected = decoded = min(segment_duration, decoded - media_time)
        rule = "elst-media-time" if media_time else None
    elif codec == "aac" and fragmented:
        # 11 Demux and decode: an AAC-LC stream without container priming
        # information carries the standard 1024-sample priming, which our
        # decoder drops. libavcodec itself does not trim it, so `decodedSamples`
        # records the untrimmed count.
        expected -= 1024
        rule = "aac-fmp4-priming-1024"

    chapters: list[dict[str, Any]] = []
    chpl = mp4_find(data, ("moov", "udta", "chpl"))
    if chpl:
        _, offset, size = chpl[0]
        version = data[offset]
        cursor = offset + 4
        if version == 1:
            cursor += 4
        count = data[cursor]
        cursor += 1
        for _index in range(count):
            start_100ns = struct.unpack_from(">Q", data, cursor)[0]
            length = data[cursor + 8]
            title = data[cursor + 9 : cursor + 9 + length].decode("utf-8", "replace")
            chapters.append(
                {"startMs": start_100ns // 10000, "endMs": None, "title": title}
            )
            cursor += 9 + length
    if chapters:
        # chpl has no end times; each chapter ends where the next starts.
        for index in range(len(chapters) - 1):
            chapters[index]["endMs"] = chapters[index + 1]["startMs"]
        chapters[-1]["endMs"] = chapters[-1]["startMs"] + 1000
    if chapter_trak is not None and not chapters:
        raise CorpusError("chapter trak without chpl: add the text-track reader")

    return {
        "container": "mp4",
        "codec": codec,
        "sampleRate": timescale,
        "channels": 2,
        "expectedSamples": expected,
        "decodedSamples": decoded,
        "trimRule": rule,
        "chapters": chapters,
    }


# --------------------------------------------------------------------------- OGG


def parse_ogg(data: bytes) -> dict[str, Any]:
    pages = []
    pos = 0
    while pos + 27 <= len(data):
        if data[pos : pos + 4] != b"OggS":
            break
        header_type = data[pos + 5]
        granule = struct.unpack_from("<q", data, pos + 6)[0]
        segments = data[pos + 26]
        table = data[pos + 27 : pos + 27 + segments]
        payload = sum(table)
        pages.append((header_type, granule, pos + 27 + segments, payload))
        pos += 27 + segments + payload
    if not pages:
        raise CorpusError("no Ogg pages")

    identification = None
    comments = None
    for _type, _granule, body, payload in pages[:2]:
        packet = data[body : body + payload]
        if packet[:7] == b"\x01vorbis":
            identification = packet
        elif packet[:7] == b"\x03vorbis":
            comments = packet
        elif packet[:8] == b"OpusHead":
            identification = packet
        elif packet[:8] == b"OpusTags":
            comments = packet
    last_granule = pages[-1][1]

    if identification and identification[:7] == b"\x01vorbis":
        channels = identification[11]
        rate = struct.unpack_from("<I", identification, 12)[0]
        return {
            "container": "ogg",
            "codec": "vorbis",
            "sampleRate": rate,
            "channels": channels,
            "expectedSamples": last_granule,
            "decodedSamples": last_granule,
            "trimRule": None,
        }
    if identification and identification[:8] == b"OpusHead":
        channels = identification[9]
        pre_skip = struct.unpack_from("<H", identification, 10)[0]
        rate = struct.unpack_from("<I", identification, 12)[0]
        return {
            "container": "ogg",
            "codec": "opus",
            "sampleRate": rate,
            "channels": channels,
            "expectedSamples": last_granule - pre_skip,
            "decodedSamples": last_granule - pre_skip,
            "trimRule": "opus-pre-skip",
        }
    raise CorpusError("Ogg without a Vorbis or Opus identification header")


# ---------------------------------------------------------------------- Matroska


def ebml_id(data: bytes, pos: int) -> tuple[int, int]:
    """An EBML element ID: the raw vint, marker bit included (0x1F43B675 = Cluster)."""
    first = data[pos]
    length = 1
    mask = 0x80
    while length <= 8 and not (first & mask):
        mask >>= 1
        length += 1
    return int.from_bytes(data[pos : pos + length], "big"), length


def ebml_size(data: bytes, pos: int) -> tuple[int, int]:
    """An EBML size vint: the marker bit is masked off; all-ones means unknown."""
    first = data[pos]
    length = 1
    mask = 0x80
    while length <= 8 and not (first & mask):
        mask >>= 1
        length += 1
    value = first & (mask - 1)
    for index in range(1, length):
        value = (value << 8) | data[pos + index]
    return value, length


EBML_UNKNOWN_SIZE = (1 << 56) - 1


def matroska_elements(data: bytes, start: int, end: int) -> list[tuple[int, int, int]]:
    """(id, offset of body, body size) of the elements under [start, end)."""
    out = []
    pos = start
    while pos < end:
        try:
            element_id, id_length = ebml_id(data, pos)
            size, size_length = ebml_size(data, pos + id_length)
        except IndexError:
            break
        body = pos + id_length + size_length
        if size == EBML_UNKNOWN_SIZE:
            size = end - body
        if size < 0 or body + size > end:
            break
        out.append((element_id, body, size))
        pos = body + size
    return out


def block_packet(data: bytes, block: tuple[int, int, int]) -> bytes:
    """The payload of a Matroska SimpleBlock or Block element.

    A block is a track number vint, a 16-bit relative timestamp and a flags
    byte, followed by the frame data.
    """
    _element_id, offset, size = block
    _track_number, used = ebml_id(data, offset)
    return data[offset + used + 3 : offset + size]


def opus_packet_samples(packet: bytes, rate: int) -> int:
    """The frame size an Opus packet decodes to, from its TOC config byte."""
    if not packet:
        raise CorpusError("empty Opus packet")
    config = (packet[0] >> 3) & 0x1F
    if config < 20:
        milliseconds = (10, 20, 40, 60)[config % 4]
    else:
        milliseconds = (2.5, 5, 10, 20)[config % 4]
    return int(rate * milliseconds / 1000)


def parse_matroska(data: bytes) -> dict[str, Any]:
    if data[:4] != b"\x1a\x45\xdf\xa3":
        raise CorpusError("not an EBML/Matroska file")
    top = matroska_elements(data, 0, len(data))
    segment = [element for element in top if element[0] == 0x18538067]
    if not segment:
        raise CorpusError("no Segment")
    _, body, size = segment[0]
    children = matroska_elements(data, body, body + size)
    info = [element for element in children if element[0] == 0x1549A966]
    tracks = [element for element in children if element[0] == 0x1654AE6B]
    if not info or not tracks:
        raise CorpusError("no Info or Tracks")

    timescale = 1000000
    for element_id, offset, length in matroska_elements(data, info[0][1], info[0][1] + info[0][2]):
        if element_id == 0x2AD7B1:  # TimestampScale, in nanoseconds
            timescale = int.from_bytes(data[offset : offset + length], "big")

    audio_rate = None
    audio_channels = None
    codec_delay = 0
    codec = "vorbis"
    for track in matroska_elements(data, tracks[0][1], tracks[0][1] + tracks[0][2]):
        if track[0] != 0xAE:  # TrackEntry
            continue
        entries = matroska_elements(data, track[1], track[1] + track[2])
        track_type = None
        for element_id, offset, length in entries:
            if element_id == 0x83:  # TrackType
                track_type = data[offset]
            elif element_id == 0x86:  # CodecID
                codec = data[offset : offset + length].decode("latin1")
            elif element_id == 0x56AA:  # CodecDelay, in nanoseconds
                codec_delay = int.from_bytes(data[offset : offset + length], "big")
            elif element_id == 0xE1:  # Audio
                for audio_id, audio_offset, audio_length in matroska_elements(data, offset, offset + length):
                    if audio_id == 0xB5:  # SamplingFrequency: an 8-byte double
                        audio_rate = struct.unpack_from(
                            ">d" if audio_length == 8 else ">f", data, audio_offset
                        )[0]
                    elif audio_id == 0x9F:  # Channels
                        audio_channels = data[audio_offset]
        if track_type == 2 and audio_rate:
            break
    if audio_rate is None:
        raise CorpusError("no audio track")
    rate = int(audio_rate)
    channels = audio_channels or 2
    is_opus = "OPUS" in codec.upper()

    # libavformat turns CodecDelay into a skip at the front and a BlockGroup's
    # DiscardPadding into a discard at the back (libavformat/matroskadec.c), so
    # the decoded count is the sum of the block durations minus both.
    total_samples = 0
    discard_samples = 0
    for element_id, offset, length in children:
        if element_id != 0x1F43B675:  # Cluster
            continue
        for cluster_element in matroska_elements(data, offset, offset + length):
            if cluster_element[0] == 0xA3:  # SimpleBlock
                total_samples += opus_packet_samples(block_packet(data, cluster_element), rate)
            elif cluster_element[0] == 0xA0:  # BlockGroup
                padding_ns = 0
                for group_element in matroska_elements(
                    data, cluster_element[1], cluster_element[1] + cluster_element[2]
                ):
                    if group_element[0] == 0x75A2:  # DiscardPadding, nanoseconds
                        padding_ns = int.from_bytes(
                            data[group_element[1] : group_element[1] + group_element[2]], "big"
                        )
                    elif group_element[0] == 0xA1:  # Block
                        total_samples += opus_packet_samples(block_packet(data, group_element), rate)
                if padding_ns:
                    discard_samples += round(padding_ns / 1e9 * rate)
    delay_samples = round(codec_delay / 1e9 * rate)
    # The skip and the discard are applied by libavformat, so the count an
    # independent decoder reports is the trimmed one.
    samples = total_samples - delay_samples - discard_samples
    return {
        "container": "webm",
        "codec": "opus" if is_opus else "vorbis",
        "sampleRate": rate,
        "channels": channels,
        "expectedSamples": samples,
        "decodedSamples": samples,
        "trimRule": "matroska-codec-delay-and-discard-padding" if codec_delay else None,
    }


# --------------------------------------------------------------------- ADTS

# An ADTS header is seven bytes with protection_absent set (nine with the CRC
# otherwise): sync, layer, profile, rate index, channels and the whole frame's
# length, so walking it counts the frames without decoding them. It also lets
# the `aac` demuxer into the corpus, which no other fixture reaches.
ADTS_HEADER_BYTES = 7

ADTS_SAMPLE_RATES = [
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
]

# The HE-AAC fixtures, as make-engine-corpus.sh generated them: v1 is SBR over a
# stereo core, v2 is Parametric Stereo over a mono core. An AAC-LC frame decodes
# to 1024 samples; SBR doubles it to 2048 at twice the core rate. SBR data rides
# inside the frames' extension elements — invisible to the ADTS header and out
# of reach for a structural parser without decoding Huffman sections — so the
# variant is recorded here from the generation recipe, and `--verify`'s
# reference decode (ffprobe reports "HE-AAC" / "HE-AACv2") is the witness that
# the SBR payload is really there.
ADTS_SBR_VARIANTS = {
    "heaac_v1_2s.aac": "aac-he",
    "heaac_v2_2s.aac": "aac-hev2",
}


def parse_adts(data: bytes, name: str) -> dict[str, Any]:
    frames = 0
    pos = 0
    sf_index = None
    channels = None
    while pos + ADTS_HEADER_BYTES <= len(data):
        if data[pos] != 0xFF or (data[pos + 1] & 0xF0) != 0xF0:
            raise CorpusError(f"no ADTS sync at offset {pos}")
        if (data[pos + 1] >> 1) & 0x03:
            raise CorpusError(f"ADTS layer is not 0 at offset {pos}")
        if not (data[pos + 1] & 0x01):
            raise CorpusError("an ADTS CRC (protection_absent=0) is not handled")
        frame_length = (
            ((data[pos + 3] & 0x03) << 11) | (data[pos + 4] << 3) | (data[pos + 5] >> 5)
        )
        if frame_length < ADTS_HEADER_BYTES or pos + frame_length > len(data):
            raise CorpusError(f"the ADTS frame at {pos} overruns the file")
        if frames == 0:
            sf_index = (data[pos + 2] >> 2) & 0x0F
            channels = ((data[pos + 2] & 0x01) << 2) | (data[pos + 3] >> 6)
        pos += frame_length
        frames += 1
    if pos != len(data):
        raise CorpusError(f"{len(data) - pos} trailing bytes after the last ADTS frame")
    if frames == 0:
        raise CorpusError("no ADTS frames")
    if sf_index is None or sf_index >= len(ADTS_SAMPLE_RATES):
        raise CorpusError(f"ADTS sampling frequency index {sf_index}")

    variant = ADTS_SBR_VARIANTS.get(name)
    core_rate = ADTS_SAMPLE_RATES[sf_index]
    # Parametric Stereo stores a mono core; the decoded output is stereo.
    decoded_channels = 2 if variant == "aac-hev2" else channels
    samples_per_frame = 2048 if variant else 1024
    decoded_rate = core_rate * 2 if variant else core_rate
    decoded = frames * samples_per_frame
    return {
        "container": "adts",
        "codec": variant or "aac",
        "sampleRate": decoded_rate,
        "channels": decoded_channels,
        "expectedSamples": decoded,
        "decodedSamples": decoded,
        "trimRule": None,
        "chapters": [],
    }


# ------------------------------------------------------------------------- driver

PARSERS = {
    ".aac": parse_adts,
    ".wav": parse_wav,
    ".flac": parse_flac,
    ".mp3": parse_mp3,
    ".m4a": parse_mp4,
    ".m4b": parse_mp4,
    ".mp4": parse_mp4,
    ".ogg": parse_ogg,
    ".opus": parse_ogg,
    ".webm": parse_matroska,
}


def describe(name: str) -> dict[str, Any]:
    path = os.path.join(CORPUS_DIR, name)
    data = read(path)
    extension = os.path.splitext(name)[1]
    parser = PARSERS.get(extension)
    if parser is None:
        raise CorpusError(f"no parser for {name}")
    # parse_adts alone also needs the file name: the HE-AAC variant is recorded
    # from the generation recipe, which only the name identifies.
    entry = parser(data, name) if parser is parse_adts else parser(data)
    entry["file"] = name
    entry["bytes"] = len(data)
    entry["sha256"] = hashlib.sha256(data).hexdigest()
    return entry


def ffmpeg_measured(name: str) -> int | None:
    """The independent witness: decode with the system ffmpeg and count samples."""
    try:
        decoded = subprocess.run(
            ["ffmpeg", "-v", "error", "-i", os.path.join(CORPUS_DIR, name), "-map", "0:a:0", "-f", "s16le", "-"],
            check=True,
            capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    channels = describe(name)["channels"]
    return len(decoded) // 2 // channels


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", metavar="PATH", default=MANIFEST)
    parser.add_argument(
        "--check",
        action="store_true",
        help="recompute every entry and fail when the committed manifest disagrees",
    )
    parser.add_argument("--verify", action="store_true", help="compare with a local ffmpeg when present")
    args = parser.parse_args()

    names = sorted(name for name in os.listdir(CORPUS_DIR) if os.path.splitext(name)[1] in PARSERS)
    entries = []
    failures = []
    for name in names:
        try:
            entry = describe(name)
        except CorpusError as error:
            failures.append(f"{name}: {error}")
            continue
        entries.append(entry)
        if args.verify:
            measured = ffmpeg_measured(name)
            if measured is None:
                print(f"  {name}: ffmpeg unavailable, skipped the cross-check")
            elif measured != entry.get("decodedSamples", entry["expectedSamples"]):
                failures.append(
                    f"{name}: manifest records {entry.get('decodedSamples', entry['expectedSamples'])} "
                    f"decoded samples, ffmpeg decoded {measured}"
                )
    if failures:
        for failure in failures:
            print(f"corpus_reference: {failure}", file=sys.stderr)
        return 1

    manifest = {
        "generatedBy": "scripts/fixtures/corpus_reference.py",
        "corpus": "MD0 desktop audio engine spike (PLAN MD0 AC1)",
        "files": entries,
    }
    if args.check:
        committed = json.load(open(MANIFEST, encoding="utf-8"))
        if committed != manifest:
            for computed, recorded in zip(manifest["files"], committed["files"]):
                if computed != recorded:
                    failures.append(f"{recorded['file']}: manifest disagrees with the recomputed entry")
            if len(committed["files"]) != len(manifest["files"]):
                failures.append(
                    f"corpus holds {len(manifest['files'])} files, the manifest records "
                    f"{len(committed['files'])}"
                )
            for failure in failures:
                print(f"corpus_reference: {failure}", file=sys.stderr)
            print(
                "corpus_reference: regenerate with "
                "python3 scripts/fixtures/corpus_reference.py --write "
                "playback/engine/src/test/resources/media/corpus.json",
                file=sys.stderr,
            )
            return 1
        print(f"corpus_reference: {len(entries)} entries match the committed manifest")
        return 0

    with open(args.write, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=False)
        handle.write("\n")
    print(f"corpus_reference: wrote {len(entries)} entries to {args.write}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
