#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-runtime-sources.test.sh — the --image native-file boundary of
# scripts/ci/check-runtime-sources.sh (11 Runtime exception obligations and
# checks): every native file under runtime/ must exist in the pinned Temurin
# tree, and a Mach-O file that differs must compare equal only on its
# signature-normalized bytes (jpackage re-signs some natives ad hoc when it
# seals the .app, so raw bytes legitimately differ; nightly 37886973484 flagged
# libjli.dylib and libosxui.dylib while codesign --remove-signature could not
# restore the published bytes). A real byte difference must still fail.
#
# Run: bash scripts/ci/tests/check-runtime-sources.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

# --- fixture plumbing -----------------------------------------------------------

FIX_ROOT="$WORK/repo"
mkdir -p "$FIX_ROOT/scripts/ci" "$FIX_ROOT/desktopApp"
cp "$REPO_ROOT/scripts/ci/check-runtime-sources.sh" "$FIX_ROOT/scripts/ci/"
cp "$REPO_ROOT/desktopApp/runtime.lock" "$FIX_ROOT/desktopApp/"
CHECK="$FIX_ROOT/scripts/ci/check-runtime-sources.sh"
LOCK="$FIX_ROOT/desktopApp/runtime.lock"

lock_prop() { grep -E "^$2=" "$1" | tail -1 | cut -d= -f2-; }

# mk_macho <file> <variant> — a structurally valid thin Mach-O dylib. 'pub' is
# adhoc-signed like the pinned archive; 'sig' is the same file re-signed by
# jpackage (grown signature blob, page-rounded __LINKEDIT.vmsize, the real
# libjli/libosxui pairing); 'mut' is 'sig' with a payload byte flipped.
mk_macho() {
    python3 - "$1" "$2" <<'PY'
import struct, sys
out, variant = sys.argv[1], sys.argv[2]
content = b'payload bytes for libtest\n' + bytes(range(64))
def seg64(vmsize, filesize):
    return struct.pack('<II16sQQQQIIII', 0x19, 72, b'__LINKEDIT' + b'\0' * 6,
                       0x1000, vmsize, 0, filesize, 7, 5, 0, 0)
def cs(do, ds):
    return struct.pack('<IIII', 0x1d, 16, do, ds)
def hdr(ncmds, sizeofcmds):
    return struct.pack('<IIIIIIII', 0xfeedfacf, 0x0100000c, 0, 6,
                       ncmds, sizeofcmds, 0, 0)
do = 32 + 88 + len(content)
if variant == 'pub':
    data = hdr(2, 88) + seg64(do + 64, do + 64) + cs(do, 64) + content + b'A' * 64
else:
    data = hdr(2, 88) + seg64(0x4000, do + 128) + cs(do, 128) + content + b'B' * 128
    if variant == 'mut':
        data = bytearray(data)
        data[124] ^= 0xff
        data = bytes(data)
open(out, 'wb').write(data)
PY
}

# The image and the pinned JDK tree: release matches the lock, legal/ is
# non-empty, lib/libtest.dylib is Mach-O (magic cffaedfe) in both trees.
IMG="$WORK/img"
JDK="$WORK/jdk"
mkdir -p "$IMG/lib/runtime/legal" "$IMG/lib/runtime/lib" "$JDK/legal" "$JDK/lib"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" > "$IMG/lib/runtime/release"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" > "$JDK/release"
: > "$IMG/lib/runtime/legal/NOTICE"
: > "$JDK/legal/NOTICE"

printf 'SMOKE {"javaVendor":"%s","javaVendorVersion":"%s","javaRuntimeVersion":"%s"}\n' \
    "$(lock_prop "$LOCK" vendor)" \
    "$(lock_prop "$LOCK" vendorVersion)" \
    "$(lock_prop "$LOCK" javaRuntimeVersion)" > "$WORK/smoke.txt"

run_check() {
    bash "$CHECK" --image "$IMG" --jdk "$JDK" --smoke "$WORK/smoke.txt" \
        > "$WORK/check.out" 2>&1
}

# --- cases ----------------------------------------------------------------------

# 1. Published-signed vs jpackage-resigned bytes: normalized copies compare
#    equal (the real libjli/libosxui pairing of nightly 37886973484).
mk_macho "$JDK/lib/libtest.dylib" pub
mk_macho "$IMG/lib/runtime/lib/libtest.dylib" sig
if run_check; then
    t_ok "Mach-O differing only in the signature verifies equal"
else
    t_fail "Mach-O differing only in the signature verifies equal"
    sed 's/^/    /' "$WORK/check.out" >&2
fi

# 2. A real byte difference survives normalization and is reported, not masked.
mk_macho "$JDK/lib/libtest.dylib" pub
mk_macho "$IMG/lib/runtime/lib/libtest.dylib" mut
if ! run_check && grep -q 'runtime file differs from the pinned Temurin archive: lib/libtest.dylib' "$WORK/check.out"; then
    t_ok "Mach-O with different bytes is reported after normalization"
else
    t_fail "Mach-O with different bytes is reported after normalization"
    sed 's/^/    /' "$WORK/check.out" >&2
fi

# 3. An image file with no counterpart in the pinned tree is rejected.
mk_macho "$JDK/lib/libtest.dylib" pub
mk_macho "$IMG/lib/runtime/lib/libtest.dylib" sig
mk_macho "$IMG/lib/runtime/lib/libextra.dylib" sig
if ! run_check && grep -q 'no counterpart in the pinned Temurin archive: lib/libextra.dylib' "$WORK/check.out"; then
    t_ok "runtime file without a pinned counterpart is rejected"
else
    t_fail "runtime file without a pinned counterpart is rejected"
    sed 's/^/    /' "$WORK/check.out" >&2
fi
rm -f "$IMG/lib/runtime/lib/libextra.dylib"

# 5. macOS layout: jlink's home inside a .app is Contents/runtime/Contents/Home
#    — legal/, release and the natives all live there (nightly 37860407043's
#    real bundle). The checks must follow the same nesting the image checker
#    accepts.
IMGMAC="$WORK/img-mac"
mkdir -p "$IMGMAC/Contents/runtime/Contents/Home/legal" \
         "$IMGMAC/Contents/runtime/Contents/Home/lib"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" \
    > "$IMGMAC/Contents/runtime/Contents/Home/release"
: > "$IMGMAC/Contents/runtime/Contents/Home/legal/NOTICE"
mk_macho "$IMGMAC/Contents/runtime/Contents/Home/lib/libtest.dylib" sig
if bash "$CHECK" --image "$IMGMAC" --jdk "$JDK" --smoke "$WORK/smoke.txt" \
        > "$WORK/check-mac.out" 2>&1; then
    t_ok ".app Contents/runtime/Contents/Home is the runtime root on macOS images"
else
    t_fail ".app Contents/runtime/Contents/Home is the runtime root on macOS images"
    sed 's/^/    /' "$WORK/check-mac.out" >&2
fi

# 6. The pinned JDK tree carries the same nesting on macOS: bundled-runtime's
#    macos-arm64 jdk/ is the bundle root with the tree under Contents/Home
#    (nightly 37881222222 flagged all 93 runtime files against the flat path).
#    Counterpart lookup must unwrap it like the image side does.
JDKMAC="$WORK/jdk-mac"
mkdir -p "$JDKMAC/Contents/Home/lib" "$JDKMAC/Contents/Home/legal"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" > "$JDKMAC/Contents/Home/release"
: > "$JDKMAC/Contents/Home/legal/NOTICE"
mk_macho "$JDKMAC/Contents/Home/lib/libtest.dylib" pub
if bash "$CHECK" --image "$IMGMAC" --jdk "$JDKMAC" --smoke "$WORK/smoke.txt" \
        > "$WORK/check-macjdk.out" 2>&1; then
    t_ok "macOS bundled-runtime root resolves Contents/Home counterparts"
else
    t_fail "macOS bundled-runtime root resolves Contents/Home counterparts"
    sed 's/^/    /' "$WORK/check-macjdk.out" >&2
fi

# 7. And a file absent under the JDK's Contents/Home is still unbound.
mk_macho "$IMGMAC/Contents/runtime/Contents/Home/lib/libextra.dylib" sig
if ! bash "$CHECK" --image "$IMGMAC" --jdk "$JDKMAC" --smoke "$WORK/smoke.txt" \
        > "$WORK/check-macjdk2.out" 2>&1 \
    && grep -q 'no counterpart in the pinned Temurin archive: lib/libextra.dylib' \
        "$WORK/check-macjdk2.out"; then
    t_ok "a file absent under the JDK's Contents/Home is rejected"
else
    t_fail "a file absent under the JDK's Contents/Home is rejected"
    sed 's/^/    /' "$WORK/check-macjdk2.out" >&2
fi
rm -f "$IMGMAC/Contents/runtime/Contents/Home/lib/libextra.dylib"

# --- ELF loaded bytes -------------------------------------------------------------
# jlink --strip-debug strips natives with objcopy -g, so a legitimately
# stripped ELF must compare equal. The toolchain-difference fallback binds
# what the kernel loader maps (elf_bind: masked ELF header, the program
# header table, and every phdr's file range), never section data — sections
# describe bytes a loader ignores, so a redirected section header or bytes
# inside a segment no section covers would otherwise hide a mutation (review
# probes accepted zeroed e_entry, an RX→RWX PT_LOAD, a redirected .text and
# a padding-byte flip).

ELF_SEED=""
for c in "$(command -v true 2>/dev/null)" /bin/true /usr/bin/true; do
    if [ -n "$c" ] && [ -f "$c" ] \
        && [ "$(od -An -tx1 -N4 "$c" 2>/dev/null | tr -d ' ')" = "7f454c46" ]; then
        ELF_SEED="$c"
        break
    fi
done

# mut_elf <in> <out> <entry|rwx|sectredir|gap> — flip loader-visible bytes on
# an ELF64 LE file. sectredir mutates .text in place, appends the pristine
# .text at EOF and redirects the section header to it — identical phdrs and
# identical section-dumped bytes, different loaded bytes. gap flips a byte a
# PT_LOAD maps but no section covers.
mut_elf() {
    python3 - "$1" "$2" "$3" <<'PY'
import struct, sys
b = bytearray(open(sys.argv[1], 'rb').read())
assert b[4] == 2 and b[5] == 1, 'ELF fixture needs an ELF64 little-endian seed'
phoff = struct.unpack('<Q', b[32:40])[0]
phnum = struct.unpack('<H', b[56:58])[0]
mode = sys.argv[3]
if mode == 'entry':
    assert any(b[24:32]), 'seed entry point is already zero'
    struct.pack_into('<Q', b, 24, 0)
elif mode == 'rwx':
    hit = False
    for i in range(phnum):
        o = phoff + i * 56
        if struct.unpack('<I', b[o:o + 4])[0] == 1 \
                and struct.unpack('<I', b[o + 4:o + 8])[0] == 5:
            struct.pack_into('<I', b, o + 4, 7)
            hit = True
            break
    assert hit, 'no RX PT_LOAD in the seed'
elif mode == 'sectredir':
    shoff = struct.unpack('<Q', b[40:48])[0]
    shents, shnum = struct.unpack('<HH', b[58:62])
    shstrndx = struct.unpack('<H', b[62:64])[0]
    stroff = struct.unpack('<Q',
        b[shoff + shstrndx * shents + 24:shoff + shstrndx * shents + 32])[0]
    target = None
    for i in range(shnum):
        o = shoff + i * shents
        n = struct.unpack('<I', b[o:o + 4])[0]
        if b[stroff + n:b.index(b'\0', stroff + n)] == b'.text':
            target = o
            break
    assert target is not None, 'no .text section in the seed'
    toff = struct.unpack('<Q', b[target + 24:target + 32])[0]
    tsz = struct.unpack('<Q', b[target + 32:target + 40])[0]
    text = bytes(b[toff:toff + tsz])
    b[toff + tsz // 2] ^= 0xff
    struct.pack_into('<Q', b, target + 24, len(b))
    b += text
else:  # gap
    shoff = struct.unpack('<Q', b[40:48])[0]
    shents, shnum = struct.unpack('<HH', b[58:62])
    covered = [(0, phoff + 56 * phnum)]
    for i in range(shnum):
        o = shoff + i * shents
        if struct.unpack('<I', b[o + 4:o + 8])[0] == 8:
            continue
        s = struct.unpack('<Q', b[o + 24:o + 32])[0]
        z = struct.unpack('<Q', b[o + 32:o + 40])[0]
        if z:
            covered.append((s, s + z))
    hit = False
    for i in range(phnum):
        o = phoff + i * 56
        if struct.unpack('<I', b[o:o + 4])[0] != 1:
            continue
        po = struct.unpack('<Q', b[o + 8:o + 16])[0]
        pz = struct.unpack('<Q', b[o + 32:o + 40])[0]
        for f in range(po, po + pz):
            if not any(s <= f < e for s, e in covered):
                b[f] ^= 0xff
                hit = True
                break
        if hit:
            break
    assert hit, 'no section-free byte inside the seed PT_LOADs'
open(sys.argv[2], 'wb').write(bytes(b))
PY
}

if [ -z "$ELF_SEED" ] || ! command -v objcopy >/dev/null 2>&1; then
    # A host without an ELF binary or without objcopy cannot run the checker's
    # ELF path at all (the checker itself fails closed there); the cases run in
    # CI's static job on ubuntu.
    echo "skip: ELF loader-metadata cases need an ELF seed and objcopy" >&2
else
    printf 'fixture debug section\n' > "$WORK/dbg.sec"
    printf 'different toolchain comment\n' > "$WORK/note.sec"

    # 8. Legitimate stripping: the pinned ELF carries a debug section the
    #    image's copy lost to jlink's objcopy -g — still equal.
    cp "$ELF_SEED" "$JDK/lib/libtest.so"
    objcopy --add-section .debug_probe="$WORK/dbg.sec" "$JDK/lib/libtest.so"
    cp "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
    objcopy -g "$IMG/lib/runtime/lib/libtest.so"
    if run_check; then
        t_ok "an ELF stripped the way jlink does verifies equal"
    else
        t_fail "an ELF stripped the way jlink does verifies equal"
        sed 's/^/    /' "$WORK/check.out" >&2
    fi

    # 9. Toolchain-difference boundary: same loadable bytes and loader
    #    metadata, different non-allocated section — the fallback's whole point.
    cp "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
    objcopy -g "$IMG/lib/runtime/lib/libtest.so"
    objcopy --update-section .comment="$WORK/note.sec" \
        "$IMG/lib/runtime/lib/libtest.so" 2>/dev/null \
        || objcopy --add-section .comment="$WORK/note.sec" \
            "$IMG/lib/runtime/lib/libtest.so"
    if run_check; then
        t_ok "same loads and loader metadata with a different non-alloc section verify equal"
    else
        t_fail "same loads and loader metadata with a different non-alloc section verify equal"
        sed 's/^/    /' "$WORK/check.out" >&2
    fi

    # 10. A zeroed entry point is invisible to -O binary but bound by elf_meta.
    cp "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
    objcopy -g "$IMG/lib/runtime/lib/libtest.so"
    mut_elf "$IMG/lib/runtime/lib/libtest.so" \
        "$IMG/lib/runtime/lib/libtest.so" entry
    if ! run_check && grep -q \
        'runtime file differs from the pinned Temurin archive: lib/libtest.so' \
        "$WORK/check.out"; then
        t_ok "an ELF with a mutated entry point is rejected"
    else
        t_fail "an ELF with a mutated entry point is rejected"
        sed 's/^/    /' "$WORK/check.out" >&2
    fi

    # 11. RX → RWX on a PT_LOAD — same bytes under -O binary, different phdr.
    cp "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
    objcopy -g "$IMG/lib/runtime/lib/libtest.so"
    mut_elf "$IMG/lib/runtime/lib/libtest.so" \
        "$IMG/lib/runtime/lib/libtest.so" rwx
    if ! run_check && grep -q \
        'runtime file differs from the pinned Temurin archive: lib/libtest.so' \
        "$WORK/check.out"; then
        t_ok "an ELF with a writable PT_LOAD is rejected"
    else
        t_fail "an ELF with a writable PT_LOAD is rejected"
        sed 's/^/    /' "$WORK/check.out" >&2
    fi

    # 12. .text mutated in place while its section header is redirected to a
    #     pristine copy: identical ELF/program headers and identical
    #     section-dumped bytes, different loaded bytes — accepted by the old
    #     -O binary compare (review probe of 58fca12), rejected now because
    #     the phdr-defined range carries the mutation.
    cp "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
    objcopy -g "$IMG/lib/runtime/lib/libtest.so"
    mut_elf "$IMG/lib/runtime/lib/libtest.so" \
        "$IMG/lib/runtime/lib/libtest.so" sectredir
    if ! run_check && grep -q \
        'runtime file differs from the pinned Temurin archive: lib/libtest.so' \
        "$WORK/check.out"; then
        t_ok "an ELF whose section header hides mutated loaded bytes is rejected"
    else
        t_fail "an ELF whose section header hides mutated loaded bytes is rejected"
        sed 's/^/    /' "$WORK/check.out" >&2
    fi

    # 13. A byte a PT_LOAD maps but no section covers — invisible to a
    #     section-dumping compare, bound by the phdr-defined range.
    cp "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
    objcopy -g "$IMG/lib/runtime/lib/libtest.so"
    mut_elf "$IMG/lib/runtime/lib/libtest.so" \
        "$IMG/lib/runtime/lib/libtest.so" gap
    if ! run_check && grep -q \
        'runtime file differs from the pinned Temurin archive: lib/libtest.so' \
        "$WORK/check.out"; then
        t_ok "an ELF with a mutated loaded byte outside every section is rejected"
    else
        t_fail "an ELF with a mutated loaded byte outside every section is rejected"
        sed 's/^/    /' "$WORK/check.out" >&2
    fi
    rm -f "$JDK/lib/libtest.so" "$IMG/lib/runtime/lib/libtest.so"
fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "check-runtime-sources.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "check-runtime-sources.test: all cases behave"
