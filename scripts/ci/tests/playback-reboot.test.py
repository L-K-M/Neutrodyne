#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A reboot phase must complete exactly one test, without a skipped result."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("reboot", Path(__file__).parents[1] / "playback-reboot.py")
reboot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reboot)


class RebootEvidenceTest(unittest.TestCase):
    def test_requires_one_non_skipped_pass(self):
        reboot.require_one_pass("INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)")
        for output in ("", "OK (0 tests)", "OK (2 tests)", "FAILURES!!! Tests run: 1", "OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3"):
            with self.assertRaises(ValueError):
                reboot.require_one_pass(output)


if __name__ == "__main__":
    unittest.main()
