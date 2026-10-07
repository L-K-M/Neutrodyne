#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# public-cert-sha256.sh [--colons]
#
# Prints the SHA-256 of the certificate inside the committed
# signing/neutrodyne-public.keystore (09 Committed keystore). Lower-case hex by
# default, colon-separated pairs with --colons. This is the expected signer for
# release.yml's `android` job and for check-apk.sh --published — computed from the
# committed file, so no repository variable can drift from it. The value is a
# consistency anchor, not a trust anchor: the key is public on purpose (D61).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
KEYSTORE="$REPO_ROOT/signing/neutrodyne-public.keystore"

COLONS=0
for arg in "$@"; do
    case "$arg" in
        --colons) COLONS=1 ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "usage: $0 [--colons]" >&2; exit 2 ;;
    esac
done

[ -f "$KEYSTORE" ] || { echo "public-cert-sha256: $KEYSTORE missing" >&2; exit 1; }
if ! command -v java >/dev/null 2>&1 && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v keytool >/dev/null 2>&1 || { echo "public-cert-sha256: keytool not found (need a JDK)" >&2; exit 2; }

# `Certificate fingerprints: SHA256: 38:08:FE:...` (JDK ≥9) — keytool's own
# digest of the certificate, same value apksigner reports for the APK signer.
line="$(keytool -list -v -keystore "$KEYSTORE" -storepass neutrodyne -alias neutrodyne \
    | grep -m1 -E 'SHA-?256:')"
digest="$(echo "$line" | sed -E 's/.*SHA-?256:[[:space:]]*//;s/[^0-9A-Fa-f]//g' | tr 'A-F' 'a-f')"

[ ${#digest} -eq 64 ] || { echo "public-cert-sha256: could not read a SHA-256 from keytool" >&2; exit 1; }
if [ "$COLONS" -eq 1 ]; then
    echo "$digest" | sed 's/../&:/g;s/:$//'
else
    echo "$digest"
fi
