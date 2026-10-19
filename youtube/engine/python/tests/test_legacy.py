# SPDX-License-Identifier: Unlicense
"""The S7 host imports only the packaged shim, before engine-host activation."""

import json
from pathlib import Path
import subprocess
import sys
import unittest


class LegacyRuntimeTests(unittest.TestCase):
    def test_runtime_selftest_without_engine_or_http_configuration(self):
        shim = Path(__file__).resolve().parents[1]
        code = (
            'import sys; sys.path.insert(0, sys.argv[1]); '
            'from neutrodyne_ytx.selftest import selftest; print(selftest())'
        )
        result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', code, str(shim)],
                                capture_output=True, text=True, timeout=10, check=True)
        runtime = json.loads(result.stdout)
        self.assertTrue(runtime['ok'])
        self.assertEqual(runtime['python'], sys.version.split()[0])
        self.assertTrue(runtime['openssl'].startswith('OpenSSL '))
