#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""The desktop image gates' fixture checks (11 Image scan rules, 09 Build-output
checks; review-341 findings I1–I3).

Runs the shipped check-desktop-image.sh, check-runtime-sources.sh and
make-runtime-sources-md.sh in a throwaway fixture repo — the scripts resolve the
lockfiles and the runtime-classpath manifest relative to their own location, so
the real code paths run against fixture images:

  * I1 — image JARs are members of the Licensee-checked classpath under their
    *mangled* image names (<base>-<md5>.jar, what the manifest records) and
    identical to the row's sha256. The skiko stub is Compose's repacked derivative
    of the source runtime jar; it is bound by the stub manifest's source-derived
    expected hash and extracted natives — never by a name prefix or version row.
  * I2 — a jpackage .app keeps the runtime home at
    Contents/runtime/Contents/Home, not at Contents/runtime; release/legal/
    MODULES resolve under the home while the native-file comparison keeps the
    Contents/runtime root (its rel paths pair with jdk/Contents/Home).
  * I3 — a .dmg payload is mounted read-only (hdiutil attach -nobrowse
    -readonly), scanned with the same image rules and always detached. The
    fixture never mounts a real DMG: a fake hdiutil copies a payload tree into
    the mountpoint and records attach/detach.
  * Re-signed Mach-O natives — check-runtime-sources --image compares each
    runtime Mach-O with the pinned Temurin file by canonical content
    (canonical-jar-hash.py's Mach-O normalization; jpackage re-signs ad-hoc
    over Adoptium's signatures, and `codesign --remove-signature` leaves a
    signature-size-dependent __LINKEDIT.vmsize residue that can never match
    across mixed-size signatures). The fixtures carry real LC_CODE_SIGNATURE
    slots; where the host has clang and the real codesign, a dylib compiled
    in the test runs the same pass/fail pair under real signatures.
  * MSI — the Windows rules through a faked cygpath + powershell.exe boundary.
"""

import hashlib
import io
import os
import sys
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import tempfile
import time
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[3]
CI = REPO / "scripts/ci"

JAVA_VERSION = "25.0.4.1"  # fixture runtime.lock mirrors the real one's shape


def unpadded_md5(data: bytes) -> str:
    """Compose's contentHash: the jar's MD5 rendered per byte with
    Integer.toHexString — no zero padding (DesktopPackaging.kt imageJarName)."""
    return "".join(format(b, "x") for b in hashlib.md5(data).digest())


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def mangled(stem: str, data: bytes) -> str:
    return f"{stem}-{unpadded_md5(data)}.jar"


class Fixture:
    """A fixture repo: the real scripts and lockfiles, a fixture manifest and
    fixture images under root/. env["PATH"] prepends fakebin."""

    def __init__(self, root: Path):
        self.root = root
        (root / "scripts/ci").mkdir(parents=True)
        (root / "scripts/desktop").mkdir(parents=True)
        for script in (
            "check-desktop-image.sh",
            "check-runtime-sources.sh",
            "make-runtime-sources-md.sh",
            "canonical-jar-hash.py",
            "extract-zip.py",
        ):
            shutil.copy2(CI / script, root / "scripts/ci" / script)
        shutil.copy2(
            REPO / "scripts/desktop/smoke-start.sh",
            root / "scripts/desktop/smoke-start.sh",
        )
        for lock in (
            "desktopApp/runtime.lock",
            "desktopApp/wix.lock",
            "desktopApp/packaging/deb/control",
            "desktopApp/packaging/rpm/neutrodyne.spec",
            "youtube/ytdlp-desktop/python-components.lock",
        ):
            (root / lock).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPO / lock, root / lock)
        (root / "desktopApp/build/desktop-packaging").mkdir(parents=True)
        (root / "fakebin").mkdir()
        self.env = {
            **{k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "GH_"))},
            "PATH": str(root / "fakebin") + os.pathsep + os.environ["PATH"],
        }
        self.manifest_rows = {}
        self.stub_rows = {}

    def canonical_hash(self, data: bytes) -> str:
        """The shipped checker's canonical identity (canonical-jar-hash.py)."""
        if not data.startswith(b"PK"):
            return sha256(data)  # non-zip fallback binds the raw bytes
        path = self.root / "tmp/canonical-input"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        r = subprocess.run(["python3", str(self.root / "scripts/ci/canonical-jar-hash.py"), str(path)],
                           capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise AssertionError(r.stderr)
        return r.stdout.strip()

    def canonical_content_hash(self, name: str, data: bytes) -> str:
        """The canonical hash of a standalone file's normalized content (the
        '--content' mode): Mach-O signatures are masked, other bytes bound."""
        path = self.root / "tmp/content-input" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        r = subprocess.run(
            ["python3", str(self.root / "scripts/ci/canonical-jar-hash.py"), "--content", str(path)],
            capture_output=True, text=True, timeout=60,
        )
        if r.returncode != 0:
            raise AssertionError(r.stderr)
        return r.stdout.strip()

    def manifest(self, stem: str, data: bytes, name=None) -> str:
        """Record a classpath row like writeDesktopRuntimeClasspath writes:
        '<canonical sha256>  <mangled image name>' — the fixture's image jars then
        use it."""
        image_name = name or mangled(stem, data)
        self.manifest_rows[image_name] = self.canonical_hash(data)
        return image_name

    def write_manifest(self):
        text = "".join(f"{h}  {n}\n" for n, h in sorted(self.manifest_rows.items()))
        (self.root / "desktopApp/build/desktop-packaging/runtime-classpath.txt").write_text(
            text, encoding="utf-8"
        )
        stubs = "".join(f"{row}\n" for _, row in sorted(self.stub_rows.items()))
        (self.root / "desktopApp/build/desktop-packaging/runtime-classpath-stubs.txt").write_text(
            stubs, encoding="utf-8"
        )

    def write_engine_lock(self, python="3.14.8", targets=("macos-arm64",)):
        """Overwrite the precursor's pre-MD3 stub lock with the actual MD3
        schema: logical component names (not filenames), the CPython version,
        the PBS source and the per-target archives."""
        rows = [
            "# fixture python-components.lock (actual MD3 schema)",
            "schema = 1",
            f'python = "{python}"',
            'pbs = "20261003"',
            "",
            "[pbsSource]",
            'tag = "20261003"',
            'name = "python-build-standalone-20261003-src.tar.gz"',
            'url = "https://example.invalid/python-build-standalone-20261003-src.tar.gz"',
            'sha256 = "' + "0" * 64 + '"',
            "",
        ]
        for target in targets:
            rows += [
                "[[target]]",
                f'id = "{target}"',
                f'archive = "cpython-{python}+20261003-{target}-install_only_stripped.tar.gz"',
                'sha256 = "' + "0" * 64 + '"',
                "",
            ]
        for name, version, kind in (
            ("CPython", python, "runtime"),
            ("OpenSSL", "3.5.9", "native"),
            ("CA certificate bundle (certifi cacert.pem)", "2026.06.17", "data"),
            ("yt-dlp", "2026.08.19", "python"),
            ("neutrodyne_ytx shim", "SHIM_API_VERSION 1", "python"),
        ):
            rows += [
                "[[component]]",
                f'name = "{name}"',
                f'version = "{version}"',
                'origin = "https://example.invalid/component"',
                'licence = "Python-2.0"',
                f'kind = "{kind}"',
                'aboutLibrariesId = "fixture"',
                "",
            ]
        self.write("youtube/ytdlp-desktop/python-components.lock", "\n".join(rows).encode())

    def write(self, rel: str, data: bytes):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def run(self, *args, env_extra=None, executable_shell="bash") -> subprocess.CompletedProcess:
        env = dict(self.env)
        env.update(env_extra or {})
        return subprocess.run(
            [executable_shell, *map(str, args)],
            cwd=self.root, env=env, capture_output=True, text=True, timeout=120,
        )

    def image_check(self, *inputs, env_extra=None):
        return self.run(
            self.root / "scripts/ci/check-desktop-image.sh", *inputs,
            env_extra=env_extra,
        )

    # ----- skiko runtime jar / Compose stub derivation -----------------------

    @staticmethod
    def _skiko_base(name: str) -> str:
        """Compose's extraction rule: an entry is extracted when its name with any
        '.sha256' suffix removed is in the target's native set."""
        return name[:-len(".sha256")] if name.endswith(".sha256") else name

    @staticmethod
    def skiko_extraction(os_arch: str) -> set:
        """The native entries compose-gradle-plugin 1.12.1 extracts for the target
        (AbstractJPackageTaskKt.unpackSkikoForCurrentOS)."""
        os_name, arch = os_arch.split("-")
        if os_name == "macos":
            return {f"libskiko-macos-{arch}.dylib"}
        if os_name == "linux":
            return {f"libskiko-linux-{arch}.so"}
        return {f"skiko-windows-{arch}.dll", "icudtl.dat"}

    @staticmethod
    def default_skiko_entries(os_arch: str) -> list:
        """A source runtime jar: META-INF, the target native pair, and another arch's
        native that stays in the repacked stub (the real macos-arm64 jar carries both)."""
        os_name, arch = os_arch.split("-")
        ext = {"macos": "dylib", "linux": "so", "windows": "dll"}[os_name]
        other_arch = "x64" if arch == "arm64" else "arm64"
        if os_name == "windows":
            native = f"skiko-windows-{arch}.dll"
            other = f"skiko-windows-{other_arch}.dll"
        else:
            native = f"libskiko-{os_name}-{arch}.{ext}"
            other = f"libskiko-{os_name}-{other_arch}.{ext}"
        entries = [
            ("META-INF/", b""),
            ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"),
            (native, b"NATIVE-" + os_arch.encode()),
            (native + ".sha256", b"0" * 64),
            (other, b"NATIVE-OTHER"),
        ]
        if os_name == "windows":
            entries.append(("icudtl.dat", b"ICU"))
        return entries

    def skiko_source(self, os_arch="linux-x64", version="0.9.30", entries=None) -> list:
        """Record the Licensee-checked source jar (regular row) and derive its stub
        manifest row from the source artifact and the target extraction rule."""
        entries = entries if entries is not None else self.default_skiko_entries(os_arch)
        stem = f"skiko-awt-runtime-{os_arch}-{version}"
        self.manifest(stem, zip_bytes(entries))
        extracted = self.skiko_extraction(os_arch)
        remaining = [(n, d) for n, d in entries if self._skiko_base(n) not in extracted]
        specs = ",".join(
            f"{n.rsplit('/', 1)[-1]}={self.canonical_content_hash(n, d)}"
            for n, d in entries
            if self._skiko_base(n) in extracted
        )
        self.stub_rows[f"{stem}.jar"] = (
            f"{self.canonical_hash(zip_bytes(remaining))}  {stem}.jar  {specs}"
        )
        return entries

    def skiko_stub(self, img, os_arch="linux-x64", version="0.9.30", entries=None,
                  mutate=None, stamp=None, write_extracted=True) -> str:
        """Write the derived stub jar (and its extracted natives) into the image."""
        entries = entries if entries is not None else self.default_skiko_entries(os_arch)
        extracted = self.skiko_extraction(os_arch)
        remaining = [(n, d) for n, d in entries if self._skiko_base(n) not in extracted]
        data = zip_bytes(remaining)
        if mutate is not None:
            data = mutate(data)
        stamp = stamp if stamp is not None else unpadded_md5(data)
        name = f"skiko-awt-runtime-{os_arch}-{version}-{stamp}.jar"
        self.write(f"{img}/lib/app/{name}", data)
        if write_extracted:
            for n, d in entries:
                if self._skiko_base(n) in extracted:
                    self.write(f"{img}/lib/app/{n.rsplit('/', 1)[-1]}", d)
        return name


def release_file(modules="java.base java.desktop") -> bytes:
    return (
        f'JAVA_VERSION="{JAVA_VERSION}"\nMODULES="{" ".join(sorted(modules.split()))}"\n'
    ).encode()


def smoke_line(root: Path) -> str:
    lock = (root / "desktopApp/runtime.lock").read_text(encoding="utf-8")
    prop = lambda k: next(
        line.split("=", 1)[1] for line in reversed(lock.splitlines())
        if line.startswith(k + "=")
    )
    return (
        'SMOKE {"javaVendor":"%s","javaVendorVersion":"%s","javaRuntimeVersion":"%s"}\n'
        % (prop("vendor"), prop("vendorVersion"), prop("javaRuntimeVersion"))
    )


# Mach-O magic (little-endian MH_MAGIC_64) so the native-file comparison path
# runs image_compare_file; identical bytes pass the cmp -s leg without codesign.
MACHO = bytes.fromhex("cffaedfe") + b"\x00" * 60


# --- canonical JAR identity fixtures -------------------------------------------

def zip_bytes(entries, compression=zipfile.ZIP_DEFLATED, extra=b""):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=compression) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name)
            info.extra = extra
            z.writestr(info, data)
    return out.getvalue()


def duplicate_zip_bytes():
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("A.class", b"CODE-A")
        z.writestr("A.class", b"CODE-B")
    return out.getvalue()


def thin_macho(sig=b"SIGNATURE", code=b"CODE", linkedit_extra=b"", dataoff=0x1800,
               total=None, page=0x1000):
    """A minimal 64-bit little-endian Mach-O: one __LINKEDIT segment and one
    LC_CODE_SIGNATURE; re-signing keeps the blob's offset and extends it to EOF."""
    hdr, seg, sig_cmd = 32, 72, 16
    sizeofcmds = seg + sig_cmd
    linkedit_off = page
    if total is None:
        total = dataoff + len(sig)
    fsize = total - linkedit_off
    vmsize = -(-fsize // page) * page
    b = bytearray(total)
    struct.pack_into("<IiiIIII", b, 0, 0xFEEDFACF, 0x01000007, 3, 2, 2, sizeofcmds, 0)
    struct.pack_into("<I", b, 28, 0)
    b[hdr:hdr + seg] = struct.pack(
        "<II16sQQQQIIII", 0x19, seg, b"__LINKEDIT".ljust(16, b"\0"),
        0, vmsize, linkedit_off, fsize, 5, 1, 0, 0)
    b[hdr + seg:hdr + sizeofcmds] = struct.pack("<IIII", 0x1D, sig_cmd, dataoff, len(sig))
    b[hdr + sizeofcmds:hdr + sizeofcmds + len(code)] = code
    if linkedit_extra:
        b[linkedit_off:linkedit_off + len(linkedit_extra)] = linkedit_extra
    b[dataoff:dataoff + len(sig)] = sig
    return bytes(b)


class DesktopImageGatesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fx = Fixture(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    # ----- fixture builders -------------------------------------------------

    def flat_image(self, name="Neutrodyne", extra_jars=(), manifest_skiko=True):
        """A Linux-style image: flat lib/runtime, bin/ launcher, lib/app JARs."""
        fx = self.fx
        img = f"imgs/{name}"
        fx.write(f"{img}/bin/Neutrodyne", b"#!/bin/sh\n")
        fx.write(f"{img}/lib/runtime/release", release_file())
        fx.write(f"{img}/lib/runtime/legal/LICENSE", b"GPL-2.0 Classpath exception\n")
        fx.write(f"{img}/lib/runtime/lib/jrt-fs.jar", b"JRT")
        for stem, data in (("core", b"CORE"), ("util", b"UTIL")):
            fx.write(f"{img}/lib/app/{fx.manifest(stem, data)}", data)
        if manifest_skiko:
            # The Licensee-checked source runtime jar and its derived stub record.
            self.fx.skiko_source("linux-x64", "0.9.30")
        return f"{img}"

    def mac_app(self, root_dir="imgs/Neutrodyne.app", jars=(("app", b"APP"),)):
        """A jpackage .app: runtime home at Contents/runtime/Contents/Home."""
        fx = self.fx
        app = root_dir
        fx.write(f"{app}/Contents/MacOS/Neutrodyne", b"MACHO-LAUNCHER")
        fx.write(f"{app}/Contents/runtime/Contents/Home/release", release_file())
        fx.write(f"{app}/Contents/runtime/Contents/Home/legal/LICENSE", b"GPL-2.0\n")
        fx.write(f"{app}/Contents/runtime/Contents/Home/lib/jrt-fs.jar", b"JRT")
        fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", MACHO
        )
        for stem, data in jars:
            fx.write(f"{app}/Contents/app/{fx.manifest(stem, data)}", data)
        return app

    def mac_jdk(self, rel="jdks/macos-arm64/jdk"):
        """The pinned Temurin tree pairing the image's Contents/runtime subtree:
        every image runtime file must have a counterpart here."""
        fx = self.fx
        for rel_path, data in (
            ("release", release_file()),
            ("legal/LICENSE", b"GPL-2.0\n"),
            ("lib/jrt-fs.jar", b"JRT"),
            ("lib/server/libjvm.dylib", MACHO),
        ):
            fx.write(f"{rel}/Contents/Home/{rel_path}", data)
        return rel

    def engine_app(self, root_dir="imgs/EngineApp", minor="3.14", interpreter=True,
                   cacert=True, stdlib=True, ytdlp=True, extra=()):
        """A macOS .app carrying the representative real trimmed engine tree:
        the interpreter, cacert.pem, lib/python<minor> with threading.py,
        concurrent/futures/thread.py, asyncio/threads.py and _pyrepl/readline.py,
        the yt-dlp archive, the shim and the keys."""
        fx = self.fx
        app = root_dir
        engine = f"{app}/Contents/app/resources/engine"
        fx.write(f"{app}/Contents/MacOS/Neutrodyne", b"MACHO-LAUNCHER")
        fx.write(f"{app}/Contents/runtime/Contents/Home/release", release_file())
        fx.write(f"{app}/Contents/runtime/Contents/Home/legal/LICENSE", b"GPL-2.0\n")
        fx.write(f"{app}/Contents/runtime/Contents/Home/lib/jrt-fs.jar", b"JRT")
        data = b"APP"
        fx.write(f"{app}/Contents/app/{fx.manifest('app', data)}", data)
        if interpreter:
            fx.write(f"{engine}/python/bin/python3", b"#!/bin/sh\n")
            os.chmod(fx.root / f"{engine}/python/bin/python3", 0o755)
        if cacert:
            fx.write(f"{engine}/cacert.pem", b"FIXTURE-CA-BUNDLE")
        if stdlib:
            for rel in ("threading.py", "concurrent/futures/thread.py",
                        "asyncio/threads.py", "_pyrepl/readline.py", "os.py"):
                fx.write(f"{engine}/python/lib/python{minor}/{rel}", b"# stdlib fixture\n")
        if ytdlp:
            fx.write(
                f"{engine}/ytdlp/yt-dlp",
                zip_bytes([("__main__.py", b""), ("yt_dlp/__init__.py", b"")]),
            )
        fx.write(f"{engine}/shim/neutrodyne_ytx/__init__.py", b"")
        fx.write(f"{engine}/keys/engine-manifest-ed25519.pub", b"")
        for rel, content in extra:
            fx.write(f"{engine}/{rel}", content)
        return app

    def windows_image(self, name="Neutrodyne", extra_root=(), launcher=True):
        """A Windows-layout image: Neutrodyne.exe at the root, app/, runtime/
        (recognised by runtime/bin/java.exe) and non-entry DLL/data files."""
        fx = self.fx
        img = f"imgs/{name}"
        if launcher:
            fx.write(f"{img}/Neutrodyne.exe", b"EXE")
        fx.write(f"{img}/runtime/bin/java.exe", b"JAVA")
        fx.write(f"{img}/runtime/release", release_file())
        fx.write(f"{img}/runtime/legal/LICENSE", b"GPL-2.0\n")
        fx.write(f"{img}/runtime/lib/jrt-fs.jar", b"JRT")
        data = b"APP"
        fx.write(f"{img}/app/{fx.manifest('app', data)}", data)
        for rel, content in extra_root:
            fx.write(f"{img}/{rel}", content)
        return img

    def fake(self, name: str, body: str):
        path = self.root / "fakebin" / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def fake_hdiutil(self):
        # attach requires -nobrowse and -readonly (the gate's read-only mount);
        # the 'volume' is the sibling <dmg>.payload/ tree, copied into the
        # mountpoint so no real DMG is ever mounted by the fixture.
        self.fake(
            "hdiutil",
            """
log="${FAKE_HDIUTIL_LOG:?}"
case "${1:-}" in
    attach)
        shift
        mnt="" dmg="" ro=0 nb=0
        while [ $# -gt 0 ]; do
            case "$1" in
                -readonly) ro=1; shift ;;
                -nobrowse) nb=1; shift ;;
                -mountpoint) mnt="$2"; shift 2 ;;
                -*) shift ;;
                *) dmg="$1"; shift ;;
            esac
        done
        [ "$ro" = 1 ] && [ "$nb" = 1 ] || { echo "attach without -readonly/-nobrowse" >&2; exit 1; }
        [ -n "${FAKE_HDIUTIL_FAIL_ATTACH:-}" ] && exit 1
        [ -n "$mnt" ] && [ -d "$dmg.payload" ] || exit 1
        cp -Rp "$dmg.payload/." "$mnt/"
        echo "attach $mnt" >>"$log"
        ;;
    detach)
        mnt="$2"
        echo "detach $mnt" >>"$log"
        [ -z "${FAKE_HDIUTIL_FAIL_DETACH:-}" ] || exit 1
        ;;
    *) exit 1 ;;
esac
""",
        )

    def fake_codesign_ok(self):
        self.fake("codesign", "exit 0\n")

    # ----- I1: JAR classpath membership --------------------------------------

    def test_flat_image_passes(self):
        """Mangled-name jars matching the manifest's name+sha256, and the derived
        skiko stub bound to the source artifact's expected stub hash."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_inert_replay_contract_is_allowed_but_test_implementations_fail(self):
        contract = "ch/lkmc/neutrodyne/youtube/engine/YtxTestArming.class"
        for entry, accepted in (
            (contract, True),
            ("ch/lkmc/neutrodyne/youtube/engine/FakeYtxTestArming.class", False),
            ("ch/lkmc/neutrodyne/youtube/engine/YtxTestArmingTest.class", False),
            ("ch/lkmc/neutrodyne/youtube/engine/YtxTestArming$Test.class", False),
            ("fixtures/YtxTestArming.class", False),
        ):
            with self.subTest(entry=entry):
                img = self.flat_image(name="probe-" + str(accepted) + str(len(entry)))
                data = zip_bytes([(entry, b"CONTRACT-FIXTURE")])
                self.fx.write(f"{img}/lib/app/{self.fx.manifest('engine', data)}", data)
                self.fx.write_manifest()
                result = self.fx.image_check(img)
                self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)

    def test_production_engine_selftest_classes_pass_but_test_neighbors_fail(self):
        prefix = "ch/lkmc/neutrodyne/youtube/engine/"
        for index, (name, accepted) in enumerate((
            ("EngineSelfTestRunner.class", True),
            ("EngineSelfTestRunner$Companion.class", True),
            ("EngineSelfTestRunner$run$1.class", True),
            ("EngineSelfTestRunnerTest.class", False),
            ("EngineSelfTestRunner$Test.class", False),
            ("FakeEngineSelfTestRunner.class", False),
            ("EngineSelfTestRunner$run$1Test.class", False),
        )):
            with self.subTest(name=name):
                img = self.flat_image(name=f"selfcheck-{index}")
                data = zip_bytes([(prefix + name, b"PRODUCTION-SELFCHECK-FIXTURE")])
                self.fx.write(f"{img}/lib/app/{self.fx.manifest('engine', data)}", data)
                self.fx.write_manifest()
                result = self.fx.image_check(img)
                self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)

    def test_jar_content_mismatch_fails(self):
        """The name is on the manifest but the bytes are not the row's sha256."""
        img = self.flat_image()
        self.fx.write(f"{img}/lib/app/{mangled('core', b'CORE')}", b"CORE-EVIL")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the Licensee-checked", r.stderr)

    def test_unlisted_stamped_jar_fails(self):
        img = self.flat_image()
        data = b"ROGUE"
        self.fx.write(f"{img}/lib/app/{mangled('rogue', data)}", data)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)

    def test_unstamped_jar_fails(self):
        """A jar whose -<suffix> is not its own md5 is not a manifest entry
        either (manifest rows are all content-stamped names)."""
        img = self.flat_image()
        self.fx.write(f"{img}/lib/app/core-deadbeef.jar", b"CORE")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)

    def test_unchecked_class_in_skiko_stub_fails(self):
        """Root's confirmed bypass: an arbitrary ZIP carrying org/unchecked/Surprise.class
        passes the prefix/version stub exception while the manifest holds a matching
        os/arch/version skiko row. The stub's content must be bound, not its name."""
        img = self.flat_image()
        data = zip_bytes([("org/unchecked/Surprise.class", b"UNCHECKED-CODE")])
        name = mangled("skiko-awt-runtime-linux-x64-0.9.30", data)
        self.fx.write(f"{img}/lib/app/{name}", data)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko", r.stderr)

    def test_skiko_stub_needs_the_stub_manifest_entry(self):
        """No blanket allowlist: a stamped skiko stub fails when the stub manifest
        carries no row for its source artifact."""
        img = self.flat_image(manifest_skiko=False)
        self.fx.skiko_stub(img)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko-awt-runtime-linux-x64", r.stderr)

    def test_skiko_stub_needs_the_same_os_arch(self):
        img = self.flat_image()  # stub manifest source is linux-x64
        self.fx.skiko_stub(img, os_arch="windows-x64")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko-awt-runtime-windows-x64", r.stderr)

    def test_skiko_stub_rejects_a_false_version(self):
        """A stub whose version segment matches no source row is rejected even
        though a same-os-arch row exists."""
        img = self.flat_image()  # stub manifest source is 0.9.30
        self.fx.skiko_stub(img, version="0.9.31")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko-awt-runtime-linux-x64-0.9.31", r.stderr)

    def test_skiko_source_jar_keeps_its_regular_membership(self):
        """The source artifact's ordinary manifest row still binds an unstubbed
        source jar under its mangled name."""
        img = self.flat_image()
        data = zip_bytes(self.fx.default_skiko_entries("linux-x64"))
        self.fx.write(
            f"{img}/lib/app/{mangled('skiko-awt-runtime-linux-x64-0.9.30', data)}", data
        )
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- skiko stub content binding (root's confirmed bypass) --------------

    def skiko_stub_with_entries(self, img, entries):
        self.fx.skiko_stub(img, entries=entries)
        self.fx.write_manifest()
        return self.fx.image_check(img)

    def test_skiko_stub_added_resource_fails(self):
        img = self.flat_image()
        entries = self.fx.default_skiko_entries("linux-x64") + [("resource.txt", b"DATA")]
        r = self.skiko_stub_with_entries(img, entries)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko stub content differs", r.stderr)

    def test_skiko_stub_mutated_native_fails(self):
        """A native payload left in the stub is bound, not just classes."""
        img = self.flat_image()
        entries = [
            (n, b"NATIVE-EVIL" if n == "libskiko-linux-arm64.so" else d)
            for n, d in self.fx.default_skiko_entries("linux-x64")
        ]
        r = self.skiko_stub_with_entries(img, entries)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko stub content differs", r.stderr)

    def test_skiko_stub_extra_native_fails(self):
        img = self.flat_image()
        entries = self.fx.default_skiko_entries("linux-x64") + [
            ("libskiko-linux-x64.so.1", b"EXTRA-NATIVE")
        ]
        r = self.skiko_stub_with_entries(img, entries)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko stub content differs", r.stderr)

    def test_skiko_stub_entry_rename_fails(self):
        img = self.flat_image()
        entries = [
            ("META-INF/OTHER.MF" if n == "META-INF/MANIFEST.MF" else n, d)
            for n, d in self.fx.default_skiko_entries("linux-x64")
        ]
        r = self.skiko_stub_with_entries(img, entries)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko stub content differs", r.stderr)

    def test_skiko_stub_entry_removal_fails(self):
        img = self.flat_image()
        entries = [
            (n, d) for n, d in self.fx.default_skiko_entries("linux-x64")
            if n != "META-INF/MANIFEST.MF"
        ]
        r = self.skiko_stub_with_entries(img, entries)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko stub content differs", r.stderr)

    def test_skiko_stub_duplicate_entry_fails(self):
        img = self.flat_image()
        entries = self.fx.default_skiko_entries("linux-x64") + [
            ("META-INF/MANIFEST.MF", b"DUPLICATE")
        ]
        r = self.skiko_stub_with_entries(img, entries)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("cannot read skiko stub content", r.stderr)

    def test_skiko_stub_duplicate_jar_fails(self):
        """Two stubs for one source are ambiguous, however each is stamped."""
        img = self.flat_image()
        self.fx.skiko_stub(img, stamp="aaaa")
        self.fx.skiko_stub(img, stamp="bbbb")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("duplicate skiko stub JAR", r.stderr)

    def test_skiko_stub_non_hex_stamp_fails(self):
        """A name whose suffix is not Compose's hex md5 stamp is ambiguous."""
        img = self.flat_image()
        self.fx.skiko_stub(img, stamp="zzzz")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)

    def test_skiko_stub_false_expected_hash_fails(self):
        """A stub manifest row whose expected hash does not match the source-derived
        stub is rejected even though the name and version match."""
        img = self.flat_image()
        self.fx.stub_rows["skiko-awt-runtime-linux-x64-0.9.30.jar"] = (
            "0" * 64 + "  skiko-awt-runtime-linux-x64-0.9.30.jar  "
        )
        self.fx.skiko_stub(img)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko stub content differs", r.stderr)

    def test_skiko_stub_missing_extracted_native_fails(self):
        img = self.flat_image()
        self.fx.skiko_stub(img, write_extracted=False)
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko extracted native missing", r.stderr)

    def test_skiko_stub_mutated_extracted_native_fails(self):
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.fx.write(f"{img}/lib/app/libskiko-linux-x64.so", b"NATIVE-EVIL")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("skiko extracted native differs", r.stderr)

    def test_skiko_stub_extracted_native_resigned_passes(self):
        """The extracted native is bound by normalized content, so a re-signed
        (post-jpackage/ad-hoc) Mach-O still matches the source artifact."""
        img = self.flat_image()
        native = "libskiko-linux-x64.so"
        entries = [
            (n, thin_macho() if n == native else d)
            for n, d in self.fx.default_skiko_entries("linux-x64")
        ]
        self.fx.skiko_source("linux-x64", "0.9.30", entries=entries)
        self.fx.skiko_stub(img, entries=entries)
        self.fx.write(f"{img}/lib/app/{native}", thin_macho(sig=b"OTHER"))
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- I2: the macOS runtime home is Contents/runtime/Contents/Home -------

    def test_macos_app_runtime_sources_image(self):
        """check-runtime-sources --image on a .app: release/legal under the
        nested home, native files still compared against jdk/Contents/Home."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        r = self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt",
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_macos_app_native_compare_still_binds(self):
        """Root path meaning preserved: an image file with no counterpart under
        jdk/Contents/Home still fails (the compare did not silently loosen)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/extra.dylib", MACHO
        )
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        r = self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt",
        )
        self.assertNotEqual(0, r.returncode)
        self.assertIn("no counterpart in the pinned Temurin archive", r.stderr)

    @unittest.skipUnless(sys.platform == "darwin" and shutil.which("ditto"), "needs macOS ditto")
    def test_mac_zip_preserves_launcher_mode_and_runtime_links(self):
        app = self.mac_app()
        image = self.root / app
        (image / "Contents/MacOS/Neutrodyne").chmod(0o755)
        (image / "Contents/runtime/Contents/Home/legal/LICENSE.link").symlink_to("LICENSE")
        self.stamp_epoch(image)
        self.fx.write_manifest()
        # These are sealed-bundle properties, checked after the shipped extractor runs.
        self.fake("codesign", '''
for image; do :; done
[ -x "$image/Contents/MacOS/Neutrodyne" ] &&
    [ -L "$image/Contents/runtime/Contents/Home/legal/LICENSE.link" ]
''')
        direct = self.fx.image_check(app)
        self.assertEqual(0, direct.returncode, direct.stdout + direct.stderr)
        archive = self.root / "app.zip"
        subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(image), str(archive)], check=True)
        extracted = self.fx.image_check(archive)
        self.assertEqual(0, extracted.returncode, extracted.stdout + extracted.stderr)


    def test_macos_layout_in_image_scan(self):
        """check-desktop-image's runtime layout check resolves the nested home
        (a .app dir name pulls in codesign, so this image root uses the same
        Contents/ layout without the .app suffix)."""
        app = self.mac_app(root_dir="imgs/NestedApp")
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_make_runtime_sources_md_reads_the_nested_home(self):
        self.mac_app()
        r = self.fx.run(
            self.root / "scripts/ci/make-runtime-sources-md.sh",
            "--image", "imgs/Neutrodyne.app", "--out", "RUNTIME-SOURCES.md",
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        out = (self.root / "RUNTIME-SOURCES.md").read_text(encoding="utf-8")
        self.assertIn("java.base", out)

    def test_flat_runtime_still_works(self):
        """The flat lib/runtime root keeps resolving as before."""
        app = "imgs/FlatApp"
        self.fx.write(f"{app}/lib/runtime/release", release_file("java.base"))
        r = self.fx.run(
            self.root / "scripts/ci/make-runtime-sources-md.sh",
            "--image", app, "--out", "OUT.md",
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- re-signed Mach-O natives under check-runtime-sources ---------------

    def runtime_sources(self, app, jdk):
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        return self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt",
        )

    def test_macos_resigned_native_passes(self):
        """The jpackage ad-hoc re-sign makes image natives differ byte-wise
        from the pinned Temurin files; their canonical content must compare
        equal — here with signature blobs of different sizes, the residue
        case `codesign --remove-signature` cannot equalize."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"JVM-CODE", sig=b"ADHOC-1"),
        )
        self.fx.write(
            f"{jdk}/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"JVM-CODE", sig=b"ADOPTIUM" * 800),
        )
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_macos_resigned_code_tamper_fails(self):
        """A code difference is not a signature difference: the normalized
        contents still differ and the gate reports it (fail closed)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"EVIL-CODE", sig=b"ADHOC-1"),
        )
        self.fx.write(
            f"{jdk}/Contents/Home/lib/server/libjvm.dylib",
            thin_macho(code=b"JVM-CODE", sig=b"ADOPTIUM" * 800),
        )
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    # Real-toolchain variants: the same pass/fail pair over a dylib compiled
    # and signed in the test — not the shipped Neutrodyne artifact.

    def clang_dylib(self, tag: str, ret: int) -> Path:
        """Compile a one-function dylib with the host clang (a fixture binary
        built here — not the shipped Neutrodyne artifact)."""
        work = Path(self._tmp.name) / "cc" / tag
        work.mkdir(parents=True, exist_ok=True)
        src, lib = work / "probe.c", work / "probe.dylib"
        src.write_text(f"int probe(void) {{ return {ret}; }}\n")
        subprocess.run(
            ["clang", "-dynamiclib", "-o", str(lib), str(src)],
            check=True, capture_output=True, text=True, timeout=60,
        )
        return lib

    def adhoc_sign(self, src: Path, tag: str, ident: str, *opts) -> bytes:
        """Re-sign a copy of src with the real codesign under a distinct ad-hoc
        identity/options and return the signed bytes."""
        dst = src.with_name(f"{src.stem}-{tag}.dylib")
        shutil.copy2(src, dst)
        subprocess.run(
            ["codesign", "-f", "-s", "-", "--identifier", ident, *opts, str(dst)],
            check=True, capture_output=True, text=True, timeout=60,
        )
        return dst.read_bytes()

    @unittest.skipUnless(
        sys.platform == "darwin" and shutil.which("clang") and shutil.which("codesign"),
        "needs the macOS host toolchain (clang + codesign)",
    )
    def test_macos_real_signed_native_passes(self):
        """Real-toolchain variant of the re-signed pass: one dylib under two
        different ad-hoc identities has identical canonical content."""
        lib = self.clang_dylib("ok", 42)
        img = self.adhoc_sign(lib, "img", "ch.lkmc.fixture.img", "--options", "runtime")
        ref = self.adhoc_sign(lib, "ref", "ch.lkmc.fixture.ref")
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", img
        )
        self.fx.write(f"{jdk}/Contents/Home/lib/server/libjvm.dylib", ref)
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    @unittest.skipUnless(
        sys.platform == "darwin" and shutil.which("clang") and shutil.which("codesign"),
        "needs the macOS host toolchain (clang + codesign)",
    )
    def test_macos_real_code_tamper_fails(self):
        """Real-toolchain variant of the tamper fail: a different function body
        under the same signing treatment stays a normalized violation."""
        lib = self.clang_dylib("ok", 42)
        evil = self.clang_dylib("evil", 43)
        img = self.adhoc_sign(evil, "img", "ch.lkmc.fixture.img", "--options", "runtime")
        ref = self.adhoc_sign(lib, "ref", "ch.lkmc.fixture.ref")
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", img
        )
        self.fx.write(f"{jdk}/Contents/Home/lib/server/libjvm.dylib", ref)
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    # ----- I3: DMG payload validation -----------------------------------------

    def dmg(self, payload_app=None, name="dist/neutrodyne-1.0.0-macos-arm64.dmg"):
        self.fx.write(name, b"DMG-BYTES")
        if payload_app is not None:
            # the fake mount's volume contents live in <dmg>.payload/
            src = self.root / payload_app
            dst = self.root / f"{name}.payload" / "Neutrodyne.app"
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(src, dst)
            self.stamp_epoch(dst)
        return name

    def stamp_epoch(self, root: Path):
        """Set every non-symlink entry below root to the fixed 1980-02-01 UTC
        epoch, the way a trained image's post-image-restored payload ships."""
        for p in sorted(root.rglob("*")) + [root]:
            if not p.is_symlink():
                os.utime(p, (self.AOT_EPOCH_S, self.AOT_EPOCH_S), follow_symlinks=False)

    def test_dmg_payload_gets_the_image_scan(self):
        app = self.mac_app()
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(dmg, env_extra={"FAKE_HDIUTIL_LOG": str(log)})
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        entries = log.read_text(encoding="utf-8").splitlines()
        self.assertTrue(any(e.startswith("attach ") for e in entries), entries)
        self.assertTrue(any(e.startswith("detach ") for e in entries), entries)

    def test_dmg_payload_violation_fails(self):
        """The mounted payload receives the identical required checks: a jar
        outside the Licensee-checked classpath fails inside the DMG too."""
        app = self.mac_app(jars=(("app", b"APP"),))
        rogue = b"ROGUE"
        self.fx.write(f"{app}/Contents/app/{mangled('rogue', rogue)}", rogue)
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(dmg, env_extra={"FAKE_HDIUTIL_LOG": str(log)})
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the Licensee-checked runtime classpath", r.stderr)
        self.assertIn("detach ", log.read_text(encoding="utf-8"))

    def test_dmg_detach_is_guaranteed_on_abort(self):
        """A hard exit mid-scan (here: a failing python3 inside the JAR entry
        scan) still detaches the mount through the EXIT trap."""
        app = self.mac_app()
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        self.fake("python3", "exit 1\n")
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(dmg, env_extra={"FAKE_HDIUTIL_LOG": str(log)})
        self.assertNotEqual(0, r.returncode)
        self.assertIn("detach ", log.read_text(encoding="utf-8"))

    def test_dmg_attach_failure_fails(self):
        app = self.mac_app()
        self.fx.write_manifest()
        dmg = self.dmg(payload_app=app)
        self.fake_hdiutil()
        self.fake_codesign_ok()
        log = self.root / "hdiutil.log"
        r = self.fx.image_check(
            dmg,
            env_extra={
                "FAKE_HDIUTIL_LOG": str(log),
                "FAKE_HDIUTIL_FAIL_ATTACH": "1",
            },
        )
        self.assertNotEqual(0, r.returncode)
        self.assertIn("attach", r.stderr)

    # ----- 11 AOT cache: the .cfg classpath's fixed epoch ----------------------
    # The archive binds only its classpath inputs (the JARs app.classpath
    # names); the front-ends own every other entry's metadata.

    AOT_EPOCH_S = 318211200  # 1980-02-01T00:00:00Z — AOT_IMAGE_TIMESTAMP_MS

    def aot_cfg(self, img):
        """Make the image AOT-configured: a launcher .cfg whose app.classpath
        lists every JAR beside it and whose java-options read the trained
        archive, plus the archive file. Returns the app dir."""
        for appdir in (f"{img}/app", f"{img}/Contents/app", f"{img}/lib/app"):
            base = self.root / appdir
            if base.is_dir():
                jars = sorted(p.name for p in base.glob("*.jar"))
                lines = ["[Application]"] + [
                    f"app.classpath=$APPDIR/{n}" for n in jars
                ] + ["", "[JavaOptions]",
                     "java-options=-XX:AOTCache=$APPDIR/neutrodyne.aot"]
                self.fx.write(f"{appdir}/Neutrodyne.cfg",
                              ("\n".join(lines) + "\n").encode())
                self.fx.write(f"{appdir}/neutrodyne.aot", b"AOT")
                return appdir
        raise AssertionError(f"no app dir in {img}")

    def tarball(self, image_rel, name="dist/neutrodyne.tar.gz", epoch=AOT_EPOCH_S,
                jar_epoch=None):
        """A .tar.gz package holding <image_rel>/…; JAR entries record jar_epoch
        (default: epoch) and every other entry records epoch — only the
        classpath JARs' trained times are the archive's bound inputs."""
        src = self.root / image_rel
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(path, "w:gz") as tar:
            for p in sorted(src.rglob("*")):
                rel = str(p.relative_to(src))
                info = tar.gettarinfo(str(p), arcname=rel)
                info.mtime = jar_epoch if (
                    jar_epoch is not None and rel.endswith(".jar")
                ) else epoch
                if p.is_file():
                    with open(p, "rb") as fh:
                        tar.addfile(info, fh)
                else:
                    tar.addfile(info)
        return name

    def test_targz_payload_epoch_passes(self):
        """A trained payload whose classpath JARs carry the fixed epoch."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        r = self.fx.image_check(self.tarball(img))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    @unittest.skipUnless(sys.platform == "darwin", "needs the macOS system Bash")
    def test_macos_system_bash_runs_the_image_and_epoch_gates(self):
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        result = self.fx.run(
            self.root / "scripts/ci/check-desktop-image.sh", self.tarball(img),
            executable_shell="/bin/bash",
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("check-desktop-image: OK", result.stdout)
        self.assertIn("size: package", result.stdout)
        syntax = subprocess.run(
            ["/bin/bash", "-n", str(self.root / "scripts/ci/check-desktop-image.sh")],
            capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(0, syntax.returncode, syntax.stderr)

    @unittest.skipUnless(sys.platform == "darwin", "needs the macOS system Bash")
    def test_macos_system_bash_rejects_a_drifted_classpath(self):
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        result = self.fx.run(
            self.root / "scripts/ci/check-desktop-image.sh",
            self.tarball(img, jar_epoch=self.AOT_EPOCH_S + 1),
            executable_shell="/bin/bash",
        )
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("off the trained epoch", result.stderr)
        self.assertNotIn("check-desktop-image: OK", result.stdout)

    @unittest.skipUnless(sys.platform == "darwin", "needs the macOS system Bash")
    def test_macos_system_bash_rejects_an_epoch_probe_failure(self):
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        self.fake("python3", "if [ -n \"${APPDIR:-}\" ]; then exit 37; fi\nexec \"" + sys.executable + "\" \"$@\"\n")
        result = self.fx.run(
            self.root / "scripts/ci/check-desktop-image.sh", self.tarball(img),
            executable_shell="/bin/bash",
        )
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("cannot inspect the packaged AOT classpath epoch", result.stderr)

    def test_targz_payload_drifted_jar_fails(self):
        """A one-second drift on a classpath JAR — what jpackage's
        attribute-losing staging copy produces without the post-image hook —
        is a violation."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        r = self.fx.image_check(self.tarball(img, jar_epoch=self.AOT_EPOCH_S + 1))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("off the trained epoch", r.stderr)

    def test_targz_non_classpath_restamp_passes(self):
        """The package front-ends own non-classpath metadata: drifted
        directories, launchers, legal files and the .cfg/.aot themselves do not
        false-fail — the archive binds the classpath inputs only."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        r = self.fx.image_check(self.tarball(
            img, epoch=self.AOT_EPOCH_S + 3600, jar_epoch=self.AOT_EPOCH_S))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_targz_undeclared_jar_fails(self):
        """A JAR beside the .cfg that the classpath does not declare is outside
        the archive's consistency set: fail closed."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        data = b"EXTRA"
        self.fx.write(f"{img}/lib/app/{self.fx.manifest('extra', data)}", data)
        self.fx.write_manifest()
        r = self.fx.image_check(self.tarball(img))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not on the .cfg classpath", r.stderr)

    def test_targz_classpath_directory_fails_closed(self):
        """A classpath entry resolving to a directory carries no bindable file
        metadata: fail closed."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        appdir = self.aot_cfg(img)
        cfg = self.root / appdir / "Neutrodyne.cfg"
        cfg.write_text(cfg.read_text(encoding="utf-8").replace(
            "[JavaOptions]", "app.classpath=$APPDIR/cpdir\n\n[JavaOptions]"),
            encoding="utf-8")
        self.fx.write(f"{appdir}/cpdir/notes.txt", b"NOTES")
        self.fx.write_manifest()
        r = self.fx.image_check(self.tarball(img))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not a regular file", r.stderr)

    def test_targz_missing_archive_fails(self):
        """The flag without the archive file is a strict-launch failure the
        package must not ship."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        (self.root / f"{img}/lib/app/neutrodyne.aot").unlink()
        self.fx.write_manifest()
        r = self.fx.image_check(self.tarball(img))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("neutrodyne.aot is missing", r.stderr)

    def test_zip_payload_gets_the_image_scan(self):
        """The checker's ZIP extraction boundary (extract-zip.py off Darwin, ditto
        on Darwin) feeds the same image rules and restores the artifact's entry
        times instead of the extraction time — the .cfg classpath JARs included."""
        img = self.flat_image()
        self.fx.skiko_stub(img)
        self.aot_cfg(img)
        self.fx.write_manifest()
        archive = self.root / "dist/neutrodyne-1.0.0-windows-x64.zip"
        archive.parent.mkdir(parents=True, exist_ok=True)
        utc_extra = struct.pack("<HHB", 0x5455, 5, 0x01) + struct.pack("<I", self.AOT_EPOCH_S)
        with zipfile.ZipFile(archive, "w") as out:
            for path in sorted((self.root / img).rglob("*")):
                rel = path.relative_to(self.root / img)
                name = str(rel) + "/" if path.is_dir() else str(rel)
                # The producer writes the DOS local time plus the UTC 0x5455
                # field; either decodes to the trained epoch on this host.
                info = zipfile.ZipInfo(name, date_time=time.localtime(self.AOT_EPOCH_S)[:6])
                info.extra = utc_extra
                info.compress_type = zipfile.ZIP_STORED
                out.writestr(info, path.read_bytes() if path.is_file() else b"")
        r = self.fx.image_check(archive)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- rule 9: MSI checks through a faked Windows boundary -----------------
    # Fake cygpath records the MSYS -> native conversion; fake powershell.exe
    # serves the declared fakedb/ rows (or fails on demand) and asserts the MSI
    # path arrives as data. The rows stand in for the real COM output.

    MSI_DIR_OK = (
        "TARGETDIR||SourceDir\n"
        "LocalAppDataFolder|TARGETDIR|.\n"
        "INSTALLDIR|LocalAppDataFolder|NEUTRO~1|Neutrodyne-App\n"
    )
    # Real pinned-tool MSI, Windows package run 42457761741 (2026-11-08).
    MSI_BIN_OK = (
        "JpCaDll\nWixCA\nWixUIWixca\nWixUI_Bmp_Banner\nWixUI_Bmp_Dialog\n"
        "WixUI_Ico_Exclam\nWixUI_Ico_Info\nWixUI_Bmp_New\nWixUI_Bmp_Up\n"
    )

    def msi_check(self, dir_rows=MSI_DIR_OK, bin_rows=MSI_BIN_OK,
                  msi="dist/x.msi", **fails):
        self.fx.write(msi, b"MSI")
        self.fx.write("fakedb/Directory", dir_rows.encode())
        self.fx.write("fakedb/Binary", bin_rows.encode())
        self.fake("cygpath", r"""
log="${FAKE_CYGPATH_LOG:?}"
echo "$*" >>"$log"
[ "${1:-}" = "-w" ] || exit 1
case "$2" in
    /*) p="$2" ;;
    *) p="$PWD/$2" ;;  # real cygpath resolves relative input against cwd
esac
printf 'C:%s\n' "$p" | tr '/' '\\'
""")
        self.fake("powershell.exe", r"""
echo "msi=${ND_MSI:-} sql=${ND_SQL:-}" >>"${FAKE_PS_LOG:?}"
case "$*" in */*) echo "path in argv" >&2; exit 1 ;; esac
case "${ND_MSI:-}" in
    ?:[\\/]*) ;;
    *) echo "ND_MSI not native: $ND_MSI" >&2; exit 1 ;;
esac
case "${ND_SQL:-}" in
    *"FROM Directory"*) [ -z "${FAKE_PS_FAIL_DIR:-}" ] || exit 1; cat fakedb/Directory ;;
    *"FROM Binary"*) [ -z "${FAKE_PS_FAIL_BIN:-}" ] || exit 1; cat fakedb/Binary ;;
    *) exit 1 ;;
esac
""")
        ps_log, cy_log = self.root / "ps.log", self.root / "cygpath.log"
        env = {"FAKE_PS_LOG": str(ps_log), "FAKE_CYGPATH_LOG": str(cy_log),
               **fails}
        return self.fx.image_check(msi, env_extra=env), ps_log, cy_log

    def test_msi_valid_installdir_passes(self):
        """Per-user policy: INSTALLDIR -> LocalAppDataFolder -> TARGETDIR."""
        r, ps_log, cy_log = self.msi_check()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(1, len(cy_log.read_text().splitlines()))  # converted once
        ps = ps_log.read_text()
        self.assertIn("msi=C:\\", ps)
        self.assertNotIn("msi=/", ps)  # native path arrived as data, not MSYS form

    def test_msi_installdir_unsafe_layouts_fail(self):
        """Only the INSTALLDIR row's own chain counts."""
        root = "TARGETDIR||SourceDir\nLocalAppDataFolder|TARGETDIR|.\n"
        cases = {
            # other rows hold the stale-Programs and even the correct path,
            # yet neither may vouch for an INSTALLDIR naming the data dir
            "data directory": root + (
                "INSTALLDIR|LocalAppDataFolder|NEUTRO~1|Neutrodyne\n"
                "dirPrograms|LocalAppDataFolder|Programs\n"
                "dirLookalike|LocalAppDataFolder|dir|Neutrodyne-App\n"),
            "wrong root": self.MSI_DIR_OK.replace(  # system-wide: per-user only
                "LocalAppDataFolder", "ProgramFiles64Folder"),
            "extra nesting": root + (  # LocalAppDataFolder\Programs\Neutrodyne-App
                "dirPrograms|LocalAppDataFolder|Programs\n"
                "INSTALLDIR|dirPrograms|NEUTRO~1|Neutrodyne-App\n"),
            "missing INSTALLDIR": root,
            "parent cycle": (
                "INSTALLDIR|dirA|x|Neutrodyne-App\ndirA|dirB|A\ndirB|dirA|B\n"),
        }
        for name, rows in cases.items():
            with self.subTest(case=name):
                r, _, _ = self.msi_check(dir_rows=rows)
                self.assertNotEqual(0, r.returncode)
                self.assertIn("INSTALLDIR", r.stderr)

    def test_msi_unexpected_binary_fails(self):
        r, _, _ = self.msi_check(bin_rows=self.MSI_BIN_OK + "evil.dll\n")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not in wix.lock", r.stderr)

    def test_msi_query_errors_fail(self):
        """A COM/PowerShell failure is a query failure, never an empty table."""
        for flag, want in (("FAKE_PS_FAIL_DIR", "Directory table"),
                           ("FAKE_PS_FAIL_BIN", "Binary table")):
            with self.subTest(flag=flag):
                r, _, _ = self.msi_check(**{flag: "1"})
                self.assertNotEqual(0, r.returncode)
                self.assertIn(want, r.stderr)

    def test_msi_path_with_space_and_apostrophe_passes(self):
        """A path needing quoting arrives intact as data."""
        msi = "dist/it's a file.msi"
        r, ps_log, _ = self.msi_check(msi=msi)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("it's a file.msi", ps_log.read_text())

    # ----- canonical JAR identity (packaging recompression / re-signing) ------

    def zjar(self, img, stem, data, mutate=None):
        """Record a manifest row for data, write mutate(data) (or data) under the
        row's mangled name, then run the image scan."""
        name = self.fx.manifest(stem, data)
        self.fx.write(f"{img}/lib/app/{name}", data if mutate is None else mutate(data))
        self.fx.write_manifest()
        return self.fx.image_check(img)

    def assertJarFails(self, data, mutated):
        r = self.zjar(self.flat_image(), "zcore", data, mutate=lambda _: mutated)
        self.assertNotEqual(0, r.returncode)
        self.assertTrue(
            "differs from the Licensee-checked" in r.stderr
            or "cannot read JAR content" in r.stderr,
            r.stderr,
        )

    def test_canonical_jar_identity(self):
        """Recompression/reordering is stable; content, entry-set, duplicate and
        malformed-name mutations fail; a non-zip is its raw sha256."""
        entries = [("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"), ("A.class", b"CODE-A")]
        r = self.zjar(self.flat_image(), "zcore", zip_bytes(entries),
                      mutate=lambda _: zip_bytes(list(reversed(entries)),
                                                 compression=zipfile.ZIP_STORED, extra=b"PAD"))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        simple = zip_bytes([("A.class", b"CODE-A")])
        self.assertJarFails(simple, zip_bytes([("A.class", b"CODE-B")]))
        self.assertJarFails(simple, zip_bytes([("A.class", b"CODE-A"), ("B.class", b"CODE-B")]))
        # Duplicate entry names are ambiguous: the canonical reader refuses them.
        r = self.zjar(self.flat_image(), "zcore", simple, mutate=lambda _: duplicate_zip_bytes())
        self.assertNotEqual(0, r.returncode)
        self.assertIn("cannot read JAR content", r.stderr)
        self.assertJarFails(simple, zip_bytes([("../evil.class", b"CODE")]))
        self.assertEqual(sha256(b"NOT-A-ZIP"), self.fx.canonical_hash(b"NOT-A-ZIP"))

    def test_macho_identity_rules(self):
        """Re-signing is stable; code and out-of-segment signature mutations fail."""
        data = zip_bytes([("lib.dylib", thin_macho())])
        r = self.zjar(self.flat_image(), "zcore", data,
                      mutate=lambda _: zip_bytes([("lib.dylib", thin_macho(sig=b"OTHER"))]))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertJarFails(data, zip_bytes([("lib.dylib", thin_macho(code=b"CODE-B"))]))
        self.assertJarFails(data, zip_bytes([("lib.dylib", thin_macho(sig=b"A" * 16, dataoff=0x400, total=0x2000))]))

    def test_skiko_stub_does_not_require_its_own_md5_stamp(self):
        """Membership is the stub manifest's source row; the stub's md5 stamp is
        not recomputed (packaging changes the stub bytes)."""
        img = self.flat_image()
        self.fx.skiko_stub(img, stamp="deadbeef")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- engine tree: real trimmed paths bind to the lock's logical names ----

    def test_engine_tree_with_real_trimmed_paths_and_lock_schema_passes(self):
        """The representative real trimmed PBS paths (threading.py,
        concurrent/futures/thread.py, asyncio/threads.py, _pyrepl/readline.py)
        are legitimate stdlib, and the lock's logical component names (CPython,
        OpenSSL, ...) bind by layout, not by filename."""
        self.fx.write_engine_lock()
        app = self.engine_app()
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("python tree matches python-components.lock", r.stdout)

    def test_native_thread_itcl_and_readline_modules_still_fail(self):
        """Narrowing the globs must not admit the native modules trim-python.sh
        removes: lib/thread*, lib/itcl*, _readline* and a readline module outside
        _pyrepl."""
        self.fx.write_engine_lock()
        app = self.engine_app(extra=[
            ("python/lib/thread2.9.3/pkgIndex.tcl", b""),
            ("python/lib/itcl4.2.3/pkgIndex.tcl", b""),
            ("python/lib/_readline.so", b""),
            ("python/lib/readline.py", b""),
        ])
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        for want in ("thread2.9.3", "itcl4.2.3", "_readline.so", "lib/readline.py"):
            self.assertIn(want, r.stderr)

    def test_engine_layout_violations_fail(self):
        """The lock binds the trimmed layout: interpreter, CA bundle and stdlib
        are each required."""
        self.fx.write_engine_lock()
        cases = (
            ("missing-interpreter", dict(interpreter=False), "no Python interpreter"),
            ("missing-cacert", dict(cacert=False), "CA bundle is missing"),
            ("missing-stdlib", dict(stdlib=False), "no Python standard library"),
        )
        for name, kwargs, want in cases:
            with self.subTest(case=name):
                app = self.engine_app(root_dir=f"imgs/Engine-{name}", **kwargs)
                self.fx.write_manifest()
                r = self.fx.image_check(app)
                self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
                self.assertIn(want, r.stderr)

    def test_engine_lock_version_and_target_mismatch_fail(self):
        """The stdlib minor must equal the lock's CPython version and the image's
        target must be one of the lock's targets."""
        self.fx.write_engine_lock(python="3.13.1")
        app = self.engine_app(root_dir="imgs/Engine-minor")
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("stdlib minor", r.stderr)

        self.fx.write_engine_lock(targets=("linux-x64",))
        app = self.engine_app(root_dir="imgs/Engine-target")
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("image target", r.stderr)

    def test_engine_forbidden_component_in_tree_or_archive_fails(self):
        """The tree and the yt-dlp archive must not carry a forbidden component."""
        self.fx.write_engine_lock()
        app = self.engine_app(
            root_dir="imgs/Engine-forbidden-tree",
            extra=[("python/lib/python3.14/mutagen/__init__.py", b"")],
        )
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("forbidden file or directory", r.stderr)

        app = self.engine_app(root_dir="imgs/Engine-forbidden-zip")
        self.fx.write(
            f"{app}/Contents/app/resources/engine/ytdlp/yt-dlp",
            zip_bytes([("__main__.py", b""), ("mutagen/__init__.py", b"")]),
        )
        self.fx.write_manifest()
        r = self.fx.image_check(app)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("forbidden entry in the bundled yt-dlp archive", r.stderr)

    def test_lock_components_without_tree_fail(self):
        """A lock with components and no engine tree is a violation."""
        self.fx.write_engine_lock()
        img = self.flat_image(name="NoEngine")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("lists components but no engine/python tree", r.stderr)

    # ----- rule 4/7: only the resolved runtime root is exempt ----------------

    def test_jar_under_app_runtime_is_not_exempt(self):
        """A /runtime/ directory that is not the resolved runtime root is an
        ordinary image directory: its JARs must bind the classpath and carry no
        test entries, however valid their name looks."""
        for name, data, jar, want in (
            ("unlisted", b"EVIL", None, "not on the Licensee-checked runtime classpath"),
            ("test-containing", zip_bytes([("test/Foo.class", b"")]), None,
             "test entries inside JAR"),
            ("runtime-named", b"JRT-EVIL", "jrt-fs.jar",
             "not on the Licensee-checked runtime classpath"),
        ):
            with self.subTest(case=name):
                img = self.flat_image(name=f"RuntimeProbe-{name}")
                jar_name = jar or mangled("evil", data)
                self.fx.write(f"{img}/lib/app/runtime/{jar_name}", data)
                self.fx.write_manifest()
                r = self.fx.image_check(img)
                self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
                self.assertIn(want, r.stderr)

    def test_legitimate_runtime_jrtfs_still_exempt(self):
        """The JDK's own jrt-fs.jar under the resolved runtime root stays exempt."""
        img = self.flat_image(name="RuntimeClean")
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # ----- rule 7: the Windows entry point is enforced ------------------------

    def test_windows_layout_entry_point_enforced(self):
        """A Windows image's entry point is Neutrodyne.exe at the root; non-entry
        DLL/data files are allowed, a second executable is not."""
        img = self.windows_image(extra_root=[("vcruntime140.dll", b"DLL"),
                                             ("icudtl.dat", b"DATA")])
        self.fx.write_manifest()
        r = self.fx.image_check(img)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

        for name, extra, launcher in (
            ("extra", [("evil.exe", b"EVIL")], True),
            ("none", [], False),
        ):
            with self.subTest(case=name):
                img = self.windows_image(name=f"Neutrodyne-{name}", extra_root=extra,
                                         launcher=launcher)
                self.fx.write_manifest()
                r = self.fx.image_check(img)
                self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
                self.assertIn("entry-point", r.stderr)

    def test_windows_python_layout_matches_the_locked_version(self):
        for name, dlls, expected in (
            ("valid", ("python3.dll", "python314.dll"), 0),
            ("stable-only", ("python3.dll",), 1),
            ("wrong-minor", ("python313.dll",), 1),
        ):
            with self.subTest(case=name):
                img = self.windows_image(name=f"Engine-{name}")
                engine = f"{img}/app/resources/engine"
                self.fx.write(f"{engine}/python/python.exe", b"EXE")
                os.chmod(self.root / f"{engine}/python/python.exe", 0o755)
                self.fx.write(f"{engine}/python/Lib/os.py", b"# stdlib\n")
                self.fx.write(f"{engine}/cacert.pem", b"CA")
                for dll in dlls:
                    self.fx.write(f"{engine}/python/{dll}", b"DLL")
                self.fx.write_engine_lock(targets=("windows-x64",))
                self.fx.write_manifest()
                r = self.fx.image_check(img)
                self.assertEqual(expected, r.returncode, r.stdout + r.stderr)

if __name__ == "__main__":
    unittest.main()
