#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The packaged-launcher contract of scripts/desktop/smoke-start.sh (11 Smoke mode).

Every case runs the shipped script against a fake jpackage image: a Python
fixture launcher under bin/ plus a real Neutrodyne.cfg, with the launcher's
behaviour supplied as JSON data through the environment (no code interpolation).
Nothing here starts a JVM, an app or a GUI.

The contracts exercised:

  * exit 0 only when the launcher exits 0 and its final `SMOKE {json}` line is a
    JSON object with no `failed` field — smoke mode prints that line and then
    exits 1 when a step failed, so a SMOKE line alone is not success;
  * a nonzero launcher exit, a missing SMOKE line, a malformed or non-object
    line, or a `failed` field fails with diagnostics on stderr;
  * the watchdog bounds a hung launcher on every platform (Python 3 stdlib, not
    GNU timeout) and kills only the launched process tree on timeout or cancel;
  * the .cfg is restored byte-for-byte after success, failure and timeout —
    original bytes and mode, including a pre-existing smoke option and an absent
    final newline;
  * stdout carries exactly the SMOKE line on success, which is what
    check-runtime-sources.sh and the release checkers consume.

Run: python3 scripts/ci/tests/smoke-start.test.py
Exit 0 = every contract holds; 1 = at least one regression.
"""

import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[3]
SMOKE_START = REPO / "scripts/desktop/smoke-start.sh"

# The fixture launcher is a real executable script; its behaviour is data.
LAUNCHER_SOURCE = '''#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time

with open(os.environ["FAKE_LAUNCHER_BEHAVIOR"], "r", encoding="utf-8") as handle:
    behavior = json.load(handle)

for line in behavior.get("lines", []):
    sys.stdout.write(line + "\\n")
sys.stdout.flush()

if behavior.get("record_cfg"):
    with open(os.environ["FAKE_LAUNCHER_CFG"], "r", encoding="utf-8", errors="replace") as handle:
        recorded = handle.read()
    with open(behavior["record_cfg"], "w", encoding="utf-8") as handle:
        handle.write(recorded)

child = None
if behavior.get("spawn_child"):
    child = subprocess.Popen(
        [sys.executable, "-c", "import time; time.sleep(30)"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if behavior.get("child_pid_file"):
        with open(behavior["child_pid_file"], "w", encoding="utf-8") as handle:
            handle.write(str(child.pid))

if behavior.get("sleep"):
    time.sleep(behavior["sleep"])

if child is not None and behavior.get("wait_child"):
    child.wait()

sys.exit(behavior.get("exit", 0))
'''

# Mirrors smokeJson's shape (11 Smoke mode): a success line carries "window" but
# no "failed" key; a failed run's line carries "failed" (and no "window").
VALID_SMOKE = (
    'SMOKE {"versionName":"0.1.0","versionCode":1,"installKind":"deb",'
    '"javaVendor":"Eclipse Adoptium","javaVendorVersion":"Temurin-25+36",'
    '"javaRuntimeVersion":"25+36","steps":{"appDirs":3,"buildInfo":1,"graph":40,'
    '"initializers":2,"window":510,"firstFrameMs":480},"window":"opened",'
    '"pending":"database,destinations,ffmpeg,ndmedia,engine,dbus,aotCache,rss"}'
)
FAILED_SMOKE = (
    'SMOKE {"versionName":"0.1.0","versionCode":1,"installKind":"deb",'
    '"javaVendor":"Eclipse Adoptium","javaVendorVersion":"Temurin-25+36",'
    '"javaRuntimeVersion":"25+36","steps":{"appDirs":3,"buildInfo":1,"graph":40},'
    '"pending":"database,destinations,ffmpeg,ndmedia,engine,dbus,aotCache,rss",'
    '"failed":"initializers"}'
)

# A pre-existing smoke option, no final newline, and a non-default mode: the
# old restore deleted the option it did not add and rewrote the file's mode.
CFG_PREEXISTING = (
    b"[JavaOptions]\n"
    b"java-options=-Xmx512m\n"
    b"java-options=-Dneutrodyne.smoke=true"
)
CFG_ABSENT = b"[JavaOptions]\njava-options=-Xmx512m"
CFG_ABSENT_NEWLINE = b"[JavaOptions]\njava-options=-Xmx512m\n"

RUN_BOUND = 30  # the fixture's own bound; the script's watchdog is 1 s in the timeout case


def pid_alive(pid: int) -> bool:
    """False once the process is gone (or a reaped zombie on POSIX)."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    try:
        state = subprocess.run(
            ["ps", "-o", "stat=", "-p", str(pid)],
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()
        return bool(state) and not state.startswith("Z")
    except (OSError, subprocess.SubprocessError):
        return True


@unittest.skipIf(
    os.name == "nt",
    "the fake launcher fixtures need a POSIX executable and process tree; "
    "the Windows watchdog branch is not exercised by this fixture",
)
class SmokeStartGateTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.behavior = self.root / "behavior.json"
        self.record = self.root / "cfg.recorded"
        self.child_pid = self.root / "child.pid"

    def tearDown(self):
        self._tmp.cleanup()

    # --- fixture plumbing -------------------------------------------------------

    def make_image(self, cfg_bytes: bytes, cfg_mode: int = 0o640):
        img = self.root / "img"
        (img / "bin").mkdir(parents=True)
        (img / "lib" / "app").mkdir(parents=True)
        cfg = img / "lib" / "app" / "Neutrodyne.cfg"
        cfg.write_bytes(cfg_bytes)
        cfg.chmod(cfg_mode)
        launcher = img / "bin" / "Neutrodyne"
        launcher.write_text(LAUNCHER_SOURCE, encoding="utf-8")
        launcher.chmod(0o755)
        return img, cfg

    def behave(self, *, lines=(), exit_code=0, sleep=0.0, spawn_child=False,
               record_cfg=False, wait_child=False):
        self.behavior.write_text(
            json.dumps(
                {
                    "lines": list(lines),
                    "exit": exit_code,
                    "sleep": sleep,
                    "spawn_child": spawn_child,
                    "record_cfg": str(self.record) if record_cfg else None,
                    "child_pid_file": str(self.child_pid) if spawn_child else None,
                    "wait_child": wait_child,
                }
            ),
            encoding="utf-8",
        )

    def env(self, cfg: Path):
        return {
            "FAKE_LAUNCHER_BEHAVIOR": str(self.behavior),
            "FAKE_LAUNCHER_CFG": str(cfg),
        }

    def run_script(self, img: Path, timeout: int, env: dict):
        """Run the shipped script; on a hang, kill exactly its process group."""
        proc = subprocess.Popen(
            ["bash", str(SMOKE_START), str(img), f"--timeout={timeout}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
            env={**os.environ, **env},
        )
        try:
            out, err = proc.communicate(timeout=RUN_BOUND)
            return proc.returncode, out, err
        except subprocess.TimeoutExpired:
            self.kill_hung_run(proc)
            out, err = proc.communicate()
            self.fail(
                f"smoke-start did not finish within {RUN_BOUND}s "
                f"(rc={proc.returncode}, stderr={err[-400:]!r})"
            )

    def kill_hung_run(self, proc):
        """A hung run must not leave the fake launcher's child behind either."""
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            proc.kill()
        if self.child_pid.exists():
            try:
                os.kill(int(self.child_pid.read_text(encoding="utf-8")), signal.SIGKILL)
            except (ProcessLookupError, PermissionError, ValueError):
                pass

    def assert_restored(self, cfg: Path, original: bytes, mode: int):
        self.assertEqual(original, cfg.read_bytes(), ".cfg bytes were not restored")
        self.assertEqual(mode, stat.S_IMODE(cfg.stat().st_mode), ".cfg mode was not restored")

    def assert_child_dead(self):
        self.assertTrue(self.child_pid.exists(), "fixture launcher spawned no child")
        pid = int(self.child_pid.read_text(encoding="utf-8"))
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and pid_alive(pid):
            time.sleep(0.05)
        self.assertFalse(pid_alive(pid), f"fake child {pid} survived the watchdog")

    # --- verdict contracts ------------------------------------------------------

    def test_failed_smoke_with_exit_1_fails(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(lines=[FAILED_SMOKE], exit_code=1, record_cfg=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("failed", err)
        self.assert_restored(cfg, CFG_PREEXISTING, 0o640)
        self.assertIn("java-options=-Dneutrodyne.smoke=true", self.record.read_text())

    def test_failed_smoke_with_exit_0_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[FAILED_SMOKE], exit_code=0, record_cfg=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("failed", err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)
        self.assertIn("java-options=-Dneutrodyne.smoke=true", self.record.read_text())

    def test_malformed_smoke_json_fails(self):
        img, cfg = self.make_image(CFG_ABSENT_NEWLINE, 0o640)
        self.behave(lines=["SMOKE {not json"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("JSON", err)
        self.assert_restored(cfg, CFG_ABSENT_NEWLINE, 0o640)

    def test_non_object_smoke_json_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=["SMOKE [1, 2]"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertIn("object", err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)

    def test_failed_null_field_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=['SMOKE {"failed":null}'], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertIn("failed", err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)

    def test_nonzero_exit_without_smoke_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=["launcher noise only"], exit_code=1)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("1", err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)

    def test_missing_smoke_line_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=["launcher noise only"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("no SMOKE line", err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)

    # --- watchdog contracts -----------------------------------------------------

    def test_timeout_fails_and_kills_tree(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(spawn_child=True, sleep=30)
        started = time.monotonic()
        rc, out, err = self.run_script(img, 1, self.env(cfg))
        elapsed = time.monotonic() - started
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("watchdog", err)
        self.assertLess(elapsed, 15, "the watchdog did not bound the run")
        self.assert_child_dead()
        self.assert_restored(cfg, CFG_PREEXISTING, 0o640)

    def test_cancel_fails_and_kills_tree(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(spawn_child=True, sleep=30)
        proc = subprocess.Popen(
            ["bash", str(SMOKE_START), str(img), "--timeout=30"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
            env={**os.environ, **self.env(cfg)},
        )
        deadline = time.monotonic() + 10
        while not self.child_pid.exists():
            if time.monotonic() > deadline:
                proc.kill()
                self.fail("the fixture launcher never started its child")
            time.sleep(0.05)
        os.kill(proc.pid, signal.SIGTERM)
        try:
            out, err = proc.communicate(timeout=RUN_BOUND)
        except subprocess.TimeoutExpired:
            self.kill_hung_run(proc)
            proc.communicate()
            self.fail("smoke-start did not finish after cancel")
        self.assertNotEqual(0, proc.returncode)
        self.assertEqual("", out)
        self.assert_child_dead()
        self.assert_restored(cfg, CFG_PREEXISTING, 0o640)

    # --- success and restore contracts -----------------------------------------

    def test_valid_success_passes_and_restores_cfg(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_cfg=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_restored(cfg, CFG_PREEXISTING, 0o640)
        self.assertIn("java-options=-Dneutrodyne.smoke=true", self.record.read_text())

    def test_valid_success_without_preexisting_option_restores_cfg(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o600)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_cfg=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_restored(cfg, CFG_ABSENT, 0o600)
        self.assertIn("java-options=-Dneutrodyne.smoke=true", self.record.read_text())

    def test_stdout_carries_only_the_smoke_line(self):
        img, cfg = self.make_image(CFG_ABSENT_NEWLINE, 0o644)
        self.behave(lines=["launcher noise", VALID_SMOKE, "more noise"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_restored(cfg, CFG_ABSENT_NEWLINE, 0o644)

    def test_final_smoke_line_is_the_verdict(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[FAILED_SMOKE, VALID_SMOKE], exit_code=0)
        rc, out, _ = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)

        self.behave(lines=[VALID_SMOKE, FAILED_SMOKE], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("failed", err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)

    def test_absent_final_newline_is_restored_after_append(self):
        # The append must put the option on its own line; the restore must put
        # the file's missing final newline back.
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_cfg=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assert_restored(cfg, CFG_ABSENT, 0o640)
        recorded = self.record.read_text()
        self.assertIn("\njava-options=-Dneutrodyne.smoke=true\n", recorded)


if __name__ == "__main__":
    unittest.main(verbosity=2)
