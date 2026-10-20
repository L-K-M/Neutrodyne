#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""SDK-only synthetic build, disposable hosted legacy-transport probe, offline verifier.

Two closed profiles measure the stock API 28/29 LocalTransport refusal: API29's
PREFLIGHT_REJECTED needs the named ERROR_PREFLIGHT event and package rejection;
API28's bmgr has no --monitor, so PACKAGE_REJECTED needs the closed bare-arg
output (preamble, @pm@ ack, named rejection, overall Success) under the
download-cache-bound dataset. Both need a verified control, fresh path and
missing/zero payload. A refusal is never a successful encrypted backup, never
an unbounded claim, never M3 acceptance. Never Gradle; probe refuses local hosts.
"""
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
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/bmgr-api29-probe"
PREFIX = "ch.lkmc.neutrodyne.transportprobe."
VARIANTS = {"control": "", "cse": "clientSideEncryption", "d2d": "deviceToDeviceTransfer"}
PAYLOAD = b"transport-probe-v1\n"
EXPECTED = {"files/allowed/payload": PAYLOAD, "files/other/excluded": b"excluded-files-canary\n",
            "no_backup/excluded": b"excluded-no-backup-canary\n"}
LIMIT = 4 * 1024 * 1024
MIN_SDK = "28"
# Closed legacy profiles: API28's transport is framework-owned (no standalone
# service/APK guaranteed) with a DOWNLOAD_CACHE-bound dataset and bare bmgr argv
# (no --monitor); API29's is the installed package under app-private files.
CACHE_ROOTS = ("/cache", "/data/cache")
PROFILES = {
    "api28": {
        "env": "BMGR_DISPOSABLE_API28", "sdk": "28", "avd": "nd-bmgr-api28-probe",
        "transport": "android/com.android.internal.backup.LocalTransport",
        "dataset": None, "suffix": "/backup/1/_full/",
        "image": "system-images/android-28/default/x86_64",
        "service": None, "framework": "/system/framework/framework.jar",
        "backupnow": ["bmgr", "backupnow"],
    },
    "api29": {
        "env": "BMGR_DISPOSABLE_API29", "sdk": "29", "avd": "nd-bmgr-api29-probe",
        "transport": "com.android.localtransport/.LocalTransport",
        "dataset": "/data/user/0/com.android.localtransport/files/1/_full/",
        "image": "system-images/android-29/default/x86_64",
        "service": "com.android.localtransport/.LocalTransportService", "framework": None,
        "backupnow": ["bmgr", "backupnow", "--monitor"],
    },
}
REJECTION = "Transport rejected package because it wasn't able to process it at the time"


def cache_root(text, code):
    """A failed or empty capture cannot establish a default storage location."""
    lines = text.splitlines()
    if code != 0 or len(lines) != 1 or lines[0] not in CACHE_ROOTS:
        raise ValueError("Unknown/ambiguous download cache root")
    return lines[0]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def fresh_build():
    """Keep previous owned receipts, but never mix them into the new active run."""
    OUT.mkdir(parents=True, exist_ok=True)
    previous = [p for p in OUT.iterdir() if p.name != "history"]
    if previous:
        history = OUT / "history" / str(time.time_ns())
        history.mkdir(parents=True)
        for path in previous:
            path.rename(history / path.name)
    save(OUT / "build.json", {"status": "RUNNING"})


def command(folder, name, args, timeout=60, required=True):
    folder.mkdir(parents=True, exist_ok=True)
    try:
        p = subprocess.run([str(a) for a in args], capture_output=True, timeout=timeout)
        code, stdout, stderr = p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as error:
        code, stdout, stderr = 124, error.stdout or b"", error.stderr or b""
    (folder / (name + ".out")).write_bytes(stdout)
    (folder / (name + ".err")).write_bytes(stderr)
    save(folder / (name + ".json"), {"argv": [str(a) for a in args], "exit": code})
    if required and code:
        raise RuntimeError(f"{name}: exit {code}; see {folder}")
    return stdout.decode("utf-8", "replace").strip()


def prepare_fixture(call, variant):
    # NoDisplay finishes in onCreate; readiness is its files, not am start -W.
    call("launch-" + variant, "shell", "am", "start", "-n",
         PREFIX + variant + "/" + PREFIX[:-1] + ".ProbeActivity", timeout=15)
    deadline, attempt = time.monotonic() + 30, 0
    while True:
        ready = True
        for path, data in EXPECTED.items():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("Fixture preparation timed out: " + variant)
            receipt = variant + "-" + path.replace("/", "-") + (f"-retry-{attempt}" if attempt else "")
            call(receipt, "exec-out", "cat", "/data/user/0/" + PREFIX + variant + "/" + path,
                 required=False, timeout=min(5, remaining))
            status = json.loads((OUT / (receipt + ".json")).read_text())
            if status["exit"] != 0 or (OUT / (receipt + ".out")).read_bytes() != data:
                ready = False
        if ready and time.monotonic() <= deadline:
            save(OUT / ("prepared-" + variant + ".json"), {"package": PREFIX + variant,
                 "files": {path: digest(data) for path, data in EXPECTED.items()}})
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError("Fixture preparation timed out: " + variant)
        time.sleep(min(0.25, remaining))
        attempt += 1


def build():
    sdk = Path(os.environ["ANDROID_HOME"])
    bt, android = sdk / "build-tools/36.0.0", sdk / "platforms/android-37.0/android.jar"
    fixture, work = ROOT / "scripts/ci/transportprobe", OUT / "build"
    work.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(OUT).free < 10 * 1024**3:
        raise RuntimeError("Need 10 GiB free before building")
    tools = {n: bt / n for n in ("aapt2", "d8", "zipalign", "apksigner")}
    tools["javac"] = Path(os.environ["JAVA_HOME"]) / "bin/javac"
    for tool in [*tools.values(), android]:
        if not tool.is_file():
            raise RuntimeError(f"Missing installed SDK/JDK tool: {tool}")
    inputs = {"source": command(work, "source", ["git", "-C", str(ROOT), "rev-parse", "HEAD"]),
              "implementation": digest(Path(__file__).read_bytes()),
              "tools": {n: {"path": str(p), "sha256": digest(p.read_bytes())} for n, p in tools.items()},
              "androidJar": digest(android.read_bytes()), "fixtures": {}}
    command(work, "java-version", [tools["javac"], "-version"])
    command(work, "aapt-version", [tools["aapt2"], "version"])
    command(work, "d8-version", [tools["d8"], "--version"])
    command(work, "signer-version", [tools["apksigner"], "version"])
    shutil.copyfile(bt / "source.properties", work / "build-tools.properties")
    inputs["toolJars"] = {p.name: digest(p.read_bytes()) for p in (bt / "lib").glob("*.jar")}
    inputs["templates"] = {p.name: digest(p.read_bytes()) for p in fixture.iterdir()}
    classes, dex = work / "classes", work / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    command(work, "javac", [tools["javac"], "-source", "8", "-target", "8", "-bootclasspath", android,
                            "-d", classes, fixture / "ProbeActivity.java"])
    command(work, "dex", [tools["d8"], "--min-api", MIN_SDK, "--lib", android, "--output", dex,
                          *classes.rglob("*.class")])
    for variant, flag in VARIANTS.items():
        part = work / variant
        (part / "res/xml").mkdir(parents=True, exist_ok=True)
        manifest = part / "AndroidManifest.xml"
        manifest.write_text((fixture / manifest.name).read_text().replace("VARIANT", variant))
        rules = (fixture / "rules.xml").read_text().replace("FLAGS", f'requireFlags="{flag}"' if flag else "")
        (part / "res/xml/rules.xml").write_text(rules)
        command(part, "resources", [tools["aapt2"], "compile", "--dir", part / "res", "-o", part / "res.zip"])
        unsigned, aligned, apk = part / "unsigned.apk", part / "aligned.apk", OUT / (variant + ".apk")
        command(part, "link", [tools["aapt2"], "link", "-I", android, "--manifest", manifest,
                               "-o", unsigned, part / "res.zip"])
        with zipfile.ZipFile(unsigned, "a") as archive:
            archive.write(dex / "classes.dex", "classes.dex")
        command(part, "align", [tools["zipalign"], "-f", "4", unsigned, aligned])
        command(part, "sign", [tools["apksigner"], "sign", "--ks", ROOT / "signing/neutrodyne-public.keystore",
                               "--ks-key-alias", "neutrodyne", "--ks-pass", "pass:neutrodyne",
                               "--key-pass", "pass:neutrodyne", "--out", apk, aligned])
        cert = command(part, "certificate", [tools["apksigner"], "verify", "--verbose", "--print-certs", apk])
        command(part, "alignment", [tools["zipalign"], "-c", "4", apk])
        badging = command(part, "badging", [tools["aapt2"], "dump", "badging", apk])
        xml = command(part, "packaged-rules", [tools["aapt2"], "dump", "xmltree", "--file", "res/xml/rules.xml", apk])
        app = command(part, "packaged-manifest", [tools["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
        if f"name='{PREFIX + variant}'" not in badging or "application-debuggable" in badging:
            raise RuntimeError("Wrong packaged fixture identity/debug flag")
        if '"allowed/payload"' not in xml or ("requireFlags" in xml) != bool(flag):
            raise RuntimeError("Wrong packaged include rules")
        if flag and f'"{flag}"' not in xml.split("requireFlags", 1)[1]:
            raise RuntimeError("Wrong packaged capability bit")
        if not all(re.search(pattern, app) for pattern in (
                r":debuggable\([^\n]+\)=false", r":allowBackup\([^\n]+\)=true",
                r":fullBackupContent\([^\n]+\)=@", r":minSdkVersion\([^\n]+\)=" + MIN_SDK,
                r":targetSdkVersion\([^\n]+\)=29")):
            raise RuntimeError("Wrong packaged backup/target attributes")
        version = re.search(r"versionCode='(\d+)'", badging)
        inputs["fixtures"][variant] = {"package": PREFIX + variant, "apkSha256": digest(apk.read_bytes()),
            "certificate": re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", cert).group(1), "flag": flag,
            "version": version.group(1) if version else "1"}
    save(OUT / "inputs.json", inputs)
    save(OUT / "build.json", {"status": "COMPLETE", "inputsSha256": digest((OUT / "inputs.json").read_bytes())})


def verify_archive(raw, package, certificate, platform, version):
    """No extraction; accept only platform manifest plus the single exact file (or gated absence)."""
    if not 0 < len(raw) <= LIMIT or not re.fullmatch(re.escape(PREFIX) + r"(control|cse|d2d)", package):
        raise ValueError("Invalid archive size/package")
    base = "apps/" + package + "/"
    expected = {base + "_manifest", base + "f/allowed/payload"}
    seen, end = set(), 0
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for entry in archive:
            if (entry.name not in expected or entry.name in seen or entry.type not in (b"0", b"\0")
                    or entry.pax_headers or entry.offset_data - entry.offset != 512):
                raise ValueError("Unexpected/duplicate/path/link member: " + entry.name)
            if not 0 <= entry.size <= 16384:
                raise ValueError("Oversize member")
            seen.add(entry.name)
            data = archive.extractfile(entry).read()
            if entry.name.endswith("/_manifest"):
                lines = data.decode("utf-8").splitlines()
                if (len(lines) != 8 or lines[:4] != ["1", package, version, platform]
                        or lines[5:7] != ["0", "1"]):
                    raise ValueError("Wrong platform manifest")
                if digest(bytes.fromhex(lines[7])) != certificate:
                    raise ValueError("Wrong archive signer")
            elif data != PAYLOAD:
                raise ValueError("Payload bytes/hash mismatch")
            end = max(end, entry.offset_data + (entry.size + 511) // 512 * 512)
            if end > len(raw) or len(data) != entry.size:
                raise ValueError("Truncated member")
    if base + "_manifest" not in seen or any(raw[end:]):
        raise ValueError("Missing manifest or trailing data")
    return "PAYLOAD" if base + "f/allowed/payload" in seen else "EMPTY"


def payload_absent(folder):
    size = json.loads((folder / "size.json").read_text())
    missing = size["exit"] != 0 and "No such file" in (folder / "size.err").read_text()
    empty = size["exit"] == 0 and (folder / "size.out").read_text().strip() == "0"
    return not (folder / "transport.tar").exists() and (missing or empty)


def preflight_rejected(folder, package, version, text):
    """Only the observed refusal: fresh path, named event, rejection, Success."""
    event = "=> Event{BACKUP_MANAGER_POLICY / ERROR_PREFLIGHT : package = " + package + "(v" + version + ")}"
    result = "Package " + package + " with result: "
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return (payload_absent(folder)
            and [line for line in lines if line.startswith("=> Event{")] == [event]
            and [line for line in lines if line.startswith("Backup finished with result: ")]
            == ["Backup finished with result: Success"]
            and [line for line in lines if line.startswith("Package ")]
            == ["Package @pm@ with result: Success", result + REJECTION])


def api28_result(text, package):
    """API28's closed bare-arg output → (named result, finish) or None."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    progress = re.compile(re.escape("Package " + package + " with progress: ") + r"\d+/\d+")
    if (len(lines) < 4 or not all(progress.fullmatch(line) for line in lines[2:-2])
            or lines[:2] != ["Running incremental backup for 1 requested packages.",
                             "Package @pm@ with result: Success"]
            or not lines[-2].startswith("Package " + package + " with result: ")
            or not lines[-1].startswith("Backup finished with result: ")):
        return None
    return (lines[-2].removeprefix("Package " + package + " with result: "),
            lines[-1].removeprefix("Backup finished with result: "))


def measurement(folder, package, certificate, platform, version, control, positive, profile):
    try:
        fresh = json.loads((folder / "fresh.json").read_text())
        status = json.loads((folder / "backup.json").read_text())
        text = (folder / "backup.out").read_text()
        if fresh["exit"] != 0 or status["exit"] != 0:
            return "ERROR"
        if profile == "api28":
            shape = api28_result(text, package)
            if shape is None or shape[1] != "Success":
                return "ERROR"
            if shape[0] == REJECTION:
                if not payload_absent(folder) or package == PREFIX + "control":
                    return "ERROR"
                return "PACKAGE_REJECTED" if control else "UNAVAILABLE"
        elif preflight_rejected(folder, package, version, text):
            if package == PREFIX + "control":
                return "ERROR"
            return "PREFLIGHT_REJECTED" if control else "UNAVAILABLE"
        if any(re.findall(r"(?m)^" + re.escape(line) + r"([^\r\n]+)", text) != ["Success"]
                for line in (f"Package {package} with result: ", "Backup finished with result: ")):
            return "ERROR"
        if (folder / "transport.tar").stat().st_size > LIMIT:
            return "ERROR"
        state = verify_archive((folder / "transport.tar").read_bytes(), package, certificate, platform, version)
        if not control:
            return "UNAVAILABLE"
        if package == PREFIX + "control":
            return "CONTROL" if state == "PAYLOAD" else "ERROR"
        return ("OBSERVED" if positive else "ERROR") if state == "PAYLOAD" else ("BLOCKED" if positive else "EXCLUDED")
    except FileNotFoundError:
        return "UNAVAILABLE"
    except (ValueError, KeyError, tarfile.TarError, UnicodeError):
        return "ERROR"


def collect_cases(profile):
    inputs = json.loads((OUT / "inputs.json").read_text())
    platform = PROFILES[profile]["sdk"]
    observations = []
    for phase in ("cse", "d2d"):
        control = False
        for variant in VARIANTS:
            item = inputs["fixtures"][variant]
            folder = OUT / phase / variant
            verdict = measurement(folder, item["package"], item["certificate"], platform,
                                  item["version"], variant == "control" or control,
                                  variant == phase, profile)
            if variant == "control":
                control = verdict == "CONTROL"
            observations.append({"phase": phase, "variant": variant, "status": verdict,
                                 "evidence": str(folder.relative_to(OUT))})
            archive = folder / "transport.tar"
            if archive.exists() and archive.stat().st_size <= LIMIT:
                observations[-1]["archiveSha256"] = digest(archive.read_bytes())
    complete = all(o["status"] not in ("ERROR", "UNAVAILABLE") for o in observations)
    # OBSERVED = a protected payload crossed; REJECTED = controlled refusals or
    # gated absences — never an unsupported claim, never acceptance.
    capability = "OBSERVED" if any(o["status"] == "OBSERVED" for o in observations) else "REJECTED"
    return {"source": inputs["source"], "inputs": "inputs.json", "image": "image", "profile": profile,
            "collection": "COMPLETE" if complete else "ERROR",
            "capability": capability if complete else "UNAVAILABLE",
            "acceptance": "NOT_TESTED", "cases": observations}


def cleanup_failure():
    keys = ("backup_enabled", "backup_auto_restore", "backup_local_transport_parameters")
    names = ["cleanup-init", "restore-transport", "restore-enabled", "restore-autorestore", "restore-selection"]
    names += ["cleanup-" + PREFIX + v for v in VARIANTS] + ["cleanup-empty-" + v for v in VARIANTS]
    names += [prefix + key for key in keys for prefix in ("restore-", "restored-")]
    try:
        for name in names:
            if json.loads((OUT / (name + ".json")).read_text())["exit"] != 0:
                return name
        old = json.loads((OUT / "original-settings.json").read_text())
        if not all((OUT / ("cleanup-" + PREFIX + v + ".out")).read_text().strip() == "Success" for v in VARIANTS):
            return "uninstall verdict"
        if not all((OUT / ("restored-" + key + ".out")).read_text().strip() == old[key] for key in keys):
            return "settings restoration"
        if "* " + old["transport"] not in [s.strip() for s in (OUT / "restore-selection.out").read_text().splitlines()]:
            return "transport restoration"
    except (OSError, ValueError, KeyError):
        return "missing/invalid cleanup receipts"
    return None


def finish_run(error, cleanup, profile):
    try:
        result = collect_cases(profile)
        result["inputsSha256"] = digest((OUT / "inputs.json").read_bytes())
    except (OSError, ValueError, KeyError) as failure:
        result = {"source": None, "profile": profile, "collection": "ERROR",
                  "capability": "UNAVAILABLE", "cases": []}
        error = error or str(failure)
    result.update(cleanup=cleanup, acceptance="NOT_TESTED")
    if error or cleanup != "COMPLETE":
        result.update(collection="ERROR", capability="UNAVAILABLE", error=error or "Cleanup incomplete")
    save(OUT / "result.json", result)
    return result


def receipt_problem(result):
    """First missing/incorrect receipt the new conclusion relies on, or None."""
    profile = PROFILES.get(result.get("profile"))
    inputs = json.loads((OUT / "inputs.json").read_text())
    if profile is None or result.get("source") != inputs.get("source"):
        return "profile/source"
    def out(name):
        try:
            code = json.loads((OUT / (name + ".json")).read_text())["exit"]
            return (OUT / (name + ".out")).read_text() if code == 0 else None
        except (OSError, ValueError, KeyError):
            return None
    for name in ["devices", "avd-name", "fingerprint", "root", "root-wait", "root-id", "services",
                 "ro.kernel.qemu", "ro.build.version.sdk", "ro.product.cpu.abi", "transports",
                 "selected", "enable", "autorestore", "select", "probe-source", "cse-parameters",
                 "cse-init", "cse-before", "d2d-parameters", "d2d-init", "d2d-before",
                 "final-backup", "logcat"]:
        if out(name) is None:
            return name
    if profile["service"] and out("final-service") is None:
        return "final-service"
    checks = {
        "avd-name": lambda t: t.splitlines() and t.splitlines()[0] == profile["avd"],
        "ro.kernel.qemu": lambda t: t == "1", "ro.build.version.sdk": lambda t: t == profile["sdk"],
        "ro.product.cpu.abi": lambda t: t == "x86_64", "root-id": lambda t: t.startswith("uid=0"),
        "probe-source": lambda t: t == inputs["source"],
        "transports": lambda t: profile["transport"] in
                                [line.strip().removeprefix("* ") for line in t.splitlines()],
        "selected": lambda t: "* " + profile["transport"] in [line.strip() for line in t.splitlines()],
    }
    for name, valid in checks.items():
        if not valid((OUT / (name + ".out")).read_text().strip()):
            return name
    expected = {"files": {path: digest(data) for path, data in EXPECTED.items()}}
    for variant in VARIANTS:
        try:
            json.loads((OUT / ("existing-" + variant + ".json")).read_text())["exit"]
            if "package:" in (OUT / ("existing-" + variant + ".out")).read_text():
                return "existing-" + variant
            for name in ("install-" + variant, "launch-" + variant):
                if out(name) is None:
                    return name
            recorded = json.loads((OUT / ("prepared-" + variant + ".json")).read_text())
            if recorded != dict(expected, package=PREFIX + variant) or \
                    digest((OUT / (variant + ".apk")).read_bytes()) != \
                    inputs["fixtures"][variant]["apkSha256"]:
                return "prepared-" + variant
        except (OSError, ValueError, KeyError):
            return "fixture-" + variant
    for key in ("backup_enabled", "backup_auto_restore", "backup_local_transport_parameters", "transport"):
        if key not in json.loads((OUT / "original-settings.json").read_text()):
            return "original-settings"
    if not (OUT / "image/source.properties").is_file() or not (OUT / "image/package.xml").is_file():
        return "image"
    apk = OUT / "image/LocalTransport.apk"
    if apk.is_file():
        try:
            saved = json.loads((OUT / "image/transport-apk.json").read_text())
            if saved["sha256"] != digest(apk.read_bytes()) or out("transport-apk-pull") is None:
                return "transport-apk"
        except (OSError, ValueError, KeyError):
            return "transport-apk"
    elif profile["service"]:
        return "transport-apk"
    if profile["framework"]:
        for name in ("transport-framework-size", "transport-framework-sha"):
            if out(name) is None:
                return name
        try:
            saved = json.loads((OUT / "image/framework.json").read_text())
            if (saved["path"] != profile["framework"] or not saved["size"] > 0
                    or not re.fullmatch(r"[0-9a-f]{64}", saved["sha256"])):
                return "framework"
        except (OSError, ValueError, KeyError):
            return "framework"
    dataset = profile["dataset"]
    if dataset is None:
        try:
            capture = json.loads((OUT / "download-cache.json").read_text())
            argv = capture.get("argv")
            if not isinstance(argv, list) or argv[-3:] != ["shell", "printenv", "DOWNLOAD_CACHE"]:
                return "download-cache"
            dataset = cache_root((OUT / "download-cache.out").read_text(), capture["exit"]) + profile["suffix"]
        except (OSError, ValueError, KeyError):
            return "download-cache"
    for phase in ("cse", "d2d"):
        for variant in VARIANTS:
            part, path = OUT / phase / variant, dataset + PREFIX + variant
            for name, tail in (("fresh", ["shell", "test", "!", "-e", path]),
                               ("backup", ["shell", *profile["backupnow"], PREFIX + variant]),
                               ("size", ["shell", "stat", "-c", "%s", path])):
                try:
                    argv = json.loads((part / (name + ".json")).read_text())["argv"][-len(tail):]
                except (OSError, ValueError, KeyError):
                    argv = []
                if argv != tail:
                    return phase + "/" + variant + "/" + name
            if (part / "transport.tar").is_file():
                try:
                    pull = json.loads((part / "transport.json").read_text())
                    argv = pull.get("argv")
                    if (pull["exit"] != 0 or not isinstance(argv, list)
                            or argv[-3:] != ["exec-out", "cat", path]):
                        return phase + "/" + variant + "/transport"
                except (OSError, ValueError, KeyError):
                    return phase + "/" + variant + "/transport"
    return None


def evaluate():
    """Read-only verification requires the final post-cleanup outcome, not just TARs."""
    try:
        result = json.loads((OUT / "result.json").read_text())
        inputs = (OUT / "inputs.json").read_bytes()
        built = json.loads((OUT / "build.json").read_text())
        # Older failed captures used error.json; never certify those.
        if (OUT / "error.json").exists() or result.get("error") or result["collection"] != "COMPLETE" or result["cleanup"] != "COMPLETE":
            raise ValueError("Failed/incomplete run or stale error")
        if cleanup_failure():
            raise ValueError("Failed/incomplete cleanup receipts")
        if built["status"] != "COMPLETE" or built["inputsSha256"] != digest(inputs) or result["inputsSha256"] != digest(inputs):
            raise ValueError("Build/input mismatch")
        problem = receipt_problem(result)
        if problem:
            raise ValueError("Missing/incorrect receipt: " + problem)
        observed = collect_cases(result["profile"])
        if any(result[key] != observed[key] for key in observed):
            raise ValueError("Final outcome differs from raw measurements")
        return result
    except (OSError, ValueError, KeyError) as error:
        return {"collection": "ERROR", "capability": "UNAVAILABLE", "acceptance": "NOT_TESTED", "error": str(error)}


def probe():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Linux":
        raise RuntimeError("Probe requires the explicitly opted-in disposable hosted Linux AVD")
    selected = [name for name, item in PROFILES.items() if os.environ.get(item["env"]) == "1"]
    if len(selected) != 1:
        raise RuntimeError("Probe requires exactly one opted-in legacy profile (api28 or api29)")
    profile = PROFILES[selected[0]]
    inputs = json.loads((OUT / "inputs.json").read_text())
    built = json.loads((OUT / "build.json").read_text())
    if built["status"] != "COMPLETE" or built["inputsSha256"] != digest((OUT / "inputs.json").read_bytes()) or inputs["implementation"] != digest(Path(__file__).read_bytes()):
        raise RuntimeError("Incomplete/stale builder receipt")
    if command(OUT, "probe-source", ["git", "-C", str(ROOT), "rev-parse", "HEAD"]) != inputs["source"]:
        raise RuntimeError("Stale build source")
    for variant in VARIANTS:
        if digest((OUT / (variant + ".apk")).read_bytes()) != inputs["fixtures"][variant]["apkSha256"]:
            raise RuntimeError("Changed APK")
    if any((OUT / phase).exists() for phase in ("cse", "d2d")):
        raise RuntimeError("Refusing reused measurement directories")
    adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", "emulator-5554"]
    def call(name, *args, folder=OUT, required=True, timeout=60):
        return command(folder, name, [*adb, *args], timeout, required)
    def shell(name, *args, **kw):
        return call(name, "shell", *args, **kw)
    old, installed, transport, safe, changed = {}, [], None, False, False
    dataset = profile["dataset"]
    error = None
    save(OUT / "result.json", {"profile": selected[0], "collection": "RUNNING",
                               "cleanup": "PENDING", "acceptance": "NOT_TESTED"})
    try:
        devices = command(OUT, "devices", [adb[0], "devices"]).splitlines()[1:]
        if [line.strip() for line in devices if line.strip()] != ["emulator-5554\tdevice"]:
            raise RuntimeError("Refusing ambiguous/non-disposable devices")
        if call("avd-name", "emu", "avd", "name").splitlines()[0] != profile["avd"]:
            raise RuntimeError("Unexpected AVD")
        for prop, value in (("ro.kernel.qemu", "1"), ("ro.build.version.sdk", profile["sdk"]),
                            ("ro.product.cpu.abi", "x86_64")):
            if shell(prop, "getprop", prop) != value:
                raise RuntimeError("Wrong emulator/image: " + prop)
        safe = True
        shell("fingerprint", "getprop", "ro.build.fingerprint")
        image = Path(os.environ["ANDROID_HOME"]) / profile["image"]
        (OUT / "image").mkdir(exist_ok=True)
        for name in ("source.properties", "package.xml"):
            shutil.copyfile(image / name, OUT / "image" / name)
        call("root", "root")
        call("root-wait", "wait-for-device")
        if not shell("root-id", "id").startswith("uid=0"):
            raise RuntimeError("UNAVAILABLE: image is not rootable")
        deadline = time.monotonic() + 90
        while "package:" not in shell("services", "pm", "path", "android", required=False, timeout=15):
            if time.monotonic() >= deadline:
                raise RuntimeError("UNAVAILABLE: package service")
            time.sleep(2)
        apk_paths = [line.strip() for line in
                     shell("transport-pm-path", "pm", "path", "com.android.localtransport",
                           required=False).splitlines() if line.strip()]
        if apk_paths:
            if len(apk_paths) != 1 or not re.fullmatch(r"package:/(system|product|system_ext)/[\w./-]+\.apk", apk_paths[0]):
                raise RuntimeError("Unexpected installed LocalTransport APK path")
            apk_path = apk_paths[0].removeprefix("package:")
            size = shell("transport-apk-size", "stat", "-c", "%s", apk_path)
            if not size.isdecimal() or not 0 < int(size) <= 16 * 1024 * 1024:
                raise RuntimeError("Unexpected LocalTransport APK size")
            local_apk = OUT / "image/LocalTransport.apk"
            call("transport-apk-pull", "pull", apk_path, str(local_apk))
            if local_apk.stat().st_size != int(size):
                raise RuntimeError("Incomplete LocalTransport APK pull")
            save(OUT / "image/transport-apk.json", {"path": apk_path, "sha256": digest(local_apk.read_bytes())})
        elif profile["service"]:
            raise RuntimeError("Missing standalone LocalTransport APK")
        if profile["framework"]:
            # Stock API28's transport is framework-owned; bound metadata of the
            # known framework file stands in for the standalone APK of API29.
            size = shell("transport-framework-size", "stat", "-c", "%s", profile["framework"])
            if not size.isdecimal() or not 0 < int(size) <= 128 * 1024 * 1024:
                raise RuntimeError("Unexpected framework file size")
            sha = shell("transport-framework-sha", "sha256sum", profile["framework"])
            save(OUT / "image/framework.json",
                 {"path": profile["framework"], "size": int(size), "sha256": sha.split()[0]})
            raw = shell("download-cache", "printenv", "DOWNLOAD_CACHE", required=False)
            code = json.loads((OUT / "download-cache.json").read_text())["exit"]
            dataset = cache_root(raw, code) + profile["suffix"]
        for key in ("backup_enabled", "backup_auto_restore", "backup_local_transport_parameters"):
            old[key] = shell(key, "settings", "get", "secure", key)
        listed = shell("transports", "bmgr", "list", "transports")
        names = [line.strip().removeprefix("* ") for line in listed.splitlines() if line.strip()]
        transport = profile["transport"]
        if transport not in names:
            raise RuntimeError("UNAVAILABLE: known LocalTransport not listed")
        old["transport"] = next(line.strip()[2:] for line in listed.splitlines() if line.strip().startswith("* "))
        save(OUT / "original-settings.json", old)
        for variant in VARIANTS:
            pkg = PREFIX + variant
            if "package:" in shell("existing-" + variant, "pm", "path", pkg, required=False):
                raise RuntimeError("Refusing existing fixture package")
        changed = True
        shell("enable", "bmgr", "enable", "true")
        shell("autorestore", "bmgr", "autorestore", "false")
        shell("select", "bmgr", "transport", transport)
        if "* " + transport not in [s.strip() for s in shell("selected", "bmgr", "list", "transports").splitlines()]:
            raise RuntimeError("LocalTransport selection failed")
        for variant in VARIANTS:
            installed.append(PREFIX + variant)
            call("install-" + variant, "install", "--user", "0", str(OUT / (variant + ".apk")))
            prepare_fixture(call, variant)
        for phase in ("cse", "d2d"):
            params = f"is_encrypted={'true' if phase == 'cse' else 'false'},is_device_transfer={'true' if phase == 'd2d' else 'false'},fake_encryption_flag=false"
            shell(phase + "-parameters", "settings", "put", "secure", "backup_local_transport_parameters", params)
            shell(phase + "-init", "bmgr", "init", transport)
            shell(phase + "-before", "dumpsys", "backup")
            for variant in VARIANTS:
                folder, pkg = OUT / phase / variant, PREFIX + variant
                path = dataset + pkg
                shell("fresh", "test", "!", "-e", path, folder=folder)
                shell("backup", *profile["backupnow"], pkg, folder=folder, required=False, timeout=120)
                size = shell("size", "stat", "-c", "%s", path, folder=folder, required=False)
                if size.isdecimal() and 0 < int(size) <= LIMIT:
                    call("transport", "exec-out", "cat", path, folder=folder)
                    (folder / "transport.out").rename(folder / "transport.tar")
            shell(phase + "-after", "dumpsys", "backup", required=False)
    except Exception as failure:
        error = str(failure)
    finally:
        if safe:
            if profile["service"]:
                shell("final-service", "dumpsys", "activity", "service", profile["service"], required=False)
            shell("final-backup", "dumpsys", "backup", required=False)
            shell("logcat", "logcat", "-d", "-b", "all", "-t", "1500", required=False)
        if changed:
            for pkg in installed:
                shell("cleanup-" + pkg, "pm", "uninstall", "--user", "0", pkg, required=False)
            shell("cleanup-init", "bmgr", "init", transport, required=False)
            shell("restore-transport", "bmgr", "transport", old["transport"], required=False)
            shell("restore-enabled", "bmgr", "enable", "true" if old["backup_enabled"] == "1" else "false", required=False)
            shell("restore-autorestore", "bmgr", "autorestore", "false" if old["backup_auto_restore"] == "0" else "true", required=False)
            for key, value in old.items():
                if key != "transport":
                    shell("restore-" + key, "settings", "delete" if value == "null" else "put", "secure", key,
                          *([] if value == "null" else [value]), required=False)
                    shell("restored-" + key, "settings", "get", "secure", key, required=False)
            shell("restore-selection", "bmgr", "list", "transports", required=False)
            if dataset:
                for variant in VARIANTS:
                    shell("cleanup-empty-" + variant, "test", "!", "-e", dataset + PREFIX + variant, required=False)
    failed = cleanup_failure() if changed else "cleanup not reached"
    if safe:
        for name in ["final-backup", "logcat"] + (["final-service"] if profile["service"] else []):
            if json.loads((OUT / (name + ".json")).read_text())["exit"] != 0:
                error = error or "Diagnostic capture failed: " + name
    result = finish_run(error or failed, "ERROR" if failed else "COMPLETE", selected[0])
    return 0 if result["collection"] == "COMPLETE" else 1


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) == 2 else ""
    try:
        if mode == "build":
            fresh_build()
            build()
        elif mode == "probe":
            sys.exit(probe())
        elif mode == "verify":
            result = evaluate()
            print(json.dumps(result, indent=2))
            sys.exit(0 if result["collection"] == "COMPLETE" else 1)
        else:
            raise ValueError("Use build, probe or verify")
    except Exception as error:
        if mode == "build":
            save(OUT / "build.json", {"status": "ERROR", "error": str(error)})
        elif mode == "probe":
            opted = [n for n, i in PROFILES.items() if os.environ.get(i["env"]) == "1"]
            finish_run(str(error), "ERROR", opted[0] if len(opted) == 1 else None)
        print(str(error), file=sys.stderr)
        sys.exit(1)
