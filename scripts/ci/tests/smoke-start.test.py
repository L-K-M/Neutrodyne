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
  * smoke mode rides in through JAVA_TOOL_OPTIONS: the child sees
    `-Dneutrodyne.smoke=true` appended to the pre-existing options, while the
    .cfg is never written and stays byte-for-byte and mode-for-mode identical;
  * a conflicting .cfg option or `_JAVA_OPTIONS` value prevents the launch;
  * another process's .cfg mutation (bytes, mode or type) fails the gate without
    a SMOKE line and is never repaired;
  * stdout carries exactly the SMOKE line on success, which is what
    check-runtime-sources.sh and the release checkers consume.

A separate real boundary regression builds a tiny jpackage app-image from a
generated Java probe and runs the shipped script against that real launcher.

Run: python3 scripts/ci/tests/smoke-start.test.py
Exit 0 = every contract holds; 1 = at least one regression.
"""

import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
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

if behavior.get("ran_marker"):
    with open(behavior["ran_marker"], "w", encoding="utf-8") as handle:
        handle.write("ran")

if behavior.get("record_env"):
    with open(behavior["record_env"], "w", encoding="utf-8") as handle:
        handle.write(os.environ.get("JAVA_TOOL_OPTIONS", ""))

cfg_action = behavior.get("cfg_action")
cfg_path = os.environ["FAKE_LAUNCHER_CFG"]
if cfg_action == "chmod":
    os.chmod(cfg_path, behavior.get("cfg_mode", 0o400))
elif cfg_action == "replace":
    os.unlink(cfg_path)
    with open(cfg_path, "w") as handle:
        handle.write("replacement")
    os.chmod(cfg_path, 0o600)
elif cfg_action == "mkdir":
    os.unlink(cfg_path)
    os.mkdir(cfg_path)
    with open(os.path.join(cfg_path, "blocked"), "w") as handle:
        handle.write("directory prevents writes")

for line in behavior.get("lines", []):
    sys.stdout.write(line + "\\n")
sys.stdout.flush()

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

# The real boundary regression's probe: a valid SMOKE line only when the smoke
# property is true and a second JAVA_TOOL_OPTIONS property arrived.
PROBE_SOURCE = '''public class SmokeProbe {
    public static void main(String[] args) throws Exception {
        String marker = System.getProperty("neutrodyne.probe.marker");
        if (marker != null) java.nio.file.Files.writeString(java.nio.file.Path.of(marker), "launched");
        String smoke = System.getProperty("neutrodyne.smoke");
        String probe = System.getProperty("neutrodyne.probe");
        if (!"true".equals(smoke) || !"env-options".equals(probe)) {
            System.out.println("SMOKE {\\"failed\\":\\"probe\\",\\"smoke\\":\\"" + smoke
                + "\\",\\"probe\\":\\"" + probe + "\\"}");
            System.exit(1);
        }
        System.out.println("SMOKE {\\"versionName\\":\\"0.1.0\\",\\"versionCode\\":1,"
            + "\\"installKind\\":\\"dev\\",\\"javaVendor\\":\\""
            + System.getProperty("java.vendor") + "\\",\\"javaVendorVersion\\":\\""
            + System.getProperty("java.vendor.version") + "\\",\\"javaRuntimeVersion\\":\\""
            + System.getProperty("java.runtime.version") + "\\",\\"steps\\":{\\"probe\\":1},"
            + "\\"window\\":\\"skipped-headless\\",\\"pending\\":\\"database\\"}");
        System.exit(0);
    }
}
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

# A pre-existing smoke option, no final newline, and a non-default mode.
CFG_PREEXISTING = (
    b"[JavaOptions]\n"
    b"java-options=-Xmx512m\n"
    b"java-options=-Dneutrodyne.smoke=true"
)
CFG_ABSENT = b"[JavaOptions]\njava-options=-Xmx512m"
CFG_ABSENT_NEWLINE = b"[JavaOptions]\njava-options=-Xmx512m\n"
CFG_CONFLICTING = (
    b"[JavaOptions]\n"
    b"java-options=-Xmx512m\n"
    b"java-options=-Dneutrodyne.smoke=false\n"
)
CFG_CRLF_PREEXISTING = (
    b"[JavaOptions]\r\n"
    b"java-options=-Xmx512m\r\n"
    b"java-options=-Dneutrodyne.smoke=true\r\n"
)

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
        self.run_tmp = self.root / "run-tmp"
        self.run_tmp.mkdir()
        self.behavior = self.root / "behavior.json"
        self.env_record = self.root / "env.recorded"
        self.ran_marker = self.root / "launcher.ran"
        self.child_pid = self.root / "child.pid"

    def tearDown(self):
        self._tmp.cleanup()

    # --- fixture plumbing -------------------------------------------------------

    def make_image(self, cfg_bytes: bytes, cfg_mode: int = 0o640, image_mode: int = 0o755):
        img = self.root / "img"
        (img / "bin").mkdir(parents=True)
        (img / "lib" / "app").mkdir(parents=True)
        cfg = img / "lib" / "app" / "Neutrodyne.cfg"
        cfg.write_bytes(cfg_bytes)
        cfg.chmod(cfg_mode)
        launcher = img / "bin" / "Neutrodyne"
        launcher.write_text(LAUNCHER_SOURCE, encoding="utf-8")
        launcher.chmod(0o755)
        if image_mode != 0o755:
            for directory in (img, img / "bin", img / "lib", img / "lib" / "app"):
                directory.chmod(image_mode)
        return img, cfg

    def behave(self, *, lines=(), exit_code=0, sleep=0.0, spawn_child=False,
               wait_child=False, cfg_action=None, cfg_mode=0o400,
               record_env=False, ran_marker=False):
        self.behavior.write_text(
            json.dumps(
                {
                    "lines": list(lines),
                    "exit": exit_code,
                    "sleep": sleep,
                    "spawn_child": spawn_child,
                    "child_pid_file": str(self.child_pid) if spawn_child else None,
                    "wait_child": wait_child,
                    "cfg_action": cfg_action,
                    "cfg_mode": cfg_mode,
                    "record_env": str(self.env_record) if record_env else None,
                    "ran_marker": str(self.ran_marker) if ran_marker else None,
                }
            ),
            encoding="utf-8",
        )

    def env(self, cfg: Path, *, java_tool_options="", underscore_java_options=""):
        return {
            "FAKE_LAUNCHER_BEHAVIOR": str(self.behavior),
            "FAKE_LAUNCHER_CFG": str(cfg),
            "TMPDIR": str(self.run_tmp),
            "JAVA_TOOL_OPTIONS": java_tool_options,
            "_JAVA_OPTIONS": underscore_java_options,
        }

    def run_script(self, img: Path, timeout: int, env: dict, shell="bash",
                   replay=None):
        """Run the shipped script; on a hang, kill exactly its process group."""
        extra = [] if replay is None else [f"--engine-replay={replay}"]
        proc = subprocess.Popen(
            [shell, str(SMOKE_START), str(img), f"--timeout={timeout}", *extra],
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

    def assert_unchanged(self, cfg: Path, original: bytes, mode: int):
        self.assertEqual(original, cfg.read_bytes(), ".cfg bytes changed")
        self.assertEqual(mode, stat.S_IMODE(cfg.stat().st_mode), ".cfg mode changed")

    def assert_child_dead(self):
        self.assertTrue(self.child_pid.exists(), "fixture launcher spawned no child")
        pid = int(self.child_pid.read_text(encoding="utf-8"))
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and pid_alive(pid):
            time.sleep(0.05)
        self.assertFalse(pid_alive(pid), f"fake child {pid} survived the watchdog")

    def assert_not_launched(self):
        self.assertFalse(self.ran_marker.exists(), "the launcher ran despite a conflict")

    # --- verdict contracts ------------------------------------------------------

    @unittest.skipUnless(sys.platform == "darwin", "macOS ships Bash 3.2")
    def test_macos_system_bash_runs_the_shipped_smoke_gate(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], record_env=True, ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg), shell="/bin/bash")
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assertTrue(self.ran_marker.exists())
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_failed_smoke_with_exit_1_fails(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(lines=[FAILED_SMOKE], exit_code=1, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("failed", err)
        self.assert_unchanged(cfg, CFG_PREEXISTING, 0o640)
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())

    def test_failed_smoke_with_exit_0_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[FAILED_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("failed", err)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())

    def test_malformed_smoke_json_fails(self):
        img, cfg = self.make_image(CFG_ABSENT_NEWLINE, 0o640)
        self.behave(lines=["SMOKE {not json"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("JSON", err)
        self.assert_unchanged(cfg, CFG_ABSENT_NEWLINE, 0o640)

    def test_non_object_smoke_json_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=["SMOKE [1, 2]"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertIn("object", err)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_failed_null_field_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=['SMOKE {"failed":null}'], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertIn("failed", err)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_nonzero_exit_without_smoke_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=["launcher noise only"], exit_code=1)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("1", err)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_missing_smoke_line_fails(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=["launcher noise only"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("no SMOKE line", err)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

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
        self.assert_unchanged(cfg, CFG_PREEXISTING, 0o640)

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
        self.assert_unchanged(cfg, CFG_PREEXISTING, 0o640)

    # --- environment and read-only contracts ------------------------------------

    def test_valid_success_passes_and_keeps_cfg(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_PREEXISTING, 0o640)
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())

    def test_valid_success_without_preexisting_option_keeps_cfg(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o600)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o600)
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())

    def test_readonly_cfg_image_succeeds(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o444, image_mode=0o555)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o444)
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())

    def test_absent_final_newline_is_kept_unchanged(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)
        self.assertIn("-Dneutrodyne.smoke=true", self.env_record.read_text())

    def test_preexisting_true_cfg_is_accepted(self):
        img, cfg = self.make_image(CFG_PREEXISTING, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_PREEXISTING, 0o640)

    # --- armed engine-replay contracts (MD3 AC2) ---------------------------------
    #
    # The fake image carries engine/python/bin/python3 so the evidence's inside-
    # the-image checks have a real file to canonicalise. The pins the gate reads
    # come from the real lock files, so the fixture derives them rather than
    # hardcoding them twice.

    def make_engine_image(self, cfg_bytes: bytes = CFG_ABSENT, cfg_mode: int = 0o640):
        img, cfg = self.make_image(cfg_bytes, cfg_mode)
        interpreter = img / "engine" / "python" / "bin" / "python3"
        interpreter.parent.mkdir(parents=True)
        interpreter.write_text("#!/bin/sh\n", encoding="utf-8")
        return img, cfg

    def engine_evidence(self, img: Path, **overrides):
        lock = (REPO / "youtube/ytdlp-desktop/python-components.lock").read_text()
        python = re.search(r'^python\s*=\s*"([^"]+)"', lock, re.M).group(1)
        bundled = json.loads(
            (REPO / "youtube/ytdlp/engine/bundled.json").read_text())
        evidence = {
            "resolve": "ok",
            "videoId": "aaaaaaaaaaA",
            "formatId": "140",
            "urlHost": "media.example.invalid",
            "durationMs": 120000,
            "interpreter": os.path.realpath(img / "engine" / "python" / "bin" / "python3"),
            "resources": os.path.realpath(img / "engine"),
            "python": python,
            "engineVersion": bundled["version"],
            "ytDlp": bundled["version"],
            "ejs": bundled["ejsVersion"],
            "expectedYtdlp": bundled["version"],
            "shimApi": 1,
            "host": "stdio",
            "cacert": True,
            "child": "exited",
        }
        evidence.update(overrides)
        report = json.loads(VALID_SMOKE[len("SMOKE "):])
        report["engine"] = evidence
        return "SMOKE " + json.dumps(report, separators=(",", ":"))

    def test_engine_replay_injects_the_property_and_checks_evidence(self):
        img, cfg = self.make_engine_image()
        replay = REPO / "youtube/engine/src/test/resources/recorded/resolve"
        line = self.engine_evidence(img)
        self.behave(lines=[line], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg), replay=replay)
        self.assertEqual(0, rc, err)
        self.assertEqual(line + "\n", out)
        options = self.env_record.read_text()
        self.assertIn("-Dneutrodyne.smoke=true", options)
        self.assertIn(
            f'-Dneutrodyne.smoke.engineReplay="{os.path.realpath(replay)}"', options)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_engine_replay_rejects_a_missing_dir(self):
        img, cfg = self.make_engine_image()
        self.behave(lines=[self.engine_evidence(img)], ran_marker=True)
        rc, out, err = self.run_script(
            img, 60, self.env(cfg), replay=self.root / "no-such-dir")
        self.assertEqual(2, rc)
        self.assertIn("recorded scenario", err)
        self.assert_not_launched()

    def test_engine_replay_rejects_an_empty_value(self):
        img, cfg = self.make_engine_image()
        self.behave(lines=[self.engine_evidence(img)], ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg), replay="")
        self.assertEqual(2, rc)
        self.assertIn("recorded scenario", err)
        self.assert_not_launched()

    def test_engine_replay_requires_engine_evidence(self):
        img, cfg = self.make_engine_image()
        replay = REPO / "youtube/engine/src/test/resources/recorded/resolve"
        self.behave(lines=[VALID_SMOKE], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg), replay=replay)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("engine", err)

    def test_engine_replay_rejects_wrong_evidence(self):
        img, cfg = self.make_engine_image()
        replay = REPO / "youtube/engine/src/test/resources/recorded/resolve"
        cases = {
            "interpreter outside the image": {"interpreter": "/usr/bin/python3"},
            "interpreter not under engine/python": {
                "interpreter": os.path.realpath(img / "engine" / "python3")},
            "wrong python pin": {"python": "3.13.0"},
            "wrong yt-dlp pin": {"ytDlp": "2025.01.01"},
            "wrong engine version": {"engineVersion": "2025.01.01"},
            "host interpreter claim": {"resolve": "ok", "host": "chaquopy"},
            "failed resolve claim": {"resolve": "unavailable"},
            "child still running": {"child": "running"},
        }
        for name, override in cases.items():
            with self.subTest(case=name):
                self.behave(
                    lines=[self.engine_evidence(img, **override)], exit_code=0)
                rc, out, err = self.run_script(img, 60, self.env(cfg), replay=replay)
                self.assertNotEqual(0, rc, name)
                self.assertEqual("", out, name)
                self.assertIn("engine evidence", err, name)

    def test_engine_replay_cfg_declaration_fails_prelaunch(self):
        cfg_bytes = (
            b"[JavaOptions]\n"
            b"java-options=-Xmx512m\n"
            b"java-options=-Dneutrodyne.smoke.engineReplay=/x\n"
        )
        img, cfg = self.make_engine_image(cfg_bytes)
        replay = REPO / "youtube/engine/src/test/resources/recorded/resolve"
        self.behave(lines=[self.engine_evidence(img)], ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg), replay=replay)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assert_not_launched()
        self.assert_unchanged(cfg, cfg_bytes, 0o640)

    def test_engine_replay_underscore_java_options_declaration_fails(self):
        img, cfg = self.make_engine_image()
        replay = REPO / "youtube/engine/src/test/resources/recorded/resolve"
        self.behave(lines=[self.engine_evidence(img)], ran_marker=True)
        env = self.env(cfg, underscore_java_options="-Dneutrodyne.smoke.engineReplay=/x")
        rc, out, err = self.run_script(img, 60, env, replay=replay)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assert_not_launched()

    def test_engine_replay_failed_smoke_fails(self):
        img, cfg = self.make_engine_image()
        replay = REPO / "youtube/engine/src/test/resources/recorded/resolve"
        self.behave(lines=[FAILED_SMOKE], exit_code=1)
        rc, out, err = self.run_script(img, 60, self.env(cfg), replay=replay)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)

    def test_crlf_cfg_is_parsed(self):
        original = CFG_ABSENT_NEWLINE.replace(b"\n", b"\r\n")
        img, cfg = self.make_image(original, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, original, 0o640)

    def test_ignored_section_cannot_mask_a_conflicting_smoke_option(self):
        original = CFG_CONFLICTING + b"[Ignored]\njava-options=-Dneutrodyne.smoke=true\n"
        img, cfg = self.make_image(original)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assert_not_launched()
        self.assert_unchanged(cfg, original, 0o640)

    def test_trailing_value_whitespace_prevents_launch(self):
        original = CFG_ABSENT_NEWLINE + b"java-options=-Dneutrodyne.smoke=true \n"
        img, cfg = self.make_image(original)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assert_not_launched()
        self.assert_unchanged(cfg, original, 0o640)

    def test_conflicting_cfg_false_prevents_launch(self):
        img, cfg = self.make_image(CFG_CONFLICTING, 0o640)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("not true", err)
        self.assert_not_launched()
        self.assert_unchanged(cfg, CFG_CONFLICTING, 0o640)

    def test_conflicting_underscore_java_options_false_prevents_launch(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        env = self.env(cfg, underscore_java_options="-Dneutrodyne.smoke=false")
        rc, out, err = self.run_script(img, 60, env)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("not true", err)
        self.assert_not_launched()
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_preexisting_java_tool_options_preserved_and_true_overrides_false(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], exit_code=0, record_env=True)
        env = self.env(
            cfg,
            java_tool_options="-Xmx256m -Dneutrodyne.smoke=false",
        )
        rc, out, err = self.run_script(img, 60, env)
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)
        recorded = self.env_record.read_text()
        self.assertIn("-Xmx256m", recorded)
        self.assertIn("-Dneutrodyne.smoke=false", recorded)
        self.assertTrue(recorded.endswith("-Dneutrodyne.smoke=true"))
        self.assertLess(
            recorded.index("-Dneutrodyne.smoke=false"),
            recorded.index("-Dneutrodyne.smoke=true"),
            "our true must come after the pre-existing false",
        )

    def test_invalid_quoting_in_java_tool_options_fails_closed(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        env = self.env(cfg, java_tool_options='-Dneutrodyne.smoke="true')
        rc, out, err = self.run_script(img, 60, env)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("quote", err)
        self.assert_not_launched()
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    def test_invalid_quoting_in_underscore_java_options_fails_closed(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        env = self.env(cfg, underscore_java_options="-Dneutrodyne.smoke='true")
        rc, out, err = self.run_script(img, 60, env)
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("quote", err)
        self.assert_not_launched()
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

    # --- child mutation contracts -----------------------------------------------

    def test_child_cfg_mode_change_fails_without_smoke(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], cfg_action="chmod", cfg_mode=0o400)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("changed", err)
        self.assertEqual(0o400, stat.S_IMODE(cfg.stat().st_mode), "the child's mutation was repaired")

    def test_child_cfg_replacement_fails_without_smoke(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], cfg_action="replace")
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("changed", err)
        self.assertEqual(b"replacement", cfg.read_bytes(), "the child's mutation was repaired")
        self.assertEqual(0o600, stat.S_IMODE(cfg.stat().st_mode))

    def test_child_cfg_type_change_fails_without_smoke(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[VALID_SMOKE], cfg_action="mkdir")
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("regular file", err)
        self.assertTrue(cfg.is_dir(), "the child's mutation was repaired")

    @unittest.skipIf(hasattr(os, "geteuid") and os.geteuid() == 0, "root bypasses read permissions")
    def test_unreadable_cfg_fails_before_launch_without_writing(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o200)
        self.behave(lines=[VALID_SMOKE], ran_marker=True)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("cannot read", err)
        self.assert_not_launched()
        self.assertEqual(0o200, stat.S_IMODE(cfg.stat().st_mode))
        cfg.chmod(0o640)
        self.assertEqual(CFG_ABSENT, cfg.read_bytes())

    # --- success and verdict contracts -----------------------------------------

    def test_stdout_carries_only_the_smoke_line(self):
        img, cfg = self.make_image(CFG_ABSENT_NEWLINE, 0o644)
        self.behave(lines=["launcher noise", VALID_SMOKE, "more noise"], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc, err)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_ABSENT_NEWLINE, 0o644)

    def test_final_smoke_line_is_the_verdict(self):
        img, cfg = self.make_image(CFG_ABSENT, 0o640)
        self.behave(lines=[FAILED_SMOKE, VALID_SMOKE], exit_code=0)
        rc, out, _ = self.run_script(img, 60, self.env(cfg))
        self.assertEqual(0, rc)
        self.assertEqual(VALID_SMOKE + "\n", out)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)

        self.behave(lines=[VALID_SMOKE, FAILED_SMOKE], exit_code=0)
        rc, out, err = self.run_script(img, 60, self.env(cfg))
        self.assertNotEqual(0, rc)
        self.assertEqual("", out)
        self.assertIn("failed", err)
        self.assert_unchanged(cfg, CFG_ABSENT, 0o640)


class RealJPackageSmokeTest(unittest.TestCase):
    """The real boundary: a tiny jpackage app-image and the shipped script.

    A Python fake can certify the wrong JVM injection; this regression builds a
    real launcher from a generated Java probe and runs the shipped script against
    it with a read-only .cfg and a pre-existing JAVA_TOOL_OPTIONS flag.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    @staticmethod
    def _jdk_home():
        """JAVA_HOME first, then the known local JDK 21, then CI's JDK 21 env."""
        candidates = []
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidates.append(Path(java_home))
        for key, value in sorted(os.environ.items()):
            if key.startswith("JAVA_HOME_21") and value:
                candidates.append(Path(value))
        candidates.append(Path.home() / ".local/jdks/jdk21/Contents/Home")
        candidates.append(Path.home() / ".local/jdks/jdk21")
        for candidate in candidates:
            if (candidate / "bin/javac").is_file() and (candidate / "bin/jpackage").is_file():
                return candidate
        return None

    def test_real_jpackage_launcher_smoke(self):
        jdk = self._jdk_home()
        if jdk is None:
            self.skipTest("no JDK 21+ with jpackage found via JAVA_HOME or ~/.local/jdks/jdk21")

        work = self.root / "build"
        input_dir = work / "input"
        dest = work / "dest"
        input_dir.mkdir(parents=True)
        dest.mkdir()

        source = work / "SmokeProbe.java"
        source.write_text(PROBE_SOURCE, encoding="utf-8")
        subprocess.run(
            [str(jdk / "bin/javac"), "SmokeProbe.java"],
            cwd=work, check=True, capture_output=True, text=True,
        )
        subprocess.run(
            [str(jdk / "bin/jar"), "cfe", "probe.jar", "SmokeProbe", "SmokeProbe.class"],
            cwd=work, check=True, capture_output=True, text=True,
        )
        shutil.copy2(work / "probe.jar", input_dir / "probe.jar")
        subprocess.run(
            [
                str(jdk / "bin/jpackage"),
                "--type", "app-image",
                "--name", "Neutrodyne",
                "--input", str(input_dir),
                "--main-jar", "probe.jar",
                "--main-class", "SmokeProbe",
                "--add-modules", "java.base",
                "--dest", str(dest),
            ],
            check=True, capture_output=True, text=True,
        )

        image = dest / "Neutrodyne.app"
        if not image.exists():
            image = dest / "Neutrodyne"
        cfg = None
        for candidate in (
            image / "Contents/app/Neutrodyne.cfg",
            image / "lib/app/Neutrodyne.cfg",
            image / "app/Neutrodyne.cfg",
        ):
            if candidate.is_file():
                cfg = candidate
                break
        self.assertIsNotNone(cfg, f"no Neutrodyne.cfg under {image}")

        original = cfg.read_bytes()
        cfg.chmod(0o444)

        env = {
            **os.environ,
            "JAVA_TOOL_OPTIONS": "-Dneutrodyne.probe=env-options",
            "_JAVA_OPTIONS": "",
        }
        proc = subprocess.run(
            ["bash", str(SMOKE_START), str(image), "--timeout=60"],
            capture_output=True, text=True, env=env, timeout=RUN_BOUND,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertTrue(proc.stdout.startswith("SMOKE "), proc.stdout)
        report = json.loads(proc.stdout[len("SMOKE "):].strip())
        self.assertNotIn("failed", report)
        self.assertEqual("skipped-headless", report.get("window"))
        self.assertEqual(original, cfg.read_bytes(), "the real launcher's .cfg changed")
        self.assertEqual(0o444, stat.S_IMODE(cfg.stat().st_mode))

        # Admission must fail before main: the real app's ordinary main would
        # otherwise open user AppDirs before its missing SMOKE report is noticed.
        suffixes = {
            "ignored-decoy": b"\njava-options=-Dneutrodyne.smoke=false\n[Ignored]\njava-options=-Dneutrodyne.smoke=true\n",
            "trailing-space": b"\njava-options=-Dneutrodyne.smoke=true \n",
        }
        if os.name == "posix":
            # The POSIX jpackage reader preserves CR in the JVM option value.
            suffixes["crlf-true"] = b"\njava-options=-Dneutrodyne.smoke=true\r\n"
        for name, suffix in suffixes.items():
            with self.subTest(name=name):
                marker = work / f"{name}.launched"
                cfg.chmod(0o644)
                cfg.write_bytes(original + suffix)
                cfg.chmod(0o444)
                env["JAVA_TOOL_OPTIONS"] = (
                    '-Dneutrodyne.probe=env-options '
                    f'-Dneutrodyne.probe.marker="{marker.as_posix()}"'
                )
                proc = subprocess.run(
                    ["bash", str(SMOKE_START), str(image), "--timeout=60"],
                    capture_output=True, text=True, env=env, timeout=RUN_BOUND,
                )
                self.assertNotEqual(0, proc.returncode, proc.stderr)
                self.assertFalse(marker.exists(), f"ordinary main ran: {proc.stderr}")
                self.assertEqual("", proc.stdout)
                self.assertEqual(original + suffix, cfg.read_bytes())
                self.assertEqual(0o444, stat.S_IMODE(cfg.stat().st_mode))


if __name__ == "__main__":
    unittest.main(verbosity=2)
