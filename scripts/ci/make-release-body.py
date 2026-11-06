#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Render the release body from scripts/ci/release-body-template.md (09 Release body).

release.yml's `publish` job calls this after the asset set is verified and before the
draft is created, so the notes always name the assets the release actually carries:
a block whose product the release does not ship is left out. The placeholders come
from the same lockfiles as verify-tag's expected asset set (release_assets.py), and
the changelog is the tagged `changelogs/<versionCode>.txt`.

The template is the source of truth for the body; this script only fills it in. The
test renders a sample tag and checks the required sections (09/AC13).
"""

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_assets

TEMPLATE = Path(__file__).resolve().with_name("release-body-template.md")
DIGEST_RE = re.compile(r"^(?:ghcr\.io/[^@]+@)?(sha256:[0-9a-f]{64})$")
TAG_RE = re.compile(r"^v\d+\.\d+\.\d+$")


def gradle_property(repo_root: Path, key: str) -> str:
    for line in (repo_root / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    raise ValueError(f"gradle.properties carries no {key}")


def normalize_digest(value: str) -> str:
    match = DIGEST_RE.match(value)
    if not match:
        raise ValueError(f"the server image must be a digest reference, got: {value}")
    return match.group(1)


def render(template: str, flags: dict[str, bool], values: dict[str, str]) -> str:
    """Fill the template: `{% if flag %}` blocks and `{{placeholder}}` values."""
    tokens = re.split(r"({% if [a-z]+ %}|{% endif %})", template)
    active: list[bool] = []
    output = []
    for token in tokens:
        if token.startswith("{% if "):
            flag = token[len("{% if ") : -len(" %}")]
            active.append(all(active) and flags[flag])
        elif token == "{% endif %}":
            if not active:
                raise ValueError("release body template: unmatched {% endif %}")
            active.pop()
        elif all(active):
            output.append(token)
    if active:
        raise ValueError("release body template: unmatched {% if %}")
    body = "".join(output)
    for name, value in values.items():
        body = body.replace("{{" + name + "}}", value)
    leftover = re.search(r"{{[a-z]+}}|{%", body)
    if leftover:
        raise ValueError(f"release body template: unresolved placeholder {leftover.group(0)}")
    return body


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--products", required=True, help="comma-separated release-assets.json groups")
    parser.add_argument("--digest", help="server image reference or sha256 digest")
    parser.add_argument("--ffmpeg-version", help="verify-tag's ffmpeg_version output")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    if not TAG_RE.match(args.tag):
        raise ValueError(f"tag {args.tag} is not vX.Y.Z")
    version = gradle_property(args.repo_root, "neutrodyne.versionName")
    version_code = gradle_property(args.repo_root, "neutrodyne.versionCode")
    repo_url = gradle_property(args.repo_root, "neutrodyne.repoUrl")
    if args.tag != "v" + version:
        raise ValueError(f"tag {args.tag} but gradle.properties carries versionName={version}")

    changelog_path = args.repo_root / "changelogs" / f"{version_code}.txt"
    changelog = changelog_path.read_text(encoding="utf-8").strip()
    if not changelog:
        raise ValueError(f"{changelog_path} is empty")
    if len(changelog) > 500:
        raise ValueError(f"{changelog_path} is longer than 500 characters")

    products = [product for product in args.products.split(",") if product]
    major = int(version.split(".")[0])
    mac_ext = "dmg" if major >= 1 else "zip"
    assets = release_assets.expected_assets(args.repo_root, products, version, mac_ext)
    ph = release_assets.placeholders(args.repo_root)
    if args.ffmpeg_version and args.ffmpeg_version != ph["{ffmpeg}"]:
        raise ValueError(
            f"verify-tag names FFmpeg {args.ffmpeg_version} but native-components.lock "
            f"names {ph['{ffmpeg}']}"
        )

    server = any(asset.startswith("neutrodyne-server-") for asset in assets)
    digest = normalize_digest(args.digest) if args.digest else None
    if server and not digest:
        raise ValueError("the release carries the server image but no digest was given")
    if digest and not server:
        raise ValueError("a server image digest was given but the release carries no server asset")

    flags = {
        "changelog": True,
        "android": any(asset.endswith(".apk") for asset in assets),
        "desktop": "desktop" in products,
        "server": server,
        "ffmpeg": any(asset.startswith("ffmpeg-") and asset.endswith(".tar.xz") for asset in assets),
        "pbs": any(asset.startswith("python-build-standalone-") for asset in assets),
        "wix": any(asset.startswith("wix-") for asset in assets),
        "jdk": any(asset.startswith("openjdk-") for asset in assets),
    }
    # The template writes `{{owner}}/Neutrodyne`, so owner is the GitHub account.
    owner = repo_url.removeprefix("https://github.com/").split("/")[0]
    values = {
        "changelog": changelog,
        "v": version,
        "tag": args.tag,
        "owner": owner,
        "repoUrl": repo_url,
        "mac_ext": mac_ext,
        "jdk": ph["{jdk}"] or "",
        "wix": ph["{wix}"] or "",
        "ffmpeg": ph["{ffmpeg}"] or "",
        "pbsTag": ph["{pbsTag}"] or "",
        "digest": digest or "",
    }
    body = render(TEMPLATE.read_text(encoding="utf-8"), flags, values)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(body, encoding="utf-8")
    print(f"make-release-body: wrote {args.out} ({len(body)} chars, products {args.products})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(f"make-release-body: FAIL  {error}", file=sys.stderr)
        sys.exit(1)
