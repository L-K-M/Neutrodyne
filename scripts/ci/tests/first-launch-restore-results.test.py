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
import time
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
        "phase": phase, "nonce": nonce, "api": api,
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
    unplayed = {e["k"] for e in fixture["episodes"] if e.get("pl") is None}
    for i, ref in enumerate(fixture["queue"], start=1):
        if ref["k"] not in unplayed:
            continue
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
    listed = [f"/sdcard/Android/data/{PKG}/{rel[len('external/'):]}"
              if rel.startswith("external/") else f"/data/data/{PKG}/{rel}"
              for rel in exp["canaries"]]
    listed += [f"/data/data/{PKG}/{rel}" for rel in (
        "files/backup/auto-snapshot.zip", "files/datastore/settings.preferences_pb",
        "files/datastore/device_settings.preferences_pb")]
    (pre / "prebackup-files.txt").write_text("\n".join(listed) + "\n")
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
    (post / "no_backup").mkdir(parents=True, exist_ok=True)
    build_workdb(post / "no_backup/androidx.work.workdb", 1)
    (source / "databases").mkdir(parents=True, exist_ok=True)
    build_source_db(source / "databases/neutrodyne.db", fixture)
    (post / "notifications.txt").write_text("")
    (empty / "databases").mkdir(parents=True, exist_ok=True)
    build_empty_db(empty / "databases/neutrodyne.db")
    (empty / "files").mkdir(parents=True, exist_ok=True)
    (empty / "files-listing.txt").write_text("")
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
            "canaries": [f"databases/bmgr-canary-{NONCE}.db"] + [
                f"{domain}/bmgr-canary-{NONCE}.dat" for domain in (
                    "files/downloads", "files/artwork", "files/other", "cache",
                    "no_backup", "external/files")],
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
        sidecar = {"nonce": NONCE, "api": api, "seedSha256": exp["seedSha256"],
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
            f"l {data}/lib", f"complete {NONCE}"]) + "\n")
        (directory / "prelaunch-ps.txt").write_text("USER PID NAME\nroot 1 init\n")
        for name in ("prelaunch-files", "prelaunch-ps", "prelaunch-logcat"):
            (directory / f"{name}.exit").write_text("0\n")
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

    def test_driver_records_also_accept_explicit_version_one(self):
        def mutate(directory, run, exp, logs, statuses):
            path = directory / "seed.expected.json"
            record = json.loads(path.read_text())
            record["v"] = 1
            path.write_text(json.dumps(record))
            for status in statuses.values():
                status["v"] = 1
        self.build(mutate={"api31": mutate, "api36": mutate})
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_driver_records_reject_unknown_or_malformed_versions(self):
        for name in ("seed.expected.json", "driver-status-seed.json"):
            for value in (2, None, True, "1", 1.0):
                with self.subTest(record=name, version=value):
                    def mutate(directory, run, exp, logs, statuses):
                        if name == "driver-status-seed.json":
                            statuses["seed"]["v"] = value
                        else:
                            path = directory / name
                            record = json.loads(path.read_text())
                            record["v"] = value
                            path.write_text(json.dumps(record))
                    self.build(mutate={"api31": mutate})
                    result = self.check()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("sidecar" if name == "seed.expected.json" else "status identity", result.stderr)

    def test_driver_sidecar_seed_size_is_bound(self):
        def mutate(directory, *unused):
            path = directory / "seed.expected.json"
            record = json.loads(path.read_text())
            record["seedBytes"] += 1
            path.write_text(json.dumps(record))
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

    def test_rejects_malformed_portable_preference_entries(self):
        value = _pb_field(2, 2, _pb_field(1, 0, _vint(1)))
        for name, suffix in (
                ("short64", b"\x49\x00"), ("short32", b"\x4d\x00"),
                ("unknown", value + _pb_field(9, 0, _vint(1))),
                ("missing-value", b""), ("empty-value", _pb_field(2, 2, b""))):
            with self.subTest(entry=name):
                def mutate(directory, *unused):
                    path = directory / "pulls/post/files/datastore/settings.preferences_pb"
                    entry = _pb_field(1, 2, b"unexpected") + suffix
                    path.write_bytes(path.read_bytes() + _pb_field(1, 2, entry))
                self.build(mutate={"api31": mutate})
                result = self.check()
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn("first-launch-restore:", result.stderr)

    def test_accepts_falsy_portable_preference_values(self):
        def mutate(directory, *unused):
            path = directory / "pulls/post/files/datastore/settings.preferences_pb"
            path.write_bytes(path.read_bytes() + pb_blob(**{
                "extra.bool": ("b", False), "extra.zero": ("i", 0),
                "extra.empty": ("s", "")}))
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_a_nonboolean_portable_snapshot_flag(self):
        for value in (("s", "true"), ("i", 1)):
            with self.subTest(value=value):
                def mutate(directory, *unused):
                    path = directory / "pulls/post/files/datastore/settings.preferences_pb"
                    path.write_bytes(pb_blob(**{"backup.opml_layout": ("s", "FLAT"),
                                               "backup.auto_snapshot_enabled": value}))
                self.build(mutate={"api31": mutate})
                result = self.check()
                self.assertNotEqual(0, result.returncode, result.stdout)

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

    def test_rejects_an_external_canary_leak(self):
        def mutate(directory, *unused):
            with (directory / "postlaunch-files.txt").open("a") as out:
                out.write(f"/sdcard/Android/data/{PKG}/files/bmgr-canary-{NONCE}.dat\n")
        self.build(mutate={"api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("canary leaked", result.stderr)

    def test_rejects_an_external_canary_missing_from_prebackup(self):
        def mutate(directory, *unused):
            path = directory / "pulls/pre/prebackup-files.txt"
            path.write_text("\n".join(line for line in path.read_text().splitlines()
                                      if not line.startswith("/sdcard/")) + "\n")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("absent from the prebackup tree", result.stderr)

    def test_rejects_a_played_episode_left_in_up_next(self):
        def mutate(directory, *unused):
            import sqlite3
            con = sqlite3.connect(directory / "pulls/post/databases/neutrodyne.db")
            con.execute("INSERT INTO queue_entry VALUES(2,4,'2',0)")
            con.commit()
            con.close()
        self.build(mutate={"api31": mutate, "api36": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("queue order", result.stderr)

    def test_rejects_a_missing_unplayed_up_next_entry(self):
        def mutate(directory, *unused):
            import sqlite3
            con = sqlite3.connect(directory / "pulls/post/databases/neutrodyne.db")
            con.execute("DELETE FROM queue_entry")
            con.commit()
            con.close()
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("queue order", result.stderr)

    def _rejects_prelaunch(self, mutate, needle, device="api31"):
        self.build(mutate={device: mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(needle, result.stderr)

    def test_rejects_a_nonterminal_adopted_work_spec(self):
        def mutate(directory, *unused):
            import sqlite3
            path = directory / "pulls/post/no_backup/androidx.work.workdb"
            db = sqlite3.connect(path)
            db.execute("UPDATE workspec SET state=1")
            db.commit()
            db.close()
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not SUCCEEDED", result.stderr)

    def test_rejects_prelaunch_path_violations(self):
        """DB file, framework-dir descendant, or special entry -> fail."""
        for entry, needle in (
                (f"f /data/data/{PKG}/databases/neutrodyne.db", "payload pair"),
                (f"d /data/data/{PKG}/databases/wal", "unexpected dirs/links"),
                (f"f /data/data/{PKG}/files/backup/auto-snapshot.zip", "malformed"),
                (f"? /data/data/{PKG}/sock", "malformed")):
            with self.subTest(entry=entry):
                def mutate(directory, run, exp, logs, statuses):
                    path = directory / "prelaunch-files.txt"
                    path.write_text(path.read_text().replace(
                        f"complete {NONCE}", entry + f"\ncomplete {NONCE}"))
                self._rejects_prelaunch(mutate, needle)

    def test_rejects_a_live_pid_without_backup_origin(self):
        def mutate(directory, run, exp, logs, statuses):
            (directory / "prelaunch-ps.txt").write_text(
                f"u0_a123  9999 1 0 0 {PKG}\n")
        self._rejects_prelaunch(mutate, "after force-stop")

    def test_prelaunch_requires_complete_captures(self):
        for name in ("prelaunch-files", "prelaunch-ps", "prelaunch-logcat"):
            for suffix, value in (("exit", "1"), ("txt", "")):
                with self.subTest(capture=f"{name}.{suffix}"):
                    def mutate(directory, *unused):
                        (directory / f"{name}.{suffix}").write_text(value)
                    self._rejects_prelaunch(mutate, "prelaunch")

    def test_prelaunch_rejects_ambiguous_provenance(self):
        changes = (
            (f"{PKG}/u0a123 for backup", f"{PKG}:child/u0a123 for backup", "normal process"),
            (f"for backup {{android/FullBackupAgent}}", f"for top-activity {{{PKG}/.MainActivity}}", "normal process"),
            (f"{PKG}/u0a123 for backup", f"{PKG}:restore/u0a123 for added application", "normal process"),
            ("Start proc 4455:", "Unobserved proc 4455:", "provenance"),
            (f"restoreAtInstall pkg={PKG} ", f"restoreAtInstall pkg={PKG}2 ", "provenance"),
            (f"{TAG}:", "other-tag:", "markers"),
            (f"begin {NONCE}", "begin run-9999999999-99999", "markers"),
            (f"begin {NONCE} {PKG}", f"begin {NONCE} {PKG}2", "markers"),
            (f"end {NONCE} {PKG}", f"end {NONCE} {PKG}\nI {TAG}: end {NONCE} {PKG}", "markers"),
        )
        for old, new, needle in changes:
            with self.subTest(change=new):
                def mutate(directory, *unused):
                    path = directory / "prelaunch-logcat.txt"
                    path.write_text(path.read_text().replace(old, new))
                self._rejects_prelaunch(mutate, needle)

    def test_prelaunch_ignores_old_windows(self):
        def mutate(directory, *unused):
            path = directory / "prelaunch-logcat.txt"
            path.write_text(f"I {TAG}: begin run-1-1 {PKG}\n" + path.read_text()
                            + f"I {TAG}: end run-1-1 {PKG}\n")
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

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

    def test_positive_pull_precedes_the_empty_control(self):
        import shutil
        directory = self.build_leg("api31", 31)
        (directory / "run.json").unlink()
        shutil.rmtree(directory / "pulls/empty")
        (directory / "init-empty.txt").unlink()
        result = self.pull_check(directory)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("bmgr-pull: PASS", result.stdout)

    def test_empty_control_cli_requires_an_empty_capture(self):
        import sqlite3
        directory = self.build_leg("api31", 31)

        def check():
            return subprocess.run(
                [sys.executable, str(SCRIPT), "--bmgr-empty", str(directory)],
                capture_output=True, text=True, timeout=60)

        result = check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("bmgr-empty: PASS", result.stdout)
        path = directory / "pulls/empty/databases/neutrodyne.db"
        con = sqlite3.connect(path)
        con.execute("INSERT INTO podcast VALUES(1)")
        con.commit()
        con.close()
        result = check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("empty-leg podcast", result.stderr)
        path.unlink()
        result = check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing or empty", result.stderr)

    def test_aggregate_still_requires_the_empty_control(self):
        import sqlite3
        self.build()
        path = self.root / "api31/pulls/empty/databases/neutrodyne.db"
        con = sqlite3.connect(path)
        con.execute("INSERT INTO podcast VALUES(1)")
        con.commit()
        con.close()
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("empty-leg podcast", result.stderr)
        path.unlink()
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing or empty", result.stderr)

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
            "import json, os, sys, time\n"
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
            "    if prior == 0:\n"
            "        time.sleep(float(os.environ.get('FAKE_TRANSPORT_QUERY_DELAY', '0')))\n"
            "    if prior < int(os.environ.get('FAKE_TRANSPORT_DELAY', '0')):\n"
            "        if prior == 1:\n"
            "            done('No transports available\\n  * com.android.localtransport/.LocalTransport\\n', 1)\n"
            "        done('No transports available\\n')\n"
            "    if any(a[:3] == ['shell', 'bmgr', 'transport'] for a in state):\n"
            "        done('    com.example.stub/.OtherTransport\\n'\n"
            "             '  * com.android.localtransport/.LocalTransport\\n')\n"
            "    done('  * com.example.stub/.OtherTransport\\n'\n"
            "         '    com.android.localtransport/.LocalTransport\\n')\n"
            "if args[:3] == ['shell', 'pm', 'path'] or args[:3] == ['shell', 'pm', 'list']:\n"
            "    done()\n"
            "if args[:3] == ['shell', 'bmgr', 'init']:\n"
            "    if os.environ.get('FAKE_INIT'):\n"
            "        done(os.environ['FAKE_INIT'] + '\\n')\n"
            "    if os.environ.get('FAKE_INIT_FAIL'):\n"
            "        done('error: transport unavailable\\n', 1)\n"
            "    prior = sum(1 for a in state if a[:3] == ['shell', 'bmgr', 'init'])\n"
            "    seq = os.environ.get('FAKE_INIT_SEQ', '0').split(',')\n"
            "    shape = seq[min(prior, len(seq) - 1)]\n"
            "    if shape == 'missing':\n"
            "        done('Initializing transports: [t]\\n')\n"
            "    if shape == 'mixed':\n"
            "        done('Initialization result: -1000\\nInitialization result: 0\\n')\n"
            "    if shape == 'mixed-tight':\n"
            "        done('Initialization result: 0\\nInitialization result:-1000\\n')\n"
            "    if shape == 'mixed-leading':\n"
            "        done('Initialization result: 0\\n Initialization result: -1000\\n')\n"
            "    done('Initializing transports: [com.a/.B]\\n'\n"
            "         'Initialization result: ' + shape + '\\n')\n"
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
        before_mutation = observed[:observed.index(["shell", "bmgr", "enable", "true"])]
        self.assertEqual(3, sum(call[:4] == ["shell", "bmgr", "list", "transports"]
                                for call in before_mutation))
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

    def test_transport_wait_budget_is_wall_time(self):
        started = time.monotonic()
        result, observed = self.run_script(FAKE_TRANSPORT_DELAY="99",
            FAKE_TRANSPORT_QUERY_DELAY="2", BMGR_TRANSPORT_WAIT_S="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not register", result.stderr + result.stdout)
        # Includes the existing two-second adbd restart plus cleanup captures.
        self.assertLess(time.monotonic() - started, 5)
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

    def _lib_driver(self, body, extra_env=None):
        driver = self.root / f"drv{len(list(self.root.glob('drv*.sh')))}.sh"
        driver.write_text(textwrap.dedent(f"""\
            set -uo pipefail
            export BMGR_CHECK_LIB_ONLY=1
            exec 0</dev/null
            source {self.script}
            {body}
            """))
        env = {**os.environ, "ADB_CALLS": str(self.calls), **(extra_env or {})}
        return subprocess.run(["bash", str(driver)], env=env,
                              capture_output=True, text=True, timeout=60)

    def test_bmgr_init_retries_only_the_observed_race_shape(self):
        """Run8 api36 InitObserver race: a single explicit -1000 on rc0 may be
        retried bounded; only a later explicit result 0 succeeds."""
        for seq, ok in (("-1000,0", True), ("0", True),
                        ("-1000,-1000,-1000,0", False),
                        ("missing", False), ("mixed", False), ("mixed-tight", False),
                        ("mixed-leading", False), ("-2000,0", False)):
            with self.subTest(seq=seq):
                ev = self.root / f"init-{seq.replace(',', '_')}"
                ev.mkdir()
                result = self._lib_driver(
                    f"ADB={self.adb}\nEV={ev}\nTRANSPORT=com.a/.B\n"
                    "bmgr_init leg\necho INIT-OK\n",
                    extra_env={"FAKE_INIT_SEQ": seq})
                calls = self.calls.read_text().splitlines() \
                    if self.calls.exists() else []
                self.calls.write_text("")
                attempts = [json.loads(a) for a in calls
                            if json.loads(a)[:3] == ["shell", "bmgr", "init"]]
                self.assertEqual(ok, "INIT-OK" in result.stdout,
                                 f"seq={seq}: {result.stdout + result.stderr}")
                expected = {"-1000,0": 2, "0": 1, "-1000,-1000,-1000,0": 3,
                            "missing": 1, "mixed": 1, "mixed-tight": 1,
                            "mixed-leading": 1, "-2000,0": 1}[seq]
                self.assertEqual(expected, len(attempts))
                self.assertLessEqual(len(attempts), 3)
                self.assertTrue((ev / "init-leg.txt").is_file())
                for n in range(1, len(attempts) + 1):
                    self.assertTrue((ev / f"init-leg-attempt{n}.txt").is_file())
                ledger = (ev / "init-leg-attempts.txt").read_text()
                self.assertEqual(len(attempts), ledger.count("attempt "))

    def test_bmgr_init_command_failure_never_retries(self):
        ev = self.root / "init-fail"
        ev.mkdir()
        result = self._lib_driver(
            f"ADB={self.adb}\nEV={ev}\nTRANSPORT=com.a/.B\nbmgr_init leg\necho INIT-OK\n",
            extra_env={"FAKE_INIT_FAIL": "1"})
        self.assertNotIn("INIT-OK", result.stdout)
        inits = [a for a in self.calls.read_text().splitlines()
                 if json.loads(a)[:3] == ["shell", "bmgr", "init"]]
        self.calls.write_text("")
        self.assertEqual(1, len(inits))

    def test_pull_tree_keeps_listing_off_child_stdin(self):
        """Run8 canary loss: adb children inherited the .listing read cursor on
        fd0 and drained the remaining entries. Real pull_tree via the lib seam
        against an adb double that deliberately consumes stdin on every call."""
        drain = self.root / "adb-drain"
        drain.write_text(
            f"#!{sys.executable}\n"
            "import os, sys\n"
            "args = sys.argv[1:]\n"
            "sys.stdin.read()  # the real adb client consumes our stdin\n"
            "files = os.environ['FAKE_PULL_LIST'].split('\\n')\n"
            "if args[:1] == ['shell'] and args[1].startswith('find '):\n"
            "    sys.stdout.write('\\n'.join(files) + '\\n'); sys.exit(0)\n"
            "if args[:3] == ['shell', 'test', '-d'] or args[:3] == ['shell', 'test', '-L']:\n"
            "    sys.exit(1)\n"
            "if args[:2] == ['exec-out', 'cat']:\n"
            "    sys.stdout.write('content-of-' + args[2].rsplit('/', 1)[-1] + '\\n'); sys.exit(0)\n"
            "sys.exit(0)\n")
        drain.chmod(0o755)
        ev = self.root / "pull-ev"
        remote = f"/data/data/{PKG}/databases"
        result = self._lib_driver(
            f"ADB={drain}\nEV={ev}\n"
            f"pull_tree {remote} {ev}/pulls/databases\n"
            "echo PULL-OK\n",
            extra_env={"FAKE_PULL_LIST":
                       f"{remote}/canary.db\n{remote}/neutrodyne.db"})
        self.assertIn("PULL-OK", result.stdout,
                      result.stdout + result.stderr)
        for name in ("canary.db", "neutrodyne.db"):
            pulled = ev / "pulls/databases" / name
            self.assertTrue(pulled.is_file(), f"missing {name}")
            self.assertEqual(f"content-of-{name}\n", pulled.read_text())
        listing = ev / "pulls/databases-listing.txt"
        self.assertTrue(listing.is_file())
        self.assertIn("neutrodyne.db", listing.read_text())

    def _canary_listing(self, phase, failure="", external=True):
        """Execute the actual pre/post capture call site against a local shell device."""
        home = self.root / f"listing-{len(list(self.root.glob('listing-*')))}"
        home.mkdir()
        internal, parent = home / "internal", home / "external"
        internal.mkdir()
        parent.mkdir()
        for rel in ("files/backup/auto-snapshot.zip", "files/datastore/settings.preferences_pb",
                    "files/datastore/device_settings.preferences_pb"):
            path = internal / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"capture fixture")
        if external:
            path = parent / PKG / f"files/bmgr-canary-{NONCE}.dat"
            path.parent.mkdir(parents=True)
            path.write_text("external canary")
        double = home / "adb"
        double.write_text(textwrap.dedent(f"""\
            #!{sys.executable}
            import os, subprocess, sys
            args = sys.argv[1:]
            data, parent = os.environ['LIST_DATA'], os.environ['LIST_PARENT']
            def local(value):
                return value.replace('/data/data/{PKG}', data).replace('/sdcard/Android/data', parent)
            if args[:2] == ['exec-out', 'cat']:
                sys.stdout.buffer.write(open(local(args[2]), 'rb').read()); sys.exit(0)
            assert len(args) == 2 and args[0] == 'shell', args
            command = local(args[1])
            # Preserve real shell/find behavior; inject a failed command after partial output.
            failing = os.environ.get('LIST_FAILURE', '')
            marker = data if failing == 'internal' else parent
            if failing and marker in command and ('find ' in command or failing == 'parent'):
                print('partial capture'); sys.exit(1)
            result = subprocess.run(['bash', '-c', command], capture_output=True)
            sys.stdout.buffer.write(result.stdout.replace(data.encode(), b'/data/data/{PKG}')
                                   .replace(parent.encode(), b'/sdcard/Android/data'))
            sys.stderr.buffer.write(result.stderr)
            sys.exit(result.returncode)
            """))
        double.chmod(0o755)
        text = self.script.read_text()
        if phase == "pre":
            start = text.index('    write_canaries > "$EV/canaries.txt"')
            end = text.index('    python3 - "$EV" "$PULL_PRE"', start)
            listing = home / "ev/pulls/pre/prebackup-files.txt"
        else:
            start = text.index('    PULL_POST="$EV/pulls/post"')
            end = text.index('    pull_tree "$DATA_DIR/files"', start)
            listing = home / "ev/postlaunch-files.txt"
        ev = home / "ev"
        ev.mkdir()
        result = self._lib_driver(
            f"ADB={double}\nEV={ev}\nNONCE={NONCE}\n" + text[start:end],
            {"LIST_DATA": str(internal), "LIST_PARENT": str(parent), "LIST_FAILURE": failure})
        return result, listing

    def test_prebackup_listing_includes_the_external_canary(self):
        result, listing = self._canary_listing("pre")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        paths = listing.read_text().splitlines()
        self.assertIn(f"/sdcard/Android/data/{PKG}/files/bmgr-canary-{NONCE}.dat", paths)
        self.assertIn(f"/data/data/{PKG}/databases/bmgr-canary-{NONCE}.db", paths)

    def test_postlaunch_listing_observes_external_leaks_or_an_absent_app_directory(self):
        for external in (True, False):
            with self.subTest(external=external):
                result, listing = self._canary_listing("post", external=external)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(external, f"/sdcard/Android/data/{PKG}/files/"
                                 f"bmgr-canary-{NONCE}.dat" in listing.read_text().splitlines())

    def test_canary_listing_command_errors_fail_closed(self):
        for phase in ("pre", "post"):
            for failure in ("internal", "external", "parent"):
                with self.subTest(phase=phase, failure=failure):
                    result, _ = self._canary_listing(phase, failure)
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

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


# ---- bgstart fixtures --------------------------------------------------------

BG_CLASS = "ch.lkmc.neutrodyne.tools.bmgrdriver.BgStartTest"
BG_METHODS = {"bgseed": "seedPausedSession",
              "bgstart": "deniedBackgroundStartOffersWorkingResume"}
BG_STEPS = {
    "bgseed": ["built", "launched", "preview", "revoked", "categories", "requested"],
    "bgstart": ["sidecar", "served", "actor", "bound", "settled", "guarded",
                "card", "play-tapped", "denied", "resume-tapped", "resumed"],
}
BG_SELECTORS = (
    "apkNonDebuggable", "driverInstrumentationAvailable", "seedImportDone",
    "durableQueueSeeded", "targetColdBelowBfgs", "bgstartCompleted",
)
BG_UID = 10150


def bg_instrument_log(phase, nonce, fail_code=0):
    method = BG_METHODS[phase]
    return (
        f"INSTRUMENTATION_STATUS: class={BG_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS: numtests=1\n"
        f"INSTRUMENTATION_STATUS_CODE: 1\n"
        f"INSTRUMENTATION_STATUS: bmgrDriver={phase}:{nonce}:ok\n"
        f"INSTRUMENTATION_STATUS_CODE: 0\n"
        f"INSTRUMENTATION_STATUS: class={BG_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS_CODE: {fail_code}\n"
        "INSTRUMENTATION_RESULT: stream=\nTest results ran to completion.\nOK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


def bg_driver_status(phase, nonce, api):
    return {
        "phase": phase, "nonce": nonce, "api": api,
        "driverPackage": DRV_PKG, "targetPackage": PKG,
        "startedAt": 1_700_000_000_000,
        "steps": [{"name": s, "at": 1_700_000_000_000} for s in BG_STEPS[phase]],
        "result": "ok",
    }


def bg_services(stage, api):
    """A ServiceRecord dump for the bound playback service: a live process
    attached, no `isForeground=` line until the service runs foreground (the
    dump only prints it then), the api36 `mAllowStart_*` verdicts that api31
    dumps never emit, and the denial attempt's own record with no grant."""
    resumed = stage == "resumed"
    foreground = "    isForeground=true foregroundId=4000 foregroundNoti=x\n" \
        if resumed else ""
    allows = ("    mAllowStart_noBinding=DENIED\n"
              "    mAllowStart_byBindings=DENIED\n") if api >= 36 else ""
    info = (
        "infoAllowStartForeground=[callingPackage: ch.lkmc.neutrodyne; "
        f"callingUid: {BG_UID}; uidState: SVC ; uidBFSL: []; "
        "intent: Intent { cmp=ch.lkmc.neutrodyne/.NeutrodynePlaybackService }; "
        "code:DENIED; tempAllowListReason:<null>; allowWiu:0; "
        "targetSdkVersion:36; callerTargetSdkVersion:0; startForegroundCount:0; "
        "bindFromPackage:null: isBindService:false]"
    )
    return f"""ACTIVITY MANAGER SERVICES (dumpsys activity services)
  ServiceRecord{{u0 4f7a123 u0a{BG_UID} ch.lkmc.neutrodyne/.NeutrodynePlaybackService}}
    intent={{cmp=ch.lkmc.neutrodyne/ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService}}
    packageName=ch.lkmc.neutrodyne
    app=ProcessRecord{{abc123 5123:ch.lkmc.neutrodyne/u0a{BG_UID % 1000}}}
{foreground}{allows}    {info}
"""


def bg_processes():
    return f"""ACTIVITY MANAGER LRU PROCESSES (dumpsys activity processes)
  ProcessRecord{{abc123 5123:ch.lkmc.neutrodyne/u0a{BG_UID % 1000}}}
    user #0 uid={BG_UID}
    tempAllowListReason=<null>
"""


def bg_notification(records):
    return "\n".join(records) + "\n"


def bg_notif(nid, channel):
    return (f"NotificationRecord(0x{nid:04x}: pkg={PKG} user=UserHandle{{0}} "
            f"id={nid} tag=null key=t: Notification(channel={channel} pri=0))")


def bg_audio(started):
    state = "started" if started else "stopped"
    return ("AudioPlaybackConfiguration items:\n"
            f"  - piid:391 uid:{BG_UID} state:{state} "
            "attr:{Content type: CONTENT_TYPE_SPEECH Usage: USAGE_MEDIA}\n")


def bg_media_session(state, speed):
    return (f"Media sessions (1):\n"
            f"  {PKG}/ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService (userId=0)\n"
            f"    PlaybackState {{state={state}, position=750000, "
            f"buffered position=760000, speed={speed}, updated=9000, actions=12345}}\n")


BG_POSITIONS = (
    "sample=1 elapsed=10500 state=3 position=750000 speed=1.0 updated=9000 live=751500\n"
    "sample=2 elapsed=12000 state=3 position=750000 speed=1.0 updated=9000 live=753000\n"
    "delta=1500\n"
)

BG_SETTLE = "".join(f"t={t} state=10 fg=false\n" for t in range(0, 16000, 1000))


def bg_sidecar(nonce, api):
    fixture = bmgr_fixture()
    for episode in fixture["episodes"]:
        if episode["k"] == "epk-a2":
            episode["u"] = "http://127.0.0.1:47123/ep2.mp3"
            episode["ty"] = "audio/wav"
    return {
        "v": 1, "nonce": nonce, "api": api,
        "seedSha256": "e" * 64, "seedBytes": 7,
        "port": 47123, "enclosureUrl": "http://127.0.0.1:47123/ep2.mp3",
        "fixture": fixture,
    }


class BgstartEvidenceTest(unittest.TestCase):
    """`--bgstart` strict-mode fixtures — built entirely with the stdlib, offline."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def build_leg(self, device, api, mutate=None):
        directory = self.root / device
        directory.mkdir(parents=True, exist_ok=True)
        run = {
            "schema": "bgstart-evidence/v1", "device": device, "api": api,
            "fingerprint": "google/sdk/fp", "abi": "x86_64", "nonce": NONCE,
            "sourceSha": "abc", "apkSha256": "a" * 64, "apkBytes": 10,
            "driverApkSha256": "b" * 64, "actorApkSha256": "f" * 64,
            "driverPackage": DRV_PKG,
            "driverRunner": DRV_RUNNER, "certSha256": "c" * 64,
            "avd": f"bgstart-api{api}", "appUid": BG_UID,
            "cleanupStatus": "ok",
            "startedAt": "2026-01-01T00:00:00Z", "finishedAt": "2026-01-01T00:10:00Z",
            "selectors": {name: "pass" for name in BG_SELECTORS},
        }
        texts = {
            "facts.txt": f"device={device}\napi={api}\nnonce={NONCE}\n"
                         + "".join(f"selector.{n}=pass\n" for n in BG_SELECTORS),
            "selectors.txt": "".join(f"{n}=pass\n" for n in BG_SELECTORS),
            "instrumentations.txt":
                f"instrumentation:{DRV_PKG}/{DRV_RUNNER} (target={DRV_PKG})\n",
            "bgstart.expected.json": json.dumps(bg_sidecar(NONCE, api)),
            "uid-state-cold.txt": "20 (NONEXISTENT)\n",
            "bgstart-uid-state-before.txt": "10 (SERVICE)\n",
            "bgstart-uid-state-card.txt": "10 (SERVICE)\n",
            "bgstart-uid-state-denied.txt": "10 (SERVICE)\n",
            "bgstart-uid-state-resumed.txt": "4 (FOREGROUND_SERVICE)\n",
            "bgstart-services-before.txt": bg_services("before", api),
            "bgstart-services-card.txt": bg_services("card", api),
            "bgstart-services-denied.txt": bg_services("denied", api),
            "bgstart-services-resumed.txt": bg_services("resumed", api),
            "bgstart-processes-before.txt": bg_processes(),
            "bgstart-processes-card.txt": bg_processes(),
            "bgstart-processes-denied.txt": bg_processes(),
            "bgstart-processes-resumed.txt": bg_processes(),
            "bgstart-notification-denied.txt": bg_notification([bg_notif(4001, "alerts")]),
            "bgstart-notification-resumed.txt":
                bg_notification([bg_notif(4000, "playback")]),
            "bgstart-media-session-card.txt": bg_media_session("PAUSED(2)", "0.0"),
            "bgstart-media-session-denied.txt": bg_media_session("PAUSED(2)", "0.0"),
            "bgstart-settle.txt": BG_SETTLE,
            "bgstart-media-session-resumed.txt": bg_media_session("PLAYING(3)", "1.0"),
            "bgstart-audio-denied.txt": bg_audio(False),
            "bgstart-audio-resumed.txt": bg_audio(True),
            "bgstart-positions.txt": BG_POSITIONS,
            "bgstart-fssnae-logcat.txt": "",
            "ui-bgstart-shade.xml": "<hierarchy />",
        }
        logs = {p: bg_instrument_log(p, NONCE) for p in BG_METHODS}
        statuses = {p: bg_driver_status(p, NONCE, api) for p in BG_METHODS}
        if mutate:
            mutate(directory, run, texts, logs, statuses)
        for name, text in texts.items():
            (directory / name).write_text(text)
        for phase, text in logs.items():
            (directory / f"instrument-{phase}.log").write_text(text)
            (directory / f"driver-status-{phase}.json").write_text(
                json.dumps(statuses[phase]))
        (directory / "run.json").write_text(json.dumps(run))
        return directory

    def build(self, devices=("api31", "api36"), mutate=None):
        apis = {"api31": 31, "api36": 36}
        for device in devices:
            self.build_leg(device, apis[device],
                           mutate.get(device) if mutate else None)

    def check(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--bgstart", str(self.root)],
            capture_output=True, text=True, timeout=60)

    def assert_rejected(self, device_mutations, diagnostic=None):
        self.build(mutate=device_mutations)
        result = self.check()
        self.assertNotEqual(0, result.returncode, result.stdout)
        if diagnostic:
            self.assertIn(diagnostic, result.stderr)

    def test_accepts_a_complete_two_leg_run(self):
        self.build()
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("bgstart: PASS api31 + api36", result.stdout)

    def test_rejects_a_missing_leg(self):
        self.build(("api31",))
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("api36:", result.stderr)

    def test_rejects_a_failed_or_skipped_case(self):
        def mutate(directory, run, texts, logs, statuses):
            logs["bgstart"] = bg_instrument_log("bgstart", NONCE, fail_code=-3)
        self.assert_rejected({"api36": mutate}, "instrument evidence mismatch")

    def test_rejects_a_nonce_mismatch(self):
        def mutate(directory, run, texts, logs, statuses):
            run["nonce"] = "other-nonce-1"
            texts["bgstart.expected.json"] = json.dumps(
                bg_sidecar("other-nonce-1", run["api"]))
        self.assert_rejected({"api31": mutate}, "marker evidence")

    def test_rejects_a_tampered_driver_status(self):
        def mutate(directory, run, texts, logs, statuses):
            statuses["bgstart"]["driverPackage"] = "evil.driver"
        self.assert_rejected({"api31": mutate}, "status identity/result mismatch")

    def test_rejects_a_targeting_instrumentation(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["instrumentations.txt"] = (
                f"instrumentation:{DRV_PKG}/{DRV_RUNNER} (target={PKG})\n")
        self.assert_rejected({"api36": mutate}, "self-targeting")

    def test_rejects_a_foreground_service_under_denial(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-denied.txt"] = bg_services("resumed", 31)
        self.assert_rejected({"api31": mutate}, "isForeground=true")

    def test_rejects_a_phantom_service_record(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-before.txt"] = (
                texts["bgstart-services-before.txt"].replace(
                    "app=ProcessRecord{abc123 5123:ch.lkmc.neutrodyne/u0a150}",
                    "app=null"))
        self.assert_rejected({"api31": mutate}, "no live process")

    def test_rejects_a_card_stage_elevation(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-uid-state-card.txt"] = "4 (FOREGROUND_SERVICE)\n"
        self.assert_rejected({"api36": mutate}, "card uid-state")

    def test_rejects_a_card_binding_grant(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-card.txt"] = (
                texts["bgstart-services-card.txt"].replace(
                    "bindFromPackage:null", "bindFromPackage:com.android.systemui"))
        self.assert_rejected({"api31": mutate}, "binding grants FGS")

    def test_rejects_an_allow_code(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-denied.txt"] = (
                texts["bgstart-services-denied.txt"].replace(
                    "code:DENIED", "code:SYSTEM_UID"))
        self.assert_rejected({"api36": mutate}, "grants FGS")

    def test_rejects_a_denial_with_no_self_attempt(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-denied.txt"] = (
                texts["bgstart-services-denied.txt"].replace(
                    "isBindService:false", "isBindService:true"))
        self.assert_rejected({"api36": mutate}, "no DENIED self attempt")

    def test_rejects_a_short_settle(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-settle.txt"] = "".join(
                f"t={t} state=10 fg=false\n" for t in range(0, 9000, 1000))
        self.assert_rejected({"api31": mutate}, "settle window")

    def test_rejects_a_settle_breach(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-settle.txt"] = BG_SETTLE.replace(
                "t=8000 state=10", "t=8000 state=4")
        self.assert_rejected({"api36": mutate}, "settle window")

    def test_rejects_a_settle_foreground_breach(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-settle.txt"] = BG_SETTLE.replace(
                "t=8000 state=10 fg=false", "t=8000 state=10 fg=true")
        self.assert_rejected({"api31": mutate}, "settle window")

    def test_accepts_pre_window_elevation_samples(self):
        # The uid may still sit elevated right after the bind; only the
        # contiguous demoted run must reach 15s.
        def mutate(directory, run, texts, logs, statuses):
            elevated = "".join(
                f"t={t} state=4 fg=false\n" for t in range(0, 3000, 1000))
            demoted = "".join(
                f"t={t} state=10 fg=false\n" for t in range(3000, 19000, 1000))
            texts["bgstart-settle.txt"] = elevated + demoted
        self.build(mutate={"api31": mutate})
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_a_bfsl_self_record(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-denied.txt"] = (
                texts["bgstart-services-denied.txt"].replace(
                    "uidBFSL: []", "uidBFSL: [BFSL]"))
        self.assert_rejected({"api36": mutate}, "[BFSL]")

    def test_rejects_an_elevated_uid_state(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-denied.txt"] = (
                texts["bgstart-services-denied.txt"].replace(
                    "uidState: SVC", "uidState: FGS"))
        self.assert_rejected({"api31": mutate}, "above BFGS")

    def test_rejects_a_temp_allowlist(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-services-denied.txt"] = (
                texts["bgstart-services-denied.txt"].replace(
                    "tempAllowListReason:<null>", "tempAllowListReason:EXEMPTED"))
        self.assert_rejected({"api36": mutate}, "temp allowlist")

    def test_rejects_a_missing_recovery_notification(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-notification-denied.txt"] = bg_notification(
                [bg_notif(9999, "alerts")])
        self.assert_rejected({"api31": mutate}, "4001")

    def test_rejects_active_audio_under_denial(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-audio-denied.txt"] = bg_audio(True)
        self.assert_rejected({"api36": mutate}, "audio active")

    def test_rejects_a_missing_resume_surface(self):
        for name, replacement, diagnostic in (
                ("bgstart-notification-resumed.txt",
                 bg_notification([bg_notif(4000, "other")]), "4000"),
                ("bgstart-audio-resumed.txt", bg_audio(False), "active playback"),
                ("bgstart-services-resumed.txt",
                 bg_services("denied", 31), "isForeground=true")):
            with self.subTest(evidence=name):
                def mutate(directory, run, texts, logs, statuses,
                           n=name, r=replacement):
                    texts[n] = r
                self.assert_rejected({"api31": mutate}, diagnostic)

    def test_rejects_an_uncancelled_recovery_notification(self):
        def mutate(directory, run, texts, logs, statuses):
            texts["bgstart-notification-resumed.txt"] = bg_notification(
                [bg_notif(4000, "playback"), bg_notif(4001, "alerts")])
        self.assert_rejected({"api36": mutate}, "not cancelled")

    def test_rejects_an_unmoving_position(self):
        def mutate(directory, run, texts, logs, statuses):
            # Keep live consistent with the sample fields; the delta shrinks to 100ms.
            texts["bgstart-positions.txt"] = BG_POSITIONS.replace(
                "elapsed=12000", "elapsed=10600").replace(
                "live=753000", "live=751600")
        self.assert_rejected({"api31": mutate}, "position advance")

    def test_rejects_a_stale_sidecar(self):
        def mutate(directory, run, texts, logs, statuses):
            sidecar = json.loads(texts["bgstart.expected.json"])
            sidecar["api"] = 28
            texts["bgstart.expected.json"] = json.dumps(sidecar)
        self.assert_rejected({"api36": mutate}, "sidecar identity malformed")

    def test_rejects_a_non_loopback_enclosure(self):
        def mutate(directory, run, texts, logs, statuses):
            sidecar = json.loads(texts["bgstart.expected.json"])
            sidecar["enclosureUrl"] = "https://example.test/ep2.mp3"
            texts["bgstart.expected.json"] = json.dumps(sidecar)
        self.assert_rejected({"api31": mutate}, "enclosureUrl/port mismatch")

    def test_rejects_divergent_apks_across_legs(self):
        def mutate(directory, run, texts, logs, statuses):
            run["apkSha256"] = "d" * 64
        self.assert_rejected({"api36": mutate}, "apkSha256 differs")

    def test_rejects_an_unpassed_selector(self):
        def mutate(directory, run, texts, logs, statuses):
            run["selectors"]["targetColdBelowBfgs"] = "fail"
        self.assert_rejected({"api36": mutate}, "targetColdBelowBfgs")


if __name__ == "__main__":
    unittest.main()
