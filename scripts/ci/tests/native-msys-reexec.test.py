#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""An MSYS2 shell may still report MINGW: re-exec must be single-shot."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class MsysReexecTest(unittest.TestCase):
    def run_block(self, already_reexecuted):
        source = (ROOT / "playback/native/ffmpeg/build.sh").read_text()
        start = source.index('case "$(uname -s')
        end = source.index('\nesac', start) + len('\nesac')
        block = source[start:end]
        with tempfile.TemporaryDirectory() as temporary:
            shell = Path(temporary) / "bash.exe"
            shell.write_text('#!/bin/bash\n[ "$MSYS_IS_REEXEC" = 1 ] || exit 87\nexit 86\n')
            shell.chmod(0o700)
            block = block.replace('/c/msys64/usr/bin/bash.exe', str(shell))
            prefix = 'uname() { echo MINGW64_NT; }; log() { :; }; SCRIPT_PATH=unused; '
            env = os.environ.copy()
            env.pop("MSYS_IS_REEXEC", None)
            if already_reexecuted:
                env["MSYS_IS_REEXEC"] = "1"
            return subprocess.run(["bash", "-c", prefix + block], env=env).returncode

    def test_initial_git_bash_enters_msys(self):
        self.assertEqual(86, self.run_block(False))

    def test_reexecuted_mingw_shell_does_not_recurse(self):
        self.assertEqual(0, self.run_block(True))


if __name__ == "__main__":
    unittest.main()
