#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Write the engine approval manifest the apps download (04 Trust chain).

Reads the staged candidate from `youtube/ytdlp/engine/bundled.json` (what
`bump-ytdlp.sh` left and the gate verified) and the previous manifest with
its detached signature; the signature must verify against the committed
public keys before `sequence`/`revoked` are trusted, so a tampered
`engine-approvals` asset can never roll a run backwards.

    make-engine-manifest.py --current M --current-sig S --out O
        [--revoke VERSION]... [--keep-candidate] [--floor VERSION]
        [--now ISO-INSTANT]
    make-engine-manifest.py --bootstrap --out O [--revoke V]... [--now I]
    make-engine-manifest.py --heartbeat --sequence N --out O [--now I]

`--floor` is the version bundled in the current release's checkout: the
canary never approves anything below it (09 engine-canary.yml Rules).
`--keep-candidate` re-emits the current `ytdlp` block for a revoke-only
dispatch where the gate did not stage a new engine.
"""

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import edsig

ROOT = Path(__file__).resolve().parents[2]
KEYS_DIR = ROOT / 'youtube/ytdlp/keys'
BUNDLED = ROOT / 'youtube/ytdlp/engine/bundled.json'
VERIFIER = ROOT / 'youtube/engine/src/main/kotlin/ch/lkmc/neutrodyne/youtube/engine/EngineManifestVerifier.kt'

VERSION_PATTERN = re.compile(r'[0-9]{4}\.[0-9]{2}\.[0-9]{2}')
EJS_PATTERN = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+')
SCHEMA = 1


def shim_api():
    """min/max the shim suites ran: 1..SHIM_API_VERSION from the verifier."""
    constant = re.search(r'SHIM_API_VERSION = (\d+)', VERIFIER.read_text())
    if not constant:
        sys.exit('make-engine-manifest: SHIM_API_VERSION not found in EngineManifestVerifier.kt')
    return {'min': 1, 'max': int(constant.group(1))}


def load_current(path, signature_path, keys_dir=KEYS_DIR):
    """The previous manifest after its detached signature verifies."""
    document = Path(path).read_bytes()
    signature = Path(signature_path).read_text()
    if not edsig.verify_detached(document, signature, keys_dir):
        sys.exit('make-engine-manifest: current manifest signature does not verify')
    manifest = json.loads(document)
    if manifest.get('schema') != SCHEMA:
        sys.exit('make-engine-manifest: current manifest schema is not 1')
    return manifest


def candidate_block(bundled=BUNDLED):
    meta = json.loads(Path(bundled).read_text())
    version = meta['version']
    if not VERSION_PATTERN.fullmatch(version) or meta.get('tag') != version:
        sys.exit(f'make-engine-manifest: candidate version {version!r} is not a stable tag')
    if not EJS_PATTERN.fullmatch(meta['ejsVersion']):
        sys.exit(f"make-engine-manifest: ejsVersion {meta['ejsVersion']!r} is malformed")
    return {
        'version': version,
        'tag': meta['tag'],
        'repo': 'yt-dlp/yt-dlp',
        'asset': 'yt-dlp',
        'sha256': meta['sha256'],
        'ejsVersion': meta['ejsVersion'],
    }


def version_key(version):
    return tuple(int(part) for part in version.split('.'))


def heartbeat(sequence, issued_at):
    return {'kind': 'heartbeat', 'lastRunAt': issued_at, 'sequence': sequence}


def build(candidate, sequence, issued_at, revoked):
    return {
        'schema': SCHEMA,
        'sequence': sequence,
        'issuedAt': issued_at,
        'ytdlp': candidate,
        'shimApi': shim_api(),
        'revoked': revoked,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--current', type=Path)
    parser.add_argument('--current-sig', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--revoke', action='append', default=[])
    parser.add_argument('--bootstrap', action='store_true')
    parser.add_argument('--keep-candidate', action='store_true')
    parser.add_argument('--floor')
    parser.add_argument('--bundled', type=Path, default=BUNDLED)
    parser.add_argument('--keys-dir', type=Path, default=KEYS_DIR)
    parser.add_argument('--now', help='ISO-8601 instant; tests pin this')
    parser.add_argument('--heartbeat', action='store_true')
    parser.add_argument('--sequence', type=int)
    args = parser.parse_args(argv)

    issued_at = args.now or datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

    if args.heartbeat:
        if not args.sequence:
            parser.error('--heartbeat needs --sequence')
        args.out.write_text(json.dumps(heartbeat(args.sequence, issued_at), indent=2) + '\n')
        return

    for version in args.revoke:
        if not VERSION_PATTERN.fullmatch(version):
            sys.exit(f'make-engine-manifest: revoked version {version!r} is not yyyy.mm.dd')

    current = None
    if args.current:
        if not args.current_sig:
            parser.error('--current needs --current-sig')
        current = load_current(args.current, args.current_sig, args.keys_dir)
    elif not args.bootstrap:
        parser.error('no --current manifest: pass --bootstrap for the very first run')

    if current and args.bootstrap:
        parser.error('--bootstrap must not be combined with --current')

    if args.keep_candidate:
        if not current:
            parser.error('--keep-candidate needs --current')
        candidate = current['ytdlp']
        if not args.revoke:
            sys.exit('make-engine-manifest: nothing changed; publish the current manifest instead')
    else:
        candidate = candidate_block(args.bundled)
        if args.floor and version_key(candidate['version']) < version_key(args.floor):
            sys.exit(
                f"make-engine-manifest: candidate {candidate['version']} is below "
                f'the bundled floor {args.floor}'
            )

    revoked = sorted(set((current or {}).get('revoked', [])) | set(args.revoke))
    if candidate['version'] in revoked:
        sys.exit(f"make-engine-manifest: refusing to approve revoked {candidate['version']}")

    sequence = (current or {}).get('sequence', 0) + 1
    manifest = build(candidate, sequence, issued_at, revoked)
    args.out.write_text(json.dumps(manifest, indent=2) + '\n')
    print(f"sequence={sequence} version={candidate['version']}")


if __name__ == '__main__':
    main()
