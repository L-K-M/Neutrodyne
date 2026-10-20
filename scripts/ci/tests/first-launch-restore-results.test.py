#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise the XML selector and its nightly gate with isolated GMD fixtures."""

import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import unittest
import zipfile


REPO = Path(__file__).resolve().parents[3]
SCRIPT = REPO / "scripts/ci/check-first-launch-restore-results.py"
CLASS = "ch.lkmc.neutrodyne.FirstLaunchRestoreDeviceTest"
CASE = "firstLaunchRestoresTheSeededSnapshot"
RESULTS = Path("app/build/outputs/androidTest-results/managedDevice/debug")


def report(device, outcome="", classname=CLASS, name=CASE):
    # Shape and device property from nightly 39136456538's archived GMD XML.
    return f'''<testsuites tests="1" failures="0" errors="0" skipped="0">
  <testsuite name="{classname}" tests="1" failures="0" errors="0" skipped="0">
    <properties><property name="device" value="_app_{device}DebugAndroidTest" /></properties>
    <testcase name="{name}" classname="{classname}">{outcome}</testcase>
  </testsuite>
</testsuites>'''


class RestoreResultsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.results = self.root / RESULTS

    def write_report(self, device, xml=None, root=None):
        path = (root or self.results) / device / f"TEST-{device}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(report(device) if xml is None else xml)
        return path

    def run_nightly(self, devices, outcome=""):
        fixture = self.root / "fixture"
        for device in devices:
            self.write_report(device, report(device, outcome), fixture)
        fixture.mkdir(exist_ok=True)
        # Simulate a successful GMD command even when tests skip or a leg is absent.
        gradlew = self.root / "gradlew"
        gradlew.write_text(
            "#!/usr/bin/env python3\n"
            "import os, shutil\n"
            f"shutil.copytree(os.environ['GMD_FIXTURE'], {str(RESULTS)!r}, dirs_exist_ok=True)\n"
        )
        gradlew.chmod(0o755)
        if SCRIPT.exists():
            target = self.root / SCRIPT.relative_to(REPO)
            target.parent.mkdir(parents=True)
            shutil.copyfile(SCRIPT, target)
        workflow = (REPO / ".github/workflows/nightly.yml").read_text()
        job = workflow.split("  first-launch-restore:\n", 1)[1].split("\n  repro:", 1)[0]
        gate = job.split("      - name: First-launch restore on api26 + api36\n", 1)[1]
        gate = gate.split("      - name: Report failure\n", 1)[0]
        runs = re.findall(r"        run: ([|>]-?)\n((?:          .*\n)+)", gate)
        self.assertTrue(runs, "the real GMD run step must be exercised")
        for style, body in runs:
            command = textwrap.dedent(body)
            if style.startswith(">"):
                command = " ".join(command.splitlines())
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", command], cwd=self.root,
                env={**os.environ, "GMD_FIXTURE": str(fixture)},
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode:
                return result
        return result

    def test_nightly_rejects_successful_gmd_with_skipped_evidence(self):
        result = self.run_nightly(("api26", "api36"), "<skipped />")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("api26: skipped or failed test evidence", result.stderr)

    def test_nightly_cannot_reuse_a_stale_missing_leg(self):
        self.write_report("api36")
        result = self.run_nightly(("api26",))
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("api36: expected only", result.stderr)

    def test_nightly_accepts_both_executed_legs(self):
        result = self.run_nightly(("api26", "api36"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("api26: PASS", result.stdout)
        self.assertIn("api36: PASS", result.stdout)

    def select(self):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.results)],
                              capture_output=True, text=True, timeout=30)

    def assert_rejected(self, diagnostic):
        result = self.select()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(diagnostic, result.stderr)

    def test_accepts_archived_gmd_shape(self):
        for device in ("api26", "api36"):
            self.write_report(device)
        result = self.select()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, result.stdout.count(": PASS"))

    def test_rejects_missing_results(self):
        self.assert_rejected("api26:")

    def test_rejects_either_missing_leg(self):
        for device in ("api26", "api36"):
            with self.subTest(missing=device):
                self.write_report("api36" if device == "api26" else "api26")
                self.assert_rejected(f"{device}:")
                shutil.rmtree(self.results)

    def test_rejects_bad_evidence_on_either_leg(self):
        for device in ("api26", "api36"):
            invalid = {
                "empty file": "",
                "malformed": "<testsuites>",
                "empty suite": '<testsuites tests="0" />',
                "wrong class": report(device, classname="OtherTest"),
                "wrong case": report(device, name="someOtherTest"),
                "skipped": report(device, "<skipped />"),
                "failure": report(device, "<failure />"),
                "error": report(device, "<error />"),
                "wrong device": report("api36" if device == "api26" else "api26"),
                "no device": report(device).replace('name="device"', 'name="other"'),
                "no cases": report(device).replace(
                    f'<testcase name="{CASE}" classname="{CLASS}"></testcase>', ""),
                "duplicate case": report(device).replace(
                    "</testsuite>", f'<testcase name="{CASE}" classname="{CLASS}" /></testsuite>'),
            }
            # Counters alone cannot establish success, but contradictions must fail too.
            for count in ("skipped", "failures", "errors"):
                invalid[f"suite {count}"] = report(device).replace(
                    f'{count}="0"', f'{count}="1"')
            invalid["wrong count"] = report(device).replace('tests="1"', 'tests="2"')
            invalid["invalid count"] = report(device).replace('tests="1"', 'tests="NaN"')
            for label, xml in invalid.items():
                with self.subTest(device=device, evidence=label):
                    for other in ("api26", "api36"):
                        self.write_report(other)
                    self.write_report(device, xml)
                    self.assert_rejected("first-launch-restore:")

    def test_aggregate_cannot_supply_missing_device(self):
        self.write_report("api26")
        aggregate = self.results / "TEST-all.xml"
        aggregate.write_text(report("api36"))
        self.assert_rejected("api36:")

    def test_duplicate_api26_report_cannot_supply_api36(self):
        path = self.write_report("api26")
        self.write_report("api36", path.read_text())
        self.assert_rejected("api36:")

    def test_duplicate_aggregate_is_rejected_in_device_directory(self):
        for device in ("api26", "api36"):
            path = self.write_report(device)
        path.with_name("TEST-aggregate.xml").write_text(path.read_text())
        self.assert_rejected("api36:")


BMGR_CLASS = "ch.lkmc.neutrodyne.BmgrTransportDeviceTest"
BMGR_METHODS = {
    "prepare": "prepareLocalTransportBackup",
    "verify": "firstLaunchRestoresTransportedSnapshot",
    "empty": "firstLaunchWithoutTransportDataStaysEmpty",
}


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def bmgr_expectation(api, snapshot_zip=None):
    exp = {
        "v": 1, "nonce": "run-1700000000-42", "package": "ch.lkmc.neutrodyne",
        "api": api, "installationId": "install-abc",
        "snapshotSha256": "", "snapshotBytes": 0,
        "portableSha256": _sha(b"portable-pb"), "portableBytes": 11,
        "portableKey": "backup.opml_layout",
        "portableInSnapshot": "GROUPED", "portableAfter": "FLAT",
        "syncIds": {"k1": "s1"}, "groupUuids": {"G": "u1"},
        "canaries": [{"where": "files", "rel": "downloads/x", "sha256": _sha(b"c"), "bytes": 1}],
        "receiverLine": "ok at=1 bytes=2 level=3", "preparedAt": 1,
    }
    if snapshot_zip is not None:
        exp["snapshotSha256"], exp["snapshotBytes"] = _sha(snapshot_zip), len(snapshot_zip)
    return exp


def bmgr_snapshot_zip(exp):
    payloads = {
        "library.json": b'{"podcasts":[],"groups":[]}',
        "episodes.jsonl": b"",
        "queue.json": b'{"upNext":[]}',
        "settings.json": json.dumps(
            {"values": [{"key": "backup.opml_layout", "type": "CHOICE",
                         "value": exp["portableInSnapshot"]}]}).encode(),
    }
    manifest = {
        "format": "neutrodyne-backup", "formatVersion": 1, "minReaderVersion": 1,
        "kind": "AUTO_SNAPSHOT", "installationId": exp["installationId"],
        "entries": [{"name": n, "bytes": len(b), "sha256": _sha(b), "records": 1}
                    for n, b in payloads.items()],
    }
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("manifest.json", json.dumps(manifest))
        for name, data in payloads.items():
            zf.writestr(name, data)
    return buffer.getvalue()


def bmgr_tar(exp, snapshot_zip):
    pkg = exp["package"]
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as tar:
        for name, data in (
            (f"apps/{pkg}/_manifest", b"<manifest />"),
            (f"apps/{pkg}/f/backup/auto-snapshot.zip", snapshot_zip),
            (f"apps/{pkg}/f/datastore/settings.preferences_pb", b"portable-pb"),
        ):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def bmgr_instrument_log(phase, b64=None, fail_code=0, other=None):
    method = BMGR_METHODS[phase]
    body = (
        f"INSTRUMENTATION_STATUS: class={BMGR_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS: numtests=1\n"
        f"INSTRUMENTATION_STATUS_CODE: 1\n"
    )
    if b64 is not None:
        body += (f"INSTRUMENTATION_STATUS: bmgrExpected={b64}\n"
                 "INSTRUMENTATION_STATUS_CODE: 2\n")
    body += (
        f"INSTRUMENTATION_STATUS: class={BMGR_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS_CODE: {fail_code}\n"
        f"{other or ''}"
        "INSTRUMENTATION_RESULT: stream=\nTest results ran to completion.\nOK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )
    return body


class BmgrEvidenceTest(unittest.TestCase):
    """`--bmgr` strict-mode fixtures — built entirely with the stdlib, offline."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def build_leg(self, device, api, mutate=None):
        directory = self.root / device
        directory.mkdir(parents=True, exist_ok=True)
        exp = bmgr_expectation(api)
        snapshot_zip = bmgr_snapshot_zip(exp)
        exp["snapshotSha256"], exp["snapshotBytes"] = _sha(snapshot_zip), len(snapshot_zip)
        tar = bmgr_tar(exp, snapshot_zip)
        b64 = base64.urlsafe_b64encode(
            json.dumps(exp).encode()).decode().rstrip("=")
        refusal = "Transport rejected package because it wasn't able to process it at the time"
        run = {
            "schema": "bmgr-evidence/v1", "device": device, "api": api,
            "fingerprint": "google/sdk/fp", "abi": "x86_64", "nonce": exp["nonce"],
            "sourceSha": "abc", "apkSha256": "a" * 64, "testApkSha256": "b" * 64,
            "certSha256": "c" * 64, "transport": "com.android.localtransport/.LocalTransport",
            "tarSha256": _sha(tar), "unencryptedDiagnosis": "encoff:" + refusal,
            "unencryptedBackupnowExit": 0, "positiveBackupnowExit": 0,
            "cleanupStatus": "ok", "avd": f"bmgr-api{api}",
            "tarDevicePath": f"/data/data/com.android.localtransport/files/1/_full/{exp['package']}",
            "selectors": {name: "pass" for name in (
                "apkNonDebuggable", "apkSignatureStable", "testInstrumentationAvailable",
                "expectationCaptured", "unencryptedTransportExcluded",
                "positivePayloadVerified", "restoredFilesMatched", "emptyLegExcluded")},
        }
        (directory / "run.json").write_text(json.dumps(run))
        (directory / "expectation.json").write_text(json.dumps(exp))
        (directory / "transport.tar").write_bytes(tar)
        for label in ("encoff-pre", "encoff", "positive", "empty"):
            (directory / f"init-{label}.txt").write_text(
                "Initializing transports: [com.android.localtransport/.LocalTransport]\n"
                "Initialization result: 0\n")
        (directory / "encoff-payload.txt").write_text("")
        (directory / "backupnow-positive.log").write_text(
            f"Package {exp['package']} with result: Success\n"
            "Backup finished with result: Success\n")
        (directory / "backupnow-encoff.log").write_text(
            "Running incremental backup for 1 requested packages.\n"
            "Package @pm@ with result: Success\n"
            f"=> Event{{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = {exp['package']}(v1)}}\n"
            f"Package {exp['package']} with result: {refusal}\n"
            "Backup finished with result: Success\n")
        (directory / "restored-files.txt").write_text(
            f"{exp['snapshotSha256']}  files/backup/auto-snapshot.zip\n"
            f"{exp['portableSha256']}  files/datastore/settings.preferences_pb\n"
            f"/data/user/0/{exp['package']}/files/backup/auto-snapshot.zip\n"
            f"/data/user/0/{exp['package']}/files/datastore/settings.preferences_pb\n")
        logs = {
            "prepare": bmgr_instrument_log("prepare", b64=b64),
            "verify": bmgr_instrument_log("verify"),
            "empty": bmgr_instrument_log("empty"),
        }
        if mutate:
            mutate(directory, run, exp, logs)
        for phase, text in logs.items():
            (directory / f"instrument-{phase}.log").write_text(text)
        return directory

    def build(self, devices=("api31", "api36"), mutate=None):
        apis = {"api31": 31, "api36": 36}
        for device in devices:
            self.build_leg(device, apis[device], mutate.get(device) if mutate else None)

    def check(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--bmgr", str(self.root)],
            capture_output=True, text=True, timeout=30)

    def test_accepts_a_complete_two_leg_run(self):
        self.build()
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("bmgr: PASS api31 + api36", result.stdout)

    def test_rejects_undiagnosed_encryption_off_controls(self):
        pkg = "ch.lkmc.neutrodyne"
        refusal = "Transport rejected package because it wasn't able to process it at the time"
        valid = ("Package @pm@ with result: Success\n"
                 f"=> Event{{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = {pkg}(v1)}}\n"
                 f"Package {pkg} with result: {refusal}\n"
                 "Backup finished with result: Success\n")
        invalid = (
            ("empty", "", "encoff:"),
            ("transport error", f"Package {pkg} with result: Transport error\n", "encoff:Transport error"),
            ("agent error", f"Package {pkg} with result: Agent error\n", "encoff:Agent error"),
            ("wrong package", valid.replace(pkg, "ch.lkmc.other"), "encoff:" + refusal),
            ("contradictory finish", valid + "Backup finished with result: Error\n", "encoff:" + refusal),
            ("successful backup", f"Package {pkg} with result: Success\nBackup finished with result: Success\n", "encoff:Success"),
            ("metadata error", valid.replace("Package @pm@ with result: Success", "Package @pm@ with result: Error"), "encoff:" + refusal),
            ("mismatched diagnosis", valid, "encoff:Backup is not allowed"),
        )
        for label, text, diagnosis in invalid:
            with self.subTest(control=label):
                def mutate(directory, run, exp, logs):
                    run["unencryptedDiagnosis"] = diagnosis
                    (directory / "run.json").write_text(json.dumps(run))
                    (directory / "backupnow-encoff.log").write_text(text)
                self.build(mutate={"api31": mutate})
                result = self.check()
                self.assertNotEqual(0, result.returncode, result.stdout)

    def test_host_never_cleans_up_an_unverified_device(self):
        adb = self.root / "adb"
        calls = self.root / "adb-calls.jsonl"
        adb.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\n"
            "with open(os.environ['ADB_CALLS'], 'a') as out:\n"
            "    out.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "sys.exit(1)\n")
        adb.chmod(0o755)
        result = subprocess.run(
            ["bash", str(REPO / "scripts/ci/bmgr-check.sh"), "31", str(self.root / "evidence")],
            env={**os.environ, "ADB": str(adb), "ADB_CALLS": str(calls),
                 "GITHUB_ACTIONS": "true", "BMGR_DISPOSABLE_API31": "1"},
            capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, result.returncode)
        observed = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
        destructive = [args for args in observed if args[:3] in (
            ["shell", "pm", "uninstall"], ["shell", "settings", "delete"],
            ["shell", "settings", "put"], ["shell", "bmgr", "init"],
            ["shell", "bmgr", "enable"], ["shell", "bmgr", "autorestore"])]
        self.assertEqual([], destructive)

    def test_rejects_a_missing_leg(self):
        self.build(("api31",))
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("api36:", result.stderr)

    def test_rejects_a_skipped_or_failed_method(self):
        def mutate(directory, run, exp, logs):
            logs["verify"] = bmgr_instrument_log("verify", fail_code=-3)
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("instrument evidence mismatch", result.stderr)

    def test_rejects_an_unexpected_method(self):
        def mutate(directory, run, exp, logs):
            logs["empty"] = logs["empty"].replace(
                "firstLaunchWithoutTransportDataStaysEmpty", "prepareLocalTransportBackup")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("instrument evidence mismatch", result.stderr)

    def test_rejects_a_missing_expectation_status(self):
        def mutate(directory, run, exp, logs):
            logs["prepare"] = bmgr_instrument_log("prepare")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("bmgrExpected", result.stderr)

    def test_rejects_a_mismatched_emitted_expectation(self):
        def mutate(directory, run, exp, logs):
            other = dict(exp, installationId="foreign")
            b64 = base64.urlsafe_b64encode(json.dumps(other).encode()).decode().rstrip("=")
            logs["prepare"] = bmgr_instrument_log("prepare", b64=b64)
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("bmgrExpected != expectation.json", result.stderr)

    def test_rejects_a_tampered_tar_payload(self):
        def mutate(directory, run, exp, logs):
            tampered = exp["package"]
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w") as tar:
                for name, data in (
                    (f"apps/{tampered}/_manifest", b"<manifest />"),
                    (f"apps/{tampered}/f/backup/auto-snapshot.zip", b"not the zip"),
                    (f"apps/{tampered}/f/datastore/settings.preferences_pb", b"portable-pb"),
                ):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    tar.addfile(info, io.BytesIO(data))
            data = buffer.getvalue()
            (directory / "transport.tar").write_bytes(data)
            run["tarSha256"] = _sha(data)
            (directory / "run.json").write_text(json.dumps(run))
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected hash/size", result.stderr)

    def test_rejects_an_extra_tar_member(self):
        def mutate(directory, run, exp, logs):
            pkg = exp["package"]
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w") as tar:
                with tarfile.open(directory / "transport.tar") as src:
                    for member in src:
                        tar.addfile(member, io.BytesIO(src.extractfile(member).read()))
                info = tarfile.TarInfo(f"apps/{pkg}/d/neutrodyne.db")
                info.size = 4
                tar.addfile(info, io.BytesIO(b"db!!"))
            (directory / "transport.tar").write_bytes(buffer.getvalue())
            run["tarSha256"] = _sha(buffer.getvalue())
            (directory / "run.json").write_text(json.dumps(run))
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("TAR members", result.stderr)

    def test_rejects_a_nonce_mismatch(self):
        def mutate(directory, run, exp, logs):
            run["nonce"] = "other-nonce-1"
            (directory / "run.json").write_text(json.dumps(run))
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("nonce/api mismatch", result.stderr)

    def test_rejects_an_unpassed_selector(self):
        def mutate(directory, run, exp, logs):
            run["selectors"]["unencryptedTransportExcluded"] = "fail"
            (directory / "run.json").write_text(json.dumps(run))
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unencryptedTransportExcluded", result.stderr)

    def test_rejects_divergent_apks_across_legs(self):
        def mutate(directory, run, exp, logs):
            run["apkSha256"] = "d" * 64
            (directory / "run.json").write_text(json.dumps(run))
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("apkSha256 differs", result.stderr)

    def test_rejects_restored_extra_files(self):
        def mutate(directory, run, exp, logs):
            with (directory / "restored-files.txt").open("a") as f:
                f.write(f"/data/user/0/{exp['package']}/files/downloads/bmgr-canary-x\n")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("extra files/", result.stderr)

    def test_rejects_a_missing_runner_code(self):
        def mutate(directory, run, exp, logs):
            logs["empty"] = logs["empty"].replace("INSTRUMENTATION_CODE: -1", "")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("successful runner completion", result.stderr)


SCRIPT_SH = REPO / "scripts/ci/bmgr-check.sh"
MUTATING_PREFIXES = (
    ("shell", "pm", "uninstall"), ("shell", "settings", "delete"),
    ("shell", "settings", "put"), ("shell", "bmgr", "init"),
    ("shell", "bmgr", "enable"), ("shell", "bmgr", "autorestore"),
    ("shell", "bmgr", "transport"), ("shell", "bmgr", "backupnow"),
    ("shell", "am", "start"), ("install",), ("install-incremental",),
)


class BmgrHostScriptTest(unittest.TestCase):
    """Fake-ADB conformance for scripts/ci/bmgr-check.sh — argv-level, offline."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.calls = self.root / "adb-calls.jsonl"
        self.adb = self.root / "adb"
        self.adb.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\n"
            "args = sys.argv[1:]\n"
            "calls = os.environ['ADB_CALLS']\n"
            "state = [json.loads(l) for l in open(calls)] if os.path.exists(calls) else []\n"
            "with open(calls, 'a') as out:\n"
            "    out.write(json.dumps(args) + '\\n')\n"
            "if os.environ.get('ADB_FAIL_ALL'):\n"
            "    sys.exit(1)\n"
            "def done(text='', rc=0):\n"
            "    sys.stdout.write(text); sys.exit(rc)\n"
            "if args == ['wait-for-device'] or args == ['root']:\n"
            "    done()\n"
            "if args[:2] == ['emu', 'avd']:\n"
            "    done(os.environ.get('FAKE_AVD_NAME', 'bmgr-api31') + '\\n')\n"
            "if args[:2] == ['shell', 'getprop']:\n"
            "    done({'sys.boot_completed': '1',\n"
            "          'ro.build.version.sdk': os.environ.get('FAKE_SDK', '31'),\n"
            "          'ro.product.cpu.abi': 'x86_64', 'ro.build.fingerprint': 'g/f/p',\n"
            "          'ro.kernel.qemu': '1'}.get(args[2], '') + '\\n')\n"
            "if args[:2] == ['shell', 'id']:\n"
            "    done('0\\n')\n"
            "if args[:2] == ['shell', 'dumpsys'] or args[:1] == ['logcat']:\n"
            "    done()\n"
            "if args[:3] == ['shell', 'settings', 'get']:\n"
            "    done({'backup_enabled': '0', 'backup_auto_restore': 'null',\n"
            "          'backup_local_transport_parameters': 'null'}.get(args[4], 'null') + '\\n')\n"
            "if args[:3] == ['shell', 'bmgr', 'enabled']:\n"
            "    done('Backup Manager currently disabled\\n')\n"
            "if args[:3] == ['shell', 'bmgr', 'list'] and args[3:] == ['transports']:\n"
            "    if any(a[:3] == ['shell', 'bmgr', 'transport'] for a in state):\n"
            "        done('    com.example.stub/.OtherTransport\\n'\n"
            "             '  * com.android.localtransport/.LocalTransport\\n')\n"
            "    done('  * com.example.stub/.OtherTransport\\n'\n"
            "         '    com.android.localtransport/.LocalTransport\\n')\n"
            "if args[:3] == ['shell', 'pm', 'path'] or args[:3] == ['shell', 'pm', 'list']:\n"
            "    done()\n"
            "if args[:3] == ['shell', 'bmgr', 'init']:\n"
            "    done(os.environ.get('FAKE_INIT', 'Initialization result: 0') + '\\n')\n"
            "done()\n")
        self.adb.chmod(0o755)
        # A scratch repo keeps the APK gate deterministic: no app/build/ exists there.
        script = self.root / "repo/scripts/ci/bmgr-check.sh"
        script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT_SH, script)
        subprocess.run(["git", "init", "-q"], cwd=script.parents[2], check=True)
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t",
                        "commit", "-q", "--allow-empty", "-m", "x"],
                       cwd=script.parents[2], check=True)
        self.script = script
        self.evidence = self.root / "evidence"

    def run_script(self, drop=(), **extra_env):
        env = {**os.environ, "ADB": str(self.adb), "ADB_CALLS": str(self.calls),
               "GITHUB_ACTIONS": "true", "BMGR_DISPOSABLE_API31": "1",
               "BMGR_EXPECT_AVD": "bmgr-api31"}
        for key in drop:
            env.pop(key, None)
        env.update(extra_env)
        result = subprocess.run(
            ["bash", str(self.script), "31", str(self.evidence)],
            env=env, capture_output=True, text=True, timeout=60)
        observed = ([json.loads(l) for l in self.calls.read_text().splitlines()]
                    if self.calls.exists() else [])
        return result, observed

    def mutating(self, observed):
        return [a for a in observed
                if any(tuple(a[:len(p)]) == p for p in MUTATING_PREFIXES)]

    def test_host_restores_only_owned_changes(self):
        result, observed = self.run_script()
        self.assertNotEqual(0, result.returncode)
        calls = [tuple(a) for a in observed]
        # The verified device was configured, then the run failed at the APK gate.
        for expected in (
                ("shell", "bmgr", "enable", "true"),
                ("shell", "bmgr", "autorestore", "true"),
                ("shell", "bmgr", "transport", "com.android.localtransport/.LocalTransport")):
            self.assertIn(expected, calls)
        # Cleanup reverted exactly those changes to their raw captured state.
        self.assertIn(("shell", "bmgr", "transport", "com.example.stub/.OtherTransport"), calls)
        self.assertIn(("shell", "settings", "put", "secure", "backup_enabled", "0"), calls)
        self.assertIn(("shell", "settings", "delete", "secure", "backup_auto_restore"), calls)
        # Nothing else was owned: no package uninstall, no params write, no init.
        self.assertNotIn(("shell", "settings", "delete", "secure",
                          "backup_local_transport_parameters"), calls)
        self.assertFalse(any(a[:3] == ["shell", "pm", "uninstall"] for a in observed))
        self.assertFalse(any(a[:3] == ["shell", "bmgr", "init"] for a in observed))
        # Bare getter forms that upstream maps to usage must never be invoked.
        self.assertNotIn(("shell", "bmgr", "autorestore"), calls)
        self.assertNotIn(("shell", "bmgr", "init"), calls)
        run = json.loads((self.evidence / "run.json").read_text())
        self.assertEqual("ok", run.get("cleanupStatus"))

    def test_host_refuses_an_unguarded_environment(self):
        result, observed = self.run_script(drop=("BMGR_DISPOSABLE_API31",))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.mutating(observed))

    def test_host_refuses_an_unexpected_avd(self):
        result, observed = self.run_script(FAKE_AVD_NAME="somebody-elses-avd")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.mutating(observed))

    def test_host_bmgr_command_conformance(self):
        driver = self.root / "drive.sh"
        driver.write_text(textwrap.dedent(f"""\
            set -uo pipefail
            export BMGR_CHECK_LIB_ONLY=1
            ADB={self.adb}
            EV={self.root / "lib-ev"}
            TRANSPORT=com.android.localtransport/.LocalTransport
            mkdir -p "$EV"
            source {self.script} 31 "$EV"
            printf '  * com.example.stub/.OtherTransport\\n    com.android.localtransport/.LocalTransport\\n' \\
                > "$EV/t.txt"
            [ "$(selected_transport "$EV/t.txt")" = "com.example.stub/.OtherTransport" ] \\
                || {{ echo "starred line not parsed"; exit 2; }}
            printf '    com.a/.B\\n    com.c/.D\\n' > "$EV/t2.txt"
            [ -z "$(selected_transport "$EV/t2.txt")" ] \\
                || {{ echo "unselected line parsed"; exit 2; }}
            bmgr_init ok || {{ echo "bmgr_init rejected Initialization result: 0"; exit 2; }}
            ( FAKE_INIT="usage: bmgr init TRANSPORT..." bmgr_init bad ) \\
                && {{ echo "bmgr_init accepted usage output"; exit 2; }}
            echo LIB-OK
        """))
        env = {**os.environ, "ADB_CALLS": str(self.calls)}
        result = subprocess.run(["bash", str(driver)], env=env,
                                capture_output=True, text=True, timeout=30)
        self.assertIn("LIB-OK", result.stdout, result.stderr + result.stdout)
        observed = ([json.loads(l) for l in self.calls.read_text().splitlines()]
                    if self.calls.exists() else [])
        inits = [a for a in observed if a[:3] == ["shell", "bmgr", "init"]]
        self.assertTrue(inits, "bmgr_init never ran")
        for a in inits:
            self.assertEqual(["shell", "bmgr", "init",
                              "com.android.localtransport/.LocalTransport"], a)


if __name__ == "__main__":
    unittest.main()
