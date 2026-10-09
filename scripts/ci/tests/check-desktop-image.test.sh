#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-desktop-image.test.sh — boundary cases for the JAR rule of
# scripts/ci/check-desktop-image.sh (11 Image scan rules): an image JAR is on
# the Licensee-checked runtime classpath when the manifest
# (writeDesktopRuntimeClasspath's `sha256  <image name>` rows, the image name
# being Compose's `<base>-<md5>.jar` mangle) names it exactly and its SHA-256
# equals the row's. Compose's skiko-awt-runtime stub JAR is the one documented
# off-manifest case: its name carries the stub's own md5, the manifest carries
# a skiko-awt-runtime-<os>-<arch>-*.jar entry plus `<sha256>  <jar>#<entry>`
# rows for the natives and sidecars that jar carries, the extracted native
# sits beside the stub bound to those rows, and the jar itself holds only
# metadata and the other targets' natives with their .sha256 — every byte
# bound to the classpath artifact, never to a package-authored pin.
# All jar fixtures are genuine tiny ZIPs, like the artifacts the scan reads.
#
# The scan resolves runtime.lock and the manifest relative to its own path, so
# each run copies it into a fixture repo root — no repository state is touched.
#
# Run: bash scripts/ci/tests/check-desktop-image.test.sh
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
mkdir -p "$FIX_ROOT/scripts/ci" "$FIX_ROOT/desktopApp/build/desktop-packaging"
cp "$REPO_ROOT/scripts/ci/check-desktop-image.sh" "$FIX_ROOT/scripts/ci/"
cp "$REPO_ROOT/desktopApp/runtime.lock" "$FIX_ROOT/desktopApp/"
mkdir -p "$FIX_ROOT/desktopApp/packaging/rpm"
cp "$REPO_ROOT/desktopApp/packaging/rpm/"*.spec "$FIX_ROOT/desktopApp/packaging/rpm/"
MANIFEST="$FIX_ROOT/desktopApp/build/desktop-packaging/runtime-classpath.txt"
SCAN="$FIX_ROOT/scripts/ci/check-desktop-image.sh"

# norm.py — the fixture's copy of the Mach-O signature normalization
# (norm_thin/norm_macho, identical to the checker's and to
# normalizeThinMacho/normalizeMacho in DesktopPackaging.kt). Shared by
# canon_of and skiko_cp so manifest rows are computed the way the build does.
mkdir -p "$WORK/normpy"
cat > "$WORK/normpy/norm.py" <<'PYEOF'
import struct

def norm_thin(b):
    if len(b) < 28:
        return b, 0
    m = struct.unpack('<I', b[:4])[0]
    if m == 0xfeedfacf: is64, be = True, False
    elif m == 0xfeedface: is64, be = False, False
    elif m == 0xcffaedfe: is64, be = True, True
    elif m == 0xcefaedfe: is64, be = False, True
    else: return b, 0
    hdr = 32 if is64 else 28
    e = '>' if be else '<'
    ncmds = struct.unpack(e + 'I', b[16:20])[0]
    sizeofcmds = struct.unpack(e + 'I', b[20:24])[0]
    if ncmds > 4096:
        return b, 0
    cmds = []
    blobs = []
    off = hdr
    for _ in range(ncmds):
        if off + 8 > len(b):
            return b, 0
        cmd, csz = struct.unpack(e + 'II', b[off:off + 8])
        if csz < 8 or off + csz > len(b):
            return b, 0
        cmds.append((cmd, csz, off))
        if cmd == 0x1d:
            blobs.append(struct.unpack(e + 'II', b[off + 8:off + 16]))
        off += csz
    csz_sum = sum(c[1] for c in cmds if c[0] == 0x1d)
    if len(blobs) > ncmds or csz_sum > sizeofcmds:
        return b, 0
    page = 0x4000 if struct.unpack(e + 'I', b[4:8])[0] in (0x0100000c, 0x0200000c) else 0x1000
    out = bytearray(b[:16])
    out += struct.pack(e + 'I', ncmds - len(blobs))
    out += struct.pack(e + 'I', sizeofcmds - csz_sum)
    out += b[24:hdr]
    for cmd, csz, co in cmds:
        if cmd == 0x1d:
            out += b'\0' * csz
            continue
        cb = b[co:co + csz]
        if cmd in (0x1, 0x19) and cb[8:24].rstrip(b'\0') == b'__LINKEDIT':
            cb = bytearray(cb)
            if is64:
                vm, vs, foff, fsize = struct.unpack(e + 'QQQQ', cb[24:56])
                inside = sum(d for o, d in blobs if foff <= o and o + d <= foff + fsize)
                if inside > fsize or vs not in (fsize, -(-fsize // page) * page):
                    return b, 0
                cb[32:40] = struct.pack(e + 'Q', -(-(fsize - inside) // page) * page)
                cb[48:56] = struct.pack(e + 'Q', fsize - inside)
            else:
                vm, vs, foff, fsize = struct.unpack(e + 'IIII', cb[24:40])
                inside = sum(d for o, d in blobs if foff <= o and o + d <= foff + fsize)
                if inside > fsize or vs not in (fsize, -(-fsize // page) * page):
                    return b, 0
                cb[28:32] = struct.pack(e + 'I', -(-(fsize - inside) // page) * page)
                cb[36:40] = struct.pack(e + 'I', fsize - inside)
        out += cb
    emit = bytes(out) + b[off:]
    base = len(out) - off
    shift = 0
    for do, ds in sorted(blobs):
        s = do + base - shift
        if 0 <= s < len(emit):
            emit = emit[:s] + emit[min(len(emit), s + ds):]
        shift += ds
    return emit, sum(d for _, d in blobs)

def norm_macho(data):
    if len(data) >= 8 and data[:4] == b'\xca\xfe\xba\xbe':
        nfat = struct.unpack('>I', data[4:8])[0]
        if nfat > 64 or 8 + 20 * nfat > len(data):
            return data
        out = bytearray(data[:8])
        prev_end = 8 + 20 * nfat
        for i in range(nfat):
            ro = 8 + 20 * i
            off, size = struct.unpack('>II', data[ro + 8:ro + 16])
            align = struct.unpack('>I', data[ro + 16:ro + 20])[0]
            if align > 30 or off != -(-prev_end // (1 << align)) * (1 << align):
                return data
            if off + size > len(data):
                return data
            emit, sigsize = norm_thin(data[off:off + size])
            if sigsize > size:
                return data
            out += data[ro:ro + 8] + b'\0' * 4 + struct.pack('>I', size - sigsize) + data[ro + 16:ro + 20]
            out += emit
            prev_end = off + size
        return bytes(out)
    return norm_thin(data)[0]
PYEOF

if [ -n "${LEGACY_NORM:-}" ]; then
# The pre-refinement normalization (75cc476): header counts and __LINKEDIT
# sizes zeroed wholesale. Fail-first runs pair it with the matching checker.
cat > "$WORK/normpy/norm.py" <<'PYEOF'
import struct

def norm_thin(b):
    if len(b) < 28:
        return b
    m = struct.unpack('<I', b[:4])[0]
    if m == 0xfeedfacf: is64, be = True, False
    elif m == 0xfeedface: is64, be = False, False
    elif m == 0xcffaedfe: is64, be = True, True
    elif m == 0xcefaedfe: is64, be = False, True
    else: return b
    hdr = 32 if is64 else 28
    e = '>' if be else '<'
    ncmds = struct.unpack(e + 'I', b[16:20])[0]
    if ncmds > 4096:
        return b
    out = bytearray(b[:16] + b'\0' * 8 + b[24:hdr])
    off = hdr
    blobs = []
    for _ in range(ncmds):
        if off + 8 > len(b):
            return b
        cmd, csz = struct.unpack(e + 'II', b[off:off + 8])
        if csz < 8 or off + csz > len(b):
            return b
        if cmd == 0x1d:
            blobs.append(struct.unpack(e + 'II', b[off + 8:off + 16]))
            out += b'\0' * csz
        else:
            cb = b[off:off + csz]
            if cmd in (0x1, 0x19) and cb[8:24].rstrip(b'\0') == b'__LINKEDIT':
                cb = bytearray(cb)
                if is64:
                    cb[32:40] = b'\0' * 8; cb[48:56] = b'\0' * 8
                else:
                    cb[28:32] = b'\0' * 4; cb[36:40] = b'\0' * 4
            out += cb
        off += csz
    emit = bytes(out) + b[off:]
    base = len(out) - off
    shift = 0
    for do, ds in sorted(blobs):
        s = do + base - shift
        if 0 <= s < len(emit):
            emit = emit[:s] + emit[min(len(emit), s + ds):]
        shift += ds
    return emit

def norm_macho(data):
    if len(data) >= 8 and data[:4] == b'\xca\xfe\xba\xbe':
        nfat = struct.unpack('>I', data[4:8])[0]
        if nfat > 64 or 8 + 20 * nfat > len(data):
            return data
        out = bytearray(data[:8])
        for i in range(nfat):
            ro = 8 + 20 * i
            off, size = struct.unpack('>II', data[ro + 8:ro + 16])
            if off + size > len(data):
                return data
            out += data[ro:ro + 8] + b'\0' * 8 + data[ro + 16:ro + 20]
            out += norm_thin(data[off:off + size])
        return bytes(out)
    return norm_thin(data)

def norm_entry(name, data):
    if name.rsplit('.', 1)[-1] in ('dylib', 'jnilib', 'so'):
        return norm_macho(data)
    return data
PYEOF
else
cat >> "$WORK/normpy/norm.py" <<'PYEOF'

def norm_entry(name, data):
    if name.rsplit('.', 1)[-1] in ('dylib', 'jnilib', 'so'):
        return norm_macho(data)
    return data
PYEOF
fi

md5_of() { md5sum "$1" | cut -d' ' -f1; }
sha_of() { sha256sum "$1" | cut -d' ' -f1; }

# canon_of <jar> — the manifest row hash: canonical content sha256, identical to
# jar_content_hash in the scan and canonicalSha256Of in the Gradle task.
canon_of() {
    PYTHONPATH="$WORK/normpy" python3 - "$1" <<'PY'
import sys, zipfile, hashlib
from norm import norm_entry
path = sys.argv[1]
try:
    z = zipfile.ZipFile(path)
except zipfile.BadZipFile:
    print(hashlib.sha256(open(path, 'rb').read()).hexdigest())
    sys.exit(0)
outer = hashlib.sha256()
with z:
    for i in sorted(z.infolist(), key=lambda i: i.filename):
        h = hashlib.sha256(b'' if i.is_dir() else norm_entry(i.filename, z.read(i))).hexdigest()
        outer.update(i.filename.encode('utf-8') + b'\0' + h.encode('ascii') + b'\n')
print(outer.hexdigest())
PY
}

# Compose's mangle suffix: the file's md5 rendered per byte with %x (no padding).
unpadded_md5() {
    local padded byte i out=""
    padded="$(md5_of "$1")"
    for ((i = 0; i < ${#padded}; i += 2)); do
        byte="${padded:i:2}"
        out+="${byte#0}"
    done
    echo "$out"
}

# new_image <tag> — a fresh case: an empty manifest plus the smallest image the
# scan accepts (one launcher, a jlink'd runtime/release matching runtime.lock, a
# non-empty legal/, and the JDK's own jar, skipped by the */runtime/* rule).
# Prints the image path.
new_image() {
    local img="$WORK/img-$1"
    : > "$MANIFEST"
    rm -f "${MANIFEST%.txt}"-*.txt
    mkdir -p "$img/bin" "$img/lib/app" "$img/lib/runtime/legal" "$img/lib/runtime/lib"
    : > "$img/bin/Neutrodyne"
    printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
        "$(grep -E '^javaVersion=' "$FIX_ROOT/desktopApp/runtime.lock" | cut -d= -f2)" \
        > "$img/lib/runtime/release"
    : > "$img/lib/runtime/legal/NOTICE"
    : > "$img/lib/runtime/lib/jrt-fs.jar"
    echo "$img"
}

# jar_zip <path> [entry...] — a real zip: META-INF/MANIFEST.MF plus each named
# entry filled with its own name as content.
jar_zip() {
    local out="$1"
    shift
    python3 - "$out" "$@" <<'PY'
import sys, zipfile
out, entries = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(out, 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    for e in entries:
        z.writestr(e, e + ' bytes')
PY
}

# mkjar <dir> <name> [entry...] — a jar carrying Compose's image name
# <name>-<unpadded md5>.jar; prints "basename<TAB>canonical sha256".
mkjar() {
    local dir="$1" name="$2" f
    shift 2
    jar_zip "$dir/.tmp-jar" "$@"
    f="$dir/${name}-$(unpadded_md5 "$dir/.tmp-jar").jar"
    mv "$dir/.tmp-jar" "$f"
    printf '%s\t%s\n' "${f##*/}" "$(canon_of "$f")"
}

# mkstub <dir> [entry...] — Compose's stub shape: a manifest-only zip named
# skiko-awt-runtime-linux-x64-0.150.1-<own unpadded md5>.jar. The zip variant
# loops until the md5 carries a leading-zero byte, so the name exercises
# Compose's %x rendering dropping it (padded hex would not match). Extra
# entries turn the "stub" into whatever content the case needs. Prints the path.
mkstub() {
    local dir="$1" i=0 f padded j
    shift
    while :; do
        jar_zip "$dir/.stub" "META-INF/stub-$i" "$@"
        padded="$(md5_of "$dir/.stub")"
        for ((j = 0; j < ${#padded}; j += 2)); do
            [ "${padded:j:1}" = 0 ] && break
        done
        [ "$j" -lt "${#padded}" ] && break
        i=$((i + 1))
    done
    f="$dir/skiko-awt-runtime-linux-x64-0.150.1-$(unpadded_md5 "$dir/.stub").jar"
    mv "$dir/.stub" "$f"
    echo "$f"
}

# skiko_cp <dir> <os> <arch> [extra-native-entry...] — the Licensee-checked
# classpath jar: META-INF plus libskiko-<os>-<arch>.<ext> + .sha256 and each
# named extra native + .sha256 (the macOS jar carries both arches). An entry
# written as `name@file` takes the file's bytes as content, otherwise the
# content is the fixture's `cp-native:<name>` — the @-form carries real Mach-O
# fixtures. Prints the manifest rows writeDesktopRuntimeClasspath emits: the
# canonical jar row, then a <entry sha256>  <imgname>#<entry> row per
# native/sidecar — the rows the stub's retained and extracted bytes bind to.
skiko_cp() {
    local dir="$1" os="$2" arch="$3" ext=so
    shift 3
    case "$os" in macos) ext=dylib ;; windows) ext=dll ;; esac
    python3 - "$dir/.cp" "libskiko-${os}-${arch}.${ext}" "$@" <<'PY'
import hashlib, sys, zipfile
out, host, extras = sys.argv[1], sys.argv[2], sys.argv[3:]
with zipfile.ZipFile(out, 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    specs = list(extras)
    if host not in [s.partition('@')[0] for s in specs]:
        specs = [host] + specs
    for spec in specs:
        n, _, f = spec.partition('@')
        native = open(f, 'rb').read() if f else ('cp-native:' + n).encode()
        z.writestr(n, native)
        z.writestr(n + '.sha256', hashlib.sha256(native).hexdigest())
PY
    local f="$dir/skiko-awt-runtime-${os}-${arch}-0.150.1-$(unpadded_md5 "$dir/.cp").jar"
    mv "$dir/.cp" "$f"
    PYTHONPATH="$WORK/normpy" python3 - "$f" <<'PY'
import hashlib, re, sys, zipfile
from norm import norm_entry
p = sys.argv[1]
name = p.rsplit('/', 1)[-1]
with zipfile.ZipFile(p) as z:
    outer = hashlib.sha256()
    for i in sorted(z.infolist(), key=lambda i: i.filename):
        h = hashlib.sha256(b'' if i.is_dir() else norm_entry(i.filename, z.read(i))).hexdigest()
        outer.update(i.filename.encode('utf-8') + b'\0' + h.encode('ascii') + b'\n')
    print('%s  %s' % (outer.hexdigest(), name))
    for n in sorted(z.namelist()):
        if n.endswith('/') or not (re.match(r'^(?:libskiko-|skiko-)[^/]+\.(?:so|dylib|dll)$', n)
                                   or ('/' not in n and n.endswith('.sha256'))):
            continue
        print('%s  %s#%s' % (hashlib.sha256(norm_entry(n, z.read(n))).hexdigest(), name, n))
PY
}

# native_next_to <jar-dir> [os arch [native-file [pin-file]]] — the host
# native extracted loose beside the stub: the classpath jar's entry bytes and
# its sidecar entry's bare hex. With a native-file, the loose bytes come from
# it (a re-signed Mach-O); pin-file then carries the published byte pin — the
# loose sidecar records the pre-sign hash on macOS.
native_next_to() {
    local dir="$1" os="${2:-linux}" arch="${3:-x64}" nf="${4:-}" pf="${5:-}" ext=so n
    case "$os" in macos) ext=dylib ;; windows) ext=dll ;; esac
    n="libskiko-${os}-${arch}.${ext}"
    if [ -n "$nf" ]; then
        cat "$nf" > "$dir/$n"
        cat "$pf" > "$dir/$n.sha256"
    else
        printf 'cp-native:%s' "$n" > "$dir/$n"
        printf 'cp-native:%s' "$n" | sha256sum | cut -d' ' -f1 | tr -d '\n' \
            > "$dir/$n.sha256"
    fi
}

# macho_pair <mode> <name> — writes $WORK/<name>-{pub,re,re-x,pin}: a synthetic
# thin Mach-O pair as published (adhoc-signed; mode=insert leaves the
# LC_CODE_SIGNATURE slot out entirely, a 16-byte header pad in its place) and
# as packaging re-signed it (slot: same load commands, grown blob; insert: the
# cs command written over the pad). re-x is the re-signed bytes with a payload
# byte flipped. pin is the bare sha256 hex of the pub bytes — what the
# classpath .sha256 sidecar records.
macho_pair() {
    python3 - "$WORK/$2" "$1" <<'PY'
import hashlib, struct, sys
base, mode = sys.argv[1], sys.argv[2]
content = b'payload bytes for ' + base.encode().rsplit(b'/', 1)[-1] + b'\n' + bytes(range(64))
# __LINKEDIT spans the whole file like the real dylibs: fileoff=0 and
# filesize=len, with the signature blob as the segment's tail — the real
# pairing normalization relies on (the blob lives inside the segment).
def seg64(vmsize, filesize):
    return struct.pack('<II16sQQQQIIII', 0x19, 72, b'__LINKEDIT' + b'\0' * 6,
                       0x1000, vmsize, 0, filesize, 7, 5, 0, 0)
def cs(do, ds):
    return struct.pack('<IIII', 0x1d, 16, do, ds)
def hdr(ncmds, sizeofcmds):
    return struct.pack('<IIIIIIII', 0xfeedfacf, 0x0100000c, 0, 6,
                       ncmds, sizeofcmds, 0, 0)
# vmsize covers the two legal signed shapes seen on real artifacts: the
# published file may carry the raw extent (jna-x64) while re-signing writes
# the 16K-page-rounded extent (arm64 cputype below).
if mode == 'slot':
    do = 32 + 88 + len(content)
    pub = hdr(2, 88) + seg64(do + 64, do + 64) + cs(do, 64) + content + b'A' * 64
    re_ = hdr(2, 88) + seg64(0x4000, do + 128) + cs(do, 128) + content + b'B' * 128
else:
    # insert: the unsigned file has 16 zero pad bytes where the cs cmd lands
    do = 32 + 88 + len(content)
    pub = hdr(1, 72) + seg64(do, do) + b'\0' * 16 + content
    re_ = hdr(2, 88) + seg64(0x4000, do + 128) + cs(do, 128) + content + b'B' * 128
rex = bytearray(re_)
rex[124] ^= 0xff  # payload starts at 120 (= 32 hdr + 88 cmds) in both modes
open(base + '-pub', 'wb').write(pub)
open(base + '-re', 'wb').write(bytes(re_))
open(base + '-re-x', 'wb').write(bytes(rex))
open(base + '-pin', 'w').write(hashlib.sha256(pub).hexdigest())
PY
}

manifest_row() { printf '%s  %s\n' "$1" "$2" >> "$MANIFEST"; }

# own_jar <dir> <kind> [extra entry...] — the project's own desktopApp jar
# carrying build-info.properties with installKind=<kind>, mangled like any
# image jar. Prints "basename<TAB>canonical sha256". check_jars picks the
# manifest file from this kind: runtime-classpath-<kind>.txt when present.
own_jar() {
    local dir="$1" kind="$2" f
    shift 2
    python3 - "$dir/.own-jar" "$kind" "$@" <<'PY'
import sys, zipfile
out, kind, entries = sys.argv[1], sys.argv[2], sys.argv[3:]
with zipfile.ZipFile(out, 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    z.writestr('build-info.properties',
               'installKind=%s\nversionName=0.1.0\n' % kind)
    for e in entries:
        z.writestr(e, e + ' bytes')
PY
    f="$dir/desktopApp-$(unpadded_md5 "$dir/.own-jar").jar"
    mv "$dir/.own-jar" "$f"
    printf '%s\t%s\n' "${f##*/}" "$(canon_of "$f")"
}

# kind_manifest <kind> — the per-install-kind manifest path
# writeDesktopRuntimeClasspath writes beside runtime-classpath.txt.
kind_manifest() {
    echo "${MANIFEST%.txt}-$1.txt"
}

run_scan() {
    bash "$SCAN" "$1" > "$WORK/scan.out" 2>&1
}

expect_fail() { # <pattern> <case name>
    local status
    run_scan "$img"
    status=$?
    if [ "$status" -eq 0 ] || ! grep -q "$1" "$WORK/scan.out"; then
        t_fail "$2"
        sed 's/^/    /' "$WORK/scan.out" | tail -8 >&2
    else
        t_ok "$2"
    fi
}

# --- cases ---------------------------------------------------------------------

# 1. A mangled-name image jar listed on the manifest by its image name, hashing
#    to the row's sha256, is a classpath jar. (The old base-name lookup rejected
#    every real image jar.)
img="$(new_image a)"
read -r name sha < <(mkjar "$img/lib/app" neutrodyne-core ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$sha" "$name"
if run_scan "$img"; then
    t_ok "image jar named on the manifest with matching sha256 is accepted"
else
    t_fail "image jar named on the manifest with matching sha256 is accepted"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 2. Same name, different bytes: the row's sha256 binds the artifact.
img="$(new_image b)"
read -r name _sha < <(mkjar "$img/lib/app" neutrodyne-core ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$(printf 'tampered' | sha256sum | cut -d' ' -f1)" "$name"
expect_fail 'JAR differs from the Licensee-checked classpath artifact' \
    "manifest-named jar with wrong bytes is rejected"

# 3. A mangled-looking jar absent from the manifest is rejected.
img="$(new_image c)"
mkjar "$img/lib/app" intruder ch/lkmc/neutrodyne/Intruder.class >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "jar missing from the manifest is rejected"

# 4. The skiko stub under its documented conditions: a manifest-only zip whose
#    name suffix is its own md5, the manifest carries a
#    skiko-awt-runtime-<os>-<arch>-*.jar entry (the classpath jar stays
#    manifest-only) with #entry rows for its natives/sidecars, and the
#    extracted native beside the stub carries the classpath entry's bytes.
img="$(new_image d)"
skiko_cp "$WORK" linux x64 >> "$MANIFEST"
skiko_name="$(head -1 "$MANIFEST" | sed 's/^[0-9a-f]*  //')"
skiko_sha="$(head -1 "$MANIFEST" | cut -d' ' -f1)"
mkstub "$img/lib/app" >/dev/null
native_next_to "$img/lib/app"
if run_scan "$img"; then
    t_ok "skiko stub is accepted under the documented conditions"
else
    t_fail "skiko stub is accepted under the documented conditions"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 5. The stub without a manifest skiko entry is just another off-manifest jar.
img="$(new_image e)"
mkstub "$img/lib/app" >/dev/null
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko stub without a manifest entry is rejected"

# 6. A jar squatting on the stub's name — arbitrary md5-shaped suffix, manifest
#    entry and native in place — is rejected the moment it carries class
#    payload: that is the classpath jar in disguise, not the stub.
img="$(new_image f)"
manifest_row "$(printf 'x' | sha256sum | cut -d' ' -f1)" \
    "skiko-awt-runtime-linux-x64-0.150.1-00000000000000000000000000000000.jar"
jar_zip "$img/lib/app/.squat" org/jetbrains/skiko/SkiaLayer.class
mv "$img/lib/app/.squat" \
    "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-11111111111111111111111111111111.jar"
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "class-bearing jar squatting on the skiko stub name is rejected"

# 7. The stub without the extracted native and its sidecar is rejected (the
#    exemption exists because the classpath jar's payload lands as the native).
img="$(new_image g)"
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko stub without the extracted native and .sha256 sidecar is rejected"

# 8. A manifest skiko entry for a different os-arch does not cover the stub.
#    The stub here carries its md5 zero-padded — the accepted alternative
#    rendering — and still fails at the os-arch gate.
img="$(new_image h)"
manifest_row "$skiko_sha" "skiko-awt-runtime-windows-x64-0.150.1-${skiko_name##*-0.150.1-}"
stub="$(mkstub "$img/lib/app")"
mv "$stub" "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-$(md5_of "$stub").jar"
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko stub whose manifest entry names another os-arch is rejected"

# 9. A "stub" carrying executable classes is the classpath jar in disguise: the
#    exemption is for Compose's manifest-only stub, not for the name alone.
img="$(new_image i)"
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" org/jetbrains/skiko/SkiaLayer.class >/dev/null
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named jar carrying a .class is rejected"

# 10. Non-class payload outside META-INF is unexpected stub content too.
img="$(new_image j)"
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" skiko/linux/x64/libskiko.so >/dev/null
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named jar carrying a non-META-INF payload is rejected"

# 11. A file under the stub's correctly-mangled name that is not a jar at all
#     is not the stub.
img="$(new_image k)"
manifest_row "$skiko_sha" "$skiko_name"
printf 'not a zip' > "$img/lib/app/.raw"
mv "$img/lib/app/.raw" \
    "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-$(unpadded_md5 "$img/lib/app/.raw").jar"
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named non-zip is rejected"

# 12. The macOS pipeline recompresses image jars after mangling: same entries,
#     different container bytes, name unchanged. The canonical content hash
#     must still match — this is nightly 37860407043's mass "JAR differs".
img="$(new_image l)"
read -r name sha < <(mkjar "$img/lib/app" neutrodyne-core ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$sha" "$name"
python3 - "$img/lib/app/$name" <<'PY' || t_fail "repacked jar fixture did not change container bytes"
import sys, zipfile, hashlib
p = sys.argv[1]
before = hashlib.sha256(open(p, 'rb').read()).hexdigest()
with zipfile.ZipFile(p) as z:
    entries = [(i.filename, b'' if i.is_dir() else z.read(i)) for i in z.infolist()]
with zipfile.ZipFile(p, 'w', zipfile.ZIP_DEFLATED) as z:
    for n, data in entries:
        z.writestr(n, data)
sys.exit(0 if hashlib.sha256(open(p, 'rb').read()).hexdigest() != before else 1)
PY
if run_scan "$img"; then
    t_ok "recompressed jar with identical content is accepted"
else
    t_fail "recompressed jar with identical content is accepted"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 13. macOS runtime home — jlink nests the JDK under Contents/runtime/
#     Contents/Home; legal/ and release live there, jars under Contents/app
#     (nightly 37860407043's real bundle layout).
img="$WORK/img-mac"
: > "$MANIFEST"
mkdir -p "$img/Contents/app" "$img/Contents/runtime/Contents/Home/legal"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(grep -E '^javaVersion=' "$FIX_ROOT/desktopApp/runtime.lock" | cut -d= -f2)" \
    > "$img/Contents/runtime/Contents/Home/release"
: > "$img/Contents/runtime/Contents/Home/legal/NOTICE"
read -r name sha < <(mkjar "$img/Contents/app" neutrodyne-core ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$sha" "$name"
if run_scan "$img"; then
    t_ok ".app Contents/runtime/Contents/Home runtime home is recognised"
else
    t_fail ".app Contents/runtime/Contents/Home runtime home is recognised"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# --- package-format boundaries ---------------------------------------------------
# Stub tools reproduce the native runners' boundary: rpm2cpio fails on a path
# that is not a file, rpm answers check_rpm's -qp query, cpio plants the
# extracted opt/neutrodyne root, ditto records its args and extracts.
mkdir -p "$WORK/stubbin" "$WORK/dist"
cat >"$WORK/stubbin/rpm2cpio" <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$RPM2CPIO_ARGS"
if [ ! -f "$1" ]; then
    echo "rpm2cpio: $1: No such file or directory" >&2
    exit 2
fi
cat /dev/null
EOF
cat >"$WORK/stubbin/cpio" <<'EOF'
#!/usr/bin/env bash
cat >/dev/null
mkdir -p opt/neutrodyne
EOF
cat >"$WORK/stubbin/rpm" <<'EOF'
#!/usr/bin/env bash
# the Requires a neutrodyne.spec-built package carries; RPM_REQUIRES overrides
reqs="${RPM_REQUIRES:-libc.so.6()(64bit) libasound.so.2()(64bit) libX11.so.6()(64bit) libXext.so.6()(64bit) libXi.so.6()(64bit) libXrender.so.6()(64bit) libXtst.so.6()(64bit) libfreetype.so.6()(64bit) libfontconfig.so.1()(64bit) libz.so.1()(64bit) /bin/sh rpmlib(CompressedFileNames) rpmlib(FileDigests) rpmlib(PayloadFilesHavePrefix)}"
for r in $reqs; do printf '%s\n' "$r"; done
EOF
cat >"$WORK/stubbin/ditto" <<'EOF'
#!/usr/bin/env bash
echo "$@" >> "$DITTO_ARGS"
python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$3" "$4"
EOF
chmod +x "$WORK/stubbin/"*
: >"$WORK/dist/neutrodyne-0.1.0-1.x86_64.rpm"
: > "$MANIFEST"

# 14. A relative dist/…rpm input must still reach rpm2cpio as an existing path —
#     the extraction subshell cd's away from the caller's cwd (nightly
#     37860407043: rpm2cpio dist/… → No such file). A spec-conforming Requires
#     list passes check_rpm; the planted root proves traversal ran.
out="$(cd "$WORK" && RPM2CPIO_ARGS="$WORK/rpm2cpio.args" \
    PATH="$WORK/stubbin:$PATH" bash "$SCAN" dist/neutrodyne-0.1.0-1.x86_64.rpm 2>&1 || true)"
arg="$(cat "$WORK/rpm2cpio.args" 2>/dev/null)"
if [ "${arg#/}" != "$arg" ] && [ -f "$arg" ] \
    && ! printf '%s' "$out" | grep -q 'No such file' \
    && ! printf '%s' "$out" | grep -q 'missing our Requires' \
    && printf '%s' "$out" | grep -q 'bundled runtime'; then
    t_ok "relative rpm path extracts and spec-conforming Requires pass"
else
    t_fail "relative rpm path extracts and spec-conforming Requires pass"
    printf '    rpm2cpio arg: %s\n' "$arg" >&2
    printf '%s\n' "$out" | sed 's/^/    /' | tail -8 >&2
fi

# 15. The Requires gate is untouched: a package built without our spec still
#     fails on every soname it should carry.
out="$(cd "$WORK" && RPM2CPIO_ARGS="$WORK/rpm2cpio2.args" \
    RPM_REQUIRES='/bin/sh rpmlib(CompressedFileNames)' \
    PATH="$WORK/stubbin:$PATH" bash "$SCAN" dist/neutrodyne-0.1.0-1.x86_64.rpm 2>&1 || true)"
if printf '%s' "$out" | grep -qF 'RPM is missing our Requires: libc.so.6()(64bit)'; then
    t_ok "rpm without our spec's Requires is rejected"
else
    t_fail "rpm without our spec's Requires is rejected"
    printf '%s\n' "$out" | sed 's/^/    /' | tail -8 >&2
fi

# 16. zip extraction takes ditto -x -k where it exists so an .app's symlinks
#     survive (nightly 37860407043: python's zipfile wrote the jlink legal/
#     symlinks as regular files and broke the sealed bundle).
python3 - "$WORK/dist/neutrodyne-0.1.0-macos-arm64.zip" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('Neutrodyne.app/Contents/runtime/Contents/Home/release', 'JAVA_VERSION="x"')
PY
out="$(cd "$WORK" && DITTO_ARGS="$WORK/ditto.args" \
    PATH="$WORK/stubbin:$PATH" bash "$SCAN" dist/neutrodyne-0.1.0-macos-arm64.zip 2>&1 || true)"
if grep -q '^-x -k /.*/dist/neutrodyne-0.1.0-macos-arm64.zip ' "$WORK/ditto.args" 2>/dev/null; then
    t_ok "zip extraction goes through ditto -x -k where available"
else
    t_fail "zip extraction goes through ditto -x -k where available"
    cat "$WORK/ditto.args" 2>/dev/null | sed 's/^/    /' >&2
fi

# --- macOS skiko stub shape --------------------------------------------------------
# The real macOS artifact's stub was named
# skiko-awt-runtime-macos-arm64-0.150.1-20151b90a8aba3e93f91242dfd61af.jar:
# a 30-hex mangle (Compose's per-byte %x md5 is unpadded, 16-32 digits) whose
# value no longer matches the recompressed file's md5.

# 17. macOS stub under the real artifact's shape: repacked jar (its md5 does not
#     match the suffix), manifest row for macos-arm64, stub-only entries, dylib
#     with matching sidecar.
img="$WORK/img-skm"
: > "$MANIFEST"
mkdir -p "$img/Contents/app" "$img/Contents/runtime/Contents/Home/legal"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(grep -E '^javaVersion=' "$FIX_ROOT/desktopApp/runtime.lock" | cut -d= -f2)" \
    > "$img/Contents/runtime/Contents/Home/release"
: > "$img/Contents/runtime/Contents/Home/legal/NOTICE"
skiko_cp "$WORK" macos arm64 >> "$MANIFEST"
jar_zip "$img/Contents/app/.stub"
mv "$img/Contents/app/.stub" \
    "$img/Contents/app/skiko-awt-runtime-macos-arm64-0.150.1-20151b90a8aba3e93f91242dfd61af.jar"
native_next_to "$img/Contents/app" macos arm64
if run_scan "$img"; then
    t_ok "macOS skiko stub with a repacked (non-md5) 30-hex mangle is accepted"
else
    t_fail "macOS skiko stub with a repacked (non-md5) 30-hex mangle is accepted"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 18. The fallback is macOS-only: a linux-named stub whose suffix is 30 hex but
#     not the file's own md5 is still the classpath jar squatting on the name.
img="$(new_image m)"
manifest_row "$(printf 'x' | sha256sum | cut -d' ' -f1)" \
    "skiko-awt-runtime-linux-x64-0.150.1-00000000000000000000000000000000.jar"
jar_zip "$img/lib/app/.squat"
mv "$img/lib/app/.squat" \
    "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-20151b90a8aba3e93f91242dfd61af.jar"
native_next_to "$img/lib/app"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "linux skiko stub whose suffix is not its own md5 is rejected"

# --- per-install-kind manifests ---------------------------------------------------
# installKind lands in build-info.properties inside the own-app jar, so every
# packaging invocation mangles desktopApp.jar differently; one shared manifest
# cannot cover all packages of a matrix job (nightly 37868304885: the deb and
# rpm jars missed the manifest the tar.gz invocation wrote). The task writes
# runtime-classpath-<kind>.txt and the scan resolves the image's kind file.

# 19. The deb image's own jar is bound by runtime-classpath-deb.txt even though
#     the shared runtime-classpath.txt names the tar.gz invocation's jar.
img="$(new_image n)"
read -r own_name own_sha \
    < <(own_jar "$img/lib/app" deb ch/lkmc/neutrodyne/MainKt.class)
printf '%s  %s\n' "$own_sha" "$own_name" >> "$(kind_manifest deb)"
manifest_row "$(printf 'tar' | sha256sum | cut -d' ' -f1)" \
    "desktopApp-25a6f515c521b1821505750593c5122.jar"
if run_scan "$img"; then
    t_ok "own-app jar is bound by its install kind's manifest"
else
    t_fail "own-app jar is bound by its install kind's manifest"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 20. The kind file is authoritative when present: a jar the shared manifest
#     names but the kind file does not is still off the classpath (a stale
#     shared manifest must not widen the binding).
img="$(new_image o)"
read -r own_name own_sha \
    < <(own_jar "$img/lib/app" rpm ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$own_sha" "$own_name"
printf '%s  %s\n' "$(printf 'x' | sha256sum | cut -d' ' -f1)" "other-1.jar" \
    > "$(kind_manifest rpm)"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "kind manifest overrides the shared one when present"

# 21. Without a kind file the shared manifest still covers the image (dev
#     builds and manifests written before the per-kind split).
img="$(new_image p)"
read -r own_name own_sha \
    < <(own_jar "$img/lib/app" rpm ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$own_sha" "$own_name"
if run_scan "$img"; then
    t_ok "shared manifest still binds when no kind file exists"
else
    t_fail "shared manifest still binds when no kind file exists"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 22. A foreign own-app jar — desktopApp-<md5>.jar that no manifest names —
#     stays a violation: the per-kind split is not an exemption.
img="$(new_image q)"
own_jar "$img/lib/app" msi ch/lkmc/neutrodyne/Evil.class >/dev/null
printf '%s  %s\n' "$(printf 'x' | sha256sum | cut -d' ' -f1)" "other-1.jar" \
    > "$(kind_manifest msi)"
manifest_row "$(printf 'y' | sha256sum | cut -d' ' -f1)" "other-2.jar"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "foreign desktopApp jar absent from every manifest is rejected"

# --- in-jar natives of the skiko stub ----------------------------------------------
# The real macOS stub keeps the natives for the other targets inside the jar —
# libskiko-macos-x64.dylib plus its .sha256 — while the host arch's lands loose
# beside it (nightly 37868304885). The in-jar pair must still pin each other.

# mkstub_native <dir> [no_sidecar|bad_sidecar|mutated] [native-file [pin-file]]
# — a skiko-awt-runtime-macos-arm64 stub keeping libskiko-macos-x64.dylib +
# .sha256 in-jar; named by its own unpadded md5 like Compose does. The retained
# native carries the classpath entry's bytes — except 'mutated', where the
# in-jar native is rewritten and its sidecar is recomputed to match, a
# self-consistent pair that never saw the Licensee-checked classpath jar.
# With native-file the retained bytes come from it (a re-signed Mach-O);
# pin-file then carries the classpath sidecar's published pin.
mkstub_native() {
    local dir="$1" mode="${2:-}" nf="${3:-}" pf="${4:-}" f
    python3 - "$dir/.stubn" "$mode" "$nf" "$pf" <<'PY'
import hashlib, sys, zipfile
out, mode, nf, pf = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
n = 'libskiko-macos-x64.dylib'
if mode == 'mutated':
    native = b'attacker native bytes'
elif nf:
    native = open(nf, 'rb').read()
else:
    native = ('cp-native:' + n).encode()
with zipfile.ZipFile(out, 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    z.writestr(n, native)
    if mode != 'no_sidecar':
        if pf:
            sidecar = open(pf).read()
        elif mode == 'bad_sidecar':
            sidecar = '0' * 64
        else:
            sidecar = hashlib.sha256(native).hexdigest()
        z.writestr(n + '.sha256', sidecar)
PY
    f="$dir/skiko-awt-runtime-macos-arm64-0.150.1-$(unpadded_md5 "$dir/.stubn").jar"
    mv "$dir/.stubn" "$f"
    echo "$f"
}

# mac_stub_image <tag> — an image whose manifest carries the macos-arm64
# classpath jar's rows (with the x64 native entry the stub retains) and whose
# app dir has the loose arm64 native + sidecar bound to the classpath bytes.
mac_stub_image() {
    local img
    img="$(new_image "$1")"
    skiko_cp "$WORK" macos arm64 libskiko-macos-x64.dylib >> "$MANIFEST"
    native_next_to "$img/lib/app" macos arm64
    echo "$img"
}

# 23. Stub with the retained x64 native and a matching in-jar .sha256 is
#     accepted — the documented stub shape on macOS.
img="$(mac_stub_image s)"
mkstub_native "$img/lib/app" >/dev/null
if run_scan "$img"; then
    t_ok "skiko stub retaining an in-jar other-arch native + .sha256 is accepted"
else
    t_fail "skiko stub retaining an in-jar other-arch native + .sha256 is accepted"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 24. The retained native without its in-jar sidecar is unbound payload.
img="$(mac_stub_image t)"
mkstub_native "$img/lib/app" no_sidecar >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "in-jar skiko native without its .sha256 entry is rejected"

# 25. A sidecar pinning different bytes is not the artifact Compose wrote.
img="$(mac_stub_image u)"
mkstub_native "$img/lib/app" bad_sidecar >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "in-jar skiko native whose .sha256 does not match is rejected"

# 26. The boundary of the exemption: mutating a retained native and recomputing
#     its in-jar .sha256 leaves a self-consistent pair, but the manifest's
#     #entry rows pin the Licensee-checked classpath bytes — provenance, not
#     self-consistency, is what binds (review of nightly 37868304885's fix).
img="$(mac_stub_image v)"
mkstub_native "$img/lib/app" mutated >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "mutated in-jar native with a matching rewritten sidecar is rejected"

# 27. The same attack on the loose native beside the stub.
img="$(mac_stub_image w)"
mkstub_native "$img/lib/app" >/dev/null
printf 'attacker native bytes' > "$img/lib/app/libskiko-macos-arm64.dylib"
sha_of "$img/lib/app/libskiko-macos-arm64.dylib" \
    | tr -d ' \n' > "$img/lib/app/libskiko-macos-arm64.dylib.sha256"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "mutated loose native with a matching rewritten sidecar is rejected"

# --- Mach-O signature normalization -----------------------------------------------
# macOS packaging re-signs natives in place: the published jar already carries
# an ad-hoc signature (datasize grows, __LINKEDIT fields move) or is unsigned
# and gains an LC_CODE_SIGNATURE command over header padding (nightly
# 37874042553 showed codesign --remove-signature cannot byte-roundtrip either
# shape). The manifest's #entry rows hash normalized published bytes; the
# image's retained and loose natives are normalized the same way — so a
# re-signed native binds, a mutated payload does not.

# 28. Re-signed natives (pre-allocated signature slot, grown blob) match the
#     published classpath bytes after normalization — both surfaces.
macho_pair slot mx
macho_pair slot ma
img="$(new_image x)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-arm64.dylib@$WORK/ma-pub" \
    "libskiko-macos-x64.dylib@$WORK/mx-pub" >> "$MANIFEST"
native_next_to "$img/lib/app" macos arm64 "$WORK/ma-re" "$WORK/ma-pin"
mkstub_native "$img/lib/app" "" "$WORK/mx-re" "$WORK/mx-pin" >/dev/null
if run_scan "$img"; then
    t_ok "re-signed Mach-O natives (grown slot) bind to the classpath bytes"
else
    t_fail "re-signed Mach-O natives (grown slot) bind to the classpath bytes"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 29. The unsigned-published shape: packaging inserts the signature command
#     over header padding — normalization still binds.
macho_pair insert iy
macho_pair insert ia
img="$(new_image y)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-arm64.dylib@$WORK/ia-pub" \
    "libskiko-macos-x64.dylib@$WORK/iy-pub" >> "$MANIFEST"
native_next_to "$img/lib/app" macos arm64 "$WORK/ia-re" "$WORK/ia-pin"
mkstub_native "$img/lib/app" "" "$WORK/iy-re" "$WORK/iy-pin" >/dev/null
if run_scan "$img"; then
    t_ok "Mach-O natives signed fresh over header padding are accepted"
else
    t_fail "Mach-O natives signed fresh over header padding are accepted"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 30. A mutated re-signed payload on the loose surface: normalization is not a
#     side channel — the manifest row still pins the published bytes, and the
#     loose sidecar rewritten to the mutated hash mismatches its own row.
macho_pair slot za
img="$(new_image z)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-arm64.dylib@$WORK/za-pub" \
    libskiko-macos-x64.dylib >> "$MANIFEST"
native_next_to "$img/lib/app" macos arm64 "$WORK/za-re-x" "$WORK/za-pin"
sha_of "$img/lib/app/libskiko-macos-arm64.dylib" \
    | tr -d ' \n' > "$img/lib/app/libskiko-macos-arm64.dylib.sha256"
mkstub_native "$img/lib/app" >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "mutated re-signed loose native with a rewritten sidecar is rejected"

# 31. The same mutated re-signed payload retained inside the stub, in-jar
#     sidecar recomputed to match it.
macho_pair insert qx
img="$(new_image aa)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-x64.dylib@$WORK/qx-pub" >> "$MANIFEST"
native_next_to "$img/lib/app" macos arm64
python3 - "$img/lib/app/.stubx" "$WORK/qx-re-x" <<'PY'
import hashlib, sys, zipfile
out, nf = sys.argv[1], sys.argv[2]
native = open(nf, 'rb').read()
with zipfile.ZipFile(out, 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    z.writestr('libskiko-macos-x64.dylib', native)
    z.writestr('libskiko-macos-x64.dylib.sha256',
               hashlib.sha256(native).hexdigest())
PY
f="$img/lib/app/skiko-awt-runtime-macos-arm64-0.150.1-$(unpadded_md5 "$img/lib/app/.stubx").jar"
mv "$img/lib/app/.stubx" "$f"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "mutated re-signed in-jar native with a rewritten sidecar is rejected"

# 32. A header-field mutation that normalization must still see: bumping the
#     re-signed dylib's __LINKEDIT filesize (the non-signature extent is
#     bound as filesize minus the enclosed signature bytes — normalization
#     discounts the blob, not the whole field).
macho_pair slot hf
img="$(new_image ab)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-arm64.dylib@$WORK/hf-pub" \
    libskiko-macos-x64.dylib >> "$MANIFEST"
python3 - "$WORK/hf-re" "$WORK/hf-re-hdr" <<'PY'
import struct, sys
b = bytearray(open(sys.argv[1], 'rb').read())
fs = struct.unpack('<Q', b[32 + 48:32 + 56])[0]
struct.pack_into('<Q', b, 32 + 48, fs + 0x100)
open(sys.argv[2], 'wb').write(bytes(b))
PY
native_next_to "$img/lib/app" macos arm64 "$WORK/hf-re-hdr" "$WORK/hf-pin"
mkstub_native "$img/lib/app" >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "re-signed native with a mutated __LINKEDIT extent is rejected"

# 33. The command-table size field is bound the same way: a sizeofcmds bump
#     on a retained in-jar re-signed native (the manifest's #entry row pins
#     sizeofcmds minus the signature slots).
macho_pair slot sc
img="$(new_image ac)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-x64.dylib@$WORK/sc-pub" >> "$MANIFEST"
native_next_to "$img/lib/app" macos arm64
python3 - "$WORK/sc-re" "$WORK/sc-pin" "$img/lib/app/.stubsc" <<'PY'
import struct, sys, zipfile
b = bytearray(open(sys.argv[1], 'rb').read())
struct.pack_into('<I', b, 20, struct.unpack('<I', b[20:24])[0] + 16)
with zipfile.ZipFile(sys.argv[3], 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    z.writestr('libskiko-macos-x64.dylib', bytes(b))
    z.writestr('libskiko-macos-x64.dylib.sha256', open(sys.argv[2]).read())
PY
f="$img/lib/app/skiko-awt-runtime-macos-arm64-0.150.1-$(unpadded_md5 "$img/lib/app/.stubsc").jar"
mv "$img/lib/app/.stubsc" "$f"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "re-signed in-jar native with a mutated sizeofcmds is rejected"

# 34. vmsize is not byte-reversible, but it is not a free field either: the
#     only legal values are the file extent or its architecture page-rounded
#     form — an arbitrary reservation fails closed.
macho_pair slot vm
img="$(new_image ad)"
skiko_cp "$WORK" macos arm64 \
    "libskiko-macos-arm64.dylib@$WORK/vm-pub" \
    libskiko-macos-x64.dylib >> "$MANIFEST"
python3 - "$WORK/vm-re" "$WORK/vm-re-vs" <<'PY'
import struct, sys
b = bytearray(open(sys.argv[1], 'rb').read())
struct.pack_into('<Q', b, 32 + 32, 0x2222)  # not fsize, not page-rounded
open(sys.argv[2], 'wb').write(bytes(b))
PY
native_next_to "$img/lib/app" macos arm64 "$WORK/vm-re-vs" "$WORK/vm-pin"
mkstub_native "$img/lib/app" >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "re-signed native with an out-of-range __LINKEDIT vmsize is rejected"

echo
if [ "$FAILED" -gt 0 ]; then
    echo "check-desktop-image.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "check-desktop-image.test: all cases behave"
