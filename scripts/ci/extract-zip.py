#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Extract a ZIP, restoring each entry's recorded modification time and Unix mode.

The release ZIPs are written with ``isPreserveFileTimestamps = true`` (Gradle's
Zip task), so every entry carries the file's original mtime. Python's
``zipfile.extractall`` drops those times, which would silently lose the trained
AOT cache in a smoke run from an extracted ZIP: the JVM falls back to the
non-AOT path when a classpath JAR's recorded timestamp no longer matches. This
extractor restores the artifact's own recorded times instead of stamping an
expected epoch — a drifted producer stays drifted, so a strict
``-XX:AOTMode=on`` launch still fails.

The Info-ZIP extended timestamp extra field (0x5455) carries UTC seconds since
the epoch; when it is present it is authoritative. When it is absent the
MS-DOS time is interpreted in the local timezone, which matches the build runner
because the release builds and smokes on the same host. That fallback is the
cross-timezone gap: a ZIP written in another timezone and extracted here would
restore a different UTC instant, and the MS-DOS time's 2-second resolution is
another (smaller) loss.

Usage: extract-zip.py <archive.zip> <destination-dir>
Exit 0 = extracted; 1 = a corrupt entry, a path traversal or a write failure.
"""

import os
from pathlib import Path
import stat
import sys
import time
import zipfile

EXTENDED_TIMESTAMP_ID = 0x5455


class ExtractionError(Exception):
    pass


def entry_mtime(info: zipfile.ZipInfo) -> int:
    """The entry's modification time in seconds since the epoch (UTC)."""
    data = info.extra
    offset = 0
    mtime: int | None = None
    while offset < len(data):
        if offset + 4 > len(data):
            raise ExtractionError(
                f"truncated extra field header in {info.filename!r}"
            )
        header_id = int.from_bytes(data[offset : offset + 2], "little")
        size = int.from_bytes(data[offset + 2 : offset + 4], "little")
        if offset + 4 + size > len(data):
            raise ExtractionError(
                f"extra field {header_id:#06x} overruns its data in {info.filename!r}"
            )
        if header_id == EXTENDED_TIMESTAMP_ID:
            # The data is flags (1 byte) followed by the 4-byte mtime when bit 0 is
            # set, so a size below 5 cannot carry it. The loop keeps validating
            # the remaining fields: malformed bytes after a valid timestamp
            # still mean a corrupt artifact.
            if size < 5:
                raise ExtractionError(
                    f"corrupt extended timestamp extra field in {info.filename!r}"
                )
            flags = data[offset + 4]
            if not flags & 0x1:
                raise ExtractionError(
                    f"extended timestamp extra field has no mtime in {info.filename!r}"
                )
            mtime = int.from_bytes(data[offset + 5 : offset + 9], "little")
        offset += 4 + size
    if mtime is not None:
        return mtime
    # MS-DOS time: the local time of the host that wrote the ZIP. time.mktime
    # interprets it in this host's local timezone.
    return int(time.mktime(info.date_time + (0, 0, -1)))


def safe_target(destination: Path, name: str) -> Path:
    """Resolve an entry name below the destination, refusing path traversal."""
    root = destination.resolve()
    target = (destination / name).resolve()
    if target != root and root not in target.parents:
        raise ExtractionError(f"entry escapes the destination: {name!r}")
    return target


def extract(zip_path: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    directories: list[tuple[Path, int]] = []
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            target = safe_target(destination, info.filename)
            mtime = entry_mtime(info)
            mode = stat.S_IMODE(info.external_attr >> 16)
            if stat.S_ISLNK(info.external_attr >> 16):
                # The Windows image the release ZIP archives never carries
                # links. Materializing the entry as a plain file would hide a
                # producer defect, and creating a real link is unsafe here —
                # an escaping target is unchecked and Windows link creation
                # needs privileges the smoke does not have.
                raise ExtractionError(f"symbolic link entry: {info.filename!r}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                directories.append((target, mtime))
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, open(target, "wb") as out:
                out.write(source.read())
            if mode:
                os.chmod(target, mode)
            os.utime(target, (mtime, mtime))
    # Creating files updates a directory's mtime, so restore the directories'
    # own times last, deepest first.
    for path, mtime in sorted(directories, key=lambda entry: -len(entry[0].parts)):
        os.utime(path, (mtime, mtime))


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"usage: {argv[0]} <archive.zip> <destination-dir>", file=sys.stderr)
        return 2
    try:
        extract(Path(argv[1]), Path(argv[2]))
    except (ExtractionError, OSError, zipfile.BadZipFile) as error:
        print(f"extract-zip: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
