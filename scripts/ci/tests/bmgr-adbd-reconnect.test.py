#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise the backup gate's real adbd bootstrap without contacting a device."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASH = shutil.which("bash") or "bash"
FAKE_ADB = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

path = Path(os.environ["ADB_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
state["calls"].append(args)
mode = os.environ["ADB_MODE"]
code = 0
if args == ["root"]:
    state["rootRequests"] += 1
    if mode == "transient" and state["rootRequests"] == 1:
        state["offline"] = True
        print("adb: unable to connect for root: closed", file=sys.stderr)
        code = 1
    elif mode == "unsupported":
        print("adbd cannot run as root in production builds", file=sys.stderr)
        code = 1
    else:
        state["root"] = True
elif args == ["wait-for-device"]:
    if mode == "unreachable":
        code = 124
    else:
        state["offline"] = False
elif args == ["shell", "id", "-u"]:
    if state["offline"]:
        print("adb: device offline", file=sys.stderr)
        code = 1
    elif mode == "failed-id":
        # A failed command's output is not evidence of root.
        print("0")
        code = 1
    else:
        print("0\r" if state["root"] else "2000\r")
else:
    raise SystemExit("unexpected device command: " + repr(args))
path.write_text(json.dumps(state))
raise SystemExit(code)
'''


class AdbdReconnectTest(unittest.TestCase):
    def bootstrap(self, mode, already_root=False):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            state = folder / "state.json"
            state.write_text(json.dumps({
                "calls": [], "rootRequests": 0, "root": already_root,
                "offline": False,
            }))
            adb = folder / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o755)
            # Only the fake transport runs. Do not wait or sleep in the fixtures.
            for name, body in {
                "timeout": '#!/bin/sh\nshift\nexec "$@"\n',
                "sleep": "#!/bin/sh\nexit 0\n",
            }.items():
                command = folder / name
                command.write_text(body)
                command.chmod(0o755)
            env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ["PATH"],
                       ADB=str(adb), ADB_STATE=str(state), ADB_MODE=mode,
                       BMGR_CHECK_LIB_ONLY="1")
            run = subprocess.run(
                [BASH, "-c", 'source "$1"; require_root_adb; printf "ROOT_READY\\n"',
                 "fixture", str(ROOT / "scripts/ci/bmgr-check.sh")],
                env=env, capture_output=True, text=True, timeout=10,
            )
            return run, json.loads(state.read_text())

    def test_normal_transition_requires_a_successful_uid_check(self):
        run, state = self.bootstrap("normal")
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("ROOT_READY", run.stdout)
        self.assertTrue(state["root"])
        self.assertIn(["shell", "id", "-u"], state["calls"])

    def test_transient_root_disconnect_reconnects_before_retry(self):
        run, state = self.bootstrap("transient")
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("ROOT_READY", run.stdout)
        self.assertEqual(2, state["rootRequests"])
        self.assertTrue(state["root"])
        calls = state["calls"]
        first = calls.index(["root"])
        second = calls.index(["root"], first + 1)
        self.assertIn(["wait-for-device"], calls[first + 1:second])

    def test_an_already_rooted_daemon_is_accepted(self):
        run, _ = self.bootstrap("normal", already_root=True)
        self.assertEqual(0, run.returncode, run.stderr)

    def test_unsupported_images_fail_after_bounded_attempts(self):
        run, state = self.bootstrap("unsupported")
        self.assertNotEqual(0, run.returncode)
        self.assertNotIn("ROOT_READY", run.stdout)
        self.assertLessEqual(state["rootRequests"], 3)
        self.assertIn("adbd is not root", run.stderr)

    def test_a_failed_uid_command_cannot_report_root(self):
        run, _ = self.bootstrap("failed-id")
        self.assertNotEqual(0, run.returncode)
        self.assertNotIn("ROOT_READY", run.stdout)

    def test_an_unreachable_device_never_reaches_the_backup_gate(self):
        run, state = self.bootstrap("unreachable")
        self.assertNotEqual(0, run.returncode)
        self.assertNotIn("ROOT_READY", run.stdout)
        self.assertFalse(any(call[:1] == ["shell"] for call in state["calls"]))


if __name__ == "__main__":
    unittest.main()
