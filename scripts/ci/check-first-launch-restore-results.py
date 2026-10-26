#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Require named, unskipped first-launch restore evidence.

Two modes:

- default: the GMD XML gate (`api26`/`api36` managed-device reports), unchanged.
- `--bmgr ROOT`: the M3 AC6 local-transport gate — strict verification of the
  `scripts/ci/bmgr-check.sh` evidence tree (one `apiNN/` directory per leg).
- `--bmgr-tar TAR EXPECTATION_JSON`: single-TAR payload verification used by
  the script itself, writing `TAR.members` next to the archive.
- `--bmgr-encoff LOG PACKAGE`: the shared strict parser for the encryption-off
  `bmgr backupnow --monitor` log; prints the verbatim package verdict. Used by
  the script itself and by the aggregate — only a closed, diagnosed policy
  exclusion is accepted.
- `--bmgr-pb PB_FILE KEY`: prints one decoded androidx.datastore
  `preferences_pb` value (a closed wire-format reader; nothing is skipped).
- `--bmgr-pull EVIDENCE_DIR`: validates one leg's post-restore quiescent
  capture — pulled payload pair, preferences_pb contents, pre-launch gate,
  restored database content. `bmgr-check.sh` runs it before the empty control.
- `--bmgr-empty EVIDENCE_DIR`: validates the subsequent empty-dataset capture.
  The `--bmgr` aggregate requires both phase checks verbatim.
- `--bgstart ROOT`: the M4 P6b external background-start gate — strict
  verification of the `scripts/ci/bgstart-check.sh` evidence tree (one
  `apiNN/` directory per leg): denial and resume dumpsys captures, the
  instrument/status/marker wire, sidecar binding and selectors.
"""

import argparse
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
BMGR_TARGET = "ch.lkmc.neutrodyne"
BMGR_CLASS = "ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverTest"
BMGR_METHODS = {
    "seed": "seedImport",
    "flip": "settingsFlip",
    "encrypted": "encryptedImportRejected",
}
BMGR_LOGS = {phase: f"instrument-{phase}.log" for phase in BMGR_METHODS}
BMGR_SELECTORS = (
    "apkNonDebuggable", "apkSignatureStable", "driverInstrumentationAvailable",
    "expectationCaptured", "unencryptedTransportExcluded", "positivePayloadVerified",
    "restoreAdoptionObserved", "restoredLibraryVerified", "emptyLegExcluded",
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

# `bmgr backupnow` verdicts that can certify an encryption-off exclusion (the
# BackupObserver status strings, upstream Bmgr.java). Transport/agent errors,
# quota, cancel and "Unknown error" are failures, never exclusions.
ENCOFF_POLICY_VERDICTS = {
    "Backup is not allowed",
    "Transport rejected package because it wasn't able to process it at the time",
}


def parse_encoff_backupnow(text, package, device="encoff"):
    """Strict verdict extraction from a `bmgr backupnow --monitor` log.

    Returns the verbatim package verdict only for a closed diagnosed policy
    exclusion: the run line, a successful @pm@ metadata verdict, exactly one
    verdict for the expected package in ENCOFF_POLICY_VERDICTS, an
    ERROR_PREFLIGHT monitor event naming it, and a single successful finish.
    """
    text = text.replace("\r", "")
    if not re.search(r"^Running (non-)?incremental backup for \d+ requested "
                     r"packages\.$", text, re.M):
        raise ValueError(f"{device}: enc-off log lacks the backupnow run line")
    verdicts = re.findall(r"^Package (\S+) with result: (.+)$", text, re.M)
    if not verdicts:
        raise ValueError(f"{device}: enc-off log has no package verdicts")
    names = [name for name, _ in verdicts]
    if len(names) != len(set(names)):
        raise ValueError(f"{device}: duplicate package verdict {sorted(names)}")
    own = []
    pm_metadata = 0
    for name, verdict in verdicts:
        verdict = verdict.strip()
        if name == "@pm@":
            if verdict != "Success":
                raise ValueError(f"{device}: @pm@ verdict is {verdict!r}, not Success")
            pm_metadata += 1
        elif name == package:
            own.append(verdict)
        else:
            raise ValueError(f"{device}: unexpected package verdict for {name}")
    if pm_metadata != 1:
        raise ValueError(f"{device}: expected one @pm@ metadata success")
    if len(own) != 1:
        raise ValueError(f"{device}: expected exactly one verdict for {package}")
    verdict = own[0]
    if verdict not in ENCOFF_POLICY_VERDICTS:
        raise ValueError(f"{device}: {verdict!r} is not a policy exclusion")
    events = [line for line in text.splitlines() if line.startswith("=> Event{")]
    expected_event = (r"=> Event\{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = "
                      + re.escape(package) + r"\(v\d+\)\}")
    if len(events) != 1 or not re.fullmatch(expected_event, events[0]):
        raise ValueError(f"{device}: expected only the ERROR_PREFLIGHT event for {package}")
    finishes = re.findall(r"^Backup finished with result: (.+)$", text, re.M)
    if finishes != ["Success"]:
        raise ValueError(
            f"{device}: backup finish evidence {finishes} != ['Success']")
    return verdict


def run_bmgr_encoff(log_path, package):
    verdict = parse_encoff_backupnow(Path(log_path).read_text(errors="replace"),
                                   package)
    print(verdict)


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


def check_instrument_log(device, phase, nonce, text, cls=BMGR_CLASS, method=None):
    method = BMGR_METHODS[phase] if method is None else method
    expected = [(cls, method, 1), (cls, method, 0)]
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
    markers = status_values(text, "bmgrDriver")
    if markers != [f"{phase}:{nonce}:ok"]:
        raise ValueError(
            f"{device}: {phase} marker evidence {markers!r} "
            f"!= {phase}:{nonce}:ok")
    if f"{phase}:{nonce}:ok" not in markers[0]:
        raise ValueError(f"{device}: {phase} marker is not the armed nonce")


def driver_v1(record):
    # The driver's Kotlin serializers omit the declared v=1 default.
    version = record.get("v", 1)
    return type(version) is int and version == 1


def check_driver_status(device, phase, nonce, api, driver_pkg, path,
                        required_steps=None):
    """The durable per-phase record — a foreign, stale or unknown status fails."""
    try:
        status = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"{device}: {phase} status record unreadable") from error
    if not driver_v1(status) or status.get("phase") != phase \
            or status.get("nonce") != nonce or status.get("api") != api \
            or status.get("driverPackage") != driver_pkg \
            or status.get("targetPackage") != BMGR_TARGET \
            or status.get("result") != "ok":
        raise ValueError(f"{device}: {phase} status identity/result mismatch")
    steps = status.get("steps")
    if not isinstance(steps, list) or not steps \
            or any(not isinstance(s.get("name"), str) or not isinstance(s.get("at"), int)
                   for s in steps):
        raise ValueError(f"{device}: {phase} status has malformed steps")
    required = required_steps or {
        "seed": {"built", "launched", "preview", "categories", "requested"},
        "flip": {"built", "launched", "preview", "categories", "requested"},
        "encrypted": {"built", "launched", "rejected"},
    }[phase]
    names = [s["name"] for s in steps]
    if len(names) != len(set(names)) or not required <= set(names):
        raise ValueError(f"{device}: {phase} status misses steps {sorted(required - set(names))}")


# ---- preferences_pb wire parser ---------------------------------------------
# androidx.datastore preferences_pb.proto:
#   PreferencesMap { map<string, Value> value = 1; }
#   Value: oneof { bool=1 float=2 int32=3 int64=4 string=5 string_set=6
#                  double=7 bytes=8 }
# The parser is intentionally a closed reader: unknown wire types or malformed
# entries raise instead of being skipped, so a tampered PB fails closed.

def _varint(buf, pos):
    value = 0
    shift = 0
    while True:
        if pos >= len(buf) or shift > 63:
            raise ValueError("malformed varint")
        byte = buf[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7


def _pb_fields(buf):
    """Yield (field_no, wire_type, value) for every field; LEN gives a slice."""
    pos = 0
    while pos < len(buf):
        tag, pos = _varint(buf, pos)
        field, wire = tag >> 3, tag & 7
        if field == 0:
            raise ValueError("field number 0")
        if wire == 0:
            value, pos = _varint(buf, pos)
        elif wire == 1:
            value = buf[pos:pos + 8]
            if len(value) != 8:
                raise ValueError("truncated 64-bit field")
            pos += 8
        elif wire == 2:
            length, pos = _varint(buf, pos)
            value = buf[pos:pos + length]
            if len(value) != length:
                raise ValueError("truncated LEN field")
            pos += length
        elif wire == 5:
            value = buf[pos:pos + 4]
            if len(value) != 4:
                raise ValueError("truncated 32-bit field")
            pos += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield field, wire, value


def pb_preferences(data):
    """{key: value} from a PreferencesMap blob; values lose the oneof tag."""
    prefs = {}
    for field, wire, entry in _pb_fields(data):
        if field != 1 or wire != 2:
            raise ValueError("PreferencesMap carries a non-map field")
        key = None
        value = None
        for efield, ewire, evalue in _pb_fields(entry):
            if efield == 1 and ewire == 2:
                key = evalue.decode("utf-8")
            elif efield == 2 and ewire == 2:
                for vfield, vwire, vvalue in _pb_fields(evalue):
                    if vfield == 5 and vwire == 2:
                        value = vvalue.decode("utf-8")
                    elif vfield == 1 and vwire == 0:
                        value = bool(vvalue)
                    elif vfield in (3, 4) and vwire == 0:
                        value = vvalue
                    elif vfield == 6 and vwire == 2:
                        value = sorted(s.decode("utf-8")
                                       for _, _, s in _pb_fields(vvalue))
                    else:
                        raise ValueError(
                            f"unsupported Value field {vfield}/{vwire}")
            else:
                raise ValueError(f"unsupported map-entry field {efield}/{ewire}")
        if key is None:
            raise ValueError("map entry without a key")
        if value is None:
            raise ValueError(f"map entry {key!r} without a value")
        prefs[key] = value
    return prefs


def run_bmgr_pb(pb_path, key):
    value = pb_preferences(Path(pb_path).read_bytes()).get(key)
    if value is not None:
        print(value if not isinstance(value, bool) else str(value).lower())


# ---- post-restore quiescent-capture validation --------------------------------

UUID4 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
SNAPSHOT_REL = "files/backup/auto-snapshot.zip"
PORTABLE_REL = "files/datastore/settings.preferences_pb"
DEVICE_PB_REL = "files/datastore/device_settings.preferences_pb"
# WorkManager's pinned runtime creates its new scheduling ledger outside backup.
WORK_DB_REL = "no_backup/androidx.work.workdb"


def open_db(device, path):
    """Read-only sqlite open; a corrupt or absent pull fails closed."""
    import sqlite3
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"{device}: pulled DB missing or empty: {path.name}")
    try:
        return sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    except sqlite3.Error as error:
        raise ValueError(f"{device}: pulled DB {path.name} unreadable") from error


def pull_listing(path):
    return [line.strip() for line in path.read_text(errors="replace").splitlines()
            if line.strip()]


# Framework roots AOSP's FullBackup.BackupScheme constructs around a default-agent
# restore (database/shared-prefs/cache/no_backup bootstrap); empty dirs only.
PRELAUNCH_FRAMEWORK_DIRS = {"cache", "code_cache", "databases",
                            "no_backup", "shared_prefs"}
PRELAUNCH_PAYLOAD_FILES = {
    "files/backup/auto-snapshot.zip",
    "files/datastore/settings.preferences_pb",
}
PRELAUNCH_PARENT_DIRS = {"files", "files/backup", "files/datastore"}
PRELAUNCH_LINKS = {"lib"}
PRELAUNCH_TAG = "bmgr-prelaunch"
AGENT_REASON = "backup {android/FullBackupAgent}"


def check_prelaunch(device, directory, nonce):
    """Require a stopped restore window with complete paths and default-agent provenance."""
    for name in ("prelaunch-files", "prelaunch-ps", "prelaunch-logcat"):
        if (directory / f"{name}.exit").read_text().strip() != "0" \
                or not (directory / f"{name}.txt").read_text().strip():
            raise ValueError(f"{device}: prelaunch capture failed: {name}")
    data = f"/data/data/{BMGR_TARGET}"
    rel_of = {"f": set(), "d": set(), "l": set()}
    listing = (directory / "prelaunch-files.txt").read_text().splitlines()
    if not listing or listing[-1] != f"complete {nonce}":
        raise ValueError(f"{device}: prelaunch capture incomplete")
    seen = set()
    for line in listing[:-1]:
        t, _, p = line.partition(" ")
        if t not in rel_of or not p.startswith(f"{data}/") or p in seen:
            raise ValueError(f"{device}: prelaunch listing malformed: {line!r}")
        seen.add(p)
        rel_of[t].add(p[len(data) + 1:])
    if rel_of["f"] != PRELAUNCH_PAYLOAD_FILES:
        raise ValueError(
            f"{device}: prelaunch files != payload pair: {sorted(rel_of['f'])}")
    extra = rel_of["d"] - PRELAUNCH_PARENT_DIRS - PRELAUNCH_FRAMEWORK_DIRS
    if extra or rel_of["l"] - PRELAUNCH_LINKS or not PRELAUNCH_PARENT_DIRS <= rel_of["d"]:
        raise ValueError(f"{device}: prelaunch unexpected dirs/links: "
                         f"{sorted(extra | (rel_of['l'] - PRELAUNCH_LINKS))}")
    # Empty-dir proof is structural: a descendant would be its own entry.

    lines = pull_listing(directory / "prelaunch-logcat.txt")
    marker = re.compile(rf"\b{PRELAUNCH_TAG}: (begin|end) {re.escape(nonce)} {re.escape(BMGR_TARGET)}$")
    bounds = [(i, match.group(1)) for i, line in enumerate(lines) if (match := marker.search(line))]
    if [kind for _, kind in bounds] != ["begin", "end"]:
        raise ValueError(f"{device}: prelaunch logcat window markers missing")
    begin, end = (index for index, _ in bounds)

    pkg = re.escape(BMGR_TARGET)
    start = re.compile(rf"Start proc (\d+):({pkg}(?::\S+)?)/\S+ for (\S.*)$")
    backup_pids = set()
    saw_restore = False
    for line in lines[begin + 1:end]:
        if re.search(rf"restoreAtInstall pkg={pkg}(?:\s|$)", line):
            saw_restore = True
        if (found := start.search(line)):
            if found.group(2) != BMGR_TARGET or found.group(3) != AGENT_REASON:
                raise ValueError(
                    f"{device}: normal process start inside prelaunch window: "
                    f"{found.group(2)} for {found.group(3)}")
            backup_pids.add(found.group(1))
        if f"cmp={BMGR_TARGET}/" in line or f"cmp={BMGR_TARGET}:" in line:
            raise ValueError(f"{device}: activity/component start in window")
        if f"{PRELAUNCH_TAG}:" in line:
            raise ValueError(f"{device}: unexpected prelaunch window markers")
    if not (saw_restore and backup_pids):
        raise ValueError(
            f"{device}: no default-agent restore provenance in window")

    for line in pull_listing(directory / "prelaunch-ps.txt"):
        fields = line.split()
        if "NeutrodyneApplication" in line or "FATAL EXCEPTION" in line:
            raise ValueError(
                f"{device}: prelaunch process evidence shows app init")
        if fields and (fields[-1] == BMGR_TARGET or fields[-1].startswith(f"{BMGR_TARGET}:")):
            raise ValueError(f"{device}: live target process after force-stop: {line}")


def check_source_db(device, directory):
    """The pre-uninstall ledger: exactly the two manual seed/flip imports. They
    die with the source install — a fresh reinstall can never carry them."""
    src = directory / "pulls/source"
    db = open_db(device, src / "databases/neutrodyne.db")
    sessions = db.execute(
        "SELECT id, sourceFormat, state, finishedAt, payloadPath, optionsJson "
        "FROM import_session ORDER BY id").fetchall()
    db.close()
    if len(sessions) != 2:
        raise ValueError(
            f"{device}: expected 2 source import_session rows, got {len(sessions)}")
    for row in sessions:
        opts = json.loads(row[5])
        if row[1] != "NEUTRODYNE_BACKUP" or row[2] != "DONE" or row[3] is None \
                or opts.get("restoreMode") != "MERGE" \
                or opts.get("fromAndroidBackup"):
            raise ValueError(f"{device}: manual session row {row[0]} malformed")
        if Path(row[4] or "").name != f"{row[0]}.bin":
            raise ValueError(f"{device}: session {row[0]} payloadPath is not its own")
    seed_opts = json.loads(sessions[0][5])
    if sorted(seed_opts.get("restoreCategories", [])) != \
            ["HISTORY", "SETTINGS", "UP_NEXT"]:
        raise ValueError(f"{device}: seed session categories mismatch")
    flip_opts = json.loads(sessions[1][5])
    if flip_opts.get("restoreCategories") != ["SETTINGS"]:
        raise ValueError(f"{device}: flip session categories mismatch")


def check_restored_db(device, directory, exp):
    """Terminal proof over the coherent pulled DB — fixture content, session
    lifecycle, queue/session anchors and the WorkSpec ledger."""
    post = directory / "pulls/post"
    db = open_db(device, post / "databases/neutrodyne.db")
    fixture = exp["fixture"]
    podcasts = {p["key"]: p for p in fixture["podcasts"]}
    episodes = fixture["episodes"]

    # Session ledger: exactly one row — the adopted Android-backup restore.
    # The manual seed/flip sessions lived in the uninstalled source DB
    # (pulls/source); a forged extra row here is evidence tampering.
    sessions = db.execute(
        "SELECT id, sourceFormat, state, finishedAt, payloadPath, optionsJson "
        "FROM import_session ORDER BY id").fetchall()
    if len(sessions) != 1:
        raise ValueError(
            f"{device}: expected 1 adopted import_session row, got {len(sessions)}")
    adopted = sessions[0]
    adopted_opts = json.loads(adopted[5])
    if adopted[1] != "NEUTRODYNE_BACKUP" or adopted[2] != "DONE" \
            or adopted[3] is None or not adopted_opts.get("fromAndroidBackup") \
            or adopted_opts.get("restoreMode") != "MERGE" \
            or "SETTINGS" in adopted_opts.get("restoreCategories", []) \
            or not {"HISTORY", "UP_NEXT"} <= set(adopted_opts.get("restoreCategories", [])):
        raise ValueError(f"{device}: adopted restore session malformed")
    payload_rel = adopted[4]
    if not payload_rel or not payload_rel.startswith("import/") \
            or ".." in payload_rel:
        raise ValueError(f"{device}: adopted payloadPath {payload_rel!r} invalid")
    payload = post / "cache" / payload_rel
    if not payload.is_file() or sha256_hex(payload.read_bytes()) != exp["snapshotSha256"]:
        raise ValueError(
            f"{device}: adopted restore payload is not the transported snapshot")

    # Library: exact feedKey<->syncId mapping and fixture identity.
    rows = db.execute(
        "SELECT feedKey, syncId, feedUrl, title, link FROM podcast ORDER BY feedKey"
        ).fetchall()
    if len(rows) != len(podcasts):
        raise ValueError(f"{device}: restored podcast count {len(rows)}")
    for key, sync_id, url, title, link in rows:
        spec = podcasts.get(key)
        if spec is None or spec["syncId"] != sync_id or spec["feedUrl"] != url \
                or spec["title"] != title or spec["link"] != link:
            raise ValueError(f"{device}: restored podcast {key} mismatches fixture")

    # Groups: same names/members, fresh v4 uuids (never the seed uuid).
    group_rows = db.execute(
        "SELECT id, uuid, name FROM podcast_group ORDER BY name").fetchall()
    fixture_groups = {g["name"]: g for g in fixture["groups"]}
    if len(group_rows) != len(fixture_groups):
        raise ValueError(f"{device}: restored group count {len(group_rows)}")
    by_episode_id = dict(db.execute("SELECT id, identityKey FROM episode"))
    feedkey_by_id = dict(db.execute("SELECT id, feedKey FROM podcast"))
    for gid, uuid, name in group_rows:
        spec = fixture_groups.get(name)
        if spec is None or not UUID4.fullmatch(uuid) or uuid == spec["uuid"]:
            raise ValueError(f"{device}: group {name!r} uuid not freshly minted")
        members = sorted(feedkey_by_id[pid] for (pid,) in db.execute(
            "SELECT podcastId FROM podcast_group_member WHERE groupId=?", (gid,)))
        if members != sorted(spec["members"]):
            raise ValueError(f"{device}: group {name!r} members mismatch {members}")

    # Episodes: identity + the merged state rows (episode_state/position).
    ep_rows = db.execute(
        "SELECT identityKey, title, guid FROM episode").fetchall()
    want = {e["k"]: e for e in episodes}
    if {r[0] for r in ep_rows} != set(want) or len(ep_rows) != len(want):
        raise ValueError(f"{device}: restored episode identities mismatch")
    id_by_key = {k: i for i, k in by_episode_id.items()}
    for spec in episodes:
        eid = id_by_key.get(spec["k"])
        if eid is None:
            raise ValueError(f"{device}: episode {spec['k']} missing")
        state = db.execute(
            "SELECT startedAt, playedAt, playCount, lastPlayedAt, isFavorite, "
            "measuredDurationMs FROM episode_state WHERE episodeId=?",
            (eid,)).fetchone()
        position = db.execute(
            "SELECT positionMs, positionSource FROM episode_position WHERE episodeId=?",
            (eid,)).fetchone()
        want_state = (spec.get("st"), spec.get("pl"), spec.get("pc", 0),
                      spec.get("lp"), 1 if spec.get("fav") else 0, spec.get("md"))
        got_state = state or (None, None, 0, None, 0, None)
        if got_state != want_state:
            raise ValueError(
                f"{device}: episode {spec['k']} state {got_state} != {want_state}")
        if spec.get("pos") is None:
            if position is not None:
                raise ValueError(f"{device}: episode {spec['k']} has a stray position")
        elif position is None or position[0] != spec["pos"] \
                or position[1] != spec.get("posSrc", "STREAM"):
            raise ValueError(f"{device}: episode {spec['k']} position {position}")

    # A restore into the empty install skips already-played refs (05 Up next).
    queue_ids = [r[0] for r in db.execute(
        "SELECT episodeId FROM queue_entry ORDER BY orderKey")]
    inv = {v: k for k, v in id_by_key.items()}
    queue_order = [inv.get(i) for i in queue_ids]
    expected_queue = [r["k"] for r in fixture["queue"] if want[r["k"]].get("pl") is None]
    if queue_order != expected_queue:
        raise ValueError(f"{device}: queue order {queue_order} != fixture")
    play = db.execute(
        "SELECT currentEpisodeId, contextType, contextId FROM play_session WHERE id=0"
        ).fetchone()
    session = fixture.get("session")
    if session and session.get("current"):
        want_current = id_by_key.get(session["current"]["k"])
        if play is None or play[0] != want_current:
            raise ValueError(f"{device}: play_session current {play} != fixture")
        if session.get("contextType") == "GROUP":
            want_group = next(g[0] for g in group_rows
                              if fixture_groups[g[2]]["uuid"] == session["contextGroupUuid"])
            if play[1] != "GROUP" or play[2] != want_group:
                raise ValueError(f"{device}: play_session context {play[1:]} != fixture")

    # WorkSpec ledger: the adopted restore's tagged spec must be terminal-done.
    try:
        workdb = open_db(device, post / WORK_DB_REL)
        specs = workdb.execute(
            "SELECT w.id, w.state FROM workspec w JOIN worktag t ON w.id=t.work_spec_id "
            "WHERE t.tag=?", (f"restore-session-{adopted[0]}",)).fetchall()
        if len(specs) != 1 or specs[0][1] != 2:
            raise ValueError(f"{device}: adopted restore WorkSpec {specs} not SUCCEEDED")
    except ValueError:
        raise
    except Exception:
        raise ValueError(f"{device}: workdb ledger unreadable")
    db.close()


def check_pull(device, directory, exp, run):
    """The whole post-restore capture — run by the script and re-run verbatim by
    the aggregate, so a premature pull can never pass."""
    nonce = run["nonce"]
    post = directory / "pulls/post"
    pre = directory / "pulls/pre"
    source = directory / "pulls/source"
    for path in (pre / SNAPSHOT_REL, pre / PORTABLE_REL, pre / DEVICE_PB_REL,
                 source / "databases/neutrodyne.db",
                 post / PORTABLE_REL, post / "databases/neutrodyne.db",
                 post / WORK_DB_REL):
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"{device}: missing pulled evidence {path}")

    # The transported pair binds to the expectation byte-for-byte.
    if sha256_hex((pre / SNAPSHOT_REL).read_bytes()) != exp["snapshotSha256"] \
            or (pre / SNAPSHOT_REL).stat().st_size != exp["snapshotBytes"] \
            or sha256_hex((pre / PORTABLE_REL).read_bytes()) != exp["portableSha256"] \
            or (pre / PORTABLE_REL).stat().st_size != exp["portableBytes"]:
        raise ValueError(f"{device}: prebackup payload pull mismatches expectation")
    validate_snapshot_zip(device, (pre / SNAPSHOT_REL).read_bytes(), exp)

    # Device PB: fresh installationId + the receiver's recorded write.
    match = re.fullmatch(r"ok at=(\d+) bytes=(\d+) level=(\d+)", exp["receiverLine"])
    if not match:
        raise ValueError(f"{device}: receiverLine {exp['receiverLine']!r} malformed")
    dev_pb = pb_preferences((pre / DEVICE_PB_REL).read_bytes())
    if dev_pb.get("backup.installation_id") != exp["installationId"]:
        raise ValueError(f"{device}: device PB installationId != expectation")
    at, bts, lvl = int(match[1]), int(match[2]), int(match[3])
    if dev_pb.get("backup.last_snapshot_at") != at \
            or dev_pb.get("backup.last_snapshot_bytes") != bts \
            or dev_pb.get("backup.last_snapshot_level") != lvl \
            or bts != exp["snapshotBytes"]:
        raise ValueError(f"{device}: device PB snapshot status != receiver line")
    # Only file=SettingsFile.DEVICE keys may live in the device PB (05's
    # BackupSettingKeys) — a portable key here is evidence of the wrong file.
    if {"backup.auto_snapshot_enabled", "backup.opml_layout"} & set(dev_pb):
        raise ValueError(f"{device}: device PB carries portable keys")
    portable_pre = pb_preferences((pre / PORTABLE_REL).read_bytes())
    if portable_pre.get("backup.auto_snapshot_enabled") is not True:
        raise ValueError(f"{device}: portable PB auto-snapshot flag unset")
    if portable_pre.get(exp["portableKey"]) != exp["portableAfter"] \
            or "backup.installation_id" in portable_pre:
        raise ValueError(f"{device}: prebackup portable PB not the flipped value")

    # Canary receipt: every planted canary was in the pre-backup tree.
    prebackup = pull_listing(pre / "prebackup-files.txt")
    for rel in exp.get("canaries", []):
        want = f"/data/data/{BMGR_TARGET}/{rel}" \
            if not rel.startswith("external/") \
            else f"/sdcard/Android/data/{BMGR_TARGET}/{rel[len('external/'):]}"
        if want not in prebackup:
            raise ValueError(f"{device}: canary {rel} absent from the prebackup tree")

    check_prelaunch(device, directory, exp["nonce"])
    check_source_db(device, directory)
    check_restored_db(device, directory, exp)

    # Post-restore file domains: retired/replaced snapshot + restored PB only.
    portable_post = pb_preferences((post / PORTABLE_REL).read_bytes())
    if portable_post.get(exp["portableKey"]) != exp["portableAfter"] \
            or "backup.installation_id" in portable_post \
            or portable_post.get("backup.auto_snapshot_enabled") is not True:
        raise ValueError(f"{device}: post-restore portable PB != FLAT/clean")
    backup_dir = post / "files/backup"
    retired = sorted(p.name for p in backup_dir.glob("restored-*.zip")) \
        if backup_dir.is_dir() else []
    replaced = backup_dir / "auto-snapshot.zip"
    if retired:
        # A retired blob is the transported bytes moved aside, verbatim.
        retired_path = backup_dir / retired[0]
        if len(retired) != 1 \
                or sha256_hex(retired_path.read_bytes()) != exp["snapshotSha256"]:
            raise ValueError(f"{device}: retired snapshot mismatch {retired}")
    if replaced.is_file():
        # A legitimate replacement is the NEW install's debounce product: the
        # post-restore device installationId and the current FLAT settings —
        # FirstLaunchRestoreChecks.assertReplacementSnapshot's contract.
        replaced_pb = pb_preferences((post / DEVICE_PB_REL).read_bytes()) \
            if (post / DEVICE_PB_REL).is_file() else {}
        new_id = replaced_pb.get("backup.installation_id")
        if not new_id or new_id == exp["installationId"]:
            raise ValueError(
                f"{device}: post-restore device PB installationId unusable")
        validate_snapshot_zip(device, replaced.read_bytes(), exp,
                              installation_id=new_id,
                              portable_value=exp["portableAfter"])
        if sha256_hex(replaced.read_bytes()) == exp["snapshotSha256"]:
            raise ValueError(f"{device}: replacement snapshot is the old one")
    elif not retired:
        raise ValueError(f"{device}: restored snapshot neither retired nor replaced")
    post_files = pull_listing(directory / "postlaunch-files.txt")
    canary_hits = [p for p in post_files if f"bmgr-canary-{nonce}" in p]
    if canary_hits:
        raise ValueError(f"{device}: canary leaked post-restore {canary_hits}")


def check_empty(device, directory):
    """The later empty-control phase must be opened and still a fresh install."""
    empty = directory / "pulls/empty"
    empty_db = open_db(device, empty / "databases/neutrodyne.db")
    for table in ("podcast", "episode", "podcast_group", "queue_entry",
                  "import_session", "episode_state"):
        count = empty_db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        if count != 0:
            raise ValueError(f"{device}: empty-leg {table} has {count} rows")
    empty_db.close()
    empty_listing = pull_listing(empty / "files-listing.txt")
    delivered = [p for p in empty_listing if "/backup/" in p or "restored" in p]
    if delivered:
        raise ValueError(f"{device}: empty leg delivered files {delivered}")


def validate_snapshot_zip(device, data, exp, installation_id=None,
                          portable_value=None):
    """The structural snapshot contract. The transported/retired input carries
    the source install's id and the pre-snapshot value; a legitimate
    replacement carries the new install's id and the current value."""
    want_id = exp["installationId"] if installation_id is None else installation_id
    want_value = exp["portableInSnapshot"] if portable_value is None else portable_value
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
    if manifest.get("installationId") != want_id:
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
    if values.get(exp["portableKey"]) != want_value:
        raise ValueError(f"{device}: snapshot settings value mismatch")


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
    if run.get("schema") != "bmgr-evidence/v2" or run.get("api") != api:
        raise ValueError(f"{device}: run.json schema/api mismatch")
    if not run.get("fingerprint") or run.get("abi") != "x86_64" \
            or not run.get("avd"):
        raise ValueError(f"{device}: emulator identity evidence missing")
    if not NONCE.fullmatch(run.get("nonce", "")):
        raise ValueError(f"{device}: run nonce invalid")
    nonce = run["nonce"]
    if run.get("apkSha256") == run.get("driverApkSha256") \
            or not SHA256.fullmatch(run.get("apkSha256", "")) \
            or not SHA256.fullmatch(run.get("driverApkSha256", "")):
        raise ValueError(f"{device}: apk/driver sha256 evidence missing or shared")
    if run.get("driverPackage") == BMGR_TARGET or not run.get("driverPackage") \
            or not run.get("driverRunner"):
        raise ValueError(f"{device}: driver identity evidence missing")
    instruments = (directory / "instrumentations.txt").read_text(errors="replace") \
        if (directory / "instrumentations.txt").is_file() else ""
    if f"instrumentation:{run['driverPackage']}/{run['driverRunner']} " \
            f"(target={run['driverPackage']})" not in instruments \
            or f"(target={BMGR_TARGET})" in instruments:
        raise ValueError(f"{device}: driver instrumentation not self-targeting")
    exp = json.loads((directory / "expectation.json").read_text())
    if exp.get("v") != 2 or exp.get("nonce") != nonce or exp.get("api") != api \
            or exp.get("package") != BMGR_TARGET:
        raise ValueError(f"{device}: expectation nonce/api/package mismatch")
    for field in ("snapshotSha256", "portableSha256", "installationId",
                  "receiverLine", "portableKey", "seedSha256", "fixture"):
        if not exp.get(field):
            raise ValueError(f"{device}: expectation misses {field}")
    for field in ("snapshotSha256", "portableSha256", "seedSha256"):
        if not SHA256.fullmatch(exp[field]):
            raise ValueError(f"{device}: expectation {field} is not SHA-256")
    selectors = run.get("selectors")
    if not isinstance(selectors, dict) or any(selectors.get(n) != "pass" for n in BMGR_SELECTORS):
        missing = [n for n in BMGR_SELECTORS if (selectors or {}).get(n) != "pass"]
        raise ValueError(f"{device}: selectors not all 'pass' ({', '.join(missing)})")

    # The driver-owned sidecar binds the fixture to the expectation verbatim.
    sidecar = json.loads((directory / "seed.expected.json").read_text())
    if not driver_v1(sidecar) or sidecar.get("nonce") != nonce \
            or sidecar.get("api") != api \
            or sidecar.get("seedSha256") != exp["seedSha256"] \
            or sidecar.get("seedBytes") != exp["seedBytes"] \
            or sidecar.get("fixture") != exp["fixture"]:
        raise ValueError(f"{device}: sidecar/expectation fixture binding broken")
    if {p["key"]: p["syncId"] for p in exp["fixture"]["podcasts"]} != exp["syncIds"] \
            or {g["name"]: g["uuid"] for g in exp["fixture"]["groups"]} != exp["groupUuids"]:
        raise ValueError(f"{device}: expectation lookup maps != fixture")

    for phase, name in BMGR_LOGS.items():
        path = directory / name
        if not path.is_file():
            raise ValueError(f"{device}: missing {name}")
        check_instrument_log(device, phase, nonce, path.read_text(errors="replace"))
        status_path = directory / f"driver-status-{phase}.json"
        if not status_path.is_file():
            raise ValueError(f"{device}: missing driver-status-{phase}.json")
        check_driver_status(device, phase, nonce, api, run["driverPackage"], status_path)

    for field in ("unencryptedBackupnowExit", "positiveBackupnowExit"):
        if run.get(field) != 0:
            raise ValueError(f"{device}: {field} is {run.get(field)!r}, not 0")
    if run.get("cleanupStatus") != "ok":
        raise ValueError(
            f"{device}: cleanup status is {run.get('cleanupStatus')!r}, not 'ok'")
    for label in ("encoff-pre", "encoff", "positive", "empty"):
        receipt = directory / f"init-{label}.txt"
        if not receipt.is_file() or "Initialization result: 0" not in \
                receipt.read_text(errors="replace"):
            raise ValueError(
                f"{device}: missing a successful init-{label}.txt receipt")

    backup = (directory / "backupnow-positive.log").read_text(errors="replace")
    for needle in (f"Package {exp['package']} with result: Success",
                   "Backup finished with result: Success"):
        if needle not in backup:
            raise ValueError(f"{device}: backupnow log misses {needle!r}")
    encoff_log = directory / "backupnow-encoff.log"
    if not encoff_log.is_file():
        raise ValueError(f"{device}: missing backupnow-encoff.log")
    verdict = parse_encoff_backupnow(encoff_log.read_text(errors="replace"),
                                   exp["package"], device)
    if run.get("unencryptedDiagnosis") != f"encoff:{verdict}":
        raise ValueError(
            f"{device}: unencryptedDiagnosis does not match the log verdict")
    payload = directory / "encoff-payload.txt"
    if not payload.is_file() or payload.read_text(errors="replace").strip():
        raise ValueError(
            f"{device}: enc-off payload receipt is missing or non-empty")

    tar_path = directory / "transport.tar"
    if not tar_path.is_file():
        raise ValueError(f"{device}: missing transport.tar")
    if sha256_hex(tar_path.read_bytes()) != run.get("tarSha256"):
        raise ValueError(f"{device}: transport.tar hash does not match run.json")
    check_transport_tar(tar_path, exp, device)

    # The whole quiescent capture again — the aggregate re-proves what the
    # script already validated, so no host-side short-circuit is possible.
    check_pull(device, directory, exp, run)
    check_empty(device, directory)
    print(f"{device}: PASS bmgr black-box lifecycle "
          f"(seed/snapshot/flip/encrypted -> backup -> install-restore -> pulls)")


def check_bmgr(root):
    runs = {}
    for device, api in BMGR_DEVICES.items():
        check_bmgr_device(root, device, api)
        runs[device] = json.loads((root / device / "run.json").read_text())
    a, b = runs["api31"], runs["api36"]
    for field in ("sourceSha", "apkSha256", "driverApkSha256",
                  "driverPackage", "driverRunner"):
        if not a.get(field) or a[field] != b.get(field):
            raise ValueError(f"bmgr: {field} differs across legs")
    print("bmgr: PASS api31 + api36")


# ---- M4 P6b bgstart external gate ---------------------------------------------
# The denial+resume case cannot live inside the instrumented app APK (active
# instrumentation grants PROCESS_CAPABILITY_BFSL), so this aggregate re-verifies
# the external lane's saved dumpsys/notification/audio/session captures.

BGSTART_DEVICES = {"api31": 31, "api36": 36}
BGSTART_CLASS = "ch.lkmc.neutrodyne.tools.bmgrdriver.BgStartTest"
BGSTART_METHODS = {
    "bgseed": "seedPausedSession",
    "bgstart": "deniedBackgroundStartOffersWorkingResume",
}
BGSTART_LOGS = {phase: f"instrument-{phase}.log" for phase in BGSTART_METHODS}
BGSTART_SELECTORS = (
    "apkNonDebuggable", "driverInstrumentationAvailable", "seedImportDone",
    "durableQueueSeeded", "targetColdBelowBfgs", "bgstartCompleted",
)
BGSTART_STEPS = {
    "bgseed": {"built", "launched", "preview", "revoked", "categories", "requested"},
    "bgstart": {"sidecar", "served", "actor", "bound", "settled", "guarded",
                "trusted", "card", "denied", "resume-tapped", "resumed"},
}
# The trigger fires exactly one of two surfaces: the SystemUI card's Play when
# the card renders, else the trusted trigger's own framework-path play().
BGSTART_PLAY_STEPS = {"play-tapped", "play-triggered"}
BGSTART_PROCSTATE_SERVICE = 10
BGSTART_SETTLE_MS = 15_000
BGSTART_RESUME_ID = 4001
BGSTART_PLAYBACK_ID = 4000
BGSTART_RESUME_CHANNEL = "alerts"
BGSTART_PLAYBACK_CHANNEL = "playback"
BGSTART_POSITION_ADVANCE_MS = 200
# uidState names numerically worse than BFGS(5): an attempt made from any of these
# cannot have been allowed by the uid's own process state.
BGSTART_UIDSTATE_BELOW_BFGS = {
    "IMPF", "IMPB", "TRNB", "BAK", "SVC", "RCVR", "TPSL",
    "HVY", "HOME", "LAST", "CAC", "CCE", "CRE", "NONE", "NEX",
}

# The record body carries one-level brackets (`uidBFSL: [BFSL]`); the balanced
# outer pair keeps the record from truncating at the first inner `]`.
ALLOW_INFO_RE = re.compile(r"infoAllowStartForeground=\[((?:[^\[\]]|\[[^\]]*\])*)\]")
ALLOW_FIELD_RE = re.compile(r"([a-zA-Z]+)\s*:\s*([^;]*)")
IS_BIND_RE = re.compile(r"isBindService\s*:\s*(true|false)")
BIND_FROM_RE = re.compile(r"bindFromPackage\s*:\s*([^\s:;]+)")
TEMP_IN_RECORD_RE = re.compile(r"tempAllowListReason\s*:\s*(<null>|[^;\]]+)")
TEMP_ALLOW_RE = re.compile(r"tempAllowListReason\s*[:=]\s*([^;\]\s]+)")
UID_STATE_RE = re.compile(r"^\s*(-?\d+)\s*\(", re.M)


def allow_infos(text):
    """Every `infoAllowStartForeground=[...]` record as a {field: value} dict."""
    records = []
    for match in ALLOW_INFO_RE.finditer(text):
        body = match.group(1)
        fields = {m.group(1): m.group(2).strip()
                  for m in ALLOW_FIELD_RE.finditer(body)}
        is_bind = IS_BIND_RE.search(body)
        temp = TEMP_IN_RECORD_RE.search(body)
        bind_from = BIND_FROM_RE.search(body)
        fields["isBindService"] = is_bind.group(1) if is_bind else None
        fields["tempAllowListReason"] = temp.group(1) if temp else None
        fields["bindFromPackage"] = bind_from.group(1) if bind_from else None
        records.append(fields)
    return records


def self_attempt(info, app_uid):
    """A non-bind attempt by the target — a genuine FGS start request. When the
    record carries `isBindService` it alone decides; sparse records (api31)
    fall back to the calling uid."""
    is_bind = info.get("isBindService")
    if is_bind is not None:
        return is_bind == "false"
    return info.get("callingUid") == str(app_uid)


def uid_state(text):
    match = UID_STATE_RE.search(text)
    return int(match.group(1)) if match else None


def has_notification(text, pkg, nid, channel):
    return any(f"pkg={pkg}" in line and f"id={nid}" in line
               and f"channel={channel}" in line
               for line in text.splitlines())


def has_notification_id(text, pkg, nid):
    return any(f"pkg={pkg}" in line and f"id={nid}" in line
               for line in text.splitlines())


def apc_started(text, uid):
    uid_token = re.compile(rf"uid[:=]\s*{uid}\b")
    return any("state:started" in line and uid_token.search(line)
               for line in text.splitlines())


def playback_sample(text, pkg):
    """The PlaybackState nearest the session block naming `pkg`, or None."""
    lines = text.splitlines()
    pkg_lines = [i for i, line in enumerate(lines) if pkg in line]
    if not pkg_lines:
        return None
    candidates = [i for i, line in enumerate(lines) if "PlaybackState" in line]
    if not candidates:
        return None
    index = min(candidates,
                key=lambda i: min(abs(i - p) for p in pkg_lines))
    if min(abs(index - p) for p in pkg_lines) > 80:
        return None
    line = lines[index]
    state = re.search(r"state=(?:[A-Z_]+\()?(-?\d+)\)?", line)
    position = re.search(r"(?<!buffered )position=(-?\d+)", line)
    speed = re.search(r"speed=(-?[\d.]+)", line)
    updated = re.search(r"updated=(-?\d+)", line)
    return {
        "state": int(state.group(1)) if state else None,
        "position": int(position.group(1)) if position else None,
        "speed": float(speed.group(1)) if speed else None,
        "updated": int(updated.group(1)) if updated else None,
    }


def bgstart_text(directory, name):
    path = directory / name
    if not path.is_file() or not path.read_text(errors="replace").strip():
        raise ValueError(f"bgstart evidence missing or empty: {name}")
    return path.read_text(errors="replace")


def check_self_records(device, stage, services, app_uid):
    """No record tied to the target's own attempt may carry an FGS grant surface."""
    for info in allow_infos(services):
        if not self_attempt(info, app_uid):
            continue
        if "BFSL" in (info.get("uidBFSL") or ""):
            raise ValueError(
                f"{device}: {stage} self record carries [BFSL]: {info}")
        if info.get("uidState") and info["uidState"] \
                not in BGSTART_UIDSTATE_BELOW_BFGS:
            raise ValueError(
                f"{device}: {stage} self record uidState "
                f"{info['uidState']!r} above BFGS")
        if info.get("tempAllowListReason") not in ("<null>", "null", None):
            raise ValueError(
                f"{device}: {stage} self record tempAllowListReason "
                f"{info['tempAllowListReason']!r} != <null>")


def check_no_grants(device, stage, services):
    """Every allow record — self or binding — must show a DENIED verdict and no
    capability propagated through a binding (`bindFromPackage`). A non-DENIED
    code means that evaluation would have been allowed."""
    for info in allow_infos(services):
        if info.get("code") not in (None, "DENIED"):
            raise ValueError(
                f"{device}: {stage} allow record grants FGS: {info}")
        bind_from = info.get("bindFromPackage")
        if bind_from not in (None, "null", "<null>"):
            raise ValueError(
                f"{device}: {stage} binding grants FGS from "
                f"{bind_from}: {info}")


def check_not_foreground(device, stage, services):
    """`ServiceRecord.dump` only prints `isForeground=` when the service is
    foreground or a stale foregroundId survives — for a bound service its
    absence is the non-foreground proof; `isForeground=true` is the violation."""
    if "ServiceRecord{" not in services:
        raise ValueError(f"{device}: {stage} dump has no ServiceRecord")
    if "app=ProcessRecord{" not in services:
        raise ValueError(
            f"{device}: {stage} ServiceRecord has no live process (app=null)")
    if "isForeground=true" in services:
        raise ValueError(f"{device}: {stage} service is isForeground=true")


def check_allow_fields(device, stage, services, api):
    """`mAllowStart_*` verdicts print only on newer ServiceRecord dumps; where
    the format emits them (API 36) both fields must read DENIED."""
    if api >= 36:
        for field in ("mAllowStart_noBinding", "mAllowStart_byBindings"):
            if f"{field}=DENIED" not in services:
                raise ValueError(f"{device}: {stage} {field} not DENIED")


def check_bgstart_denial(device, directory, app_uid, api):
    """The denied stage: genuine FSSNAE surfaces, nothing allowed, session paused."""
    uid_text = bgstart_text(directory, "bgstart-uid-state-denied.txt")
    state = uid_state(uid_text)
    if state is None or state < BGSTART_PROCSTATE_SERVICE:
        raise ValueError(f"{device}: denied uid-state {state!r} above SERVICE")
    services = bgstart_text(directory, "bgstart-services-denied.txt")
    processes = bgstart_text(directory, "bgstart-processes-denied.txt")
    check_not_foreground(device, "denied", services)
    check_allow_fields(device, "denied", services, api)
    temp_values = TEMP_ALLOW_RE.findall(services + processes)
    if not temp_values:
        raise ValueError(
            f"{device}: no tempAllowListReason surface to prove no allowlist")
    if any(v not in ("<null>", "null") for v in temp_values):
        raise ValueError(f"{device}: temp allowlist granted: {temp_values}")
    check_self_records(device, "denied", services, app_uid)
    check_no_grants(device, "denied", services)
    # The denial must be a real evaluation: at least one of the target's own
    # startForeground attempts recorded a DENIED verdict.
    if not any(self_attempt(info, app_uid) and info.get("code") == "DENIED"
               for info in allow_infos(services)):
        raise ValueError(
            f"{device}: no DENIED self attempt recorded under denial")
    notification = bgstart_text(directory, "bgstart-notification-denied.txt")
    if not has_notification(notification, BMGR_TARGET, BGSTART_RESUME_ID,
                            BGSTART_RESUME_CHANNEL):
        raise ValueError(f"{device}: recovery notification 4001 absent")
    audio = bgstart_text(directory, "bgstart-audio-denied.txt")
    if apc_started(audio, app_uid):
        raise ValueError(f"{device}: audio active for uid {app_uid} under denial")
    sample = playback_sample(
        bgstart_text(directory, "bgstart-media-session-denied.txt"), BMGR_TARGET)
    if sample is None:
        raise ValueError(f"{device}: no PlaybackState for the target session")
    if sample["state"] != 2:
        raise ValueError(f"{device}: denied session state {sample['state']} != PAUSED")


def check_bgstart_guarded(device, directory, stage, api):
    """A guarded stage (before/card): bound, live, demoted, nothing granting FGS."""
    uid_text = bgstart_text(directory, f"bgstart-uid-state-{stage}.txt")
    state = uid_state(uid_text)
    if state is None or state < BGSTART_PROCSTATE_SERVICE:
        raise ValueError(f"{device}: {stage} uid-state {state!r} above SERVICE")
    services = bgstart_text(directory, f"bgstart-services-{stage}.txt")
    check_not_foreground(device, stage, services)
    check_allow_fields(device, stage, services, api)
    check_no_grants(device, stage, services)
    return services


def check_bgstart_before(device, directory, api, app_uid):
    """The pre-tap stage: a plain background service with no allow source."""
    services = check_bgstart_guarded(device, directory, "before", api)
    check_self_records(device, "before", services, app_uid)
    cold = bgstart_text(directory, "uid-state-cold.txt")
    cold_state = uid_state(cold)
    if cold_state is None or cold_state < BGSTART_PROCSTATE_SERVICE:
        raise ValueError(f"{device}: cold uid-state {cold_state!r} above SERVICE")


def check_bgstart_card(device, directory, api, app_uid):
    """The card stage — the decisive probe: once the SystemUI card rendered (and
    bound, if it binds), the target must still be demoted with no FGS grant."""
    services = check_bgstart_guarded(device, directory, "card", api)
    check_self_records(device, "card", services, app_uid)


def check_bgstart_settle(device, directory):
    """The >=15s continuous demoted-and-settled window the driver sampled.
    Samples before the window (the uid still settling after the bind) are
    legitimate; the longest run of demoted samples must span >=15s."""
    settle = bgstart_text(directory, "bgstart-settle.txt")
    rows = [(int(t), int(s), fg) for t, s, fg in
            re.findall(r"^t=(\d+) state=(-?\d+) fg=(\S+)\s*$", settle, re.M)]
    if len(rows) < 2:
        raise ValueError(f"{device}: settle window has {len(rows)} samples")
    best = 0
    run_start = None
    for t, state, fg in rows:
        if state < BGSTART_PROCSTATE_SERVICE or fg == "true":
            run_start = None
            continue
        if run_start is None:
            run_start = t
        best = max(best, t - run_start)
    if best < BGSTART_SETTLE_MS:
        raise ValueError(
            f"{device}: demoted settle window {best}ms < {BGSTART_SETTLE_MS}ms")


def check_bgstart_resumed(device, directory, app_uid):
    """The resumed stage: real playback — foreground 4000, audio, position, 4001 gone."""
    services = bgstart_text(directory, "bgstart-services-resumed.txt")
    if "isForeground=true" not in services:
        raise ValueError(f"{device}: resumed service not isForeground=true")
    notification = bgstart_text(directory, "bgstart-notification-resumed.txt")
    if not has_notification(notification, BMGR_TARGET, BGSTART_PLAYBACK_ID,
                            BGSTART_PLAYBACK_CHANNEL):
        raise ValueError(f"{device}: playback notification 4000 absent")
    if has_notification_id(notification, BMGR_TARGET, BGSTART_RESUME_ID):
        raise ValueError(f"{device}: recovery notification 4001 not cancelled")
    audio = bgstart_text(directory, "bgstart-audio-resumed.txt")
    if not apc_started(audio, app_uid):
        raise ValueError(f"{device}: no active playback for uid {app_uid}")
    sample = playback_sample(
        bgstart_text(directory, "bgstart-media-session-resumed.txt"), BMGR_TARGET)
    if sample is None or sample["state"] != 3:
        raise ValueError(f"{device}: resumed session not PLAYING")
    positions = bgstart_text(directory, "bgstart-positions.txt")
    lives = []
    for match in re.finditer(
            r"^sample=\d+ elapsed=(\d+) state=(\S+) position=(\S+) "
            r"speed=(\S+) updated=(\S+) live=(\S+)$", positions, re.M):
        elapsed, state, position, speed, updated, live = match.groups()
        if state != "3":
            raise ValueError(f"{device}: position sample not PLAYING: {match[0]}")
        missing = ("None", "null")
        if position in missing or speed in missing:
            raise ValueError(f"{device}: unparseable position sample")
        position, speed = int(position), float(speed)
        computed = position + speed * (int(elapsed) - int(updated)) \
            if updated not in missing else position
        if live not in missing and abs(int(live) - computed) > 1:
            raise ValueError(f"{device}: recorded live != computed position")
        lives.append(computed)
    if len(lives) != 2 or lives[1] - lives[0] <= BGSTART_POSITION_ADVANCE_MS:
        raise ValueError(
            f"{device}: position advance {lives} <= {BGSTART_POSITION_ADVANCE_MS}ms")


def check_bgstart_device(root, device, api):
    directory = root / device
    if not directory.is_dir():
        raise ValueError(f"{device}: evidence directory missing")
    run = json.loads((directory / "run.json").read_text())
    if run.get("schema") != "bgstart-evidence/v1" or run.get("api") != api:
        raise ValueError(f"{device}: run.json schema/api mismatch")
    if not run.get("fingerprint") or run.get("abi") != "x86_64" \
            or not run.get("avd"):
        raise ValueError(f"{device}: emulator identity evidence missing")
    if not NONCE.fullmatch(run.get("nonce", "")):
        raise ValueError(f"{device}: run nonce invalid")
    nonce = run["nonce"]
    if run.get("apkSha256") == run.get("driverApkSha256") \
            or not SHA256.fullmatch(run.get("apkSha256", "")) \
            or not SHA256.fullmatch(run.get("driverApkSha256", "")) \
            or not SHA256.fullmatch(run.get("actorApkSha256", "")) \
            or not SHA256.fullmatch(run.get("triggerApkSha256", "")):
        raise ValueError(f"{device}: apk/driver/actor/trigger sha256 evidence missing or shared")
    if run.get("driverPackage") == BMGR_TARGET or not run.get("driverPackage") \
            or not run.get("driverRunner"):
        raise ValueError(f"{device}: driver identity evidence missing")
    if not isinstance(run.get("appUid"), int) or run["appUid"] <= 0:
        raise ValueError(f"{device}: appUid evidence missing")
    app_uid = run["appUid"]
    instruments = (directory / "instrumentations.txt").read_text(errors="replace") \
        if (directory / "instrumentations.txt").is_file() else ""
    if f"instrumentation:{run['driverPackage']}/{run['driverRunner']} " \
            f"(target={run['driverPackage']})" not in instruments \
            or f"(target={BMGR_TARGET})" in instruments:
        raise ValueError(f"{device}: driver instrumentation not self-targeting")
    if run.get("cleanupStatus") != "ok":
        raise ValueError(
            f"{device}: cleanup status is {run.get('cleanupStatus')!r}, not 'ok'")
    selectors = run.get("selectors")
    if not isinstance(selectors, dict) \
            or any(selectors.get(n) != "pass" for n in BGSTART_SELECTORS):
        missing = [n for n in BGSTART_SELECTORS if (selectors or {}).get(n) != "pass"]
        raise ValueError(f"{device}: selectors not all 'pass' ({', '.join(missing)})")

    # The bgseed -> bgstart sidecar binds the restored session's loopback enclosure.
    sidecar = json.loads(bgstart_text(directory, "bgstart.expected.json"))
    if not driver_v1(sidecar) or sidecar.get("nonce") != nonce \
            or sidecar.get("api") != api \
            or not SHA256.fullmatch(sidecar.get("seedSha256", "")) \
            or not isinstance(sidecar.get("seedBytes"), int) \
            or not isinstance(sidecar.get("port"), int):
        raise ValueError(f"{device}: bgstart sidecar identity malformed")
    if not re.fullmatch(r"http://127\.0\.0\.1:\d+/ep2\.mp3",
                        sidecar.get("enclosureUrl", "")) \
            or not sidecar["enclosureUrl"].endswith(f":{sidecar['port']}/ep2.mp3"):
        raise ValueError(f"{device}: sidecar enclosureUrl/port mismatch")
    fixture = sidecar.get("fixture")
    if not isinstance(fixture, dict) \
            or not (fixture.get("session") or {}).get("current") \
            or not fixture.get("queue"):
        raise ValueError(f"{device}: sidecar fixture lacks a durable paused session")

    for phase, name in BGSTART_LOGS.items():
        path = directory / name
        if not path.is_file():
            raise ValueError(f"{device}: missing {name}")
        check_instrument_log(device, phase, nonce,
                             path.read_text(errors="replace"),
                             BGSTART_CLASS, BGSTART_METHODS[phase])
        status_path = directory / f"driver-status-{phase}.json"
        if not status_path.is_file():
            raise ValueError(f"{device}: missing driver-status-{phase}.json")
        check_driver_status(device, phase, nonce, api, run["driverPackage"],
                            status_path, BGSTART_STEPS[phase])

    # Exactly one trigger surface may have fired — never both, never neither.
    status = json.loads((directory / "driver-status-bgstart.json").read_text())
    fired = {s.get("name") for s in status.get("steps", [])} & BGSTART_PLAY_STEPS
    if len(fired) != 1:
        raise ValueError(
            f"{device}: expected exactly one play step, got {sorted(fired)}")

    # The trigger's own proof: real notification-listener trust and the restored
    # session visible through the framework controller path, plus per-stage
    # uid/listener dumps (the "does listener membership elevate" record).
    bgstart_text(directory, "bgstart-listener.txt")
    trigger_status = bgstart_text(directory, "bgstart-trigger-status.txt")
    if "trusted=1" not in trigger_status or "target=1" not in trigger_status:
        raise ValueError(
            f"{device}: trigger was not a trusted controller seeing the "
            f"session: {trigger_status.strip()!r}")
    for stage in ("card", "denied"):
        evidence = bgstart_text(directory, f"bgstart-trigger-{stage}.txt")
        if "enabledListeners=" not in evidence \
                or "BgTriggerListenerService" not in evidence:
            raise ValueError(
                f"{device}: {stage} trigger evidence lacks listener membership")

    check_bgstart_settle(device, directory)
    check_bgstart_before(device, directory, api, app_uid)
    check_bgstart_card(device, directory, api, app_uid)
    check_bgstart_denial(device, directory, app_uid, api)
    check_bgstart_resumed(device, directory, app_uid)
    print(f"{device}: PASS bgstart external gate "
          f"(denied background start -> SystemUI Resume -> playback)")


def check_bgstart(root):
    runs = {}
    for device, api in BGSTART_DEVICES.items():
        check_bgstart_device(root, device, api)
        runs[device] = json.loads((root / device / "run.json").read_text())
    a, b = runs["api31"], runs["api36"]
    for field in ("sourceSha", "apkSha256", "driverApkSha256",
                  "driverPackage", "driverRunner"):
        if not a.get(field) or a[field] != b.get(field):
            raise ValueError(f"bgstart: {field} differs across legs")
    print("bgstart: PASS api31 + api36")


def run_bmgr_pull(directory):
    """The pre-cleanup pull gate binds the closed facts, never run.json — the
    final run record is written only by cleanup(), so nothing in-progress can
    stand in for it."""
    directory = Path(directory)
    facts = {}
    for line in (directory / "facts.txt").read_text().splitlines():
        key, _, value = line.partition("=")
        facts[key] = value
    nonce = facts.get("nonce", "")
    if not NONCE.fullmatch(nonce):
        raise ValueError(f"bmgr-pull: facts.txt nonce {nonce!r} invalid")
    exp = json.loads((directory / "expectation.json").read_text())
    if exp.get("nonce") != nonce:
        raise ValueError("bmgr-pull: expectation nonce != facts nonce")
    run = {"nonce": nonce, "device": facts.get("device") or "pull"}
    check_pull(run["device"], directory, exp, run)
    print("bmgr-pull: PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, nargs="?",
                        help="managedDevice/debug result directory")
    parser.add_argument("--bmgr", metavar="ROOT", type=Path,
                        help="strict M3 bmgr evidence root (api31/api36 dirs)")
    parser.add_argument("--bmgr-tar", nargs=2, metavar=("TAR", "EXPECTATION_JSON"),
                        help="verify one transport TAR and write TAR.members")
    parser.add_argument("--bmgr-encoff", nargs=2, metavar=("LOG", "PACKAGE"),
                        help="strict enc-off backupnow verdict parser")
    parser.add_argument("--bmgr-pb", nargs=2, metavar=("PB_FILE", "KEY"),
                        help="print one decoded preferences_pb value (or nothing)")
    parser.add_argument("--bmgr-pull", metavar="EVIDENCE_DIR", type=Path,
                         help="validate the positive restore capture before the empty control")
    parser.add_argument("--bmgr-empty", metavar="EVIDENCE_DIR", type=Path,
                        help="validate the subsequent empty-dataset capture")
    parser.add_argument("--bmgr-prelaunch", nargs=3, metavar=("EVIDENCE_DIR", "DEVICE", "NONCE"),
                        help="validate the stopped install/restore window before normal launch")
    parser.add_argument("--bgstart", metavar="ROOT", type=Path,
                        help="strict M4 P6b bgstart evidence root (api31/api36 dirs)")
    args = parser.parse_args()
    try:
        if args.bmgr is not None:
            check_bmgr(args.bmgr)
        elif args.bgstart is not None:
            check_bgstart(args.bgstart)
        elif args.bmgr_encoff is not None:
            run_bmgr_encoff(*args.bmgr_encoff)
        elif args.bmgr_tar is not None:
            run_bmgr_tar(*args.bmgr_tar)
        elif args.bmgr_pb is not None:
            run_bmgr_pb(*args.bmgr_pb)
        elif args.bmgr_pull is not None:
            run_bmgr_pull(args.bmgr_pull)
        elif args.bmgr_empty is not None:
            check_empty(args.bmgr_empty.name, args.bmgr_empty)
            print("bmgr-empty: PASS")
        elif args.bmgr_prelaunch is not None:
            directory, device, nonce = args.bmgr_prelaunch
            check_prelaunch(device, Path(directory), nonce)
            print("bmgr-prelaunch: PASS restricted default-agent window")
        else:
            if args.results is None:
                parser.error(
                    "results is required without a --bmgr* mode")
            for device in DEVICES:
                check_device(args.results, device)
    except (OSError, ValueError, ET.ParseError, json.JSONDecodeError,
            tarfile.TarError, KeyError, UnicodeDecodeError) as error:
        print(f"first-launch-restore: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
