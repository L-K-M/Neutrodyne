#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""check-runtime-sources.sh's --image native-comparison fixture checks (11
Runtime exception obligations and checks).

Runs the shipped check-runtime-sources.sh and canonical-jar-hash.py in a
throwaway fixture repo — the script resolves desktopApp/runtime.lock relative
to its own location, so the real code paths run against fixture images. These
are the re-signed Mach-O cases proven on the release candidate, carried over
from the desktop image gates' suite to a minimal runtime-only fixture (a
jpackage .app's runtime tree plus the pinned JDK counterpart):

  * a jpackage .app keeps the runtime home at Contents/runtime/Contents/Home:
    release/legal resolve under the home while the native-file comparison
    keeps the Contents/runtime root, whose rel paths pair with the pinned
    tree's Contents/Home entries;
  * jpackage re-signs ad-hoc over Adoptium's signatures, so image natives
    differ byte-wise from the pinned files; the gate binds each Mach-O's
    canonical content (canonical-jar-hash.py --content: signature slots
    removed, __LINKEDIT extents rebound) — `codesign --remove-signature`
    leaves a signature-size-dependent __LINKEDIT.vmsize residue that can
    never match across mixed-size signatures;
  * the fixtures carry real LC_CODE_SIGNATURE slots; where the host has
    clang and the real codesign, a dylib compiled in the test runs the same
    pass/fail pair under real signatures.
"""

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
CI = REPO / "scripts/ci"

# Read the lock's javaVersion rather than pinning a copy: a Renovate runtime
# bump changes the lock and would otherwise desynchronize the fixture.
JAVA_VERSION = next(
    line.split("=", 1)[1]
    for line in reversed(
        (REPO / "desktopApp/runtime.lock").read_text(encoding="utf-8").splitlines()
    )
    if line.startswith("javaVersion=")
)


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

# Synthetic native magics for the classification boundary: the gate must bind a
# native image file even where the pinned counterpart is a text file, and a
# text image at a pinned native path. Own bytes, not an OS binary.
ELF = bytes.fromhex("7f454c46") + b"\x00" * 60
PE = (b"MZ" + b"\x00" * 58 + struct.pack("<I", 0x40)
      + bytes.fromhex("50450000") + b"\x00" * 20)


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


class Fixture:
    """A fixture repo: the real scripts and lockfile under root/."""

    def __init__(self, root: Path):
        self.root = root
        (root / "scripts/ci").mkdir(parents=True)
        for script in ("check-runtime-sources.sh", "canonical-jar-hash.py"):
            shutil.copy2(CI / script, root / "scripts/ci" / script)
        (root / "desktopApp").mkdir(parents=True)
        shutil.copy2(REPO / "desktopApp/runtime.lock", root / "desktopApp/runtime.lock")

    def write(self, rel: str, data: bytes):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def run(self, *args, env=None) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", *map(str, args)],
            cwd=self.root, capture_output=True, text=True, timeout=120, env=env,
        )


class RuntimeSourcesGateTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fx = Fixture(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    # ----- fixture builders -------------------------------------------------

    def mac_app(self, root_dir="imgs/Neutrodyne.app"):
        """A jpackage .app: runtime home at Contents/runtime/Contents/Home."""
        fx = self.fx
        app = str(self.root / root_dir)
        fx.write(f"{app}/Contents/MacOS/Neutrodyne", b"MACHO-LAUNCHER")
        fx.write(f"{app}/Contents/runtime/Contents/Home/release", release_file())
        fx.write(f"{app}/Contents/runtime/Contents/Home/legal/LICENSE", b"GPL-2.0\n")
        fx.write(f"{app}/Contents/runtime/Contents/Home/lib/jrt-fs.jar", b"JRT")
        fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", MACHO
        )
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
        return str(self.root / rel)

    def test_automatic_smoke_requires_a_successful_launcher(self):
        app = self.mac_app()
        jdk = self.mac_jdk()
        script = self.root / "scripts/desktop/smoke-start.sh"
        script.parent.mkdir(parents=True)
        (script.parent / "smoke.txt").write_text(smoke_line(self.root))
        for code in (0, 1):
            with self.subTest(code=code):
                script.write_text(
                    '#!/bin/sh\ncat "$(dirname "$0")/smoke.txt"\n'
                    f'exit {code}\n'
                )
                script.chmod(0o755)
                result = self.fx.run(
                    self.root / "scripts/ci/check-runtime-sources.sh",
                    "--image", app, "--jdk", jdk,
                )
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                if code:
                    self.assertIn("smoke launcher failed", result.stderr)

    # ----- symlink binding and enumeration failures ---------------------------

    def symlink(self, path: str, target: str):
        """Create a real symlink, skipping where the platform cannot."""
        try:
            os.symlink(target, path)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are not supported on this platform")

    def test_symlinked_native_escaping_target_fails(self):
        """A native at a symlinked path must bind: a link to an external file
        with different content is not skipped (the pre-fix gate said OK)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        native = f"{home}/lib/server/libjvm.dylib"
        os.unlink(native)
        self.fx.write("evil.dylib", thin_macho(code=b"EVIL", sig=b"ADHOC-1"))
        self.symlink(native, str(self.root / "evil.dylib"))
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("escapes the runtime root", r.stderr)

    def test_symlinked_native_internal_target_passes(self):
        """A native symlink that resolves inside the runtime root and matches
        the pinned counterpart is retained (the real image's legal/ links are
        the same shape)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        self.fx.write(f"{home}/lib/libjvm.dylib", MACHO)
        self.fx.write(f"{jdk}/Contents/Home/lib/libjvm.dylib", MACHO)
        native = f"{home}/lib/server/libjvm.dylib"
        os.unlink(native)
        self.symlink(native, "../libjvm.dylib")
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_symlinked_native_internal_tamper_fails(self):
        """A symlink that resolves inside the root but carries different bytes
        than the pinned counterpart at its path still fails closed."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        evil = thin_macho(code=b"EVIL", sig=b"ADHOC-1")
        self.fx.write(f"{home}/lib/libjvm.dylib", evil)
        self.fx.write(f"{jdk}/Contents/Home/lib/libjvm.dylib", evil)
        native = f"{home}/lib/server/libjvm.dylib"
        os.unlink(native)
        self.symlink(native, "../libjvm.dylib")
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    def test_nested_symlinked_native_passes(self):
        """A chain of native symlinks is resolved to its final target and bound
        against the pinned counterpart at each link's own path."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        self.fx.write(f"{home}/lib/libjvm.dylib", MACHO)
        self.fx.write(f"{jdk}/Contents/Home/lib/libjvm.dylib", MACHO)
        native = f"{home}/lib/server/libjvm.dylib"
        os.unlink(native)
        self.symlink(native, "../libjvm.dylib")
        self.symlink(f"{home}/lib/server/alias.dylib", "libjvm.dylib")
        self.fx.write(f"{jdk}/Contents/Home/lib/server/alias.dylib", MACHO)
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_broken_symlink_fails(self):
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        native = f"{home}/lib/server/libjvm.dylib"
        os.unlink(native)
        self.symlink(native, "../does-not-exist.dylib")
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("broken", r.stderr)

    def test_symlink_to_non_regular_file_fails(self):
        """A symlink to a FIFO is rejected before any comparison could block
        on it (a comparison opens and reads the target)."""
        if not hasattr(os, "mkfifo"):
            self.skipTest("mkfifo is not supported on this platform")
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        native = f"{home}/lib/server/libjvm.dylib"
        os.unlink(native)
        fifo = self.root / "probe.fifo"
        os.mkfifo(fifo)
        self.symlink(native, str(fifo))
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("does not resolve to a regular file", r.stderr)

    def test_symlinked_directory_fails(self):
        """A symlink to a directory is not descended silently: it is rejected
        so files under it cannot escape the pinned comparison."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        self.fx.write(f"{home}/lib/real-dir/probe.txt", b"PROBE")
        self.fx.write(f"{jdk}/Contents/Home/lib/real-dir/probe.txt", b"PROBE")
        self.symlink(f"{home}/lib/link-dir", "real-dir")
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("points at a directory", r.stderr)

    def test_symlinked_runtime_root_fails(self):
        """A symlinked runtime root is not enumerated through: the gate refuses
        to bind an external tree (the pre-fix find counted nothing and said OK)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        rt = f"{app}/Contents/runtime"
        external = self.root / "external-runtime"
        shutil.move(rt, external)
        self.symlink(rt, str(external))
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("runtime root is a symlink", r.stderr)

    def test_legal_symlinks_are_retained(self):
        """The real image's legal/ symlinks (relative links to a sibling
        module's notices) stay green while their counterparts are validated."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        home = f"{app}/Contents/runtime/Contents/Home"
        jdk_home = f"{jdk}/Contents/Home"
        self.fx.write(f"{home}/legal/java.base/LICENSE", b"GPL-2.0\n")
        self.fx.write(f"{jdk_home}/legal/java.base/LICENSE", b"GPL-2.0\n")
        os.unlink(f"{home}/legal/LICENSE")
        self.symlink(f"{home}/legal/LICENSE", "java.base/LICENSE")
        self.fx.write(f"{jdk_home}/legal/LICENSE", b"GPL-2.0\n")
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_enumeration_failure_fails_closed(self):
        """A find that cannot read the tree is an observed failure, not a
        silently empty enumeration (the pre-fix process substitution discarded
        find's exit status and said OK)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        fakebin = self.root / "fakebin"
        fakebin.mkdir()
        fake_find = fakebin / "find"
        fake_find.write_text(
            "#!/bin/sh\necho 'find: injected enumeration failure' >&2\nexit 1\n"
        )
        fake_find.chmod(0o755)
        env = dict(os.environ)
        env["PATH"] = f"{fakebin}{os.pathsep}{env['PATH']}"
        r = self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt", env=env,
        )
        self.assertNotEqual(0, r.returncode)
        self.assertIn("enumeration failed", r.stderr)

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

    # ----- native classification by both sides --------------------------------

    def test_native_image_at_text_ref_fails(self):
        """A native image file at a path whose pinned counterpart is a text
        file must still bind: the pre-fix gate classified by the ref's magic
        only and said OK (the R2 hole). Own synthetic bytes for all three
        formats."""
        for fmt, blob in (("macho", MACHO), ("elf", ELF), ("pe", PE)):
            with self.subTest(format=fmt):
                app = self.mac_app()
                jdk = self.mac_jdk()
                self.fx.write(
                    f"{app}/Contents/runtime/Contents/Home/lib/jrt-fs.jar", blob
                )
                r = self.runtime_sources(app, jdk)
                self.assertNotEqual(0, r.returncode)
                self.assertIn("differs from the pinned Temurin archive", r.stderr)

    def test_text_image_at_native_ref_fails(self):
        """The other side of the classification: a text image at a pinned native
        path still binds (the behavior the both-sides test keeps)."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib", b"TEXT"
        )
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    # ----- the real Linux ELF boundary ---------------------------------------

    def require_elf_toolchain(self):
        """The real ELF boundary needs the Linux C toolchain and binutils. On
        another host the test is skipped; on Linux the tools are required — a
        silent skip would hide a CI host that lost them."""
        if sys.platform != "linux":
            self.skipTest("the real ELF boundary needs the Linux cc + objcopy toolchain")
        cc = shutil.which("cc") or shutil.which("gcc")
        objcopy = shutil.which("objcopy")
        missing = []
        if cc is None:
            missing.append("cc/gcc")
        if objcopy is None:
            missing.append("objcopy")
        if missing:
            self.fail("Linux ELF fixture tools unexpectedly absent: " + ", ".join(missing))
        return cc, objcopy

    def elf_shared(self, cc: str, tag: str) -> Path:
        """Compile a real ELF shared object in the test's temp dir (a fixture
        binary built here — not the shipped Neutrodyne artifact)."""
        work = Path(self._tmp.name) / "elf" / tag
        work.mkdir(parents=True, exist_ok=True)
        src, lib = work / "probe.c", work / "probe.so"
        src.write_text("int probe(void) { return 42; }\n")
        subprocess.run(
            [cc, "-g", "-shared", "-fPIC", "-o", str(lib), str(src)],
            check=True, capture_output=True, text=True, timeout=60,
        )
        return lib

    @staticmethod
    def patch_pt_load_flags(data: bytes, flags: int) -> bytes:
        """Set every PT_LOAD segment's p_flags in an ELF64 image."""
        b = bytearray(data)
        phoff = struct.unpack_from("<Q", b, 0x20)[0]
        phentsize = struct.unpack_from("<H", b, 0x36)[0]
        phnum = struct.unpack_from("<H", b, 0x38)[0]
        changed = False
        for index in range(phnum):
            off = phoff + index * phentsize
            if struct.unpack_from("<I", b, off)[0] == 1:  # PT_LOAD
                struct.pack_into("<I", b, off + 4, flags)
                changed = True
        assert changed, "fixture ELF has no PT_LOAD segment"
        return bytes(b)

    def elf_fixture(self, app, jdk, ref: Path, img: bytes):
        self.fx.write(
            f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.so", img
        )
        self.fx.write(f"{jdk}/Contents/Home/lib/server/libjvm.so", ref.read_bytes())

    def test_linux_elf_objcopy_strip_passes(self):
        """The legitimate jlink --strip-debug case: the image is the pinned
        file after the same `objcopy -g`, so the gate binds it and passes."""
        cc, objcopy = self.require_elf_toolchain()
        ref = self.elf_shared(cc, "strip")
        img = ref.with_name("img.so")
        subprocess.run(
            [objcopy, "-g", str(ref), str(img)],
            check=True, capture_output=True, text=True, timeout=60,
        )
        self.assertNotEqual(
            ref.read_bytes(), img.read_bytes(),
            "the fixture strip must change bytes or the test proves nothing",
        )
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.elf_fixture(app, jdk, ref, img.read_bytes())
        r = self.runtime_sources(app, jdk)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_linux_elf_entry_point_change_fails(self):
        """A patched e_entry is not a debug-section change: the pre-fix
        `objcopy -O binary` fallback dropped the ELF header and passed it."""
        cc, _ = self.require_elf_toolchain()
        ref = self.elf_shared(cc, "entry")
        img = bytearray(ref.read_bytes())
        struct.pack_into("<Q", img, 24, 0x1000)  # ELF64 e_entry
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.elf_fixture(app, jdk, ref, bytes(img))
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    def test_linux_elf_pt_load_flags_change_fails(self):
        """A PT_LOAD p_flags change (RW -> RWX) is a program-header change the
        pre-fix `objcopy -O binary` fallback dropped."""
        cc, _ = self.require_elf_toolchain()
        ref = self.elf_shared(cc, "flags")
        img = self.patch_pt_load_flags(ref.read_bytes(), 7)
        app = self.mac_app()
        jdk = self.mac_jdk()
        self.elf_fixture(app, jdk, ref, img)
        r = self.runtime_sources(app, jdk)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)

    def test_elf_failed_objcopy_is_fail_closed(self):
        """A failed objcopy must not be accepted: the pre-fix `|| true` compared
        whatever the failed tool left behind. The fake writes the image bytes
        over the ref and exits 1, so only the exit-status check fails closed."""
        app = self.mac_app()
        jdk = self.mac_jdk()
        img = ELF + b"TAMPER"
        self.fx.write(f"{app}/Contents/runtime/Contents/Home/lib/server/libjvm.so", img)
        self.fx.write(f"{jdk}/Contents/Home/lib/server/libjvm.so", ELF)
        payload = self.root / "payload.bin"
        payload.write_bytes(img)
        fakebin = self.root / "fakebin"
        fakebin.mkdir()
        fake = fakebin / "objcopy"
        fake.write_text(
            "#!/bin/sh\n"
            "# in-place: objcopy -g <file>; output: objcopy -O binary <in> <out>\n"
            'if [ "$#" -eq 2 ]; then out="$2"; else out="$4"; fi\n'
            f'cp "{payload}" "$out"\n'
            "exit 1\n"
        )
        fake.chmod(0o755)
        env = dict(os.environ)
        env["PATH"] = f"{fakebin}{os.pathsep}{env['PATH']}"
        self.fx.write("smoke.txt", smoke_line(self.root).encode())
        r = self.fx.run(
            self.root / "scripts/ci/check-runtime-sources.sh",
            "--image", app, "--jdk", jdk, "--smoke", "smoke.txt", env=env,
        )
        self.assertNotEqual(0, r.returncode)
        self.assertIn("differs from the pinned Temurin archive", r.stderr)


if __name__ == "__main__":
    unittest.main()
