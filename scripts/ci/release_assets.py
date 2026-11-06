#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Release asset names and their lock-driven placeholders (09 Release assets).

`release-assets.json` names the asset set of a tag. Four names carry placeholders
that only the lockfiles can fill: `{jdk}` and `{wix}` from the desktop locks,
`{ffmpeg}` from the native components lock and `{pbsTag}` from the desktop Python
lock. `{server_runtime_source}` is the server image's Temurin source tarball; it
is attached only when it differs from the desktop runtime's source tarball (10
Image sources and the runtime exception), so a matching pair contributes one
asset, not two.

verify-tag.sh and make-release-body.py share this module so the expected asset
set and the release body can never disagree about which components ship.
"""

import argparse
import json
import re
import sys
from pathlib import Path

ASSETS_FILE = "release-assets.json"
RUNTIME_LOCK = "desktopApp/runtime.lock"
WIX_LOCK = "desktopApp/wix.lock"
NATIVE_LOCK = "playback/native/native-components.lock"
PYTHON_LOCK = "youtube/ytdlp-desktop/python-components.lock"
SERVER_SOURCE_LOCK = "sync/server/deploy/image-sources.lock.json"


def load_spec(repo_root: Path) -> dict:
    return json.loads((repo_root / ASSETS_FILE).read_text(encoding="utf-8"))


def lock_prop(path: Path, key: str) -> str | None:
    """The value of `key=` in a Java-properties lock, or None when absent."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return None
    for line in text.splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return None


def toml_section_prop(path: Path, section: str, key: str) -> str | None:
    """The value of `key = "v"` inside a `[section]` block of a TOML lock."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    in_section = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("["):
            in_section = stripped == f"[{section}]"
            continue
        if in_section and stripped.startswith(key):
            value = stripped.split("=", 1)[1].strip().strip('"')
            return value or None
    return None


def placeholders(repo_root: Path) -> dict[str, str | None]:
    """Every placeholder the asset names can carry, or None when it does not ship."""
    jdk = lock_prop(repo_root / RUNTIME_LOCK, "version")
    # The desktop release asset's name, not the upstream tarball's name: the
    # sources job renames Adoptium's OpenJDK25U-jdk-sources_*.tar.gz to
    # openjdk-{jdk}-temurin-sources.tar.gz.
    desktop_runtime_asset = f"openjdk-{jdk}-temurin-sources.tar.gz" if jdk else None
    server_source = None
    server_lock = repo_root / SERVER_SOURCE_LOCK
    if server_lock.is_file():
        try:
            server_source = json.loads(server_lock.read_text(encoding="utf-8"))["runtime"]["name"]
        except (OSError, KeyError, ValueError):
            server_source = None
    return {
        "{jdk}": jdk,
        "{wix}": lock_prop(repo_root / WIX_LOCK, "version"),
        "{ffmpeg}": toml_section_prop(repo_root / NATIVE_LOCK, "ffmpeg", "version"),
        "{pbsTag}": toml_section_prop(repo_root / PYTHON_LOCK, "pbsSource", "tag"),
        # The server image's runtime source is a second tarball only when it is
        # not the desktop runtime's source (10 Image sources and the runtime
        # exception). A matching name would be the same asset twice.
        "{server_runtime_source}": (
            server_source if server_source and server_source != desktop_runtime_asset else None
        ),
    }


def expand_asset(name: str, version: str, mac_ext: str, ph: dict[str, str | None]) -> str | None:
    """Expand one asset name, or None when a placeholder does not ship."""
    name = name.replace("{v}", version).replace("{mac_ext}", mac_ext)
    for placeholder, value in ph.items():
        if placeholder in name:
            if not value:
                return None
            name = name.replace(placeholder, value)
    if re.search(r"\{[a-zA-Z_]+\}", name):
        raise ValueError(f"release-assets.json carries an unknown placeholder: {name}")
    return name


def expected_assets(repo_root: Path, products: list[str], version: str, mac_ext: str) -> list[str]:
    """The expected asset names for the given product groups, in file order."""
    spec = load_spec(repo_root)
    ph = placeholders(repo_root)
    names = list(spec["always"])
    for group in products:
        files = spec["groups"].get(group)
        if files is None:
            raise ValueError(f"release-assets.json has no group '{group}'")
        names += files
    expanded = []
    for name in names:
        asset = expand_asset(name, version, mac_ext, ph)
        if asset is not None:
            expanded.append(asset)
    return expanded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["expected", "placeholders"])
    parser.add_argument("repo_root", type=Path)
    parser.add_argument("products", nargs="?")
    parser.add_argument("version", nargs="?")
    parser.add_argument("mac_ext", nargs="?")
    args = parser.parse_args()
    if args.command == "expected":
        for asset in expected_assets(
            args.repo_root,
            [product for product in (args.products or "").split(",") if product],
            args.version or "",
            args.mac_ext or "",
        ):
            print(asset)
    elif args.command == "placeholders":
        for name, value in placeholders(args.repo_root).items():
            print(f"{name}={value or ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
