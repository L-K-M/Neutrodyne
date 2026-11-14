#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Extract a pinned Python archive without allowing members to escape its root."""

import ntpath
import pathlib
import sys
import tarfile


def safe_members(archive):
    for member in archive:
        name = member.name
        parts = pathlib.PurePosixPath(name).parts
        if not name or "\\" in name or name.startswith("/") or ntpath.splitdrive(name)[0] or ".." in parts:
            raise ValueError(f"unsafe archive member: {name!r}")
        # tarfile may resolve an absent hardlink target from the archive without
        # reapplying the extraction filter. The pinned runtimes have no hardlinks.
        if member.islnk():
            raise ValueError(f"hardlinks are not supported: {name!r}")
        if member.issym():
            target = member.linkname
            if not target or "\\" in target or target.startswith("/") or ntpath.splitdrive(target)[0]:
                raise ValueError(f"unsafe link target: {target!r}")
        # The trim always removes share/. Omitting it also avoids case-insensitive
        # terminfo symlink collisions on macOS hosts.
        if parts[:2] == ("python", "share"):
            continue
        yield member


def main():
    if len(sys.argv) != 3:
        raise ValueError("usage: extract-python-archive.py <archive> <destination>")
    if not hasattr(tarfile, "data_filter"):
        raise ValueError("Python with tarfile.data_filter is required for safe extraction")
    with tarfile.open(sys.argv[1], "r:gz") as archive:
        archive.errorlevel = 2
        archive.extractall(sys.argv[2], members=safe_members(archive), filter="data")


if __name__ == "__main__":
    try:
        main()
    except (OSError, tarfile.TarError, ValueError) as error:
        print(f"extract-python-archive.py: {error}", file=sys.stderr)
        sys.exit(1)
