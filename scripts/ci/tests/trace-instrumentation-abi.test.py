#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Synthetic-fixture checks for the instrumentation-ABI keep-rule tracer.

Exercises scripts/ci/trace-instrumentation-abi.py without a JDK, Gradle or a
real R8: the manifest's `java` points at a fake executable (a Python stub) that
logs every argv as JSON lines together with the class entries of its program
and --lib inputs, fakes the R8 focus stage by copying its last argument (the
merged focus input jar) to --output, and fakes TraceReferences by writing the
FAKE_R8_BODY environment value to --output. FAKE_R8_FAIL_STAGE=r8|trace makes
that stage exit nonzero; the -no-output variants exit zero without writing
--output. The keep file under test is always temporary via --keep-file; the
committed keep file is never touched.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

CI_DIR = Path(__file__).resolve().parents[1]
DRIVER = CI_DIR / "trace-instrumentation-abi.py"
spec = importlib.util.spec_from_file_location("trace_instrumentation_abi", DRIVER)
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

VERSION = "9.9.99-fixture"
# Copied literally from app/src/main/keepRules/instrumentation-abi.keep; only
# the R8 version is dynamic.
HEADER = (
    "# SPDX-License-Identifier: Unlicense\n"
    "# Release instrumentation is a separately compiled client of the shipped APK.\n"
    "# Preserve its exact call surface in every build, not a test-only variant.\n"
    "# Traced with R8 " + VERSION + " from the seven smoke cases, runner and test framework.\n"
    "# Other code remains eligible for shrinking and optimization.\n"
)
BODY = (
    "-keep class ch.lkmc.neutrodyne.MainActivity {\n"
    "  public void onCreate(android.os.Bundle);\n"
    "}\n"
    "-keep class ch.lkmc.neutrodyne.core.Feed {\n"
    "  public java.lang.String title();\n"
    "}\n"
)
EXPECTED = HEADER + BODY

TARGET_ENTRIES = {
    "ch/lkmc/neutrodyne/MainActivity.class": b"cls:MainActivity",
    "ch/lkmc/neutrodyne/core/Feed.class": b"cls:Feed",
    "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
    "META-INF/services/ch.lkmc.neutrodyne.Provider": b"ch.lkmc.neutrodyne.Impl\n",
    "META-INF/versions/9/ch/lkmc/neutrodyne/Nine.class": b"cls:Nine",
    "module-info.class": b"cls:module-info",
    "res/raw/data.bin": b"not-a-class",
}
SOURCE_ENTRIES = {
    "ch/lkmc/neutrodyne/SmokeTest.class": b"cls:SmokeTest",
    "ch/lkmc/neutrodyne/YtxReleaseSmokeTest.class": b"cls:YtxReleaseSmokeTest",
    "junit/framework/TestCase.class": b"cls:junit.TestCase",
    "junit/runner/Version.class": b"cls:junit.Version",
    "notes.txt": b"not-a-class",
}

FAKE_JAVA = r'''
import json
import os
import shutil
import sys
import zipfile


def entries(path):
    if path is None:
        return None
    if os.path.isdir(path):
        found = []
        for base, _, names in os.walk(path):
            for name in names:
                found.append(os.path.relpath(os.path.join(base, name), path))
        return sorted(found)
    try:
        with zipfile.ZipFile(path) as jar:
            return jar.namelist()
    except Exception:
        return None


def value(argv, flag):
    if flag in argv:
        return argv[argv.index(flag) + 1]
    for arg in argv:
        if arg.startswith(flag + "="):
            return arg.split("=", 1)[1]
    return None


def record(data):
    with open(os.environ["FAKE_JAVA_LOG"], "a") as log:
        log.write(json.dumps(data) + "\n")


def main(argv):
    stage = ("r8" if "com.android.tools.r8.R8" in argv
             else "trace" if any("tracereferences" in a for a in argv) else "")
    libs = {}
    for index, arg in enumerate(argv):
        if arg == "--lib" and index + 1 < len(argv):
            libs[argv[index + 1]] = entries(argv[index + 1])
        elif arg.startswith("--lib="):
            libs[arg[len("--lib="):]] = entries(arg[len("--lib="):])
    output = value(argv, "--output")
    program = argv[-1] if stage == "r8" else value(argv, "--source")
    data = {"argv": argv, "libs": libs, "program_entries": entries(program)}
    fail = os.environ.get("FAKE_R8_FAIL_STAGE", "")
    wanted, _, quiet = fail.partition("-no-output")
    if wanted and wanted == stage:
        data["forced_failure"] = "no-output" if quiet else "exit"
        record(data)
        if not quiet:
            print("fake java: forced failure in stage " + stage, file=sys.stderr)
        return 0 if quiet else 2
    if stage == "r8":
        shutil.copyfile(argv[-1], output)
    elif stage == "trace":
        with open(output, "w") as out:
            out.write(os.environ.get("FAKE_R8_BODY", ""))
    record(data)
    return 0


sys.exit(main(sys.argv[1:]))
'''


def cls(name):
    return b"\xca\xfe\xba\xbe\x00\x00" + name.encode("utf-8")


def write_jar(path, entries, stamp=(2030, 5, 5, 5, 5, 4)):
    with zipfile.ZipFile(path, "w") as jar:
        for name, data in entries.items():
            jar.writestr(zipfile.ZipInfo(name, stamp), data)


def write_tree(root, entries):
    for name, data in entries.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def arg_value(argv, flag):
    if flag in argv:
        return argv[argv.index(flag) + 1]
    for arg in argv:
        if arg.startswith(flag + "="):
            return arg.split("=", 1)[1]
    return None


class MergeClassesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def jar(self, name, entries, stamp=(2030, 5, 5, 5, 5, 4)):
        path = self.root / name
        write_jar(path, entries, stamp)
        return path

    def merge(self, paths, name="merged.jar", **kwargs):
        output = self.root / name
        driver._merge_classes(paths, output, **kwargs)
        return output

    def test_output_is_deterministic_sorted_stored_and_timestamp_free(self):
        a = self.jar("a.jar", {"b/Two.class": cls("b.Two"),
                               "a/One.class": cls("a.One")}, stamp=(2031, 2, 2, 2, 2, 2))
        b = self.jar("b.jar", {"c/Three.class": cls("c.Three")}, stamp=(2001, 1, 1, 1, 1, 0))
        first = self.merge([b, a], "one.jar")
        second = self.merge([a, b], "two.jar")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as merged:
            infos = merged.infolist()
            self.assertEqual(["a/One.class", "b/Two.class", "c/Three.class"],
                             [i.filename for i in infos])
            self.assertTrue(all(i.compress_type == zipfile.ZIP_STORED for i in infos))
            self.assertEqual(1, len({i.date_time for i in infos}))
            self.assertEqual(cls("a.One"), merged.read("a/One.class"))

    def test_metadata_and_non_class_entries_are_dropped(self):
        jar = self.jar("meta.jar", TARGET_ENTRIES)
        out = self.merge([jar])
        with zipfile.ZipFile(out) as merged:
            self.assertEqual(["ch/lkmc/neutrodyne/MainActivity.class",
                              "ch/lkmc/neutrodyne/core/Feed.class"],
                             merged.namelist())

    def test_class_directories_merge_like_jars(self):
        tree = self.root / "classes"
        write_tree(tree, {"x/A.class": cls("x.A"),
                          "x/inner/B.class": cls("x.inner.B"),
                          "x/skip.txt": b"no"})
        out = self.merge([tree])
        with zipfile.ZipFile(out) as merged:
            self.assertEqual(["x/A.class", "x/inner/B.class"], merged.namelist())
            self.assertEqual(cls("x.inner.B"), merged.read("x/inner/B.class"))

    def test_identical_duplicate_class_bytes_dedup(self):
        dup = {"x/Dup.class": cls("x.Dup")}
        a = self.jar("a.jar", dup | {"a/Only.class": cls("a.Only")})
        b = self.jar("b.jar", dict(dup))
        out = self.merge([a, b])
        with zipfile.ZipFile(out) as merged:
            self.assertEqual(["a/Only.class", "x/Dup.class"], merged.namelist())

    def test_conflicting_duplicate_names_the_class(self):
        a = self.jar("a.jar", {"x/Clash.class": cls("x.Clash@a")})
        b = self.jar("b.jar", {"x/Clash.class": cls("x.Clash@b")})
        with self.assertRaisesRegex(ValueError, r"x[/\\.]Clash"):
            self.merge([a, b])

    def test_excluded_prefixes_apply_only_when_requested(self):
        src = self.jar("src.jar", {name: SOURCE_ENTRIES[name]
                                   for name in SOURCE_ENTRIES if name.endswith(".class")})
        full = self.merge([src], "full.jar")
        with zipfile.ZipFile(full) as merged:
            self.assertIn("junit/framework/TestCase.class", merged.namelist())
        focused = self.merge([src], "focused.jar", excluded_prefixes=("junit/",))
        with zipfile.ZipFile(focused) as merged:
            self.assertEqual(["ch/lkmc/neutrodyne/SmokeTest.class",
                              "ch/lkmc/neutrodyne/YtxReleaseSmokeTest.class"],
                             merged.namelist())


class CliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.target = self.root / "target.jar"
        write_jar(self.target, TARGET_ENTRIES)
        self.source = self.root / "instrumentation-classes"
        write_tree(self.source, SOURCE_ENTRIES)
        self.boot = self.root / "android.jar"
        write_jar(self.boot, {"android/app/Activity.class": cls("android.app.Activity"),
                              "android/os/Bundle.class": cls("android.os.Bundle")})
        self.target_lib = self.root / "target-lib.jar"
        write_jar(self.target_lib, {"kotlin/jvm/internal/Intrinsics.class": cls("kotlin.Intrinsics")})
        self.source_lib = self.root / "source-lib.jar"
        write_jar(self.source_lib, {"kotlinx/coroutines/test/TestScope.class": cls("ktx.TestScope")})
        self.java = self.root / "java"
        self.java.write_text("#!" + sys.executable + "\n" + FAKE_JAVA)
        self.java.chmod(0o755)
        self.r8jar = self.root / "r8.jar"
        self.r8jar.write_bytes(b"the fake java never reads this")
        self.log = self.root / "java-calls.jsonl"
        self.keep = self.root / "keep" / "instrumentation-abi.keep"
        self.keep.parent.mkdir()
        self.manifest = {
            "java": str(self.java),
            "r8Jar": str(self.r8jar),
            "r8Version": VERSION,
            "target": [str(self.target)],
            "source": [str(self.source)],
            "targetLibraries": [str(self.target_lib)],
            "sourceLibraries": [str(self.source_lib)],
            "boot": [str(self.boot)],
        }
        self.manifest_path = self.root / "manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest))

    def run_cli(self, *args, env_extra=None, manifest=None):
        env = dict(os.environ)
        env["FAKE_JAVA_LOG"] = str(self.log)
        env["FAKE_R8_BODY"] = BODY
        env.pop("FAKE_R8_FAIL_STAGE", None)
        env.update(env_extra or {})
        self.log.write_text("")
        return subprocess.run(
            [sys.executable, str(DRIVER), "--inputs",
             str(manifest or self.manifest_path), *args],
            capture_output=True, text=True, env=env, timeout=120)

    def stages(self):
        records = [json.loads(line) for line in self.log.read_text().splitlines() if line.strip()]
        r8 = [r for r in records if "com.android.tools.r8.R8" in r["argv"]]
        trace = [r for r in records
                 if "com.android.tools.r8.tracereferences.TraceReferences" in r["argv"]]
        return r8, trace

    def write_broken_manifest(self, name, manifest):
        path = self.root / name
        path.write_text(json.dumps(manifest))
        return path

    def test_write_emits_literal_header_and_traced_body(self):
        result = self.run_cli("--write", "--keep-file", str(self.keep))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(EXPECTED, self.keep.read_text())

    def test_check_agrees_with_write_and_never_touches_the_file(self):
        self.assertEqual(0, self.run_cli("--write", "--keep-file", str(self.keep)).returncode)
        result = self.run_cli("--check", "--keep-file", str(self.keep))
        self.assertEqual(0, result.returncode)
        self.assertEqual(EXPECTED, self.keep.read_text())
        _, trace = self.stages()
        self.assertEqual(1, len(trace))

    def test_check_reports_drift_and_preserves_the_file(self):
        cases = {
            "missing member":
                EXPECTED.replace("  public void onCreate(android.os.Bundle);\n", ""),
            "renamed class":
                EXPECTED.replace("ch.lkmc.neutrodyne.MainActivity",
                                 "ch.lkmc.neutrodyne.RenamedActivity"),
            "mangled signature":
                EXPECTED.replace("onCreate(android.os.Bundle)",
                                 "onCreate(android.os.Parcel)"),
            "extra rule":
                EXPECTED + "-keep class ch.lkmc.neutrodyne.Extra {\n}\n",
            "stale header":
                EXPECTED.replace(VERSION, "0.0.0-stale"),
        }
        for name, content in cases.items():
            with self.subTest(case=name):
                self.keep.write_text(content)
                result = self.run_cli("--check", "--keep-file", str(self.keep))
                self.assertEqual(1, result.returncode)
                self.assertEqual(content, self.keep.read_bytes().decode())
                self.assertTrue((result.stdout + result.stderr).strip(),
                                "drift must print a diff")

    def test_check_diff_mentions_the_drifted_rule(self):
        self.keep.write_text(EXPECTED.replace("MainActivity", "RenamedActivity"))
        result = self.run_cli("--check", "--keep-file", str(self.keep))
        self.assertRegex(result.stdout + result.stderr, "MainActivity")

    def test_check_fails_when_the_keep_file_is_absent(self):
        result = self.run_cli("--check", "--keep-file", str(self.keep))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.keep.exists())

    def test_r8_version_is_dynamic_in_the_header(self):
        other = self.write_broken_manifest(
            "other-version.json", dict(self.manifest, r8Version="8.1.47-other"))
        result = self.run_cli("--write", "--keep-file", str(self.keep), manifest=other)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(HEADER.replace(VERSION, "8.1.47-other") + BODY,
                         self.keep.read_text())

    def test_both_main_classes_run_with_contract_flags(self):
        self.assertEqual(0, self.run_cli("--write", "--keep-file", str(self.keep)).returncode)
        r8s, traces = self.stages()
        self.assertEqual(1, len(r8s))
        self.assertEqual(1, len(traces))
        r8, trace = r8s[0]["argv"], traces[0]["argv"]
        self.assertIn("-Xmx2500m", r8)
        self.assertIn(str(self.r8jar), r8)
        self.assertIn(str(self.r8jar), trace)
        for flag in ("-cp", "--classfile", "--release", "--pg-conf", "--output"):
            self.assertIn(flag, r8)
        for flag in ("-cp", "--keep-rules", "--source", "--target", "--output"):
            self.assertIn(flag, trace)
        self.assertTrue(any(a.startswith("--map-diagnostics:MissingDefinitionsDiagnostic")
                            for a in trace))
        self.assertFalse(any(a.startswith("@") for a in r8 + trace),
                         "subprocess argv only, never argfiles")
        roots = arg_value(r8, "--pg-conf")
        self.assertEqual("instrumentation-abi-roots.pro", Path(roots).name)
        self.assertEqual(arg_value(r8, "--output"), arg_value(trace, "--source"))
        self.assertTrue(arg_value(trace, "--target"))

    def test_focus_excludes_junit_while_raw_source_stays_a_library(self):
        self.assertEqual(0, self.run_cli("--write", "--keep-file", str(self.keep)).returncode)
        r8s, traces = self.stages()
        focus_input = r8s[0]["program_entries"]
        self.assertIn("ch/lkmc/neutrodyne/SmokeTest.class", focus_input)
        self.assertFalse(any(e.startswith("junit/") for e in focus_input))
        traced = traces[0]["program_entries"]
        self.assertFalse(any(e.startswith("junit/") for e in traced))
        lib_entries = [entry for record in (r8s[0], traces[0])
                       for listing in record["libs"].values() for entry in (listing or [])]
        self.assertTrue(any(e.startswith("junit/") for e in lib_entries),
                        "the unfiltered merged source must resolve junit via --lib")
        libs = set(r8s[0]["libs"]) | set(traces[0]["libs"])
        self.assertIn(str(self.boot), libs)
        self.assertIn(str(self.target_lib), libs)
        self.assertNotIn(str(self.source_lib), libs)  # audit-only input

    def test_first_stage_failure_never_reaches_the_tracer(self):
        result = self.run_cli("--write", "--keep-file", str(self.keep),
                              env_extra={"FAKE_R8_FAIL_STAGE": "r8"})
        self.assertNotEqual(0, result.returncode)
        r8s, traces = self.stages()
        self.assertEqual(1, len(r8s))
        self.assertEqual([], traces)
        self.assertFalse(self.keep.exists())

    def test_stage_failures_preserve_the_committed_baseline(self):
        self.keep.write_text(EXPECTED + "-keep class ch.lkmc.neutrodyne.Baseline {\n}\n")
        baseline = self.keep.read_bytes()
        for fail in ("r8", "trace", "r8-no-output", "trace-no-output"):
            with self.subTest(stage=fail):
                result = self.run_cli("--write", "--keep-file", str(self.keep),
                                      env_extra={"FAKE_R8_FAIL_STAGE": fail})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(baseline, self.keep.read_bytes())

    def test_empty_tracer_output_is_rejected(self):
        result = self.run_cli("--write", "--keep-file", str(self.keep),
                              env_extra={"FAKE_R8_BODY": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.keep.exists())

    def test_comment_only_tracer_output_preserves_the_baseline(self):
        self.keep.write_text(EXPECTED)
        result = self.run_cli("--write", "--keep-file", str(self.keep),
                              env_extra={"FAKE_R8_BODY": "# No target definitions\n"})
        self.assertEqual(EXPECTED, self.keep.read_text())
        self.assertNotEqual(0, result.returncode)

    def test_mode_flags_are_required_and_mutually_exclusive(self):
        self.assertNotEqual(0, self.run_cli("--keep-file", str(self.keep)).returncode)
        self.assertNotEqual(0, self.run_cli("--check", "--write",
                                          "--keep-file", str(self.keep)).returncode)
        self.assertFalse(self.keep.exists())

    def test_manifest_fields_are_required(self):
        for field in ("java", "r8Jar", "r8Version", "target", "source",
                      "targetLibraries", "sourceLibraries", "boot"):
            with self.subTest(field=field):
                broken = dict(self.manifest)
                del broken[field]
                result = self.run_cli("--check", "--keep-file", str(self.keep),
                                      manifest=self.write_broken_manifest(
                                          f"no-{field}.json", broken))
                self.assertNotEqual(0, result.returncode)
                output = result.stdout + result.stderr
                self.assertTrue(output.strip())
                self.assertIn(field, output)

    def test_target_source_and_boot_must_be_nonempty_existing_paths(self):
        for field in ("target", "source", "boot"):
            with self.subTest(field=field, kind="empty"):
                broken = dict(self.manifest, **{field: []})
                result = self.run_cli("--write", "--keep-file", str(self.keep),
                                      manifest=self.write_broken_manifest(
                                          f"empty-{field}.json", broken))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(field, result.stdout + result.stderr)
            with self.subTest(field=field, kind="missing"):
                broken = dict(self.manifest, **{field: [str(self.root / "does-not-exist")]})
                result = self.run_cli("--write", "--keep-file", str(self.keep),
                                      manifest=self.write_broken_manifest(
                                          f"gone-{field}.json", broken))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("does-not-exist", result.stdout + result.stderr)
                self.assertFalse(self.keep.exists())

    def test_executables_and_single_line_version_are_validated(self):
        for field in ("java", "r8Jar"):
            with self.subTest(field=field):
                broken = dict(self.manifest, **{field: str(self.root / "missing-bin")})
                result = self.run_cli("--write", "--keep-file", str(self.keep),
                                      manifest=self.write_broken_manifest(
                                          f"gone-{field}.json", broken))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("missing-bin", result.stdout + result.stderr)
        broken = self.write_broken_manifest(
            "multiline.json", dict(self.manifest, r8Version="9.0\noops"))
        self.assertNotEqual(0, self.run_cli("--write", "--keep-file", str(self.keep),
                                            manifest=broken).returncode)
        missing = self.run_cli("--write", "--keep-file", str(self.keep),
                               manifest=self.root / "absent.json")
        self.assertNotEqual(0, missing.returncode)


if __name__ == "__main__":
    unittest.main()
