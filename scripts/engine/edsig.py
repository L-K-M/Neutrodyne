# SPDX-License-Identifier: Unlicense
"""Ed25519 detached signatures over the manifest trust chain, via openssl.

The committed `engine-manifest-ed25519.pub` files hold the raw 32-byte
public key base64'd (one line each; slot 2 is the rotation slot, 09 Key
custody). openssl wants SubjectPublicKeyInfo DER, so the raw key is
wrapped in the fixed Ed25519 SPKI prefix `302a300506032b6570032100`.
"""

import base64
import subprocess
import tempfile
from pathlib import Path

SPKI_PREFIX = bytes.fromhex('302a300506032b6570032100')

# The manifest signature sidecar is a single base64 line, matching the
# sidecar convention `<document>.sig` the apps fetch next to the document.
def public_keys(keys_dir):
    """Every pinned raw public key (base64 text) in a keys directory."""
    return [
        path.read_text().strip()
        for path in sorted(Path(keys_dir).glob('engine-manifest-*.pub'))
        if path.read_text().strip()
    ]


def _der_public_key(raw_base64):
    return SPKI_PREFIX + base64.b64decode(raw_base64)


def verify_detached(document, signature_b64, keys_dir, work_dir=None):
    """True when the base64 signature verifies under any pinned public key."""
    signature = base64.b64decode(signature_b64.strip())
    for key in public_keys(keys_dir):
        der = _der_public_key(key)
        if _verify_der(der, document, signature, work_dir):
            return True
    return False


def _verify_der(der_key, document, signature, work_dir):
    with tempfile.TemporaryDirectory(dir=work_dir) as tmp:
        key = Path(tmp) / 'key.der'
        doc = Path(tmp) / 'doc'
        sig = Path(tmp) / 'sig'
        key.write_bytes(der_key)
        doc.write_bytes(document)
        sig.write_bytes(signature)
        result = subprocess.run(
            [
                'openssl', 'pkeyutl', '-verify', '-rawin',
                '-pubin', '-keyform', 'DER', '-inkey', str(key),
                '-in', str(doc), '-sigfile', str(sig),
            ],
            capture_output=True,
        )
    return result.returncode == 0


def sign_detached(pem_path, document, work_dir=None):
    """The base64 signature of `document` under the PEM Ed25519 private key."""
    with tempfile.TemporaryDirectory(dir=work_dir) as tmp:
        doc = Path(tmp) / 'doc'
        sig = Path(tmp) / 'sig'
        doc.write_bytes(document)
        subprocess.run(
            [
                'openssl', 'pkeyutl', '-sign', '-rawin',
                '-inkey', str(pem_path), '-in', str(doc), '-out', str(sig),
            ],
            check=True,
            capture_output=True,
        )
        return base64.b64encode(sig.read_bytes()).decode()


def public_key_of(pem_path):
    """Base64 of the raw 32-byte public half of a PEM Ed25519 private key."""
    der = subprocess.run(
        ['openssl', 'pkey', '-in', str(pem_path), '-pubout', '-outform', 'DER'],
        check=True,
        capture_output=True,
    ).stdout
    return base64.b64encode(der[-32:]).decode()
