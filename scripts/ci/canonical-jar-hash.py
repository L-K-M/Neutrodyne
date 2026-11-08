#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Canonical JAR identity for the desktop image gate (11 Image scan rules).

SHA-256 over each entry's name and uncompressed content SHA-256, sorted by entry
name. Packaging recompresses image JARs and re-signs their Mach-O natives, so the
raw file hash would report every JAR as differing; Mach-O entries are normalized
(signature slots zeroed, __LINKEDIT extent bound). A non-ZIP file falls back to
its raw byte hash. Malformed or ambiguous names and duplicate entries fail closed.
This is the checker's half of `CanonicalJarHash` in DesktopPackaging.kt; the two
implementations must stay byte-for-byte identical.
"""

import hashlib
import os
import struct
import sys
import zipfile

MACHO_EXTS = {"dylib", "jnilib", "so"}


def fail(message):
    print(f"canonical-jar-hash: {message}", file=sys.stderr)
    sys.exit(1)


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def u32(b, o, be):
    return int.from_bytes(b[o:o + 4], "big" if be else "little") if 0 <= o and o + 4 <= len(b) else 0


def u64(b, o, be):
    return int.from_bytes(b[o:o + 8], "big" if be else "little") if 0 <= o and o + 8 <= len(b) else 0


def is_zero(b, start, length):
    return start >= 0 and length >= 0 and start + length <= len(b) and not any(b[start:start + length])


def is_linkedit_name(b, cmd_off):
    return b[cmd_off + 8:cmd_off + 24] == b"__LINKEDIT".ljust(16, b"\0")


def normalize_thin_macho(b):
    """Returns (normalized_bytes, signature_bytes). Unvalidated input stays raw."""
    if len(b) < 28:
        return b, 0
    magic = u32(b, 0, False)
    if magic == 0xFEEDFACF:
        is64, be = True, False
    elif magic == 0xFEEDFACE:
        is64, be = False, False
    elif magic == 0xCFFAEDFE:
        is64, be = True, True
    elif magic == 0xCEFAEDFE:
        is64, be = False, True
    else:
        return b, 0
    hdr = 32 if is64 else 28
    if len(b) < hdr:
        return b, 0
    ncmds = u32(b, 16, be)
    sizeofcmds = u32(b, 20, be)
    if ncmds > 4096 or hdr + sizeofcmds > len(b):
        return b, 0
    cmds = []
    blobs = []
    off = hdr
    for _ in range(ncmds):
        if off + 8 > len(b):
            return b, 0
        cmd, csz = u32(b, off, be), u32(b, off + 4, be)
        if csz < 8 or off + csz > len(b):
            return b, 0
        cmds.append((cmd, csz, off))
        if cmd == 0x1D:
            if csz != 16:
                return b, 0
            dataoff, datasize = u32(b, off + 8, be), u32(b, off + 12, be)
            if dataoff + datasize > len(b):
                return b, 0
            blobs.append((dataoff, datasize))
        off += csz
    # The commands must exactly cover the declared command region.
    if off != hdr + sizeofcmds:
        return b, 0
    if len(blobs) > 1:
        return b, 0
    csz_sum = sum(c[1] for c in cmds if c[0] == 0x1D)
    if csz_sum > sizeofcmds:
        return b, 0
    page = 0x4000 if u32(b, 4, be) in (0x0100000C, 0x0200000C) else 0x1000
    segments = [c for c in cmds if c[0] in (0x1, 0x19)]
    segment_kind, segment_header_size = (0x19, 72) if is64 else (0x1, 56)
    if any(c[0] != segment_kind or c[1] < segment_header_size for c in segments):
        return b, 0
    link_cmds = [c for c in segments if is_linkedit_name(b, c[2])]
    if len(link_cmds) != 1:
        return b, 0
    _, link_size, link_off = link_cmds[0]
    if link_size != segment_header_size or u32(b, link_off + (64 if is64 else 48), be) != 0:
        return b, 0
    if is64:
        vmsize, foff, fsize = u64(b, link_off + 32, be), u64(b, link_off + 40, be), u64(b, link_off + 48, be)
    else:
        vmsize, foff, fsize = u32(b, link_off + 28, be), u32(b, link_off + 32, be), u32(b, link_off + 36, be)
    # Only a final, disjoint linkedit range may contain masked signature bytes.
    if foff < off or foff > len(b) or fsize > len(b) - foff or foff + fsize != len(b):
        return b, 0
    for _, _, co in segments:
        if co == link_off:
            continue
        other_off = u64(b, co + 40, be) if is64 else u32(b, co + 32, be)
        other_size = u64(b, co + 48, be) if is64 else u32(b, co + 36, be)
        if other_off > len(b) or other_size > len(b) - other_off:
            return b, 0
        if other_size and other_off < foff + fsize and foff < other_off + other_size:
            return b, 0
    # A single terminal blob keeps all preceding linkedit data bound.
    for dataoff, datasize in blobs:
        if dataoff < foff or dataoff + datasize != foff + fsize:
            return b, 0
    inside = sum(d for _, d in blobs)
    if inside > fsize:
        return b, 0
    rounded = -(-fsize // page) * page
    if vmsize != fsize and vmsize != rounded:
        return b, 0
    out = bytearray(b[:16])
    out += struct.pack(">I" if be else "<I", ncmds - len(blobs))
    out += struct.pack(">I" if be else "<I", sizeofcmds - csz_sum)
    out += b[24:hdr]
    for cmd, csz, co in cmds:
        if cmd == 0x1D:
            out += b"\0" * csz
            continue
        cb = b[co:co + csz]
        if cmd in (0x1, 0x19) and is_linkedit_name(b, co):
            cb = bytearray(cb)
            non_sig = fsize - inside
            non_sig_rounded = -(-non_sig // page) * page
            if is64:
                cb[32:40] = non_sig_rounded.to_bytes(8, "big" if be else "little")
                cb[48:56] = non_sig.to_bytes(8, "big" if be else "little")
            else:
                cb[28:32] = non_sig_rounded.to_bytes(4, "big" if be else "little")
                cb[36:40] = non_sig.to_bytes(4, "big" if be else "little")
        out += cb
    emit = bytes(out) + b[off:]
    shift = 0
    for dataoff, datasize in sorted(blobs):
        start = dataoff - shift
        if 0 <= start < len(emit):
            end = min(len(emit), start + datasize)
            emit = emit[:start] + emit[end:]
        shift += datasize
    return emit, inside


def normalize_macho(data):
    if len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe":
        nfat = struct.unpack(">I", data[4:8])[0]
        if nfat < 1 or nfat > 64 or 8 + 20 * nfat > len(data):
            return data
        out = bytearray(data[:8])
        prev_end = 8 + 20 * nfat
        for i in range(nfat):
            ro = 8 + 20 * i
            off, size = struct.unpack(">II", data[ro + 8:ro + 16])
            align = struct.unpack(">I", data[ro + 16:ro + 20])[0]
            if align > 30:
                return data
            bound = 1 << align
            expected = -(-prev_end // bound) * bound
            if off != expected:
                return data
            if off + size > len(data):
                return data
            # Alignment padding is metadata-derived: require it zero and bind nothing.
            if not is_zero(data, prev_end, off - prev_end):
                return data
            thin, sig_size = normalize_thin_macho(data[off:off + size])
            if sig_size > size:
                return data
            out += data[ro:ro + 8] + b"\0" * 4 + struct.pack(">I", size - sig_size) + data[ro + 16:ro + 20]
            out += thin
            prev_end = off + size
        # Trailing bytes are not metadata-derived: bind them.
        if prev_end < len(data):
            out += data[prev_end:]
        return bytes(out)
    return normalize_thin_macho(data)[0]


def normalized(data, name):
    if name.rsplit(".", 1)[-1] not in MACHO_EXTS:
        return data
    return normalize_macho(data)


def content_sha256(path):
    """SHA-256 over a standalone file's normalized content: the same Mach-O
    normalization `canonical_sha256` applies to a JAR entry's bytes. Used to bind
    the native libraries Compose extracts from a skiko runtime jar, which the
    packager re-signs (so their raw bytes differ from the source artifact)."""
    with open(path, "rb") as handle:
        data = handle.read()
    return sha256_hex(normalized(data, os.path.basename(path)))


def validate_name(name):
    if not name:
        fail("empty ZIP entry name")
    if name.startswith("/"):
        fail(f"absolute ZIP entry name: {name!r}")
    if "\\" in name:
        fail(f"backslash in ZIP entry name: {name!r}")
    if "\0" in name:
        fail("NUL in ZIP entry name")
    if any(segment == ".." for segment in name.split("/")):
        fail(f"parent-directory ZIP entry name: {name!r}")
    if any(ord(ch) > 127 for ch in name):
        fail(f"non-ASCII ZIP entry name: {name!r}")


def canonical_sha256(path):
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        return sha256_hex(open(path, "rb").read())
    with z:
        infos = z.infolist()
        seen = set()
        for info in infos:
            name = info.filename
            # ZipInfo can truncate NULs or normalize Windows separators.
            validate_name(info.orig_filename)
            key = name.lower()
            if key in seen:
                fail(f"duplicate ZIP entry name: {name!r}")
            seen.add(key)
        outer = hashlib.sha256()
        for info in sorted(infos, key=lambda i: i.filename):
            if info.is_dir():
                content = b""
            else:
                try:
                    content = z.read(info)
                except Exception as exc:
                    fail(f"cannot read ZIP entry {info.filename!r}: {exc}")
            outer.update(info.filename.encode("utf-8"))
            outer.update(b"\0")
            outer.update(hashlib.sha256(normalized(content, info.filename)).hexdigest().encode("ascii"))
            outer.update(b"\n")
    return outer.hexdigest()


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--content":
        print(content_sha256(sys.argv[2]))
        return 0
    if len(sys.argv) != 2:
        print("usage: canonical-jar-hash.py <file> | canonical-jar-hash.py --content <file>", file=sys.stderr)
        return 2
    print(canonical_sha256(sys.argv[1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
