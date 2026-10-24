#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require named, unskipped first-launch restore evidence.

Two modes:

- default: the GMD XML gate (`api26`/`api36` managed-device reports), unchanged.
- `--bmgr ROOT`: the M3 AC6 local-transport gate — strict verification of the
  `scripts/ci/bmgr-check.sh` evidence tree (one `apiNN/` directory per leg).
- `--bmgr-tar TAR EXPECTATION_JSON`: single-TAR payload verification used by
  the script itself, writing `TAR.members` next to the archive.
"""

import argparse
import base64
import hashlib
import io
import json
import re
import sys
import tarfile
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET


TEST_CLASS = "ch.lkmc.neutrodyne.FirstLaunchRestoreDeviceTest"
TEST_CASES = {"firstLaunchRestoresTheSeededSnapshot"}
DEVICES = ("api26", "api36")


def check_device(results, device):
    directory = results / device
    report = directory / f"TEST-{device}.xml"
    # Select each device's own report, never a cross-device aggregate or retry copy.
    if list(directory.rglob("*.xml")) != [report]:
        raise ValueError(f"{device}: expected only {report}")
    root = ET.parse(report).getroot()
    suites = root.findall("testsuite")
    if root.tag != "testsuites" or len(suites) != 1:
        raise ValueError(f"{device}: expected one filtered testsuite")
    suite = suites[0]
    if suite.get("name") != TEST_CLASS:
        raise ValueError(f"{device}: wrong testsuite class")
    devices = [p.get("value") for p in suite.findall("properties/property")
               if p.get("name") == "device"]
    if devices != [f"_app_{device}DebugAndroidTest"]:
        raise ValueError(f"{device}: missing or mismatched device property")
    cases = suite.findall("testcase")
    if (len(cases) != len(TEST_CASES)
            or {case.get("name") for case in cases} != TEST_CASES
            or any(case.get("classname") != TEST_CLASS for case in cases)
            or len(list(root.iter("testcase"))) != len(cases)):
        raise ValueError(f"{device}: missing, duplicate or unexpected test cases")
    if any(node.tag in {"skipped", "failure", "error"} for node in root.iter()):
        raise ValueError(f"{device}: skipped or failed test evidence")
    for node in (root, suite):
        for name, expected in (("tests", len(TEST_CASES)), ("skipped", 0),
                               ("failures", 0), ("errors", 0)):
            if int(node.get(name, "-1")) != expected:
                raise ValueError(f"{device}: inconsistent {name} count")
    print(f"{device}: PASS {TEST_CLASS}: {', '.join(sorted(TEST_CASES))}")


# ---- M3 AC6 bmgr local-transport gate ---------------------------------------

BMGR_DEVICES = {"api31": 31, "api36": 36}
BMGR_CLASS = "ch.lkmc.neutrodyne.BmgrTransportDeviceTest"
BMGR_METHODS = {
    "prepare": "prepareLocalTransportBackup",
    "verify": "firstLaunchRestoresTransportedSnapshot",
    "empty": "firstLaunchWithoutTransportDataStaysEmpty",
}
BMGR_LOGS = {phase: f"instrument-{phase}.log" for phase in BMGR_METHODS}
BMGR_SELECTORS = (
    "apkNonDebuggable", "apkSignatureStable", "testInstrumentationAvailable",
    "expectationCaptured", "unencryptedTransportExcluded", "positivePayloadVerified",
    "restoredFilesMatched", "emptyLegExcluded",
)
TAR_MANIFEST_SUFFIX = "/_manifest"
TAR_SNAPSHOT_REL = "f/backup/auto-snapshot.zip"
TAR_PORTABLE_REL = "f/datastore/settings.preferences_pb"
ENTRY_NAMES = {
    "manifest.json", "library.json", "episodes.jsonl",
    "queue.json", "settings.json", "subscriptions.opml",
}
REQUIRED_ENTRIES = ENTRY_NAMES - {"subscriptions.opml"}
NONCE = re.compile(r"^[A-Za-z0-9._-]{8,64}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def instrument_cases(text):
    """Ordered (class, test, code) triples from a raw `am instrument -r` stream."""
    cases, props = [], {}
    for line in text.splitlines():
        line = line.rstrip("\r").strip()
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            props[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = int(line.partition(":")[2].strip())
            if "class" in props and "test" in props:
                cases.append((props["class"], props["test"], code))
            props = {}
    return cases


def status_values(text, key):
    return re.findall(rf"^INSTRUMENTATION_STATUS: {re.escape(key)}=(.*)$",
                      text, re.M)


def check_instrument_log(device, phase, text):
    expected = [(BMGR_CLASS, BMGR_METHODS[phase], 1),
                (BMGR_CLASS, BMGR_METHODS[phase], 0)]
    cases = instrument_cases(text)
    if cases != expected:
        raise ValueError(
            f"{device}: {phase} instrument evidence mismatch "
            f"(want exactly {expected}, got {cases})")
    for bad in ("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
                "FAILURES", "shortMsg", "longMsg"):
        if bad in text:
            raise ValueError(f"{device}: {phase} log contains {bad!r}")
    if not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", text, re.M):
        raise ValueError(f"{device}: {phase} log lacks a successful runner completion")


def validate_snapshot_zip(device, data, exp):
    """The same contract `FirstLaunchRestoreChecks.assertReplacementSnapshot` enforces."""
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as error:
        raise ValueError(f"{device}: transported snapshot is not a ZIP") from error
    names = zf.namelist()
    if len(names) != len(set(names)):
        raise ValueError(f"{device}: snapshot ZIP has duplicate entries")
    if not set(names) <= ENTRY_NAMES:
        raise ValueError(f"{device}: snapshot ZIP carries unknown entries "
                         f"{sorted(set(names) - ENTRY_NAMES)}")
    if not REQUIRED_ENTRIES <= set(names):
        raise ValueError(f"{device}: snapshot ZIP misses {sorted(REQUIRED_ENTRIES - set(names))}")
    manifest_bytes = zf.read("manifest.json")
    if len(manifest_bytes) > 1024 * 1024:
        raise ValueError(f"{device}: manifest.json exceeds its cap")
    manifest = json.loads(manifest_bytes.decode("utf-8"))
    if manifest.get("format") != "neutrodyne-backup":
        raise ValueError(f"{device}: snapshot manifest format mismatch")
    if manifest.get("formatVersion", 0) < 1 or manifest.get("minReaderVersion", 99) > 1:
        raise ValueError(f"{device}: snapshot manifest version mismatch")
    if manifest.get("kind") != "AUTO_SNAPSHOT":
        raise ValueError(f"{device}: transported snapshot is not an AUTO_SNAPSHOT")
    if manifest.get("installationId") != exp["installationId"]:
        raise ValueError(f"{device}: snapshot installationId != expectation")
    declared = {e["name"]: e for e in manifest.get("entries", [])}
    if len(declared) != len(manifest.get("entries", [])) or not set(declared) <= set(names):
        raise ValueError(f"{device}: snapshot manifest declares a duplicate or missing entry")
    if not set(names) - {"manifest.json"} <= set(declared):
        raise ValueError(f"{device}: snapshot ZIP carries an undeclared payload")
    for name, entry in declared.items():
        payload = zf.read(name)
        if entry.get("bytes") != len(payload) or entry.get("sha256") != sha256_hex(payload):
            raise ValueError(f"{device}: snapshot entry {name} fails its declared hash/size")
    settings = json.loads(zf.read("settings.json").decode("utf-8"))
    values = {v.get("key"): v.get("value") for v in settings.get("values", [])}
    if values.get(exp["portableKey"]) != exp["portableInSnapshot"]:
        raise ValueError(f"{device}: snapshot settings must carry the pre-snapshot value")


def check_transport_tar(tar_path, exp, device="tar"):
    """Exactly `_manifest` plus the two allowed `f/` payloads, bytes and hashes checked."""
    pkg = exp.get("package")
    if not pkg or not re.fullmatch(r"[A-Za-z0-9_.]+", pkg):
        raise ValueError(f"{device}: expectation package name invalid")
    base = f"apps/{pkg}"
    allowed = {f"{base}{TAR_MANIFEST_SUFFIX}", f"{base}/{TAR_SNAPSHOT_REL}",
               f"{base}/{TAR_PORTABLE_REL}"}
    expected = {f"{base}/{TAR_SNAPSHOT_REL}": (exp["snapshotSha256"], exp["snapshotBytes"]),
                f"{base}/{TAR_PORTABLE_REL}": (exp["portableSha256"], exp["portableBytes"])}
    rows, payloads = [], {}
    with tarfile.open(tar_path, "r:*") as tar:
        for member in tar:
            name = member.name
            if not member.isfile():
                raise ValueError(f"{device}: non-regular TAR member {name}")
            if name != name.lstrip("./") or ".." in name.split("/"):
                raise ValueError(f"{device}: unsafe TAR member path {name}")
            if name in payloads:
                raise ValueError(f"{device}: duplicate TAR member {name}")
            if not name.startswith(f"{base}/"):
                raise ValueError(f"{device}: TAR member for another app {name}")
            data = tar.extractfile(member).read()
            payloads[name] = data
            rows.append(f"{name}\t{len(data)}\t{sha256_hex(data)}")
    if set(payloads) != allowed:
        raise ValueError(f"{device}: TAR members {sorted(payloads)} != {sorted(allowed)}")
    for name, (sha, size) in expected.items():
        data = payloads[name]
        if len(data) != size or sha256_hex(data) != sha:
            raise ValueError(f"{device}: TAR payload {name} fails its expected hash/size")
    validate_snapshot_zip(device, payloads[f"{base}/{TAR_SNAPSHOT_REL}"], exp)
    return rows


def run_bmgr_tar(tar, expectation_path):
    exp = json.loads(Path(expectation_path).read_text())
    rows = check_transport_tar(Path(tar), exp)
    Path(str(tar) + ".members").write_text("\n".join(rows) + "\n")
    print(f"bmgr-tar: PASS {len(rows)} members")


def check_bmgr_device(root, device, api):
    directory = root / device
    if not directory.is_dir():
        raise ValueError(f"{device}: evidence directory missing")
    run = json.loads((directory / "run.json").read_text())
    if run.get("schema") != "bmgr-evidence/v1" or run.get("api") != api:
        raise ValueError(f"{device}: run.json schema/api mismatch")
    if not run.get("fingerprint") or run.get("abi") != "x86_64":
        raise ValueError(f"{device}: emulator identity evidence missing")
    if not NONCE.fullmatch(run.get("nonce", "")):
        raise ValueError(f"{device}: run nonce invalid")
    exp = json.loads((directory / "expectation.json").read_text())
    if exp.get("nonce") != run["nonce"] or exp.get("api") != api:
        raise ValueError(f"{device}: expectation nonce/api mismatch")
    for field in ("snapshotSha256", "portableSha256", "installationId",
                  "receiverLine", "portableKey"):
        if not exp.get(field):
            raise ValueError(f"{device}: expectation misses {field}")
    if not SHA256.fullmatch(exp["snapshotSha256"]) or not SHA256.fullmatch(exp["portableSha256"]):
        raise ValueError(f"{device}: expectation hashes are not SHA-256")
    selectors = run.get("selectors")
    if not isinstance(selectors, dict) or any(selectors.get(n) != "pass" for n in BMGR_SELECTORS):
        missing = [n for n in BMGR_SELECTORS if (selectors or {}).get(n) != "pass"]
        raise ValueError(f"{device}: selectors not all 'pass' ({', '.join(missing)})")

    logs = {}
    for phase, name in BMGR_LOGS.items():
        path = directory / name
        if not path.is_file():
            raise ValueError(f"{device}: missing {name}")
        text = path.read_text(errors="replace")
        check_instrument_log(device, phase, text)
        logs[phase] = text
    expected_values = status_values(logs["prepare"], "bmgrExpected")
    if len(expected_values) != 1:
        raise ValueError(f"{device}: prepare must emit exactly one bmgrExpected status")
    decoded = json.loads(base64.urlsafe_b64decode(expected_values[0] + "===").decode("utf-8"))
    if decoded != exp:
        raise ValueError(f"{device}: emitted bmgrExpected != expectation.json")
    if status_values(logs["verify"], "bmgrExpected") or status_values(logs["empty"], "bmgrExpected"):
        raise ValueError(f"{device}: bmgrExpected may only appear in the prepare leg")

    backup = (directory / "backupnow-positive.log").read_text(errors="replace")
    for needle in (f"Package {exp['package']} with result: Success",
                   "Backup finished with result: Success"):
        if needle not in backup:
            raise ValueError(f"{device}: backupnow log misses {needle!r}")
    if "encoff" not in run.get("unencryptedDiagnosis", "") or not run.get("unencryptedDiagnosis"):
        raise ValueError(f"{device}: missing encryption-off control diagnosis")
    if not (directory / "backupnow-encoff.log").is_file():
        raise ValueError(f"{device}: missing backupnow-encoff.log")

    tar_path = directory / "transport.tar"
    if not tar_path.is_file():
        raise ValueError(f"{device}: missing transport.tar")
    if sha256_hex(tar_path.read_bytes()) != run.get("tarSha256"):
        raise ValueError(f"{device}: transport.tar hash does not match run.json")
    check_transport_tar(tar_path, exp, device)

    restored = (directory / "restored-files.txt").read_text(errors="replace")
    recorded = {rel: sha for sha, rel in
                re.findall(r"^([0-9a-f]{64})  (files/\S+)$", restored, re.M)}
    want = {"files/backup/auto-snapshot.zip": exp["snapshotSha256"],
            "files/datastore/settings.preferences_pb": exp["portableSha256"]}
    if recorded != want:
        raise ValueError(f"{device}: post-install restored files mismatch {recorded}")
    delivered = set(re.findall(r"^/\S+/(files/\S+)$", restored, re.M))
    if delivered - set(want):
        raise ValueError(f"{device}: extra files/ content restored: {sorted(delivered - set(want))}")
    for entry in exp.get("canaries", []):
        if entry.get("sha256") in recorded.values():
            raise ValueError(f"{device}: a recorded canary hash was restored")
    print(f"{device}: PASS bmgr local-transport lifecycle "
          f"(prepare/verify/empty, {len(want)} payloads)")


def check_bmgr(root):
    runs = {}
    for device, api in BMGR_DEVICES.items():
        check_bmgr_device(root, device, api)
        runs[device] = json.loads((root / device / "run.json").read_text())
    a, b = runs["api31"], runs["api36"]
    for field in ("sourceSha", "apkSha256", "testApkSha256"):
        if not a.get(field) or a[field] != b.get(field):
            raise ValueError(f"bmgr: {field} differs across legs")
    print("bmgr: PASS api31 + api36")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, nargs="?",
                        help="managedDevice/debug result directory")
    parser.add_argument("--bmgr", metavar="ROOT", type=Path,
                        help="strict M3 bmgr evidence root (api31/api36 dirs)")
    parser.add_argument("--bmgr-tar", nargs=2, metavar=("TAR", "EXPECTATION_JSON"),
                        help="verify one transport TAR and write TAR.members")
    args = parser.parse_args()
    try:
        if args.bmgr is not None:
            check_bmgr(args.bmgr)
        elif args.bmgr_tar is not None:
            run_bmgr_tar(*args.bmgr_tar)
        else:
            if args.results is None:
                parser.error("results is required without --bmgr/--bmgr-tar")
            for device in DEVICES:
                check_device(args.results, device)
    except (OSError, ValueError, ET.ParseError, json.JSONDecodeError,
            tarfile.TarError, KeyError, UnicodeDecodeError) as error:
        print(f"first-launch-restore: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
