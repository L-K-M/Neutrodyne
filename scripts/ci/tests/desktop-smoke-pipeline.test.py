#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Behavioral regression: a failed packaged smoke must fail the desktop-smoke step.

`ci.yml`'s `desktop-smoke` "Smoke-start the image and check it" step pipes the
packaged smoke start through `tee smoke.txt` so the SMOKE line is captured for
evidence and for `check-runtime-sources.sh --smoke`. GitHub runs an unspecified
Linux `run:` shell as `bash -e {0}` -- no pipefail -- so a smoke start that
exits nonzero while still printing a valid-looking SMOKE line leaves the
pipeline at tee's 0; `bash -e` then runs the image and runtime gates on a
launch that already failed and the step reports success. With `shell: bash`
(`bash --noprofile --norc -eo pipefail {0}`) or a local `set -euo pipefail`
the pipeline's nonzero status fails the step and the downstream gates never
run.

This suite extracts the ACTUAL run block from `ci.yml` (indentation/regex, no
YAML dependency) and executes it under the step's effective workflow shell
with a controlled fake xvfb/dbus/smoke producer and fake downstream gates. It
is not a mirror of the block: the block's text is the input.

Cases:
  * a smoke producer that exits 1 while printing a valid-looking SMOKE line
    must fail the step and must NOT reach the image/runtime gates (the false
    green this regression exists to prevent);
  * a smoke producer that exits 0 must pass the step and reach both gates,
    proving the downstream gates really run on success.

Run: python3 scripts/ci/tests/desktop-smoke-pipeline.test.py
Exit 0 = the pipeline's failure semantics hold; 1 = regression.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
JOB = "desktop-smoke"
STEP_NAME = "Smoke-start the image and check it"
BASH = shutil.which("bash") or "bash"
STEP_TIMEOUT_SECONDS = 30

# A valid-looking smoke verdict: the same shape scripts/desktop/smoke-start.sh
# prints and check-runtime-sources.sh consumes.
SMOKE_LINE = ('SMOKE {"java.vendor":"Eclipse Adoptium",'
              '"java.vendor.version":"Temurin-25.0.4.1+1",'
              '"java.runtime.version":"25.0.4.1+1-LTS"}')

STEP_HEAD = re.compile(r"^\s*-\s+name:\s*" + re.escape(STEP_NAME) + r"\s*$")
RUN_LITERAL = re.compile(r"^(\s*)run:\s*\|\s*$")
SHELL_LINE = re.compile(r"^\s*shell:\s*bash\b")

FAKE_XVFB_RUN = """\
#!/bin/sh
# Drop xvfb-run's own options, then run the wrapped command (dbus-run-session).
while [ $# -gt 0 ]; do
  case "$1" in
    -a) shift ;;
    *) break ;;
  esac
done
exec "$@"
"""

FAKE_DBUS_RUN_SESSION = """\
#!/bin/sh
[ "$1" = "--" ] && shift
exec "$@"
"""

FAKE_SMOKE_START = """\
#!/bin/sh
# A smoke start that prints a valid-looking SMOKE line and then reports the
# launch failure it actually saw.
echo '%s'
exit "${SMOKE_EXIT:-1}"
""" % SMOKE_LINE

FAKE_CHECK_IMAGE = """\
#!/bin/sh
echo "check-desktop-image" >> "$TRACE"
exit 0
"""

FAKE_CHECK_RUNTIME = """\
#!/bin/sh
echo "check-runtime-sources" >> "$TRACE"
grep -q '^SMOKE {"java.vendor":"Eclipse Adoptium"' smoke.txt || {
  echo "no valid-looking SMOKE line reached the runtime gate" >&2
  exit 1
}
exit 0
"""


def _indent(line):
    return len(line) - len(line.lstrip(" "))


def _job_lines(lines, job):
    start = next(i for i, line in enumerate(lines) if line.strip() == job + ":")
    base = _indent(lines[start])
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].strip() and _indent(lines[i]) <= base), len(lines))
    return lines[start:end]


def _smoke_step(lines):
    block = _job_lines(lines, JOB)
    start = next(i for i, line in enumerate(block) if STEP_HEAD.match(line))
    base = _indent(block[start])
    end = next((i for i in range(start + 1, len(block))
                if block[i].strip() and _indent(block[i]) <= base), len(block))
    return block[start:end]


def _run_block(step):
    for index, line in enumerate(step):
        literal = RUN_LITERAL.match(line)
        if not literal:
            continue
        indent = _indent(line)
        body = []
        for line in step[index + 1:]:
            if line.strip() and _indent(line) <= indent:
                break
            body.append(line)
        script = textwrap.dedent("\n".join(body)).strip("\n")
        if not script.strip():
            raise ValueError("the desktop-smoke run block is empty")
        return script + "\n"
    raise ValueError("the desktop-smoke step has no literal run block")


def _effective_shell(step):
    """GitHub's shell for the step: explicit `bash` or the Linux default."""
    run_index = next(i for i, line in enumerate(step) if RUN_LITERAL.match(line))
    if any(SHELL_LINE.match(line) for line in step[:run_index]):
        return [BASH, "--noprofile", "--norc", "-eo", "pipefail"]
    return [BASH, "-e"]


class DesktopSmokePipelineTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        # The image directory the step's `img=` path points at.
        (self.root / "desktopApp/build/compose/binaries/main/app/Neutrodyne"
         ).mkdir(parents=True)
        (self.root / "tmp").mkdir()
        self.write("bin/xvfb-run", FAKE_XVFB_RUN)
        self.write("bin/dbus-run-session", FAKE_DBUS_RUN_SESSION)
        self.write("scripts/desktop/smoke-start.sh", FAKE_SMOKE_START)
        self.write("scripts/ci/check-desktop-image.sh", FAKE_CHECK_IMAGE)
        self.write("scripts/ci/check-runtime-sources.sh", FAKE_CHECK_RUNTIME)
        step = _smoke_step(WORKFLOW.read_text().splitlines())
        self.step = _run_block(step)
        self.shell = _effective_shell(step)
        # Guard against extracting the wrong block, without mirroring it.
        self.assertIn("smoke-start.sh", self.step)
        self.assertIn("check-runtime-sources.sh", self.step)

    def write(self, relative, contents):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        path.chmod(0o755)

    def run_step(self, smoke_exit):
        script = self.root / "smoke-step.sh"
        script.write_text(self.step)
        trace = self.root / "trace.txt"
        env = dict(os.environ)
        env.update({
            "PATH": str(self.bin) + os.pathsep + env["PATH"],
            "TMPDIR": str(self.root / "tmp"),
            "TRACE": str(trace),
            "SMOKE_EXIT": str(smoke_exit),
        })
        result = subprocess.run(
            [*self.shell, str(script)],
            cwd=self.root, env=env, capture_output=True, text=True,
            timeout=STEP_TIMEOUT_SECONDS)
        reached = trace.read_text().splitlines() if trace.exists() else []
        return result, reached

    def test_failed_smoke_fails_the_step_and_stops_the_gates(self):
        result, reached = self.run_step(1)
        self.assertNotEqual(
            0, result.returncode,
            "a smoke start that exits 1 must fail the step "
            "(effective shell: %s)\nstdout:\n%s\nstderr:\n%s"
            % (" ".join(self.shell), result.stdout, result.stderr))
        self.assertEqual(
            [], reached,
            "the image/runtime gates must not run after a failed smoke: %s"
            % reached)

    def test_successful_smoke_runs_the_gates(self):
        result, reached = self.run_step(0)
        self.assertEqual(
            0, result.returncode,
            "a smoke start that exits 0 must pass the step "
            "(effective shell: %s)\nstdout:\n%s\nstderr:\n%s"
            % (" ".join(self.shell), result.stdout, result.stderr))
        self.assertEqual(
            ["check-desktop-image", "check-runtime-sources"], reached,
            "both downstream gates must run after a successful smoke")


if __name__ == "__main__":
    unittest.main()
