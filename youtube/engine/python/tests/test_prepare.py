# SPDX-License-Identifier: Unlicense
import tempfile
from pathlib import Path
import unittest

from neutrodyne_ytx.prepare import compile_bundle


class PreparationTests(unittest.TestCase):
    def test_legacy_bytecode_replaces_every_source_after_success(self):
        with tempfile.TemporaryDirectory() as directory:
            lib = Path(directory)
            package = lib / 'fixture'
            package.mkdir()
            (package / '__init__.py').write_text('value = 42\n')
            compile_bundle(directory)
            self.assertFalse(list(lib.rglob('*.py')))
            self.assertTrue((package / '__init__.pyc').is_file())

    def test_compile_failure_keeps_sources_for_a_safe_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'invalid.py'
            source.write_text('def invalid(\n')
            with self.assertRaises(RuntimeError):
                compile_bundle(directory)
            self.assertTrue(source.is_file())
            self.assertFalse(list(Path(directory).glob('.compiled-*')))
