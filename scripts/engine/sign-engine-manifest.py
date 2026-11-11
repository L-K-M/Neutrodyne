#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Sign a canary document (manifest or heartbeat) with the manifest key.

    sign-engine-manifest.py DOCUMENT [--key-env NAME]

The private key comes only from the environment secret named by
`--key-env` (default `NEUTRODYNE_ENGINE_MANIFEST_KEY`, the `engine-approval`
environment's secret — base64 of the PEM, or the PEM itself). It is
decoded into a temp directory under `$RUNNER_TEMP` and removed again; the
key never touches the checkout or an artifact.

Before writing `DOCUMENT.sig` the key's public half must match one of the
committed `engine-manifest-*.pub` slots and the produced signature must
verify against it — a key the apps do not pin fails here, not on devices.

Exit 77 (dry run) when the secret is absent: forks and unconfigured
environments exercise the whole pipeline without a key and without
publishing. Other failures exit 1.
"""

import argparse
import base64
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import edsig

ROOT = Path(__file__).resolve().parents[2]
KEYS_DIR = ROOT / 'youtube/ytdlp/keys'
DEFAULT_KEY_ENV = 'NEUTRODYNE_ENGINE_MANIFEST_KEY'
DRY_RUN = 77


def pem_from_secret(value, work_dir):
    """Decode the secret (base64 PEM, or the PEM verbatim) into a file."""
    text = value.strip()
    if '-----BEGIN' not in text:
        try:
            text = base64.b64decode(text).decode()
        except Exception:
            sys.exit('sign-engine-manifest: secret is neither base64 nor PEM')
    if '-----BEGIN' not in text:
        sys.exit('sign-engine-manifest: secret does not decode to a PEM key')
    path = Path(work_dir) / 'key.pem'
    path.write_text(text)
    path.chmod(0o600)
    return path


def sign(document_path, secret_value, keys_dir=KEYS_DIR, temp_root=None):
    """Write `<document>.sig`; exits nonzero on any trust failure."""
    document = Path(document_path).read_bytes()
    with tempfile.TemporaryDirectory(dir=temp_root) as work:
        pem = pem_from_secret(secret_value, work)
        public = edsig.public_key_of(pem)
        pinned = edsig.public_keys(keys_dir)
        if public not in pinned:
            sys.exit(
                'sign-engine-manifest: the secret key is not one of the committed '
                'engine-manifest-*.pub slots (wrong or rotated key?)'
            )
        signature = edsig.sign_detached(pem, document, work)
        if not edsig.verify_detached(document, signature, keys_dir, work):
            sys.exit('sign-engine-manifest: produced signature failed self-verification')
    Path(str(document_path) + '.sig').write_text(signature + '\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('document', type=Path)
    parser.add_argument('--key-env', default=DEFAULT_KEY_ENV)
    parser.add_argument('--keys-dir', type=Path, default=KEYS_DIR)
    args = parser.parse_args(argv)

    secret = os.environ.get(args.key_env, '').strip()
    if not secret:
        print(f'dry-run: {args.key_env} is not set; {args.document.name}.sig not written')
        return DRY_RUN
    sign(args.document, secret, args.keys_dir, os.environ.get('RUNNER_TEMP'))
    print(f'signed {args.document.name} ({args.document.name}.sig written)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
