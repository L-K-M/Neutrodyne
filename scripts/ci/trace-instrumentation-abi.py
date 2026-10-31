#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Regenerate the shipped APK's exact release-instrumentation ABI keeps."""
import argparse
import difflib
from enum import Enum
from fnmatch import fnmatchcase
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
from zipfile import BadZipFile, ZipFile, ZipInfo, ZIP_STORED

ROOT = Path(__file__).resolve().parents[2]
ROOTS = Path(__file__).with_name("instrumentation-abi-roots.pro")
KEEP_FILE = ROOT / "app/src/main/keepRules/instrumentation-abi.keep"
INPUTS = ROOT / "build/release-smoke-discovery/r8-inputs.json"
JVM_HEAP_MIB = 2500
ZIP_UNIX_PLATFORM = 3
CLASS_FILE_MODE = 0o100644
LEGACY_JUNIT = ("junit/",)
KEEP_RULE_PREFIX = "-keep "
CLASS_KEEP_PREFIX = "-keep class "
HEADER = """# SPDX-License-Identifier: Unlicense
# Release instrumentation is a separately compiled client of the shipped APK.
# Preserve its exact call surface in every build, not a test-only variant.
# Traced with R8 {version} from the seven smoke cases, runner and test framework.
# Other code remains eligible for shrinking and optimization.
"""


class Mode(Enum):
    CHECK = "check"
    WRITE = "write"


def _merge_classes(paths, output, excluded_prefixes=()):
    classes = {}
    origins = {}

    def add(name, data, source):
        if not name.endswith(".class") or name.startswith(("META-INF/", *excluded_prefixes)):
            return
        if name == "module-info.class":
            return
        if name.startswith("/") or ".." in PurePosixPath(name).parts or "\\" in name:
            raise ValueError(f"Invalid class entry {name!r} in {source}")
        if name in classes and classes[name] != data:
            raise ValueError(f"Conflicting {name} in {origins[name]} and {source}")
        classes[name] = data
        origins[name] = source

    for path in sorted(map(Path, paths)):
        if path.is_dir():
            for entry in sorted(path.rglob("*.class")):
                add(entry.relative_to(path).as_posix(), entry.read_bytes(), path)
            continue
        with ZipFile(path) as jar:
            for entry in sorted(jar.infolist(), key=lambda item: item.filename):
                if entry.filename.endswith(".class"):
                    add(entry.filename, jar.read(entry), path)

    if not classes:
        raise ValueError("No class files found; build the release class sets first")
    with ZipFile(output, "w", compression=ZIP_STORED) as jar:
        for name, data in sorted(classes.items()):
            entry = ZipInfo(name)
            entry.create_system = ZIP_UNIX_PLATFORM
            entry.external_attr = CLASS_FILE_MODE << 16
            jar.writestr(entry, data)
    return len(classes)


def _validate_analysis_roots(source):
    """Reject stale roots that R8 would silently ignore."""
    with ZipFile(source) as jar:
        names = [name.removesuffix(".class").replace("/", ".")
                 for name in jar.namelist() if name.endswith(".class")]
    for line in ROOTS.read_text(encoding="utf-8").splitlines():
        rule = line.strip()
        if not rule.startswith(CLASS_KEEP_PREFIX):
            continue
        pattern = rule.removeprefix(CLASS_KEEP_PREFIX).split()[0]
        if not any(fnmatchcase(name, pattern) for name in names):
            raise ValueError(f"Analysis root matches no input classes: {pattern}; update {ROOTS}")


def _load_inputs(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("Release ABI inputs must be a JSON object")

    def location(value, field):
        if not isinstance(value, str) or not value:
            raise ValueError(f"{field} paths must be nonempty strings")
        result = Path(value)
        return (result if result.is_absolute() else path.parent / result).resolve()

    for key in ("java", "r8Jar"):
        data[key] = location(data.get(key), key)
        if not data[key].is_file():
            raise ValueError(f"Missing {key}: {data[key]}; regenerate the Gradle input manifest")
    version = data.get("r8Version")
    if not isinstance(version, str) or not version.strip() or "\n" in version or "\r" in version:
        raise ValueError("r8Version must identify the resolved R8 in one line")
    for key in ("target", "source", "targetLibraries", "sourceLibraries", "boot"):
        values = data.get(key)
        if not isinstance(values, list) or (not values and key in ("target", "source", "boot")):
            raise ValueError(f"Missing or empty {key}; build and dump the release class sets first")
        data[key] = sorted({location(value, key) for value in values})
        # Source libraries are retained for input auditing, not supplied twice to R8.
        if key == "sourceLibraries":
            continue
        for entry in data[key]:
            if not entry.exists():
                raise ValueError(f"Missing {key} input: {entry}; rebuild the release class sets")
    return data


def _trace(data, directory):
    source = directory / "source-classes.jar"
    target = directory / "target-classes.jar"
    focus_input = directory / "source-junit4.jar"
    focused = directory / "focused-tests.jar"
    body = directory / "instrumentation-abi.pro"
    _merge_classes(data["source"], source)
    _merge_classes(data["target"], target)
    # Android supplies JUnit 3. Keep it library-only while shrinking the JUnit 4 roots.
    _merge_classes([source], focus_input, LEGACY_JUNIT)
    _validate_analysis_roots(focus_input)
    java = [str(data["java"]), f"-Xmx{JVM_HEAP_MIB}m", "-Dfile.encoding=UTF-8",
            "-cp", str(data["r8Jar"])]
    libraries = [*data["boot"], *data["targetLibraries"], source]
    library_args = [part for path in libraries for part in ("--lib", str(path))]
    subprocess.run([
        *java, "com.android.tools.r8.R8", "--classfile", "--release",
        "--output", str(focused), "--pg-conf", str(ROOTS),
        "--lib", str(target), *library_args, str(focus_input),
    ], check=True)
    if not focused.is_file():
        raise ValueError("R8 produced no focused test JAR")
    with ZipFile(focused) as jar:
        if not any(entry.endswith(".class") for entry in jar.namelist()):
            raise ValueError("R8 produced an empty focused test JAR")
    subprocess.run([
        *java, "com.android.tools.r8.tracereferences.TraceReferences", "--keep-rules",
        "--source", str(focused), "--target", str(target), "--output", str(body),
        *library_args, "--map-diagnostics:MissingDefinitionsDiagnostic", "error", "warning",
    ], check=True)
    if not body.is_file():
        raise ValueError("TraceReferences produced no ABI rules")
    traced = body.read_text(encoding="utf-8").replace("\r\n", "\n")
    if not any(line.startswith(KEEP_RULE_PREFIX) for line in traced.splitlines()):
        raise ValueError("TraceReferences produced no ABI keep rules")
    return HEADER.format(version=data["r8Version"]) + traced


def _update(keep_file, generated, mode):
    if mode == Mode.CHECK:
        original = keep_file.read_text(encoding="utf-8")
        if original == generated:
            return
        diff = "".join(difflib.unified_diff(
            original.splitlines(keepends=True), generated.splitlines(keepends=True),
            fromfile=str(keep_file), tofile="regenerated instrumentation ABI",
        ))
        raise ValueError(f"Instrumentation ABI drift; review and regenerate with --write:\n{diff}")

    keep_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=keep_file.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(generated.encode("utf-8"))
        temporary.chmod(0o644)
        os.replace(temporary, keep_file)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_const", const=Mode.CHECK, dest="mode")
    mode.add_argument("--write", action="store_const", const=Mode.WRITE, dest="mode")
    parser.add_argument("--inputs", type=Path, default=INPUTS)
    parser.add_argument("--keep-file", type=Path, default=KEEP_FILE)
    args = parser.parse_args(argv)
    try:
        data = _load_inputs(args.inputs)
        with tempfile.TemporaryDirectory(prefix="neutrodyne-instrumentation-abi-") as temporary:
            generated = _trace(data, Path(temporary))
        _update(args.keep_file, generated, args.mode)
    except (ValueError, OSError, BadZipFile, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
    print(f"Instrumentation ABI {args.mode.value} passed: {args.keep_file}")


if __name__ == "__main__":
    main()
