#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Offline synthetic TAR/verdict semantics only; never Android execution."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("probe", ROOT / "scripts/ci/bmgr-api29-probe.py")
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
PKG, CERT = p.PREFIX + "cse", hashlib.sha256(b"cert").hexdigest()
BASE = "apps/" + PKG + "/"
MANIFEST = f"1\n{PKG}\n1\n29\n\n0\n1\n{b'cert'.hex()}\n".encode()


def archive(extra=(), payload=p.PAYLOAD, manifest=MANIFEST):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name, data, kind in [(BASE + "_manifest", manifest, b"0"),
                                 *(([(BASE + "f/allowed/payload", payload, b"0")]) if payload is not None else []),
                                 *extra]:
            info = tarfile.TarInfo(name)
            info.type, info.size = kind, len(data)
            tar.addfile(info, io.BytesIO(data))
    return out.getvalue()


def refusal(pkg, version="1", monitored=True):
    event = f"=> Event{{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = {pkg}(v{version})}}\n"
    return ("Running incremental backup for 1 requested packages.\n"
            "Package @pm@ with result: Success\n" + (event if monitored else "") +
            f"Package {pkg} with result: {p.REJECTION}\n"
            "Backup finished with result: Success\n")


def accepted(pkg):
    return refusal(pkg, monitored=False).replace(p.REJECTION, "Success")


class ProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT / "build").mkdir(exist_ok=True)

    def prepare_offline(self, folder, variant, read):
        """Exercise real receipt/byte checks; subprocess and elapsed time are offline stubs."""
        clock, reads = [0.0], []
        def run(argv, capture_output, timeout):
            self.assertEqual(["adb", "-s", "emulator-5554"], argv[:3])
            if argv[3:6] == ["shell", "am", "start"]:
                self.assertEqual(["-n", p.PREFIX + variant + "/" + p.PREFIX[:-1] + ".ProbeActivity"], argv[6:])
                return p.subprocess.CompletedProcess(argv, 0, b"Starting: Intent\n", b"")
            self.assertEqual(["exec-out", "cat"], argv[3:5])
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, min(5, 30 - clock[0]))
            path = argv[5].removeprefix("/data/user/0/" + p.PREFIX + variant + "/")
            reads.append(path)
            code, data, elapsed = read(path, p.EXPECTED[path], len(reads))
            clock[0] += min(elapsed, timeout)
            if code == 124:
                raise p.subprocess.TimeoutExpired(argv, timeout, output=data, stderr=b"read timeout")
            return p.subprocess.CompletedProcess(argv, code, data, b"not ready" if code else b"")
        def call(name, *args, timeout=60, required=True):
            return p.command(folder, name, ["adb", "-s", "emulator-5554", *args], timeout, required)
        def sleep(seconds):
            clock[0] += seconds
        with patch.object(p, "OUT", folder), patch.object(p.subprocess, "run", side_effect=run), \
                patch.object(p.time, "monotonic", side_effect=lambda: clock[0]), patch.object(p.time, "sleep", side_effect=sleep):
            p.prepare_fixture(call, variant)
        return reads

    def test_launch_has_no_window_wait_and_observes_all_exact_files(self):
        for variant in p.VARIANTS:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                reads = self.prepare_offline(Path(tmp), variant, lambda path, data, n: (0, data, 0))
                self.assertEqual(["files/allowed/payload", "files/other/excluded", "no_backup/excluded"], reads)
                prepared = json.loads((Path(tmp) / ("prepared-" + variant + ".json")).read_text())
                self.assertEqual(p.PREFIX + variant, prepared["package"])
                self.assertEqual({path: p.digest(data) for path, data in p.EXPECTED.items()}, prepared["files"])

    def test_not_ready_reads_require_another_complete_observation(self):
        failures = [("files/allowed/payload", 1, b""), ("files/other/excluded", 0, b"short"),
                    ("no_backup/excluded", 0, b"x" * 26), ("files/allowed/payload", 1, p.PAYLOAD),
                    ("no_backup/excluded", 124, b"")]
        for path, code, bad in failures:
            with self.subTest(path=path, code=code), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                folder = Path(tmp)
                def read(current, expected, n):
                    return (code, bad, 0) if current == path and n <= 3 else (0, expected, 0)
                self.assertEqual(6, len(self.prepare_offline(folder, "control", read)))
                name = "control-" + path.replace("/", "-")
                self.assertEqual(code, json.loads((folder / (name + ".json")).read_text())["exit"])
                self.assertEqual(bad, (folder / (name + ".out")).read_bytes())
                self.assertTrue((folder / (name + "-retry-1.json")).is_file())

    def test_never_ready_times_out_and_cannot_certify_a_complete_looking_run(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder)
            with self.assertRaisesRegex(RuntimeError, "Fixture preparation timed out") as failure:
                self.prepare_offline(folder, "control", lambda path, data, n: (124, b"", 5))
            with patch.object(p, "OUT", folder):
                result = p.finish_run(str(failure.exception), "COMPLETE", "api29")
                self.assertEqual("ERROR", result["collection"])
                self.assertEqual("UNAVAILABLE", result["capability"])
                self.assertEqual("NOT_TESTED", result["acceptance"])
                self.assertEqual("ERROR", p.evaluate()["collection"])

    def probe_registration(self, folder, ready_after):
        """Run the real guarded driver until its first pre-mutation package check."""
        profile = p.PROFILES["api28"]
        fixtures, calls, clock = {}, [], [0.0]
        for variant in p.VARIANTS:
            data = variant.encode()
            (folder / (variant + ".apk")).write_bytes(data)
            fixtures[variant] = {"apkSha256": p.digest(data), "package": p.PREFIX + variant,
                                 "certificate": CERT, "version": "1"}
        p.save(folder / "inputs.json", {"source": "a" * 40, "fixtures": fixtures,
               "implementation": p.digest(Path(p.__file__).read_bytes())})
        p.save(folder / "build.json", {"status": "COMPLETE",
               "inputsSha256": p.digest((folder / "inputs.json").read_bytes())})
        image = folder / "sdk" / profile["image"]
        image.mkdir(parents=True)
        for name in ("source.properties", "package.xml"):
            (image / name).write_text("offline image metadata")
        answers = {"probe-source": "a" * 40, "devices": "List of devices attached\nemulator-5554\tdevice",
                   "avd-name": profile["avd"] + "\nOK", "ro.kernel.qemu": "1",
                   "ro.build.version.sdk": "28", "ro.product.cpu.abi": "x86_64", "root-id": "uid=0(root)",
                   "services": "package:/system/framework/framework-res.apk", "transport-framework-size": "123",
                   "transport-framework-sha": "b" * 64 + "  " + profile["framework"], "download-cache": "/data/cache"}
        def command(where, name, args, timeout=60, required=True):
            calls.append(name)
            if name.startswith("existing-"):
                raise RuntimeError("offline stop before mutation")
            text = answers.get(name, "null" if name.startswith("backup_") else "")
            if name == "transports":
                registered = ready_after is not None and calls.count(name) >= ready_after
                text = "  * " + profile["transport"] if registered else "No transports available."
            p.save(where / (name + ".json"), {"argv": [str(a) for a in args], "exit": 0})
            (where / (name + ".out")).write_text(text + "\n")
            (where / (name + ".err")).write_text("")
            return text
        def sleep(seconds):
            clock[0] += seconds
        env = {"ANDROID_HOME": str(folder / "sdk"), "GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux",
               "BMGR_DISPOSABLE_API28": "1", "BMGR_DISPOSABLE_API29": "0"}
        with patch.object(p, "OUT", folder), patch.object(p, "command", side_effect=command), \
                patch.dict(p.os.environ, env), patch.object(p.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(p.time, "sleep", side_effect=sleep):
            self.assertEqual(1, p.probe())
        self.assertNotIn("enable", calls)
        self.assertFalse(any(name.startswith("install-") for name in calls))
        return calls, clock[0]

    def test_transport_registration_can_follow_package_service_readiness(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            calls, elapsed = self.probe_registration(folder, ready_after=2)
            self.assertIn("existing-control", calls)
            self.assertEqual(2, calls.count("transports"))
            self.assertGreater(elapsed, 0)
            self.assertEqual("No transports available.\n", (folder / "transports-wait-0.out").read_text())

    def test_registered_transport_does_not_wait(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            calls, elapsed = self.probe_registration(Path(tmp), ready_after=1)
            self.assertIn("existing-control", calls)
            self.assertEqual(1, calls.count("transports"))
            self.assertEqual(0, elapsed)

    def test_unregistered_transport_wait_is_bounded_and_never_mutates(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            calls, elapsed = self.probe_registration(Path(tmp), ready_after=None)
            self.assertNotIn("existing-control", calls)
            self.assertGreater(elapsed, 0)
            self.assertLessEqual(elapsed, 90)
            self.assertGreater(calls.count("transports"), 1)
            self.assertEqual("ERROR", json.loads((Path(tmp) / "result.json").read_text())["collection"])

    def complete_run(self, folder, profile="api29", rejected=True):
        """Synthesize the exact receipt set a clean hosted run produces."""
        prof = p.PROFILES[profile]
        adb = ["adb", "-s", "emulator-5554"]
        dataset = prof["dataset"] or "/data/cache" + prof["suffix"]
        fixtures = {}
        for variant in p.VARIANTS:
            (folder / (variant + ".apk")).write_bytes(b"fixture-apk-" + variant.encode())
            fixtures[variant] = {"package": p.PREFIX + variant, "certificate": CERT, "version": "1",
                                 "apkSha256": p.digest(b"fixture-apk-" + variant.encode())}
        (folder / "inputs.json").write_text(json.dumps({"source": "a" * 40, "fixtures": fixtures}))
        for phase in ("cse", "d2d"):
            for variant, item in fixtures.items():
                part = folder / phase / variant
                part.mkdir(parents=True)
                pkg = item["package"]
                bound = dataset + pkg
                p.save(part / "fresh.json", {"argv": adb + ["shell", "test", "!", "-e", bound], "exit": 0})
                p.save(part / "backup.json", {"argv": adb + ["shell", *prof["backupnow"], pkg], "exit": 0})
                p.save(part / "size.json", {"argv": adb + ["shell", "stat", "-c", "%s", bound],
                                            "exit": 1 if rejected and variant != "control" else 0})
                if rejected and variant != "control":
                    (part / "backup.out").write_text(refusal(pkg, monitored=profile == "api29"))
                    (part / "size.err").write_text("stat: '" + bound + "': No such file or directory\n")
                else:
                    (part / "transport.tar").write_bytes(self.package_tar(pkg, True, prof["sdk"]))
                    p.save(part / "transport.json", {"argv": adb + ["exec-out", "cat", bound], "exit": 0})
                    (part / "backup.out").write_text(accepted(pkg))
                    (part / "size.out").write_text("4608\n")
        (folder / "build.json").write_text(json.dumps({"status": "COMPLETE",
            "inputsSha256": p.digest((folder / "inputs.json").read_bytes())}))
        receipts = {
            "devices": "List of devices attached\nemulator-5554\tdevice\n",
            "avd-name": prof["avd"] + "\nOK\n", "fingerprint": "fp/test-keys\n",
            "root": "restarting adbd as root\n", "root-wait": "", "root-id": "uid=0(root)\n",
            "services": "package:/system/framework/framework-res.apk\n",
            "ro.kernel.qemu": "1\n", "ro.build.version.sdk": prof["sdk"] + "\n",
            "ro.product.cpu.abi": "x86_64\n", "transport-pm-path": "",
            "transports": "  * " + prof["transport"] + "\n",
            "selected": "  * " + prof["transport"] + "\n",
            "enable": "Backup Manager now enabled\n", "autorestore": "",
            "select": "Selected transport " + prof["transport"] + "\n",
            "probe-source": "a" * 40 + "\n", "cse-parameters": "", "cse-init": "ok\n",
            "cse-before": "dumpsys\n", "d2d-parameters": "", "d2d-init": "ok\n",
            "d2d-before": "dumpsys\n", "final-backup": "dumpsys\n", "logcat": "logs\n",
        }
        if prof["service"]:
            receipts["final-service"] = "service\n"
            receipts["transport-apk-size"] = "29086\n"
            receipts["transport-apk-pull"] = "pulled\n"
        if prof["framework"]:
            receipts["transport-framework-size"] = "123\n"
            receipts["transport-framework-sha"] = "b" * 64 + "  " + prof["framework"] + "\n"
            receipts["download-cache"] = "/data/cache\n"
        for name, text in receipts.items():
            receipt = {"exit": 0}
            if name == "download-cache":
                receipt["argv"] = adb + ["shell", "printenv", "DOWNLOAD_CACHE"]
            p.save(folder / (name + ".json"), receipt)
            (folder / (name + ".out")).write_text(text)
        for variant in p.VARIANTS:
            p.save(folder / ("existing-" + variant + ".json"), {"exit": 1})
            (folder / ("existing-" + variant + ".out")).write_text("")
            for name, text in (("install-", "Success\n"), ("launch-", "Starting: Intent\n")):
                p.save(folder / (name + variant + ".json"), {"exit": 0})
                (folder / (name + variant + ".out")).write_text(text)
            p.save(folder / ("prepared-" + variant + ".json"), {"package": p.PREFIX + variant,
                   "files": {path: p.digest(data) for path, data in p.EXPECTED.items()}})
        (folder / "image").mkdir(exist_ok=True)
        (folder / "image/source.properties").write_text("src\n")
        (folder / "image/package.xml").write_text("<package/>\n")
        if prof["service"]:
            (folder / "image/LocalTransport.apk").write_bytes(b"transport-impl")
            p.save(folder / "image/transport-apk.json",
                   {"path": "/system/priv-app/LocalTransport/LocalTransport.apk",
                    "sha256": p.digest(b"transport-impl")})
        if prof["framework"]:
            p.save(folder / "image/framework.json",
                   {"path": prof["framework"], "size": 123, "sha256": "b" * 64})
        self.complete_cleanup(folder)

    def complete_cleanup(self, folder):
        old = {"backup_enabled": "1", "backup_auto_restore": "1",
               "backup_local_transport_parameters": "null", "transport": "com.android.localtransport/.LocalTransport"}
        p.save(folder / "original-settings.json", old)
        names = ["cleanup-init", "restore-transport", "restore-enabled", "restore-autorestore", "restore-selection"]
        for variant in p.VARIANTS:
            names += ["cleanup-" + p.PREFIX + variant, "cleanup-empty-" + variant]
            (folder / ("cleanup-" + p.PREFIX + variant + ".out")).write_text("Success\n")
        for key, value in old.items():
            if key != "transport":
                names += ["restore-" + key, "restored-" + key]
                (folder / ("restored-" + key + ".out")).write_text(value + "\n")
        (folder / "restore-selection.out").write_text("  * " + old["transport"] + "\n")
        for name in names:
            p.save(folder / (name + ".json"), {"exit": 0})

    def package_tar(self, pkg, payload, platform="29"):
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            manifest = MANIFEST.replace(PKG.encode(), pkg.encode()).replace(b"\n29\n", b"\n" + platform.encode() + b"\n")
            values = [(f"apps/{pkg}/_manifest", manifest)]
            if payload:
                values.append((f"apps/{pkg}/f/allowed/payload", p.PAYLOAD))
            for name, data in values:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        return out.getvalue()

    def test_rejected_run_is_complete_and_never_acceptance(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder)
            with patch.object(p, "OUT", folder):
                final = p.finish_run(None, "COMPLETE", "api29")
                self.assertEqual("COMPLETE", final["collection"])
                self.assertEqual("REJECTED", final["capability"])
                self.assertEqual("NOT_TESTED", final["acceptance"])
                for case in final["cases"]:
                    expected = "CONTROL" if case["variant"] == "control" else "PREFLIGHT_REJECTED"
                    self.assertEqual(expected, case["status"])
                self.assertEqual(final, p.evaluate())

    def test_refusal_requires_control_receipts_and_exact_output(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            def measure(control=True):
                return p.measurement(folder, PKG, CERT, "29", "1", control, True, "api29")
            self.assertEqual("UNAVAILABLE", measure())
            p.save(folder / "backup.json", {"exit": 0})
            (folder / "backup.out").write_text(refusal(PKG))
            self.assertEqual("UNAVAILABLE", measure())
            p.save(folder / "fresh.json", {"exit": 0})
            p.save(folder / "size.json", {"exit": 1})
            (folder / "size.err").write_text("stat: nope: No such file or directory\n")
            self.assertEqual("PREFLIGHT_REJECTED", measure())
            self.assertEqual("UNAVAILABLE", measure(control=False))
            (folder / "transport.tar").write_bytes(archive())
            self.assertEqual("ERROR", measure())
            (folder / "transport.tar").unlink()
            for bad, expected in ((refusal(PKG).replace("ERROR_PREFLIGHT", "ERROR_FULL_BACKUP"), "ERROR"),
                        (refusal(PKG, version="2"), "ERROR"),
                         (refusal(PKG).replace(p.REJECTION, "Success"), "UNAVAILABLE"),
                         (refusal(PKG) + "Package " + PKG + " with result: Success\n", "ERROR"),
                         (refusal(PKG) + "Backup finished with result: Error\n", "ERROR"),
                         (refusal(PKG) + f"=> Event{{BACKUP_MANAGER_POLICY / SUCCESS : package = {PKG}(v1)}}\n", "ERROR"),
                         (refusal(PKG).replace("Package @pm@ with result: Success", "Package @pm@ with result: Error"), "ERROR"),
                         (refusal(PKG).replace("Backup finished with result: Success", "Backup finished with result: Timeout"), "ERROR"),
                         ("", "ERROR")):
                with self.subTest(output=bad):
                    (folder / "backup.out").write_text(bad)
                    self.assertEqual(expected, measure())
            (folder / "backup.out").write_text(refusal(PKG))
            p.save(folder / "size.json", {"exit": 0})
            (folder / "size.out").write_text("0\n")
            self.assertEqual("PREFLIGHT_REJECTED", measure())
            (folder / "size.out").write_text("12\n")
            self.assertEqual("ERROR", measure())
            p.save(folder / "fresh.json", {"exit": 1})
            self.assertEqual("ERROR", measure())
            p.save(folder / "backup.json", {"exit": 1})
            self.assertEqual("ERROR", measure())

    def test_control_refusal_and_platform_version_are_errors(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            control = p.PREFIX + "control"
            p.save(folder / "fresh.json", {"exit": 0})
            p.save(folder / "backup.json", {"exit": 0})
            (folder / "backup.out").write_text(refusal(control))
            p.save(folder / "size.json", {"exit": 1})
            (folder / "size.err").write_text("stat: x: No such file or directory\n")
            self.assertEqual("ERROR", p.measurement(folder, control, CERT, "29", "1", True, False, "api29"))

    def test_refuses_complete_looking_run_after_cleanup_failure(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder)
            with patch.object(p, "OUT", folder):
                p.finish_run(None, "COMPLETE", "api29")
                self.assertEqual("COMPLETE", p.evaluate()["collection"])
                (folder / "restore-transport.json").write_text('{"exit": 1}')
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
                final = p.finish_run("cleanup failed", "ERROR", "api29")
                self.assertEqual("ERROR", final["collection"])
                self.assertEqual(final, json.loads((folder / "result.json").read_text()))
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])

    def test_unfinished_and_stale_error_cannot_be_certified_or_modified(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder)
            with patch.object(p, "OUT", folder):
                p.finish_run(None, "COMPLETE", "api29")
                final = (folder / "result.json").read_bytes()
                (folder / "error.json").write_text('{"error": "historical failure"}')
                self.assertEqual("ERROR", p.evaluate()["collection"])
                self.assertEqual(final, (folder / "result.json").read_bytes())
                (folder / "error.json").unlink()
                p.save(folder / "result.json", {"collection": "RUNNING", "cleanup": "PENDING"})
                self.assertEqual("ERROR", p.evaluate()["collection"])

    def test_missing_receipts_fail_verification(self):
        required = ["avd-name", "ro.build.version.sdk", "selected", "probe-source", "prepared-control",
                    "existing-control", "install-control", "launch-control", "cse/control/fresh",
                    "cse/control/backup", "d2d/d2d/size", "final-backup", "logcat", "final-service",
                    "transport-apk-pull"]
        for name in required:
            with self.subTest(name=name), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                folder = Path(tmp)
                self.complete_run(folder)
                (folder / (name + ".json")).unlink()
                with patch.object(p, "OUT", folder):
                    p.finish_run(None, "COMPLETE", "api29")
                    self.assertNotEqual("COMPLETE", p.evaluate()["collection"])

    def test_wrong_receipt_contents_fail_verification(self):
        wrong = {"avd-name": "other-avd\n", "ro.build.version.sdk": "30\n",
                 "probe-source": "b" * 40 + "\n", "ro.product.cpu.abi": "arm64\n",
                 "selected": "  * other/transport\n"}
        for name, text in wrong.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                folder = Path(tmp)
                self.complete_run(folder)
                (folder / (name + ".out")).write_text(text)
                with patch.object(p, "OUT", folder):
                    p.finish_run(None, "COMPLETE", "api29")
                    self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder)
            p.save(folder / "prepared-control.json", {"package": p.PREFIX + "control", "files": {}})
            with patch.object(p, "OUT", folder):
                p.finish_run(None, "COMPLETE", "api29")
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
                saved = json.loads((folder / "cse/cse/backup.json").read_text())
                saved["argv"].remove("--monitor")
                (folder / "cse/cse/backup.json").write_text(json.dumps(saved))
                p.save(folder / "prepared-control.json", {"package": p.PREFIX + "control",
                       "files": {path: p.digest(data) for path, data in p.EXPECTED.items()}})
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])

    def test_fresh_build_preserves_but_isolates_historical_receipts(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            (folder / "error.json").write_bytes(b"original failure")
            (folder / "cse").mkdir()
            with patch.object(p, "OUT", folder):
                p.fresh_build()
            self.assertFalse((folder / "error.json").exists())
            self.assertFalse((folder / "cse").exists())
            self.assertEqual([b"original failure"], [f.read_bytes() for f in folder.glob("history/*/error.json")])
            self.assertEqual("RUNNING", json.loads((folder / "build.json").read_text())["status"])

    def test_api28_profile_run_and_receipts(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder, profile="api28")
            with patch.object(p, "OUT", folder):
                final = p.finish_run(None, "COMPLETE", "api28")
                self.assertEqual(("COMPLETE", "REJECTED", "api28"),
                                 (final["collection"], final["capability"], final["profile"]))
                self.assertEqual(2 * ["CONTROL", "PACKAGE_REJECTED", "PACKAGE_REJECTED"],
                                 [case["status"] for case in final["cases"]])
                self.assertEqual(final, p.evaluate())
                (folder / "transport-framework-sha.json").unlink()
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
        for bad, code in (("", 0), ("/sdcard\n", 0), ("/cache\n/data/cache\n", 0), ("/cache\n", 1), (None, 0)):
            with self.subTest(root=bad), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                folder = Path(tmp)
                self.complete_run(folder, profile="api28")
                if bad is None:
                    (folder / "download-cache.json").unlink()
                else:
                    p.save(folder / "download-cache.json", {"exit": code})
                    (folder / "download-cache.out").write_text(bad)
                with patch.object(p, "OUT", folder):
                    p.finish_run(None, "COMPLETE", "api28")
                    self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
        for name in ("cse/cse/backup", "d2d/d2d/size"):
            with self.subTest(name=name), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                folder = Path(tmp)
                self.complete_run(folder, profile="api28")
                saved = json.loads((folder / (name + ".json")).read_text())
                saved["argv"].insert(-1, "--monitor")
                (folder / (name + ".json")).write_text(json.dumps(saved))
                with patch.object(p, "OUT", folder):
                    p.finish_run(None, "COMPLETE", "api28")
                    self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            case = Path(tmp)
            for name, code in (("fresh", 0), ("backup", 0), ("size", 1)):
                p.save(case / (name + ".json"), {"exit": code})
            (case / "size.err").write_text("stat: x: No such file or directory\n")
            refusal28 = refusal(PKG, monitored=False)
            measure = lambda control=True, pkg=PKG: p.measurement(case, pkg, CERT, "28", "1", control, True, "api28")
            (case / "backup.out").write_text(refusal28)
            self.assertEqual("PACKAGE_REJECTED", measure())
            self.assertEqual("UNAVAILABLE", measure(control=False))
            self.assertEqual("ERROR", measure(pkg=p.PREFIX + "control"))
            (case / "transport.tar").write_bytes(archive())
            self.assertEqual("ERROR", measure())
            (case / "transport.tar").unlink()
            run3 = refusal28.replace("1 requested", "2 requested").replace("Package @pm@",
                "Package --monitor with result: Package not found\nPackage @pm@")
            for bad in (run3, refusal(PKG), refusal28 + "Package " + PKG + " with result: Success\n",
                        refusal28.replace(p.REJECTION, "Unexpected: " + p.REJECTION),
                        refusal28.replace("Backup finished with result: Success",
                                          "Backup finished with result: Unexpected: Success"),
                        refusal28.replace("Backup finished with result: Success",
                                          "Backup finished with result: Timeout"), ""):
                with self.subTest(output=bad):
                    (case / "backup.out").write_text(bad)
                    self.assertEqual("ERROR", measure())
            (case / "backup.out").write_text(refusal28.replace(p.REJECTION, "Success"))
            self.assertEqual("UNAVAILABLE", measure())

    def test_cache_capture_requires_success_and_exact_command(self):
        for root in p.CACHE_ROOTS:
            self.assertEqual(root, p.cache_root(root + "\n", 0))
        for raw, code in (("", 0), ("", 1), ("/cache\n", 1), (" /cache\n", 0),
                          ("/cache\n/data/cache\n", 0), ("/sdcard\n", 0)):
            with self.subTest(raw=raw, code=code), self.assertRaises(ValueError):
                p.cache_root(raw, code)
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder, profile="api28")
            with patch.object(p, "OUT", folder):
                p.finish_run(None, "COMPLETE", "api28")
                self.assertEqual("COMPLETE", p.evaluate()["collection"])
                p.save(folder / "download-cache.json",
                       {"exit": 0, "argv": ["adb", "shell", "echo", "/data/cache"]})
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])

    def test_control_tar_requires_a_successful_bound_pull(self):
        for profile in p.PROFILES:
            for invalid in ("missing", "wrong path", "failed"):
                with self.subTest(profile=profile, invalid=invalid), tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
                    folder = Path(tmp)
                    self.complete_run(folder, profile=profile)
                    with patch.object(p, "OUT", folder):
                        p.finish_run(None, "COMPLETE", profile)
                        self.assertEqual("COMPLETE", p.evaluate()["collection"])
                        receipt = folder / "cse/control/transport.json"
                        if invalid == "missing":
                            receipt.unlink()
                        else:
                            value = json.loads(receipt.read_text())
                            if invalid == "failed":
                                value["exit"] = 1
                            else:
                                value["argv"][-1] += "-old"
                            p.save(receipt, value)
                        self.assertNotEqual("COMPLETE", p.evaluate()["collection"])

    def test_blocked_diagnostic_never_becomes_acceptance(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_run(folder)
            with patch.object(p, "OUT", folder):
                (folder / "cse/control/transport.tar").write_bytes(self.package_tar(p.PREFIX + "control", False))
                invalid = p.finish_run(None, "COMPLETE", "api29")
                self.assertEqual("ERROR", invalid["collection"])
                self.assertEqual("UNAVAILABLE", invalid["capability"])

    def test_valid_payload_and_gated_empty(self):
        self.assertEqual("PAYLOAD", p.verify_archive(archive(), PKG, CERT, "29", "1"))
        self.assertEqual("EMPTY", p.verify_archive(archive(payload=None), PKG, CERT, "29", "1"))

    def test_tamper_identity_and_invalid_archive(self):
        for raw in (archive(payload=b"bad"), archive(manifest=MANIFEST.replace(PKG.encode(), b"other")),
                    archive(manifest=MANIFEST.replace(b"63657274", b"00")),
                    archive(manifest=MANIFEST.replace(b"\n29\n", b"\n28\n")),
                    archive(manifest=MANIFEST.replace(b"\n1\n29", b"\n2\n29")), b"not tar", archive() + b"extra"):
            with self.subTest(raw=hashlib.sha256(raw).hexdigest()), self.assertRaises((ValueError, tarfile.TarError)):
                p.verify_archive(raw, PKG, CERT, "29", "1")

    def test_duplicate_extra_traversal_links_and_canaries(self):
        for name, kind in (("f/allowed/payload", b"0"), ("_manifest", b"0"), ("f/other/excluded", b"0"),
                           ("no_backup/excluded", b"0"), ("f/../allowed/payload", b"0"),
                           ("/absolute", b"0"), ("f/allowed/payload", b"2"), ("f/allowed/payload", b"1")):
            with self.subTest(name=name, kind=kind), self.assertRaises(ValueError):
                p.verify_archive(archive([(BASE + name, b"canary", kind)]), PKG, CERT, "29", "1")

    def test_verdicts_require_control_and_both_successes(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            p.save(folder / "fresh.json", {"exit": 0})
            def measure(control=True, positive=True):
                return p.measurement(folder, PKG, CERT, "29", "1", control, positive, "api29")
            self.assertEqual("UNAVAILABLE", measure())
            p.save(folder / "backup.json", {"exit": 0})
            success = f"Package {PKG} with result: Success\nBackup finished with result: Success\n"
            (folder / "backup.out").write_text(success)
            p.save(folder / "size.json", {"exit": 0})
            (folder / "size.out").write_text("4608\n")
            self.assertEqual("UNAVAILABLE", measure())
            (folder / "transport.tar").write_bytes(archive(payload=None))
            self.assertEqual("BLOCKED", measure())
            self.assertEqual("UNAVAILABLE", measure(False))
            self.assertEqual("EXCLUDED", measure(positive=False))
            (folder / "transport.tar").write_bytes(archive())
            self.assertEqual("OBSERVED", measure())
            self.assertEqual("ERROR", measure(positive=False))
            for text in ("", success.splitlines()[0], success.replace("Success", "Transport error"),
                         success.replace(f"Package {PKG} with result: Success", f"Package {PKG} with result: TransportRejected")):
                (folder / "backup.out").write_text(text)
                self.assertEqual("ERROR", measure())
            (folder / "backup.out").write_text(success + success)
            self.assertEqual("ERROR", measure())
            (folder / "backup.out").write_text(success)
            p.save(folder / "backup.json", {"exit": 1})
            self.assertEqual("ERROR", measure())

    def test_workflow_is_isolated_and_builds_before_one_probe_line(self):
        workflow = (ROOT / ".github/workflows/nightly.yml").read_text()
        following_job = (
            "\n  unrelated:\n    runs-on: ubuntu-24.04\n    timeout-minutes: 5\n"
            "    steps:\n      - run: ./gradlew help\n"
        )
        body = workflow.split("\n  bmgr-api29-probe:\n", 1)[1]
        for suffix in ("", following_job):
            job = re.split(r"\n  (?=[A-Za-z0-9_-]+:)", body + suffix, maxsplit=1)[0]
            self.assertIn("if: inputs.scope == 'bmgr-api29-probe'", job)
            self.assertIn("timeout-minutes: 30", job)
            self.assertLess(job.index(".py build"), job.index("android-emulator-runner@"))
            self.assertEqual(1, job.count("script: python3 scripts/ci/bmgr-api29-probe.py probe"))
            self.assertIn("if: always()", job)
            self.assertNotIn("./gradlew", job)
        legacy = workflow.split("\n  bmgr-legacy-negative:\n", 1)[1]
        for suffix in ("", following_job):
            job = re.split(r"\n  (?=[A-Za-z0-9_-]+:)", legacy + suffix, maxsplit=1)[0]
            self.assertIn("if: inputs.scope == 'bmgr-legacy-negative'", job)
            self.assertIn('api: ["28", "29"]', job)
            self.assertIn('api-level: "${{ matrix.api }}"', job)
            self.assertIn('avd-name: nd-bmgr-api${{ matrix.api }}-probe', job)
            self.assertIn("BMGR_DISPOSABLE_API28", job)
            self.assertIn("BMGR_DISPOSABLE_API29", job)
            self.assertIn("setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0", job)
            self.assertLess(job.index(".py build"), job.index("android-emulator-runner@"))
            self.assertEqual(1, job.count("script: python3 scripts/ci/bmgr-api29-probe.py probe"))
            self.assertIn("if: always()", job)
            self.assertNotIn("./gradlew", job)
        self.assertNotIn("de7274f081f381c8f8158605e0321c36c376e2e6", workflow)


if __name__ == "__main__":
    unittest.main()
