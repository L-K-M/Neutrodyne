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
            target.parent.mkdir(parents=True, exist_ok=True)
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


# ---- v2 wire/blob builders -----------------------------------------------------

def _vint(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            break
    return bytes(out)


def _pb_field(no, wire, payload):
    head = _vint((no << 3) | wire)
    return head + (_vint(len(payload)) + payload if wire == 2 else payload)


def _pb_entry(key, value):
    """One PreferencesMap entry; value is (kind, pyval) with kind in s/i/b."""
    kind, pyval = value
    inner = (  # Value oneof: bool=1 int64=4 string=5
        _pb_field(5, 2, pyval.encode()) if kind == "s"
        else _pb_field(4, 0, _vint(pyval)) if kind == "i"
        else _pb_field(1, 0, _vint(1 if pyval else 0)))
    body = _pb_field(1, 2, key.encode()) + _pb_field(2, 2, inner)
    return _pb_field(1, 2, body)


def pb_blob(**entries):
    return b"".join(_pb_entry(k, v) for k, v in entries.items())


NONCE = "run-1700000000-42"
PKG = "ch.lkmc.neutrodyne"
DRV_PKG = "ch.lkmc.neutrodyne.tools.bmgrdriver"
DRV_RUNNER = "ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverRunner"
TAG = "bmgr-prelaunch"
SELECTOR_NAMES = (
    "apkNonDebuggable", "apkSignatureStable", "driverInstrumentationAvailable",
    "expectationCaptured", "unencryptedTransportExcluded", "positivePayloadVerified",
    "restoreAdoptionObserved", "restoredLibraryVerified", "emptyLegExcluded",
)


def bmgr_fixture():
    base = 1_700_000_000_000
    return {
        "podcasts": [
            {"key": "alpha.example.test/f.xml", "feedUrl": "https://alpha.example.test/f.xml",
             "title": "Alpha", "link": "https://alpha.example.test",
             "syncId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "subscribedAt": base},
            {"key": "beta.example.test/f.xml", "feedUrl": "https://beta.example.test/f.xml",
             "title": "Beta", "link": "https://beta.example.test",
             "syncId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "subscribedAt": base + 1000},
        ],
        "groups": [
            {"uuid": "11111111-1111-4111-8111-111111111111", "name": "Aviation",
             "sortOrder": 0, "members": ["alpha.example.test/f.xml"]},
            {"uuid": "22222222-2222-4222-8222-222222222222", "name": "Boredom",
             "sortOrder": 1, "members": ["alpha.example.test/f.xml", "beta.example.test/f.xml"]},
        ],
        "episodes": [
            {"p": "alpha.example.test/f.xml", "k": "epk-a1", "g": "g-a1", "t": "A1",
             "d": base - 400000, "u": "https://a/e1.mp3", "ty": "audio/mpeg",
             "dur": 3600000, "l": "https://a/e1", "pl": base - 86400000,
             "pc": 1, "lp": base - 86340000, "fav": True, "md": 3580000,
             "ts": base - 86340000},
            {"p": "alpha.example.test/f.xml", "k": "epk-a2", "g": "g-a2", "t": "A2",
             "d": base - 300000, "u": "https://a/e2.mp3", "ty": "audio/mpeg",
             "dur": 3600000, "l": "https://a/e2", "st": base - 20000,
             "lp": base - 10000, "pos": 750000, "posAt": base - 10000,
             "posSrc": "STREAM", "ts": base - 10000},
            {"p": "beta.example.test/f.xml", "k": "epk-b1", "g": "g-b1", "t": "B1",
             "d": base - 200000, "u": "https://b/e1.mp3", "ty": "audio/mpeg",
             "dur": 1800000, "l": "https://b/e1", "ts": 0},
            {"p": "beta.example.test/f.xml", "k": "epk-b2", "g": "g-b2", "t": "B2",
             "d": base - 100000, "u": "https://b/e2.mp3", "ty": "audio/mpeg",
             "dur": 1800000, "l": "https://b/e2", "pl": base - 5000,
             "pc": 2, "lp": base - 4000, "ts": base - 4000},
        ],
        "queue": [{"p": "beta.example.test/f.xml", "k": "epk-b1"}, {"p": "beta.example.test/f.xml", "k": "epk-b2"}],
        "session": {"current": {"p": "alpha.example.test/f.xml", "k": "epk-a2"},
                    "contextType": "GROUP",
                    "contextGroupUuid": "22222222-2222-4222-8222-222222222222",
                    "order": "NEWEST_FIRST"},
        "seedSettings": {"backup.opml_layout": "GROUPED",
                         "backup.auto_snapshot_enabled": "true"},
        "flipSettings": {"backup.opml_layout": "FLAT"},
    }


def bmgr_snapshot_zip(fixture, installation_id="install-abc", settings_value="GROUPED"):
    payloads = {
        "library.json": json.dumps(
            {"podcasts": fixture["podcasts"], "groups": fixture["groups"]}).encode(),
        "episodes.jsonl": b"",
        "queue.json": b'{"upNext":[]}',
        "settings.json": json.dumps(
            {"values": [{"key": "backup.opml_layout", "type": "Choice",
                         "value": settings_value}]}).encode(),
    }
    manifest = {
        "format": "neutrodyne-backup", "formatVersion": 1, "minReaderVersion": 1,
        "kind": "AUTO_SNAPSHOT", "installationId": installation_id,
        "entries": [{"name": n, "bytes": len(b), "sha256": _sha(b), "records": 1}
                    for n, b in payloads.items()],
    }
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("manifest.json", json.dumps(manifest))
        for name, data in payloads.items():
            zf.writestr(name, data)
    return buffer.getvalue()


def bmgr_tar(pkg, snapshot_zip, portable_pb):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as tar:
        for name, data in (
            (f"apps/{pkg}/_manifest", b"<manifest />"),
            (f"apps/{pkg}/f/backup/auto-snapshot.zip", snapshot_zip),
            (f"apps/{pkg}/f/datastore/settings.preferences_pb", portable_pb),
        ):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def bmgr_instrument_log(phase, nonce, fail_code=0, other=""):
    method = {"seed": "seedImport", "flip": "settingsFlip",
              "encrypted": "encryptedImportRejected"}[phase]
    cls = "ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverTest"
    body = (
        f"INSTRUMENTATION_STATUS: class={cls}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS: numtests=1\n"
        f"INSTRUMENTATION_STATUS_CODE: 1\n"
        f"INSTRUMENTATION_STATUS: bmgrDriver={phase}:{nonce}:ok\n"
        f"INSTRUMENTATION_STATUS_CODE: 0\n"
        f"INSTRUMENTATION_STATUS: class={cls}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS_CODE: {fail_code}\n"
        f"{other}"
        "INSTRUMENTATION_RESULT: stream=\nTest results ran to completion.\nOK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )
    return body


def driver_status(phase, nonce, api):
    steps = {
        "seed": ["built", "launched", "preview", "categories", "requested"],
        "flip": ["built", "launched", "preview", "categories", "requested"],
        "encrypted": ["built", "launched", "rejected"],
    }[phase]
    return {
        "v": 1, "phase": phase, "nonce": nonce, "api": api,
        "driverPackage": DRV_PKG, "targetPackage": PKG,
        "startedAt": 1_700_000_000_000,
        "steps": [{"name": s, "at": 1_700_000_000_000} for s in steps],
        "result": "ok",
    }


def _build_db(path, fixture, session_rows, group_uuids=None):
    """Shared schema + seeded library for the pulled source/restored DBs."""
    Path(path).unlink(missing_ok=True)
    import sqlite3
    uuids = group_uuids or ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE import_session(id INTEGER PRIMARY KEY, createdAt INT,
            finishedAt INT, sourceName TEXT, sourceFormat TEXT, state TEXT,
            recoveredBySalvage INT, payloadPath TEXT, optionsJson TEXT,
            warningsJson TEXT);
        CREATE TABLE podcast(id INTEGER PRIMARY KEY, syncId TEXT, feedUrl TEXT,
            feedKey TEXT, title TEXT, link TEXT);
        CREATE TABLE episode(id INTEGER PRIMARY KEY, podcastId INT,
            identityKey TEXT, title TEXT, guid TEXT);
        CREATE TABLE episode_state(episodeId INTEGER PRIMARY KEY, startedAt INT,
            playedAt INT, playCount INT, lastPlayedAt INT, isFavorite INT,
            measuredDurationMs INT);
        CREATE TABLE episode_position(episodeId INTEGER PRIMARY KEY,
            positionMs INT, positionSource TEXT);
        CREATE TABLE queue_entry(id INTEGER PRIMARY KEY, episodeId INT,
            orderKey TEXT, addedAt INT);
        CREATE TABLE play_session(id INTEGER PRIMARY KEY, currentEpisodeId INT,
            contextType TEXT, contextId INT);
        CREATE TABLE podcast_group(id INTEGER PRIMARY KEY, uuid TEXT, name TEXT);
        CREATE TABLE podcast_group_member(groupId INT, podcastId INT);
    """)
    key_to_id = {}
    for i, p in enumerate(fixture["podcasts"], start=1):
        con.execute("INSERT INTO podcast VALUES(?,?,?,?,?,?)",
                    (i, p["syncId"], p["feedUrl"], p["key"], p["title"], p["link"]))
        key_to_id[p["key"]] = i
    ep_to_id = {}
    pid = {p["key"]: i for i, p in enumerate(fixture["podcasts"], start=1)}
    for i, e in enumerate(fixture["episodes"], start=1):
        con.execute("INSERT INTO episode VALUES(?,?,?,?,?)",
                    (i, pid[e["p"]], e["k"], e["t"], e["g"]))
        ep_to_id[e["k"]] = i
        if any(e.get(f) is not None for f in ("st", "pl", "lp", "md")) or e.get("pc") or e.get("fav"):
            con.execute("INSERT INTO episode_state VALUES(?,?,?,?,?,?,?)",
                        (i, e.get("st"), e.get("pl"), e.get("pc", 0),
                         e.get("lp"), 1 if e.get("fav") else 0, e.get("md")))
        if e.get("pos") is not None:
            con.execute("INSERT INTO episode_position VALUES(?,?,?)",
                        (i, e["pos"], e.get("posSrc", "STREAM")))
    for i, ref in enumerate(fixture["queue"], start=1):
        con.execute("INSERT INTO queue_entry VALUES(?,?,?,?)",
                    (i, ep_to_id[ref["k"]], f"{i}", 0))
    gid = {}
    for i, g in enumerate(fixture["groups"]):
        con.execute("INSERT INTO podcast_group VALUES(?,?,?)",
                    (10 + i, uuids[i], g["name"]))
        gid[g["name"]] = 10 + i
        for member in g["members"]:
            con.execute("INSERT INTO podcast_group_member VALUES(?,?)",
                        (10 + i, key_to_id[member]))
    session = fixture["session"]
    current = ep_to_id[session["current"]["k"]] if session.get("current") else None
    context_id = gid["Boredom"] if session.get("contextType") == "GROUP" else None
    con.execute("INSERT INTO play_session VALUES(?,?,?,?)",
                (0, current, session.get("contextType"), context_id))
    con.executemany("INSERT INTO import_session VALUES(?,?,?,?,?,?,?,?,?,?)",
                    session_rows)
    con.commit()
    con.close()


def manual_session_rows():
    """The source-side ledger: seed + flip manual MERGE imports, ids 1 and 2."""
    return [
        (1, 100, 200, "seed.zip", "NEUTRODYNE_BACKUP", "DONE", 0, "import/1.bin",
         json.dumps({"restoreMode": "MERGE",
                     "restoreCategories": ["HISTORY", "UP_NEXT", "SETTINGS"],
                     "fromAndroidBackup": False}), None),
        (2, 300, 400, "flip.zip", "NEUTRODYNE_BACKUP", "DONE", 0, "import/2.bin",
         json.dumps({"restoreMode": "MERGE", "restoreCategories": ["SETTINGS"],
                     "fromAndroidBackup": False}), None),
    ]


def adopted_session_row(adopted_id):
    return (adopted_id, 500, 600, "auto-snapshot.zip", "NEUTRODYNE_BACKUP", "DONE",
            0, f"import/{adopted_id}.bin",
            json.dumps({"restoreMode": "MERGE",
                        "restoreCategories": ["HISTORY", "UP_NEXT"],
                        "fromAndroidBackup": True}), None)


def build_source_db(path, fixture, group_uuids=None):
    """The pre-uninstall pull: seeded library + the two manual import rows."""
    _build_db(path, fixture, manual_session_rows(), group_uuids)


def build_restored_db(path, fixture, adopted_id=1, group_uuids=None):
    """The pulled post-restore DB — exactly one row, the adopted restore."""
    _build_db(path, fixture, [adopted_session_row(adopted_id)], group_uuids)


def build_empty_db(path):
    Path(path).unlink(missing_ok=True)
    import sqlite3
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE podcast(id INTEGER PRIMARY KEY);
        CREATE TABLE episode(id INTEGER PRIMARY KEY);
        CREATE TABLE podcast_group(id INTEGER PRIMARY KEY);
        CREATE TABLE queue_entry(id INTEGER PRIMARY KEY);
        CREATE TABLE import_session(id INTEGER PRIMARY KEY);
        CREATE TABLE episode_state(episodeId INTEGER PRIMARY KEY);
    """)
    con.commit()
    con.close()


def build_workdb(path, adopted_id):
    Path(path).unlink(missing_ok=True)
    import sqlite3
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE workspec(id TEXT PRIMARY KEY, state INT);
        CREATE TABLE worktag(tag TEXT, work_spec_id TEXT);
    """)
    con.execute("INSERT INTO workspec VALUES(?,2)", (f"w-{adopted_id}",))
    con.execute("INSERT INTO worktag VALUES(?,?)",
                (f"restore-session-{adopted_id}", f"w-{adopted_id}"))
    con.commit()
    con.close()


def write_pulls(directory, exp, fixture, snapshot_zip, portable_pb, device_pb):
    """The full pulls/ tree: pre payload pair, source ledger, post capture, empty leg."""
    pre = directory / "pulls/pre"
    post = directory / "pulls/post"
    empty = directory / "pulls/empty"
    source = directory / "pulls/source"
    for rel, blob in (
        ("files/backup/auto-snapshot.zip", snapshot_zip),
        ("files/datastore/settings.preferences_pb", portable_pb),
        ("files/datastore/device_settings.preferences_pb", device_pb),
    ):
        target = pre / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(blob)
    (pre / "prebackup-files.txt").write_text("\n".join(
        f"/data/data/{PKG}/{rel}" for rel in (
            exp["canaries"]
            + ["files/backup/auto-snapshot.zip",
               "files/datastore/settings.preferences_pb",
               "files/datastore/device_settings.preferences_pb"])) + "\n")
    (post / "files/backup").mkdir(parents=True, exist_ok=True)
    (post / "files/backup/restored-1700000001234.zip").write_bytes(snapshot_zip)
    (post / "files/datastore").mkdir(parents=True, exist_ok=True)
    (post / "files/datastore/settings.preferences_pb").write_bytes(portable_pb)
    (post / "files/datastore/device_settings.preferences_pb").write_bytes(
        pb_blob(**{"backup.installation_id": ("s", "install-new")}))
    (post / "cache/import").mkdir(parents=True, exist_ok=True)
    (post / "cache/import/1.bin").write_bytes(snapshot_zip)
    (post / "databases").mkdir(parents=True, exist_ok=True)
    build_restored_db(post / "databases/neutrodyne.db", fixture)
    build_workdb(post / "databases/androidx.work.workdb", 1)
    (source / "databases").mkdir(parents=True, exist_ok=True)
    build_source_db(source / "databases/neutrodyne.db", fixture)
    (post / "notifications.txt").write_text("")
    (empty / "databases").mkdir(parents=True, exist_ok=True)
    build_empty_db(empty / "databases/neutrodyne.db")
    (empty / "files").mkdir(parents=True, exist_ok=True)
    (empty / "files/.listing").write_text("")
    (directory / "postlaunch-files.txt").write_text(
        f"/data/data/{PKG}/files/backup/restored-1700000001234.zip\n")


class BmgrEvidenceTest(unittest.TestCase):
    """`--bmgr` strict-mode v2 fixtures — built entirely with the stdlib, offline."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def build_leg(self, device, api, mutate=None):
        directory = self.root / device
        directory.mkdir(parents=True, exist_ok=True)
        fixture = bmgr_fixture()
        snapshot_zip = bmgr_snapshot_zip(fixture)
        snap_at, snap_level = 1_700_000_001_234, 2
        device_pb = pb_blob(**{
            "backup.installation_id": ("s", "install-abc"),
            "backup.last_snapshot_at": ("i", snap_at),
            "backup.last_snapshot_bytes": ("i", len(snapshot_zip)),
            "backup.last_snapshot_level": ("i", snap_level)})
        portable_pb = pb_blob(**{
            "backup.opml_layout": ("s", "FLAT"),
            "backup.auto_snapshot_enabled": ("b", True)})
        exp = {
            "v": 2, "nonce": NONCE, "package": PKG, "api": api,
            "installationId": "install-abc",
            "snapshotSha256": _sha(snapshot_zip), "snapshotBytes": len(snapshot_zip),
            "portableSha256": _sha(portable_pb), "portableBytes": len(portable_pb),
            "portableKey": "backup.opml_layout",
            "portableInSnapshot": "GROUPED", "portableAfter": "FLAT",
            "seedSha256": "e" * 64, "seedBytes": 7,
            "syncIds": {p["key"]: p["syncId"] for p in fixture["podcasts"]},
            "groupUuids": {g["name"]: g["uuid"] for g in fixture["groups"]},
            "fixture": fixture,
            "canaries": ["databases/bmgr-canary-%s.db" % NONCE],
            "receiverLine": f"ok at={snap_at} bytes={len(snapshot_zip)} level={snap_level}",
            "preparedAt": 1,
        }
        tar = bmgr_tar(PKG, snapshot_zip, portable_pb)
        refusal = "Transport rejected package because it wasn't able to process it at the time"
        run = {
            "schema": "bmgr-evidence/v2", "device": device, "api": api,
            "fingerprint": "google/sdk/fp", "abi": "x86_64", "nonce": NONCE,
            "sourceSha": "abc", "apkSha256": "a" * 64, "apkBytes": 10,
            "driverApkSha256": "b" * 64, "driverPackage": DRV_PKG,
            "driverRunner": DRV_RUNNER, "certSha256": "c" * 64,
            "transport": "com.android.localtransport/.LocalTransport",
            "tarSha256": _sha(tar), "unencryptedDiagnosis": "encoff:" + refusal,
            "unencryptedBackupnowExit": 0, "positiveBackupnowExit": 0,
            "cleanupStatus": "ok", "avd": f"bmgr-api{api}",
            "prelaunchAgentObserved": True,
            "tarDevicePath": f"/data/data/com.android.localtransport/files/1/_full/{PKG}",
            "selectors": {name: "pass" for name in SELECTOR_NAMES},
        }
        sidecar = {"v": 1, "nonce": NONCE, "api": api, "seedSha256": exp["seedSha256"],
                   "seedBytes": 7, "fixture": fixture}
        # The pre-cleanup inputs the emitter turns into run.json at leg end.
        (directory / "facts.txt").write_text(
            f"device={device}\napi={api}\nnonce={NONCE}\n"
            + "".join(f"selector.{name}=pass\n" for name in SELECTOR_NAMES))
        (directory / "selectors.txt").write_text(
            "".join(f"{name}=pass\n" for name in SELECTOR_NAMES))
        (directory / "run.json").write_text(json.dumps(run))
        (directory / "expectation.json").write_text(json.dumps(exp))
        (directory / "seed.expected.json").write_text(json.dumps(sidecar))
        (directory / "transport.tar").write_bytes(tar)
        (directory / "instrumentations.txt").write_text(
            f"instrumentation:{DRV_PKG}/{DRV_RUNNER} (target={DRV_PKG})\n")
        data = f"/data/data/{PKG}"
        (directory / "prelaunch-files.txt").write_text("\n".join([
            f"d {data}/cache", f"d {data}/databases",
            f"d {data}/files", f"d {data}/files/backup",
            f"f {data}/files/backup/auto-snapshot.zip",
            f"d {data}/files/datastore",
            f"f {data}/files/datastore/settings.preferences_pb",
            f"d {data}/no_backup", f"d {data}/shared_prefs",
            f"l {data}/lib"]) + "\n")
        (directory / "prelaunch-ps.txt").write_text(
            "u0_a123  4455 1 0 0 ch.lkmc.neutrodyne\n")
        # Markers delimit the window; only the restricted agent may start inside.
        (directory / "prelaunch-logcat.txt").write_text("\n".join([
            "10-21 15:53:40.0  I ActivityManager: unrelated line",
            f"10-21 15:53:44.9  I {TAG}: begin {NONCE} {PKG}",
            "10-21 15:53:45.0  V BackupManagerService: [UserID:0]"
            f" restoreAtInstall pkg={PKG} token=3 restoreSet=1",
            "10-21 15:53:45.1  I ActivityManager: Start proc 4455:"
            f"{PKG}/u0a123 for backup {{android/FullBackupAgent}}",
            "10-21 15:53:45.2  I ActivityManager: Process "
            f"{PKG} (pid 4455) has died: bkup TRNB",
            "10-21 15:53:45.3  I BackupManagerService: Restore complete.",
            f"10-21 15:53:45.4  I {TAG}: end {NONCE} {PKG}",
            f"10-21 15:53:46.0  I ActivityTaskManager: START u0 "
            f"{{flg=0x10000000 cmp={PKG}/.MainActivity}} from uid 0"]) + "\n")
        write_pulls(directory, exp, fixture, snapshot_zip, portable_pb, device_pb)
        for label in ("encoff-pre", "encoff", "positive", "empty"):
            (directory / f"init-{label}.txt").write_text(
                "Initializing transports: [com.android.localtransport/.LocalTransport]\n"
                "Initialization result: 0\n")
        (directory / "encoff-payload.txt").write_text("")
        (directory / "backupnow-positive.log").write_text(
            f"Package {PKG} with result: Success\n"
            "Backup finished with result: Success\n")
        (directory / "backupnow-encoff.log").write_text(
            "Running incremental backup for 1 requested packages.\n"
            "Package @pm@ with result: Success\n"
            f"=> Event{{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = {PKG}(v1)}}\n"
            f"Package {PKG} with result: {refusal}\n"
            "Backup finished with result: Success\n")
        logs = {
            "seed": bmgr_instrument_log("seed", NONCE),
            "flip": bmgr_instrument_log("flip", NONCE),
            "encrypted": bmgr_instrument_log("encrypted", NONCE),
        }
        statuses = {p: driver_status(p, NONCE, api) for p in logs}
        if mutate:
            mutate(directory, run, exp, logs, statuses)
        for phase, text in logs.items():
            (directory / f"instrument-{phase}.log").write_text(text)
            (directory / f"driver-status-{phase}.json").write_text(
                json.dumps(statuses[phase]))
        # mutate may have touched run.json — re-write it last
        (directory / "run.json").write_text(json.dumps(run))
        return directory

    def build(self, devices=("api31", "api36"), mutate=None):
        apis = {"api31": 31, "api36": 36}
        for device in devices:
            self.build_leg(device, apis[device], mutate.get(device) if mutate else None)

    def check(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--bmgr", str(self.root)],
            capture_output=True, text=True, timeout=60)

    def test_accepts_a_complete_two_leg_run(self):
        self.build()
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("bmgr: PASS api31 + api36", result.stdout)

    def test_rejects_undiagnosed_encryption_off_controls(self):
        refusal = "Transport rejected package because it wasn't able to process it at the time"
        valid = ("Running incremental backup for 1 requested packages.\n"
                 "Package @pm@ with result: Success\n"
                 f"=> Event{{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = {PKG}(v1)}}\n"
                 f"Package {PKG} with result: {refusal}\n"
                 "Backup finished with result: Success\n")
        invalid = (
            ("empty", "", "encoff:"),
            ("transport error", valid.replace(refusal, "Transport error"), "encoff:Transport error"),
            ("agent error", valid.replace(refusal, "Agent error"), "encoff:Agent error"),
            ("wrong package", valid.replace(PKG, "ch.lkmc.other"), "encoff:" + refusal),
            ("contradictory finish", valid + "Backup finished with result: Error\n", "encoff:" + refusal),
            ("contradictory event", valid + f"=> Event{{BACKUP_MANAGER_POLICY / SUCCESS : package = {PKG}(v1)}}\n", "encoff:" + refusal),
            ("successful backup", valid.replace(refusal, "Success"), "encoff:Success"),
            ("metadata error", valid.replace("Package @pm@ with result: Success", "Package @pm@ with result: Error"), "encoff:" + refusal),
            ("mismatched diagnosis", valid, "encoff:Backup is not allowed"),
        )
        for label, text, diagnosis in invalid:
            with self.subTest(control=label):
                def mutate(directory, run, exp, logs, statuses):
                    run["unencryptedDiagnosis"] = diagnosis
                    (directory / "backupnow-encoff.log").write_text(text)
                self.build(mutate={"api31": mutate})
                result = self.check()
                self.assertNotEqual(0, result.returncode, result.stdout)

    def test_rejects_a_missing_leg(self):
        self.build(("api31",))
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("api36:", result.stderr)

    def test_rejects_a_skipped_or_failed_method(self):
        def mutate(directory, run, exp, logs, statuses):
            logs["flip"] = bmgr_instrument_log("flip", NONCE, fail_code=-3)
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("instrument evidence mismatch", result.stderr)

    def test_rejects_an_unknown_phase_marker(self):
        def mutate(directory, run, exp, logs, statuses):
            logs["seed"] = logs["seed"].replace("seed:", "seeded:")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("marker evidence", result.stderr)

    def test_rejects_a_nonce_mismatch(self):
        def mutate(directory, run, exp, logs, statuses):
            run["nonce"] = "other-nonce-1"
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("nonce/api/package mismatch", result.stderr)

    def test_rejects_a_tampered_driver_status(self):
        def mutate(directory, run, exp, logs, statuses):
            statuses["seed"]["driverPackage"] = "evil.driver"
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("status identity/result mismatch", result.stderr)

    def test_rejects_a_targeting_instrumentation(self):
        def mutate(directory, run, exp, logs, statuses):
            (directory / "instrumentations.txt").write_text(
                f"instrumentation:{DRV_PKG}/{DRV_RUNNER} (target={PKG})\n")
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("self-targeting", result.stderr)

    def test_rejects_a_tampered_tar_payload(self):
        def mutate(directory, run, exp, logs, statuses):
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
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected hash/size", result.stderr)

    def test_rejects_an_extra_tar_member(self):
        def mutate(directory, run, exp, logs, statuses):
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
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("TAR members", result.stderr)

    def test_rejects_an_unpassed_selector(self):
        def mutate(directory, run, exp, logs, statuses):
            run["selectors"]["unencryptedTransportExcluded"] = "fail"
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unencryptedTransportExcluded", result.stderr)

    def test_rejects_divergent_apks_across_legs(self):
        def mutate(directory, run, exp, logs, statuses):
            run["apkSha256"] = "d" * 64
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("apkSha256 differs", result.stderr)

    def test_rejects_a_tampered_sidecar(self):
        def mutate(directory, run, exp, logs, statuses):
            sidecar = json.loads((directory / "seed.expected.json").read_text())
            sidecar["fixture"]["podcasts"][0]["syncId"] = "forged"
            (directory / "seed.expected.json").write_text(json.dumps(sidecar))
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("sidecar", result.stderr)

    def test_rejects_a_missing_pull(self):
        def mutate(directory, run, exp, logs, statuses):
            (directory / "pulls/post/databases/neutrodyne.db").unlink()
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing pulled evidence", result.stderr)

    def test_rejects_a_wrong_restored_state(self):
        def mutate(directory, run, exp, logs, statuses):
            import sqlite3
            db = directory / "pulls/post/databases/neutrodyne.db"
            con = sqlite3.connect(db)
            con.execute("UPDATE episode_state SET playCount=99 WHERE episodeId=1")
            con.commit(); con.close()
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("state", result.stderr)

    def test_rejects_a_canary_leak(self):
        def mutate(directory, run, exp, logs, statuses):
            with (directory / "postlaunch-files.txt").open("a") as out:
                out.write(f"/data/data/{PKG}/files/x/bmgr-canary-{NONCE}.dat\n")
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("canary", result.stderr)

    def _rejects_prelaunch(self, mutate, needle, device="api31"):
        self.build(mutate={device: mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(needle, result.stderr)

    def test_rejects_prelaunch_path_violations(self):
        """DB file, framework-dir descendant, or special entry -> fail."""
        for entry, needle in (
                (f"f /data/data/{PKG}/databases/neutrodyne.db", "payload pair"),
                (f"d /data/data/{PKG}/databases/wal", "unexpected dirs/links"),
                (f"? /data/data/{PKG}/sock", "malformed")):
            with self.subTest(entry=entry):
                def mutate(directory, run, exp, logs, statuses):
                    with (directory / "prelaunch-files.txt").open("a") as out:
                        out.write(entry + "\n")
                self._rejects_prelaunch(mutate, needle)

    @staticmethod
    def _inject_window_line(directory, line):
        log = directory / "prelaunch-logcat.txt"
        lines = log.read_text().splitlines()
        end = next(i for i, l in enumerate(lines) if f"{TAG}: end" in l)
        lines.insert(end, line)
        log.write_text("\n".join(lines) + "\n")

    def test_rejects_prelaunch_normal_process_starts(self):
        """A top-activity or child-process bind inside the window fails."""
        for proc in (f"{PKG}/u0a123 for top-activity {{{PKG}/.MainActivity}}",
                     f"{PKG}:restore/u0a123 for added application {PKG}"):
            with self.subTest(proc=proc):
                def mutate(directory, run, exp, logs, statuses):
                    self._inject_window_line(directory,
                        "10-21 15:53:45.3  I ActivityManager:"
                        f" Start proc 4460:{proc}")
                self._rejects_prelaunch(mutate, "normal process start")

    def test_rejects_prelaunch_window_marker_gaps(self):
        """Missing markers or a foreign nonce fail the window coverage."""
        def no_markers(directory, run, exp, logs, statuses):
            (directory / "prelaunch-logcat.txt").write_text(
                "10-21 15:53:45.0  I BackupManagerService: Restore complete.\n")
        def wrong_nonce(directory, run, exp, logs, statuses):
            log = directory / "prelaunch-logcat.txt"
            log.write_text(log.read_text().replace(
                f"begin {NONCE}", "begin run-9999999999-99999"))
        self._rejects_prelaunch(no_markers, "window markers missing")
        self._rejects_prelaunch(wrong_nonce, "marker nonce")

    def test_rejects_prelaunch_without_agent_provenance(self):
        """Payloads present but no observed default-agent start -> fail."""
        def mutate(directory, run, exp, logs, statuses):
            log = directory / "prelaunch-logcat.txt"
            log.write_text(log.read_text().replace(
                "10-21 15:53:45.1  I ActivityManager: Start proc 4455:"
                f"{PKG}/u0a123 for backup {{android/FullBackupAgent}}\n", ""))
            (directory / "prelaunch-ps.txt").write_text("")
        self._rejects_prelaunch(mutate, "restore provenance")

    def test_rejects_a_live_pid_without_backup_origin(self):
        def mutate(directory, run, exp, logs, statuses):
            (directory / "prelaunch-ps.txt").write_text(
                f"u0_a123  9999 1 0 0 {PKG}\n")
        self._rejects_prelaunch(mutate, "without backup provenance")

    def test_rejects_an_unretired_snapshot(self):
        def mutate(directory, run, exp, logs, statuses):
            retired = directory / "pulls/post/files/backup/restored-1700000001234.zip"
            retired.unlink()
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("retired nor replaced", result.stderr)

    def test_rejects_a_missing_runner_code(self):
        def mutate(directory, run, exp, logs, statuses):
            logs["flip"] = logs["flip"].replace("INSTRUMENTATION_CODE: -1", "")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("successful runner completion", result.stderr)

    # -- F1: --bmgr-pull runs before cleanup writes run.json --------------------

    def pull_check(self, directory):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--bmgr-pull", str(directory)],
            capture_output=True, text=True, timeout=60)

    def test_pull_gate_accepts_a_precleanup_leg(self):
        """facts.txt/selectors.txt but no run.json — the real pre-cleanup shape."""
        directory = self.build_leg("api31", 31)
        (directory / "run.json").unlink()
        result = self.pull_check(directory)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("bmgr-pull: PASS", result.stdout)

    def test_pull_gate_rejects_a_foreign_facts_nonce(self):
        directory = self.build_leg("api31", 31)
        (directory / "run.json").unlink()
        path = directory / "facts.txt"
        path.write_text(path.read_text().replace(NONCE, "run-9999999999-42"))
        result = self.pull_check(directory)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("nonce", result.stderr)

    # -- F3: the post-restore ledger holds exactly one adopted row --------------

    def test_rejects_a_forged_extra_session_row(self):
        def mutate(directory, run, exp, logs, statuses):
            import sqlite3
            con = sqlite3.connect(
                directory / "pulls/post/databases/neutrodyne.db")
            con.execute("INSERT INTO import_session VALUES(?,?,?,?,?,?,?,?,?,?)",
                        (9, 1, 2, "forged.zip", "NEUTRODYNE_BACKUP", "DONE", 0,
                         "import/9.bin",
                         json.dumps({"restoreMode": "MERGE",
                                     "restoreCategories": ["SETTINGS"],
                                     "fromAndroidBackup": False}), None))
            con.commit()
            con.close()
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("import_session", result.stderr)

    def test_rejects_a_missing_source_ledger(self):
        def mutate(directory, run, exp, logs, statuses):
            (directory / "pulls/source/databases/neutrodyne.db").unlink()
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("source", result.stderr)

    def test_rejects_a_malformed_source_session(self):
        def mutate(directory, run, exp, logs, statuses):
            import sqlite3
            con = sqlite3.connect(
                directory / "pulls/source/databases/neutrodyne.db")
            con.execute("UPDATE import_session SET state='PENDING' WHERE id=2")
            con.commit()
            con.close()
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("manual session", result.stderr)

    # -- F4: backup.auto_snapshot_enabled is a portable key ----------------------

    @staticmethod
    def rebind_pbs(directory, run, exp, portable_pb, device_pb):
        """Re-seal mutated PBs: pulled files, TAR, expectation and run hashes."""
        (directory / "pulls/pre/files/datastore/settings.preferences_pb") \
            .write_bytes(portable_pb)
        (directory / "pulls/pre/files/datastore/device_settings.preferences_pb") \
            .write_bytes(device_pb)
        (directory / "pulls/post/files/datastore/settings.preferences_pb") \
            .write_bytes(portable_pb)
        snapshot_zip = (
            directory / "pulls/pre/files/backup/auto-snapshot.zip").read_bytes()
        tar = bmgr_tar(exp["package"], snapshot_zip, portable_pb)
        (directory / "transport.tar").write_bytes(tar)
        run["tarSha256"] = _sha(tar)
        exp["portableSha256"] = _sha(portable_pb)
        exp["portableBytes"] = len(portable_pb)
        (directory / "expectation.json").write_text(json.dumps(exp))

    def test_rejects_a_missing_portable_flag(self):
        def mutate(directory, run, exp, logs, statuses):
            match = re.match(r"ok at=(\d+) bytes=(\d+) level=(\d+)",
                             exp["receiverLine"])
            device_pb = pb_blob(**{
                "backup.installation_id": ("s", exp["installationId"]),
                "backup.last_snapshot_at": ("i", int(match[1])),
                "backup.last_snapshot_bytes": ("i", int(match[2])),
                "backup.last_snapshot_level": ("i", int(match[3]))})
            self.rebind_pbs(directory, run, exp,
                            pb_blob(**{"backup.opml_layout": ("s", "FLAT")}),
                            device_pb)
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("auto-snapshot flag", result.stderr)

    def test_rejects_the_flag_in_the_device_pb(self):
        def mutate(directory, run, exp, logs, statuses):
            match = re.match(r"ok at=(\d+) bytes=(\d+) level=(\d+)",
                             exp["receiverLine"])
            device_pb = pb_blob(**{
                "backup.installation_id": ("s", exp["installationId"]),
                "backup.last_snapshot_at": ("i", int(match[1])),
                "backup.last_snapshot_bytes": ("i", int(match[2])),
                "backup.last_snapshot_level": ("i", int(match[3])),
                "backup.auto_snapshot_enabled": ("b", True)})
            self.rebind_pbs(directory, run, exp,
                            pb_blob(**{"backup.opml_layout": ("s", "FLAT")}),
                            device_pb)
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("portable keys", result.stderr)

    # -- F5: a legitimate replacement belongs to the NEW installation ----------

    def test_accepts_a_new_replacement_snapshot(self):
        def mutate(directory, run, exp, logs, statuses):
            # retired AND a fresh replacement may coexist post-restore.
            (directory / "pulls/post/files/backup/auto-snapshot.zip").write_bytes(
                bmgr_snapshot_zip(bmgr_fixture(), installation_id="install-new",
                                  settings_value="FLAT"))
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_the_transported_zip_as_replacement(self):
        def mutate(directory, run, exp, logs, statuses):
            backup_dir = directory / "pulls/post/files/backup"
            (backup_dir / "restored-1700000001234.zip").unlink()
            (backup_dir / "auto-snapshot.zip").write_bytes(
                (directory / "pulls/pre/files/backup/auto-snapshot.zip")
                .read_bytes())
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("installationId", result.stderr)

    def test_rejects_a_source_identity_replacement(self):
        def mutate(directory, run, exp, logs, statuses):
            backup_dir = directory / "pulls/post/files/backup"
            (backup_dir / "restored-1700000001234.zip").unlink()
            (backup_dir / "auto-snapshot.zip").write_bytes(
                bmgr_snapshot_zip(bmgr_fixture(), installation_id="install-abc",
                                  settings_value="FLAT"))
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("installationId", result.stderr)


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
            "def cached(command, default):\n"
            "    for call in reversed(state):\n"
            "        if call[:3] == ['shell', 'bmgr', command] and len(call) == 4:\n"
            "            return call[3] == 'true'\n"
            "    return default\n"
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
            "if args[:2] == ['shell', 'dumpsys']:\n"
            "    enabled = 'enabled' if cached('enable', False) else 'disabled'\n"
            "    auto = cached('autorestore', os.environ.get('FAKE_AUTO_STATE', 'enabled') == 'enabled')\n"
            "    done('Backup Manager is ' + enabled + ' / provisioned / not pending init\\n'\n"
            "         + 'Auto-restore is ' + ('enabled' if auto else 'disabled') + '\\n')\n"
            "if args[:1] == ['logcat']:\n"
            "    done()\n"
            "if args[:3] == ['shell', 'settings', 'get']:\n"
            "    if os.environ.get('FAKE_SETTINGS_FAILURE') == args[4]:\n"
            "        done('', 1)\n"
            "    done({'backup_enabled': '0', 'backup_auto_restore': os.environ.get('FAKE_AUTO_RAW', 'null'),\n"
            "          'backup_local_transport_parameters': 'null'}.get(args[4], 'null') + '\\n')\n"
            "if args[:3] == ['shell', 'bmgr', 'enabled']:\n"
            "    done('Backup Manager currently ' + ('enabled' if cached('enable', False) else 'disabled') + '\\n')\n"
            "if args[:3] == ['shell', 'bmgr', 'list'] and args[3:] == ['transports']:\n"
            "    prior = sum(1 for a in state\n"
            "                if a[:4] == ['shell', 'bmgr', 'list', 'transports'])\n"
            "    if prior < int(os.environ.get('FAKE_TRANSPORT_DELAY', '0')):\n"
            "        done('No transports available\\n')\n"
            "    if any(a[:3] == ['shell', 'bmgr', 'transport'] for a in state):\n"
            "        done('    com.example.stub/.OtherTransport\\n'\n"
            "             '  * com.android.localtransport/.LocalTransport\\n')\n"
            "    done('  * com.example.stub/.OtherTransport\\n'\n"
            "         '    com.android.localtransport/.LocalTransport\\n')\n"
            "if args[:3] == ['shell', 'pm', 'path'] or args[:3] == ['shell', 'pm', 'list']:\n"
            "    done()\n"
            "if args[:3] == ['shell', 'bmgr', 'init']:\n"
            "    done(os.environ.get('FAKE_INIT', 'Initialization result: 0') + '\\n')\n"
            "if args[:3] == ['shell', 'am', 'instrument']:\n"
            "    pairs = {args[i+1]: args[i+2] for i, a in enumerate(args) if a == '-e'}\n"
            "    result = os.environ.get('FAKE_DRIVER_RESULT', 'ok')\n"
            "    done(f\"INSTRUMENTATION_STATUS: bmgrDriver={pairs['bmgrPhase']}:{pairs['bmgrNonce']}:{result}\\n\"\n"
            "         'INSTRUMENTATION_CODE: -1\\n')\n"
            "if args[:2] == ['exec-out', 'cat']:\n"
            "    done('{\\\"v\\\":1,\\\"result\\\":\\\"ok\\\"}\\n')\n"
            "done()\n")
        self.adb.chmod(0o755)
        # A scratch repo keeps the APK gate deterministic: no app/build/ exists there.
        script = self.root / "repo/scripts/ci/bmgr-check.sh"
        script.parent.mkdir(parents=True, exist_ok=True)
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
        self.assertIn(("shell", "bmgr", "enable", "false"), calls)
        self.assertEqual(2, calls.count(("shell", "bmgr", "autorestore", "true")))
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

    def test_host_restores_disabled_autorestore(self):
        result, observed = self.run_script(FAKE_AUTO_RAW="0", FAKE_AUTO_STATE="disabled")
        self.assertNotEqual(0, result.returncode)
        auto = [a for a in observed if a[:3] == ["shell", "bmgr", "autorestore"]]
        self.assertEqual(["shell", "bmgr", "autorestore", "false"], auto[-1])
        run = json.loads((self.evidence / "run.json").read_text())
        self.assertEqual("ok", run.get("cleanupStatus"))

    def test_host_refuses_failed_original_setting_capture(self):
        result, observed = self.run_script(FAKE_SETTINGS_FAILURE="backup_enabled")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.mutating(observed))

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

    def test_finish_run_json_reads_selectors_not_facts(self):
        """F2: the real emitter must map selectors.txt, not facts.txt — distinct
        inputs expose a cross-read immediately."""
        ev = self.root / "emitter-ev"
        ev.mkdir()
        (ev / "facts.txt").write_text(
            f"device=api31\napi=31\nnonce={NONCE}\ncleanupStatus=ok\n"
            + "".join(f"selector.{n}=pass\n" for n in SELECTOR_NAMES))
        (ev / "selectors.txt").write_text(
            "".join(f"{n}=pass\n" for n in SELECTOR_NAMES))
        driver = self.root / "emit.sh"
        driver.write_text(textwrap.dedent(f"""\
            set -uo pipefail
            export BMGR_CHECK_LIB_ONLY=1
            ADB={self.adb}
            EV={ev}
            source {self.script}
            finish_run_json
            """))
        result = subprocess.run(["bash", str(driver)],
                                env={**os.environ, "ADB_CALLS": str(self.calls)},
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        run = json.loads((ev / "run.json").read_text())
        self.assertEqual({n: "pass" for n in SELECTOR_NAMES}, run["selectors"])
        self.assertEqual("api31", run["device"])
        self.assertEqual(NONCE, run["nonce"])

    def test_transport_registration_wait(self):
        """Delayed LocalTransport registration (run7 api36): bounded wait with
        every raw attempt retained, then mutable originals are captured."""
        result, observed = self.run_script(
            FAKE_TRANSPORT_DELAY="2", BMGR_TRANSPORT_WAIT_S="10")
        self.assertNotIn("did not register", result.stderr + result.stdout)
        wait = (self.evidence / "transport-wait.txt").read_text()
        self.assertEqual(2, wait.count("No transports available"))
        self.assertIn(("shell", "bmgr", "transport",
                       "com.android.localtransport/.LocalTransport"),
                      [tuple(a) for a in observed])

    def test_transport_never_ready_fails_closed(self):
        result, observed = self.run_script(
            FAKE_TRANSPORT_DELAY="99", BMGR_TRANSPORT_WAIT_S="4")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not register", result.stderr + result.stdout)
        self.assertIn("No transports available",
                      (self.evidence / "transport-wait.txt").read_text())
        self.assertEqual([], self.mutating(observed))

    def test_run_driver_arms_and_captures_each_phase(self):
        """Real run_driver through the lib seam: nounset-proof locals, armed
        phase/method/nonce args, raw stream + status capture per phase."""
        ev = self.root / "driver-ev"
        ev.mkdir()
        driver = self.root / "drv.sh"
        driver.write_text(textwrap.dedent(f"""\
            set -uo pipefail
            export BMGR_CHECK_LIB_ONLY=1
            source {self.script}
            ADB={self.adb}
            EV={ev}
            NONCE={NONCE}
            DRIVER_PKG={DRV_PKG}.test
            DRIVER_DATA_DIR=/data/data/$DRIVER_PKG
            run_driver seed seedImport
            run_driver flip settingsFlip
            run_driver encrypted encryptedImportRejected
            echo DRV-OK
            """))
        result = subprocess.run(["bash", str(driver)],
                                env={**os.environ, "ADB_CALLS": str(self.calls)},
                                capture_output=True, text=True, timeout=30)
        self.assertIn("DRV-OK", result.stdout, result.stderr + result.stdout)
        observed = ([json.loads(l) for l in self.calls.read_text().splitlines()]
                    if self.calls.exists() else [])
        instruments = [a for a in observed
                       if a[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(3, len(instruments))
        for call, phase, method in zip(
                instruments, ("seed", "flip", "encrypted"),
                ("seedImport", "settingsFlip", "encryptedImportRejected")):
            pairs = {call[i+1]: call[i+2] for i, a in enumerate(call) if a == "-e"}
            self.assertEqual(phase, pairs["bmgrPhase"])
            self.assertEqual(NONCE, pairs["bmgrNonce"])
            self.assertEqual(
                "ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverTest#" + method,
                pairs["class"])
            self.assertIn(f"bmgrDriver={phase}:{NONCE}:ok",
                          (ev / f"instrument-{phase}.log").read_text())
            self.assertGreater(
                (ev / f"driver-status-{phase}.json").stat().st_size, 0)

    def test_failed_driver_keeps_status_before_rejecting_marker(self):
        ev = self.root / "failed-driver"
        ev.mkdir()
        invocation = textwrap.dedent(f"""\
            set -uo pipefail
            export BMGR_CHECK_LIB_ONLY=1
            source {self.script}
            ADB={self.adb}
            EV={ev}
            NONCE={NONCE}
            DRIVER_PKG={DRV_PKG}.test
            DRIVER_DATA_DIR=/data/data/$DRIVER_PKG
            run_driver seed seedImport
            """)
        result = subprocess.run(
            ["bash", "-c", invocation], capture_output=True, text=True, timeout=30,
            env={**os.environ, "ADB_CALLS": str(self.calls), "FAKE_DRIVER_RESULT": "error:checkbox"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not report ok", result.stderr)
        self.assertTrue((ev / "driver-status-seed.json").is_file())
        self.assertGreater((ev / "driver-status-seed.json").stat().st_size, 0)


if __name__ == "__main__":
    unittest.main()
