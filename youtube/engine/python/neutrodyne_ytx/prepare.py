# SPDX-License-Identifier: Unlicense
"""Compile the prepared bundle on its host worker, before importing yt-dlp."""

import compileall
from pathlib import Path
import sys


def compile_bundle(directory):
    lib = Path(directory)
    if not compileall.compile_dir(str(lib), quiet=2, legacy=True, force=True):
        raise RuntimeError('Engine bytecode compilation failed')
    for source in lib.rglob('*.py'):
        if not source.with_suffix('.pyc').is_file():
            raise RuntimeError('Engine bytecode missing')
    for source in lib.rglob('*.py'):
        source.unlink()


def import_path(directory):
    if directory not in sys.path:
        sys.path.insert(0, directory)
