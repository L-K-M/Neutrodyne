# SPDX-License-Identifier: Unlicense
"""Desktop schema-1 targets derived from the canonical release asset inventory."""
import json
import re
from pathlib import Path


def desktop_assets(repo_root, version):
    mac_ext = "dmg" if int(version.split(".")[0]) >= 1 else "zip"
    inventory = json.loads((Path(repo_root) / "release-assets.json").read_text())
    result = {}
    for pattern in inventory["groups"]["desktop"]:
        name = pattern.replace("{v}", version).replace("{mac_ext}", mac_ext)
        match = re.fullmatch(r"neutrodyne-" + re.escape(version) + r"-(windows|macos|linux)-(x64|arm64)\.(msi|zip|dmg|deb|rpm|tar\.gz)", name)
        if not match:
            raise ValueError(f"unsupported desktop inventory entry: {name}")
        os_name, arch, kind = match.groups()
        if os_name == "macos" and kind == "zip":
            kind = "mac-zip"
        result[(os_name, arch, kind)] = name
    return result
