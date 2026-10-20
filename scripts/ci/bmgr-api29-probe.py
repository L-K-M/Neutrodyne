#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""SDK-only synthetic build, disposable hosted API29 probe, and offline TAR verifier.

A complete diagnostic (including BLOCKED) is not M3 transport acceptance.
Never invokes Gradle; probe mode refuses local hosts and non-owned devices.
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
LIMIT = 4 * 1024 * 1024
TRANSPORT_FILES = "/data/user/0/com.android.localtransport/files/1/_full/"


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
    # NoDisplay finishes in onCreate without a window. Readiness belongs to its files,
    # not am start -W; launch acknowledgement alone can precede the writes.
    call("launch-" + variant, "shell", "am", "start", "-n",
         PREFIX + variant + "/" + PREFIX[:-1] + ".ProbeActivity", timeout=15)
    expected = {"files/allowed/payload": PAYLOAD, "files/other/excluded": b"excluded-files-canary\n",
                "no_backup/excluded": b"excluded-no-backup-canary\n"}
    deadline, attempt = time.monotonic() + 30, 0
    while True:
        ready = True
        for path, data in expected.items():
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
    inputs = {"source": command(work, "source", ["git", "rev-parse", "HEAD"]),
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
    command(work, "dex", [tools["d8"], "--min-api", "29", "--lib", android, "--output", dex,
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
                r":fullBackupContent\([^\n]+\)=@", r":targetSdkVersion\([^\n]+\)=29")):
            raise RuntimeError("Wrong packaged backup/target attributes")
        inputs["fixtures"][variant] = {"package": PREFIX + variant, "apkSha256": digest(apk.read_bytes()),
            "certificate": re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", cert).group(1), "flag": flag}
    save(OUT / "inputs.json", inputs)
    save(OUT / "build.json", {"status": "COMPLETE", "inputsSha256": digest((OUT / "inputs.json").read_bytes())})


def verify_archive(raw, package, certificate):
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
                if len(lines) != 8 or lines[:4] != ["1", package, "1", "29"] or lines[5:7] != ["0", "1"]:
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


def measurement(folder, package, certificate, control, positive):
    try:
        status = json.loads((folder / "backup.json").read_text())
        text = (folder / "backup.out").read_text()
        if status["exit"] != 0 or any(re.findall(r"(?m)^" + re.escape(line) + r"([^\r\n]+)", text) != ["Success"]
                for line in (f"Package {package} with result: ", "Backup finished with result: ")):
            return "ERROR"
        if (folder / "transport.tar").stat().st_size > LIMIT:
            return "ERROR"
        state = verify_archive((folder / "transport.tar").read_bytes(), package, certificate)
        if not control:
            return "UNAVAILABLE"
        if package == PREFIX + "control":
            return "CONTROL" if state == "PAYLOAD" else "ERROR"
        return ("OBSERVED" if positive else "ERROR") if state == "PAYLOAD" else ("BLOCKED" if positive else "EXCLUDED")
    except FileNotFoundError:
        return "UNAVAILABLE"
    except (ValueError, KeyError, tarfile.TarError, UnicodeError):
        return "ERROR"


def collect_cases():
    inputs = json.loads((OUT / "inputs.json").read_text())
    observations = []
    for phase in ("cse", "d2d"):
        control = False
        for variant in VARIANTS:
            item = inputs["fixtures"][variant]
            folder = OUT / phase / variant
            verdict = measurement(folder, item["package"], item["certificate"],
                                  variant == "control" or control, variant == phase)
            if variant == "control":
                control = verdict == "CONTROL"
            observations.append({"phase": phase, "variant": variant, "status": verdict,
                                 "evidence": str(folder.relative_to(OUT))})
            archive = folder / "transport.tar"
            if archive.exists() and archive.stat().st_size <= LIMIT:
                observations[-1]["archiveSha256"] = digest(archive.read_bytes())
    complete = all(o["status"] not in ("ERROR", "UNAVAILABLE") for o in observations)
    return {"source": inputs["source"], "inputs": "inputs.json", "image": "image",
            "collection": "COMPLETE" if complete else "ERROR",
            "capability": ("BLOCKED" if any(o["status"] == "BLOCKED" for o in observations) else "OBSERVED")
                          if complete else "UNAVAILABLE", "acceptance": "NOT_TESTED", "cases": observations}


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


def finish_run(error, cleanup):
    try:
        result = collect_cases()
        result["inputsSha256"] = digest((OUT / "inputs.json").read_bytes())
    except (OSError, ValueError, KeyError) as failure:
        result = {"source": None, "collection": "ERROR", "capability": "UNAVAILABLE", "cases": []}
        error = error or str(failure)
    result.update(cleanup=cleanup, acceptance="NOT_TESTED")
    if error or cleanup != "COMPLETE":
        result.update(collection="ERROR", capability="UNAVAILABLE", error=error or "Cleanup incomplete")
    save(OUT / "result.json", result)
    return result


def evaluate():
    """Read-only verification requires the final post-cleanup outcome, not just TARs."""
    try:
        result = json.loads((OUT / "result.json").read_text())
        inputs = (OUT / "inputs.json").read_bytes()
        built = json.loads((OUT / "build.json").read_text())
        if (OUT / "error.json").exists() or result.get("error") or result["collection"] != "COMPLETE" or result["cleanup"] != "COMPLETE":
            raise ValueError("Failed/incomplete run or stale error")
        if cleanup_failure():
            raise ValueError("Failed/incomplete cleanup receipts")
        if built["status"] != "COMPLETE" or built["inputsSha256"] != digest(inputs) or result["inputsSha256"] != digest(inputs):
            raise ValueError("Build/input mismatch")
        observed = collect_cases()
        if any(result[key] != observed[key] for key in observed):
            raise ValueError("Final outcome differs from raw measurements")
        return result
    except (OSError, ValueError, KeyError) as error:
        return {"collection": "ERROR", "capability": "UNAVAILABLE", "acceptance": "NOT_TESTED", "error": str(error)}


def probe():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Linux" or os.environ.get("BMGR_DISPOSABLE_API29") != "1":
        raise RuntimeError("Probe requires the explicitly opted-in disposable hosted Linux AVD")
    inputs = json.loads((OUT / "inputs.json").read_text())
    built = json.loads((OUT / "build.json").read_text())
    if built["status"] != "COMPLETE" or built["inputsSha256"] != digest((OUT / "inputs.json").read_bytes()) or inputs["implementation"] != digest(Path(__file__).read_bytes()):
        raise RuntimeError("Incomplete/stale builder receipt")
    if command(OUT, "probe-source", ["git", "rev-parse", "HEAD"]) != inputs["source"]:
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
    error = None
    save(OUT / "result.json", {"collection": "RUNNING", "cleanup": "PENDING", "acceptance": "NOT_TESTED"})
    try:
        devices = command(OUT, "devices", [adb[0], "devices"]).splitlines()[1:]
        if [line.strip() for line in devices if line.strip()] != ["emulator-5554\tdevice"]:
            raise RuntimeError("Refusing ambiguous/non-disposable devices")
        if call("avd-name", "emu", "avd", "name").splitlines()[0] != "nd-bmgr-api29-probe":
            raise RuntimeError("Unexpected AVD")
        for prop, value in (("ro.kernel.qemu", "1"), ("ro.build.version.sdk", "29"), ("ro.product.cpu.abi", "x86_64")):
            if shell(prop, "getprop", prop) != value:
                raise RuntimeError("Wrong emulator/image: " + prop)
        safe = True
        shell("fingerprint", "getprop", "ro.build.fingerprint")
        image = Path(os.environ["ANDROID_HOME"]) / "system-images/android-29/default/x86_64"
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
        apk_paths = shell("transport-pm-path", "pm", "path", "com.android.localtransport").splitlines()
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
        for key in ("backup_enabled", "backup_auto_restore", "backup_local_transport_parameters"):
            old[key] = shell(key, "settings", "get", "secure", key)
        listed = shell("transports", "bmgr", "list", "transports")
        names = [line.strip().removeprefix("* ") for line in listed.splitlines() if line.strip()]
        transport = "com.android.localtransport/.LocalTransport"
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
                path = TRANSPORT_FILES + pkg
                shell("fresh", "test", "!", "-e", path, folder=folder)
                shell("backup", "bmgr", "backupnow", "--monitor", pkg, folder=folder, required=False, timeout=120)
                size = shell("size", "stat", "-c", "%s", path, folder=folder, required=False)
                if size.isdecimal() and 0 < int(size) <= LIMIT:
                    call("transport", "exec-out", "cat", path, folder=folder)
                    (folder / "transport.out").rename(folder / "transport.tar")
            shell(phase + "-after", "dumpsys", "backup", required=False)
    except Exception as failure:
        error = str(failure)
    finally:
        if safe:
            shell("final-service", "dumpsys", "activity", "service", "com.android.localtransport/.LocalTransportService", required=False)
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
            for variant in VARIANTS:
                shell("cleanup-empty-" + variant, "test", "!", "-e", TRANSPORT_FILES + PREFIX + variant, required=False)
    failed = cleanup_failure() if changed else "cleanup not reached"
    if safe:
        for name in ("final-service", "final-backup", "logcat"):
            if json.loads((OUT / (name + ".json")).read_text())["exit"] != 0:
                error = error or "Diagnostic capture failed: " + name
    result = finish_run(error or failed, "ERROR" if failed else "COMPLETE")
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
            finish_run(str(error), "ERROR")
        print(str(error), file=sys.stderr)
        sys.exit(1)
