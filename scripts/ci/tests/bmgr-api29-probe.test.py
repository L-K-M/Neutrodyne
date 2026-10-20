#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Offline synthetic TAR/verdict semantics only; never Android execution."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
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


class ProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT / "build").mkdir(exist_ok=True)

    def prepare_offline(self, folder, variant, read):
        """Exercise real receipt/byte checks; subprocess and elapsed time are offline stubs."""
        clock, reads = [0.0], []
        expected = {"files/allowed/payload": p.PAYLOAD, "files/other/excluded": b"excluded-files-canary\n",
                    "no_backup/excluded": b"excluded-no-backup-canary\n"}
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
            code, data, elapsed = read(path, expected[path], len(reads))
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
            self.complete_raw_run(folder)
            self.complete_cleanup(folder)
            with self.assertRaisesRegex(RuntimeError, "Fixture preparation timed out") as failure:
                self.prepare_offline(folder, "control", lambda path, data, n: (124, b"", 5))
            with patch.object(p, "OUT", folder):
                result = p.finish_run(str(failure.exception), "COMPLETE")
                self.assertEqual("ERROR", result["collection"])
                self.assertEqual("UNAVAILABLE", result["capability"])
                self.assertEqual("NOT_TESTED", result["acceptance"])
                self.assertEqual("ERROR", p.evaluate()["collection"])

    def complete_raw_run(self, folder):
        fixtures = {v: {"package": p.PREFIX + v, "certificate": CERT} for v in p.VARIANTS}
        (folder / "inputs.json").write_text(json.dumps({"source": "a" * 40, "fixtures": fixtures}))
        for phase in ("cse", "d2d"):
            for variant, item in fixtures.items():
                part = folder / phase / variant
                part.mkdir(parents=True)
                pkg = item["package"]
                data = self.package_tar(pkg, variant in ("control", phase))
                (part / "transport.tar").write_bytes(data)
                (part / "backup.json").write_text('{"exit": 0}')
                (part / "backup.out").write_text(f"Package {pkg} with result: Success\nBackup finished with result: Success\n")
        (folder / "build.json").write_text(json.dumps({"status": "COMPLETE",
            "inputsSha256": p.digest((folder / "inputs.json").read_bytes())}))

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

    def package_tar(self, pkg, payload):
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            values = [(f"apps/{pkg}/_manifest", MANIFEST.replace(PKG.encode(), pkg.encode()))]
            if payload:
                values.append((f"apps/{pkg}/f/allowed/payload", p.PAYLOAD))
            for name, data in values:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        return out.getvalue()

    def test_refuses_complete_looking_run_after_cleanup_failure(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_raw_run(folder)
            self.complete_cleanup(folder)
            with patch.object(p, "OUT", folder):
                p.finish_run(None, "COMPLETE")
                self.assertEqual("COMPLETE", p.evaluate()["collection"])
                (folder / "restore-transport.json").write_text('{"exit": 1}')
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])
                final = p.finish_run("cleanup failed", "ERROR")
                self.assertEqual("ERROR", final["collection"])
                self.assertEqual(final, json.loads((folder / "result.json").read_text()))
                self.assertNotEqual("COMPLETE", p.evaluate()["collection"])

    def test_unfinished_and_stale_error_cannot_be_certified_or_modified(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_raw_run(folder)
            self.complete_cleanup(folder)
            with patch.object(p, "OUT", folder):
                p.finish_run(None, "COMPLETE")
                final = (folder / "result.json").read_bytes()
                (folder / "error.json").write_text('{"error": "historical failure"}')
                self.assertEqual("ERROR", p.evaluate()["collection"])
                self.assertEqual(final, (folder / "result.json").read_bytes())
                (folder / "error.json").unlink()
                p.save(folder / "result.json", {"collection": "RUNNING", "cleanup": "PENDING"})
                self.assertEqual("ERROR", p.evaluate()["collection"])

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

    def test_blocked_diagnostic_never_becomes_acceptance(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            self.complete_raw_run(folder)
            self.complete_cleanup(folder)
            for phase in ("cse", "d2d"):
                (folder / phase / phase / "transport.tar").write_bytes(self.package_tar(p.PREFIX + phase, False))
            with patch.object(p, "OUT", folder):
                final = p.finish_run(None, "COMPLETE")
                self.assertEqual("COMPLETE", final["collection"])
                self.assertEqual("BLOCKED", final["capability"])
                self.assertEqual("NOT_TESTED", final["acceptance"])
                self.assertEqual(final, p.evaluate())
                (folder / "cse/control/transport.tar").write_bytes(self.package_tar(p.PREFIX + "control", False))
                invalid = p.finish_run(None, "COMPLETE")
                self.assertEqual("ERROR", invalid["collection"])
                self.assertEqual("UNAVAILABLE", invalid["cases"][1]["status"])

    def test_valid_payload_and_gated_empty(self):
        self.assertEqual("PAYLOAD", p.verify_archive(archive(), PKG, CERT))
        self.assertEqual("EMPTY", p.verify_archive(archive(payload=None), PKG, CERT))

    def test_tamper_identity_and_invalid_archive(self):
        for raw in (archive(payload=b"bad"), archive(manifest=MANIFEST.replace(PKG.encode(), b"other")),
                    archive(manifest=MANIFEST.replace(b"63657274", b"00")), b"not tar", archive() + b"extra"):
            with self.subTest(raw=hashlib.sha256(raw).hexdigest()), self.assertRaises((ValueError, tarfile.TarError)):
                p.verify_archive(raw, PKG, CERT)

    def test_duplicate_extra_traversal_links_and_canaries(self):
        for name, kind in (("f/allowed/payload", b"0"), ("_manifest", b"0"), ("f/other/excluded", b"0"),
                           ("no_backup/excluded", b"0"), ("f/../allowed/payload", b"0"),
                           ("/absolute", b"0"), ("f/allowed/payload", b"2"), ("f/allowed/payload", b"1")):
            with self.subTest(name=name, kind=kind), self.assertRaises(ValueError):
                p.verify_archive(archive([(BASE + name, b"canary", kind)]), PKG, CERT)

    def test_verdicts_require_control_and_both_successes(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            folder = Path(tmp)
            def measure(control=True, positive=True):
                return p.measurement(folder, PKG, CERT, control, positive)
            self.assertEqual("UNAVAILABLE", measure())
            (folder / "backup.json").write_text(json.dumps({"exit": 0}))
            success = f"Package {PKG} with result: Success\nBackup finished with result: Success\n"
            (folder / "backup.out").write_text(success)
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
            (folder / "backup.json").write_text(json.dumps({"exit": 1}))
            self.assertEqual("ERROR", measure())

    def test_workflow_is_isolated_and_builds_before_one_probe_line(self):
        workflow = (ROOT / ".github/workflows/nightly.yml").read_text()
        job = workflow.split("\n  bmgr-api29-probe:\n")[1]
        self.assertIn("if: inputs.scope == 'bmgr-api29-probe'", job)
        self.assertIn("timeout-minutes: 30", job)
        self.assertLess(job.index(".py build"), job.index("android-emulator-runner@"))
        self.assertEqual(1, job.count("script: python3 scripts/ci/bmgr-api29-probe.py probe"))
        self.assertIn("if: always()", job)
        self.assertNotIn("./gradlew", job)


if __name__ == "__main__":
    unittest.main()
