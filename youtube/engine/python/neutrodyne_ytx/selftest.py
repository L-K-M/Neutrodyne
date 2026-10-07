# SPDX-License-Identifier: Unlicense
"""S7 probe (01 Spikes): reports the embedded runtime's Python and OpenSSL versions.

M9a extends this into the real API probe (`{ok, missing[]}`, 04 Engine updates). It must keep
returning a JSON string so the Binder transport can pass it through untouched.
"""

import json
import platform
import ssl


def selftest():
    """Return a JSON string with the interpreter's Python and OpenSSL versions."""
    return json.dumps({
        "ok": True,
        "python": platform.python_version(),
        "openssl": ssl.OPENSSL_VERSION,
    })
