#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise hosted KVM setup offline; successful udev is not device access."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
EXPECTED_BLOCK_COUNTS = {"ci.yml": 1, "nightly.yml": 8}
BASH = shutil.which("bash") or "bash"
STEP_TIMEOUT_SECONDS = 30

STEP_NAME = re.compile(r"^\s*- name:\s*Enable\b.*\bKVM\b")
RUN_LITERAL = re.compile(r"^(\s*)run:\s*\|\s*$")

FAKE_SUDO = """\
#!/usr/bin/env bash
# Passwordless sudo for exactly the privileged calls the Enable-KVM steps
# issue; anything else fails loudly.
set -u
printf 'sudo %s\\n' "$*" >> "$FAKE_TRACE"
case "${1:-}" in
  tee)
    [ "$#" -eq 2 ] && [ "$2" = "/etc/udev/rules.d/99-kvm.rules" ] || {
      echo "fake sudo: unexpected tee invocation: $*" >&2; exit 1; }
    cat > /dev/null
    ;;
  udevadm)
    [ "$#" -eq 3 ] && [ "$2" = "trigger" ] && [ "$3" = "--name-match=kvm" ] || {
      echo "fake sudo: unexpected udevadm invocation: $*" >&2; exit 1; }
    # A successful trigger does not prove access to the existing node.
    ;;
  chmod)
    [ "$#" -eq 3 ] && [ "$2" = "a+rw" ] && [ "$3" = "/dev/kvm" ] || {
      echo "fake sudo: unexpected chmod invocation: $*" >&2; exit 1; }
    [ -z "${FAKE_CHMOD_FAIL:-}" ] || exit 1
    : > "$FAKE_KVM_ACCESSIBLE"
    ;;
  *)
    echo "fake sudo: unhandled privileged command: $*" >&2
    exit 1
    ;;
esac
"""

FAKE_EMULATOR = """\
#!/usr/bin/env bash
# $ANDROID_HOME/emulator/emulator: only -accel-check is supported.
set -u
printf 'emulator %s\\n' "$*" >> "$FAKE_TRACE"
[ "$#" -eq 1 ] && [ "$1" = "-accel-check" ] || {
  echo "fake emulator: unhandled invocation: $*" >&2; exit 1; }
if [ -n "${FAKE_PROBE_FAIL:-}" ]; then
  echo "accel check failed" >&2
  exit 1
fi
if [ ! -e "$FAKE_KVM_ACCESSIBLE" ]; then
  echo "ProbeKVM: user '${USER:-runner}' does not have permissions to open /dev/kvm" >&2
  exit 1
fi
echo "KVM is usable"
"""


def _enable_kvm_blocks(path):
    """(1-based line, dedented script) for each `Enable*KVM` `run: |` step."""
    lines = path.read_text().splitlines()
    blocks = []
    for index, line in enumerate(lines):
        if not STEP_NAME.match(line):
            continue
        following = index + 1
        literal = following < len(lines) and RUN_LITERAL.match(lines[following])
        if not literal:
            raise ValueError(
                f"{path.name}:{index + 1} Enable KVM step is not a literal run block")
        indent = len(literal.group(1))
        body, cursor = [], following + 1
        while cursor < len(lines):
            current = lines[cursor]
            if current.strip() and len(current) - len(current.lstrip()) <= indent:
                break
            body.append(current)
            cursor += 1
        script = textwrap.dedent("\n".join(body)).strip("\n")
        if not script.strip():
            raise ValueError(f"{path.name}:{index + 1} Enable KVM run block is empty")
        blocks.append((index + 1, script + "\n"))
    return blocks


class EnableKvmTest(unittest.TestCase):
    def _run_block(self, script, extra_env=None):
        """Run one extracted `run: |` body under GitHub's bash contract."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bindir = root / "bin"
            bindir.mkdir()
            sudo = bindir / "sudo"
            sudo.write_text(FAKE_SUDO)
            sudo.chmod(0o755)
            emulator = root / "sdk" / "emulator" / "emulator"
            emulator.parent.mkdir(parents=True)
            emulator.write_text(FAKE_EMULATOR)
            emulator.chmod(0o755)
            state, trace = root / "kvm-accessible", root / "trace.txt"
            step = root / "step.sh"
            step.write_text(script)
            env = dict(os.environ)
            env.update({
                "PATH": str(bindir) + os.pathsep + env["PATH"],
                "ANDROID_HOME": str(root / "sdk"),
                "FAKE_KVM_ACCESSIBLE": str(state),
                "FAKE_TRACE": str(trace),
            })
            env.update(extra_env or {})
            result = subprocess.run(
                [BASH, "--noprofile", "--norc", "-eo", "pipefail", str(step)],
                env=env, capture_output=True, text=True,
                timeout=STEP_TIMEOUT_SECONDS)
            calls = trace.read_text() if trace.exists() else ""
            return result, state.exists(), calls

    def test_every_enable_kvm_block_grants_access_synchronously(self):
        for workflow, expected_count in EXPECTED_BLOCK_COUNTS.items():
            blocks = _enable_kvm_blocks(WORKFLOW_DIR / workflow)
            self.assertEqual(expected_count, len(blocks), workflow)

            for lineno, script in blocks:
                with self.subTest(workflow=workflow, line=lineno):
                    result, accessible, calls = self._run_block(script)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertTrue(
                        accessible,
                        "step exited 0 without synchronously granting "
                        "/dev/kvm access")

                    if workflow == "ci.yml":
                        self.assertIn("emulator -accel-check", calls)
                        continue

                    # Nightly installs the emulator after its KVM setup step.
                    self.assertNotIn("emulator ", calls)

    def test_failed_chmod_fails_the_step(self):
        for workflow in EXPECTED_BLOCK_COUNTS:
            for lineno, script in _enable_kvm_blocks(WORKFLOW_DIR / workflow):
                with self.subTest(workflow=workflow, line=lineno):
                    result, accessible, _ = self._run_block(
                        script, {"FAKE_CHMOD_FAIL": "1"})
                    self.assertFalse(accessible)
                    self.assertNotEqual(0, result.returncode)

    def test_failing_accel_probe_fails_the_ci_step(self):
        [(_, script)] = _enable_kvm_blocks(WORKFLOW_DIR / "ci.yml")
        result, accessible, calls = self._run_block(
            script, {"FAKE_PROBE_FAIL": "1"})
        self.assertTrue(accessible)
        self.assertIn("emulator -accel-check", calls)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
