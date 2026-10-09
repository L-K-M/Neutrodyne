#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-desktop-image.sh [--no-engine] <image dir|package file ...>
#
# The image scan rules of 11 on a packaged app image or package (11 Image scan rules;
# 09 Build-output checks). Runs in ci.yml's desktop-smoke (Linux x64 image), in
# nightly.yml's desktop-matrix and in release.yml's desktop job on every target.
#
#   <dir>      a jpackage app image (Neutrodyne/ or Neutrodyne.app): every rule
#   <file>     a release package: .deb, .rpm, .msi, .zip, .tar.gz — the package's own
#              rules plus the directory rules on its extracted contents
#   --no-engine  additionally asserts that no Python tree, yt-dlp file or
#              :youtube:ytdlp-desktop JAR is present (emergency build, 01)
#
# Inputs besides the image: desktopApp/runtime.lock, desktopApp/wix.lock,
# desktopApp/packaging/deb/control, desktopApp/packaging/rpm/neutrodyne.spec, the
# Licensee-checked runtime-classpath manifest
# (desktopApp/build/desktop-packaging/runtime-classpath.txt, written by
# :desktopApp:writeDesktopRuntimeClasspath) and, once the engine ships
# (MD3), desktopApp/python-components.lock.
#
# Exit 0 = all checks pass; 1 = at least one violation (all violations are printed);
# 2 = usage error. Prints the installed and package sizes for PB27 at the end.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
RUNTIME_LOCK="$REPO_ROOT/desktopApp/runtime.lock"
WIX_LOCK="$REPO_ROOT/desktopApp/wix.lock"
DEB_CONTROL="$REPO_ROOT/desktopApp/packaging/deb/control"
RPM_SPEC="$REPO_ROOT/desktopApp/packaging/rpm/neutrodyne.spec"
PY_LOCK="$REPO_ROOT/youtube/ytdlp-desktop/python-components.lock"
CLASSPATH_MANIFEST="$REPO_ROOT/desktopApp/build/desktop-packaging/runtime-classpath.txt"

NO_ENGINE=false
INPUTS=()
for arg in "$@"; do
    case "$arg" in
        --no-engine) NO_ENGINE=true ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) INPUTS+=("$arg") ;;
    esac
done

if [ "${#INPUTS[@]}" -eq 0 ]; then
    echo "usage: $0 [--no-engine] <image dir|package file ...>" >&2
    exit 2
fi

FAILURES=0
fail() {
    echo "FAIL: $1" >&2
    FAILURES=$((FAILURES + 1))
}
note() { echo "note: $1"; }

lock_prop() { grep -E "^$2=" "$1" | tail -1 | cut -d= -f2-; }

# macOS carries no sha256sum/stat -c/du -m; use what the host offers (desktop-matrix
# runs this on macos-15 too).
sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

file_bytes() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }

# jar_content_hash <jar> — SHA-256 over the jar's canonical content: for each
# entry sorted by name, "<name>\0<sha256hex(uncompressed content)>\n". The macOS
# packaging pipeline recompresses image JARs after Compose names them
# (nightly 37860407043: same entries, different container bytes), so the
# manifest pins content, not container bytes. A non-zip file hashes as its raw
# bytes. WriteDesktopRuntimeClasspath produces the identical digest.
jar_content_hash() {
    python3 - "$1" <<'PYEOF'
import struct, sys, zipfile, hashlib
path = sys.argv[1]
try:
    z = zipfile.ZipFile(path)
except zipfile.BadZipFile:
    print(hashlib.sha256(open(path, 'rb').read()).hexdigest())
    sys.exit(0)

# macOS packaging signs every Mach-O it finds, inside jars too, and
# re-signing is not byte-reversible — `codesign --remove-signature` leaves a
# re-signed file different from the published one (nightly 37874042553): the
# signature slot's LC_CODE_SIGNATURE datasize and __LINKEDIT sizes grow and a
# freshly inserted command consumes header padding. Both sides therefore hash
# the normalized form — identical to normalizedEntryBytes/normalizeThinMacho
# in DesktopPackaging.kt: ncmds/sizeofcmds zeroed, each LC_CODE_SIGNATURE
# command emitted as zeros (covers the re-filled pre-allocated slot and the
# freshly inserted one alike), __LINKEDIT's vmsize/filesize zeroed, and the
# [dataoff, dataoff+datasize) blob region cut. Everything else — code, data,
# every other load command — stays byte-exact, so tampered content still
# differs. The extension gate keeps the cafebabe fat magic away from .class.
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

def normalized(data, name):
    if name.rsplit('.', 1)[-1] not in ('dylib', 'jnilib', 'so'):
        return data
    return norm_macho(data)

outer = hashlib.sha256()
with z:
    for i in sorted(z.infolist(), key=lambda i: i.filename):
        h = hashlib.sha256(b'' if i.is_dir() else normalized(z.read(i), i.filename)).hexdigest()
        outer.update(i.filename.encode('utf-8') + b'\0' + h.encode('ascii') + b'\n')
print(outer.hexdigest())
PYEOF
}

md5_of() {
    if command -v md5sum >/dev/null 2>&1; then
        md5sum "$1" | cut -d' ' -f1
    else
        md5 -q "$1"
    fi
}

dir_mib() {
    local kb
    kb="$(du -sk "$1" | cut -f1)"
    echo $((kb / 1024))
}

# manifest_for_image <img> — the own-app jar embeds installKind in
# build-info.properties, so every packaging invocation mangles desktopApp.jar
# differently and no single manifest can cover all packages of one matrix job
# (nightly 37868304885: the deb and rpm jars missed the shared manifest the
# tar.gz invocation wrote). writeDesktopRuntimeClasspath therefore also writes
# runtime-classpath-<kind>.txt; when the image's kind file exists it is
# authoritative, otherwise the shared runtime-classpath.txt covers dev images
# and pre-kind builds. A kind outside [A-Za-z0-9._-] never reaches the path.
manifest_for_image() {
    local img="$1" jar kind
    jar="$(find "$img" -name 'desktopApp-*.jar' -print -quit)"
    kind=""
    if [ -n "$jar" ] && command -v python3 >/dev/null 2>&1; then
        kind="$(python3 - "$jar" <<'PYEOF'
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        for line in z.read('build-info.properties').decode('utf-8', 'replace').splitlines():
            if line.startswith('installKind='):
                print(line.split('=', 1)[1].strip())
                break
except Exception:
    pass
PYEOF
)"
    fi
    case "$kind" in
        ''|*[!A-Za-z0-9._-]*)
            echo "$CLASSPATH_MANIFEST" ;;
        *)
            local pk="${CLASSPATH_MANIFEST%.txt}-$kind.txt"
            if [ -f "$pk" ]; then
                echo "$pk"
            else
                echo "$CLASSPATH_MANIFEST"
            fi ;;
    esac
}

# ver_gt A B — true when dotted version A is above B (sort -V is GNU-only).
ver_gt() {
    local IFS=.
    local -a a b
    read -ra a <<<"$1"
    read -ra b <<<"$2"
    local i ai bi
    for ((i = 0; i < 8; i++)); do
        ai=${a[i]:-0}; bi=${b[i]:-0}
        ((10#$ai > 10#$bi)) && return 0
        ((10#$ai < 10#$bi)) && return 1
    done
    return 1
}

# --- rule 1: forbidden names (11 item 1) ---------------------------------------
# Basename globs; plain words without * are exact basename matches, so `bun` never
# eats `sqlite-bundled`. The python names are the tree pieces trim-python.sh removes.
FORBIDDEN_BASENAME_GLOBS=(
    '_dbm*' 'libdb*' '_gdbm*' 'libreadline*' 'readline*'
    '_tkinter*' 'libtcl*' 'libtk*' 'tcl9*' 'tk9*' 'itcl*' 'thread*'
    'ensurepip' 'mutagen*' 'bgutil*'
    'qjs' 'qjs.exe' 'deno' 'node' 'bun'
    'AppRun' 'libfuse*' 'proguard*' 'jextract*' 'javafx*' 'vlcj*'
    'gstreamer*' 'libavfilter*' 'libswscale*' 'libpostproc*' 'libmpv*'
)
# Path fragments that are forbidden wherever they appear.
FORBIDDEN_PATH_GLOBS=('*/site-packages/pip*' '*/site-packages/ensurepip*')

# libedit may carry readline's ABI; it is never a violation (11 item 1).
allowed_name() {
    case "$1" in
        libedit*) return 0 ;;
        *) return 1 ;;
    esac
}

check_forbidden_names() {
    local root="$1" path base pat
    while IFS= read -r -d '' path; do
        base="${path##*/}"
        if allowed_name "$base"; then
            continue
        fi
        for pat in "${FORBIDDEN_BASENAME_GLOBS[@]}"; do
            # shellcheck disable=SC2254
            case "$base" in
                $pat) fail "forbidden file or directory in image: ${path#"$root"/}" ;;
            esac
        done
        for pat in "${FORBIDDEN_PATH_GLOBS[@]}"; do
            # shellcheck disable=SC2254
            case "$path" in
                $pat) fail "forbidden path in image: ${path#"$root"/}" ;;
            esac
        done
    done < <(find "$root" -mindepth 1 -print0)
}

# --- rule 2: runtime/legal and runtime/release ----------------------------------
runtime_dir() {
    # The jlink'd runtime sits at lib/runtime on Linux and Windows; inside a .app
    # bundle jlink nests the JDK home one level deeper at Contents/runtime/
    # Contents/Home (legal/ and release live there, not beside runtime's top).
    local dir
    for dir in "$1/lib/runtime" "$1/Contents/runtime/Contents/Home" "$1/Contents/runtime" "$1/runtime"; do
        if [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    done
    return 1
}

check_runtime_layout() {
    local img="$1" rt
    if ! rt="$(runtime_dir "$img")"; then
        fail "no bundled runtime directory in image (expected lib/runtime or Contents/runtime)"
        return
    fi
    if [ ! -d "$rt/legal" ] || [ -z "$(ls -A "$rt/legal" 2>/dev/null)" ]; then
        fail "runtime/legal/ missing or empty in image (11 Keep the notices)"
    fi
    if [ ! -f "$rt/release" ]; then
        fail "runtime/release missing in image"
        return
    fi
    local want got
    want="$(lock_prop "$RUNTIME_LOCK" javaVersion)"
    got="$(grep -E '^JAVA_VERSION=' "$rt/release" | tail -1 | cut -d'"' -f2)"
    if [ "$got" != "$want" ]; then
        fail "runtime/release JAVA_VERSION '$got' != runtime.lock javaVersion '$want'"
    fi
}

# --- rule 3: FFmpeg names and the licence file ----------------------------------
check_ffmpeg() {
    local root="$1" path base
    while IFS= read -r -d '' path; do
        base="${path##*/}"
        case "$base" in
            libavcodec*|libavformat*|libavutil*|libswresample*|avcodec*|avformat*|avutil*|swresample*)
                ;; # upstream names, as LGPL §6 requires (11 FFmpeg LGPL obligations)
            *avcodec*|*avformat*|*avutil*|*swresample*)
                fail "FFmpeg library under a non-upstream name: ${path#"$root"/}" ;;
        esac
        if [ "$base" = "ffmpeg-license.txt" ]; then
            if ! grep -q 'LGPL version 2.1 or later' "$path"; then
                fail "ffmpeg-license.txt does not report 'LGPL version 2.1 or later'"
            fi
        fi
    done < <(find "$root" -mindepth 1 -type f -print0)
}

# --- rule 4: every JAR is on the Licensee-checked runtime classpath -------------
# The manifest (:desktopApp:writeDesktopRuntimeClasspath) holds one
# `<canonical sha256>  <image name>` row per classpath file, the image name being
# Compose's mangle <base>-<md5>.jar (11 Image scan rules). An image jar passes
# when a row names it exactly and its jar_content_hash equals the row's — the
# mangle's md5 needs no separate check because a same-named jar with other
# content would fail the SHA.
#
# The one documented off-manifest jar is Compose's skiko stub (11 Image scan
# rules): Compose ships the native-bearing skiko-awt-runtime-<os>-<arch>-<ver>
# classpath JAR as a stub named after the stub's own md5 and extracts the
# libskiko native beside it with a .sha256 sidecar. is_skiko_stub constrains the
# exemption to exactly that shape — any other skiko-named jar is a violation.

# jar_stub_only <jar> — the stub is a real jar whose entries are only
# directories, META-INF metadata and the skiko natives for the *other* targets
# with their .sha256 sidecars: packaging extracts only the host arch's libskiko
# loose beside the stub and leaves the rest inside (the real macOS artifact
# kept libskiko-macos-x64.dylib + .sha256, nightly 37868304885). A .class or
# any other payload under the stub name is the real jar (or worse) in
# disguise. python3 is the same interpreter check_no_tests uses for jar
# entries; without it the exemption cannot be verified and does not apply.
jar_stub_only() {
    command -v python3 >/dev/null 2>&1 || return 1
    python3 - "$1" <<'PYEOF'
import re, sys, zipfile
NATIVE = r'^(?:libskiko-|skiko-)[^/]+\.(?:so|dylib|dll)$'
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        ok = all(n.endswith('/') or
                 (n.startswith('META-INF/') and not n.endswith('.class')) or
                 re.match(NATIVE, n) or
                 (n.endswith('.sha256') and '/' not in n)
                 for n in z.namelist())
    sys.exit(0 if ok else 1)
except Exception:
    sys.exit(1)
PYEOF
}

# native_sha256 <file> — sha256 of the file's signature-normalized bytes:
# the same Mach-O normalization jar_content_hash applies to jar entries
# (norm_thin/norm_macho — the manifest's #entry rows are computed from it in
# DesktopPackaging.kt). Non-Mach-O bytes hash raw.
native_sha256() {
    python3 - "$1" <<'PYEOF'
import hashlib, struct, sys

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

print(hashlib.sha256(norm_macho(open(sys.argv[1], 'rb').read())).hexdigest())
PYEOF
}

# manifest_entry_hash <manifest> <image jar name> <entry> — the sha256 the
# classpath manifest recorded for that entry of the Licensee-checked
# skiko-awt-runtime jar (<hash>  <jar>#<entry> rows), empty when unbound.
manifest_entry_hash() {
    local m="$1" jar="$2" entry="$3"
    jar="$(printf '%s' "$jar" | sed 's/[].[^$*\/]/\\&/g')"
    entry="$(printf '%s' "$entry" | sed 's/[].[^$*\/]/\\&/g')"
    grep -oE "^[0-9a-f]{64}  ${jar}#${entry}\$" "$m" | cut -d' ' -f1 || true
}

# jar_stub_natives <jar> <dir> — extract the stub's retained natives and their
# in-jar .sha256 sidecars for sidecar_sha256_matches; fails when a native and
# its sidecar do not pair off exactly.
jar_stub_natives() {
    python3 - "$1" "$2" <<'PYEOF'
import os, re, sys, zipfile
NATIVE = r'^(?:libskiko-|skiko-)[^/]+\.(?:so|dylib|dll)$'
jar, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    names = set(z.namelist())
    natives = {n for n in names if re.match(NATIVE, n)}
    sidecars = {n[:-len('.sha256')] for n in names
                if '/' not in n and n.endswith('.sha256')}
    if natives != sidecars:
        sys.exit(1)
    for n in sorted(natives) + sorted(s + '.sha256' for s in sidecars):
        with open(os.path.join(out, n), 'wb') as f:
            f.write(z.read(n))
PYEOF
}

is_skiko_stub() {
    local path="$1" manifest="$2" base dir padded unpadded i byte rest os arch so
    base="${path##*/}"
    case "$base" in
        skiko-awt-runtime-*.jar) ;;
        *) return 1 ;;
    esac
    # rest is <os>-<arch>-<ver>-<md5>.jar; the manifest must carry the real
    # classpath jar for the same os-arch (its own md5 makes the names differ).
    rest="${base#skiko-awt-runtime-}"
    case "$rest" in
        *-*-*) ;;
        *) return 1 ;;
    esac
    os="${rest%%-*}"
    arch="${rest#*-}"
    arch="${arch%%-*}"
    # The name ends in the stub jar's own md5 — verified where packaging left the
    # bytes alone. The macOS pipeline recompresses image jars after the mangle
    # (nightly 37860407043), so on macOS the suffix can only be required to keep
    # the mangle's shape: Compose's per-byte %x rendering yields 16-32 lowercase
    # hex digits, never zero-padded (the real artifact had 30). The manifest
    # row, stub-only entries and the sidecar-pinned native below still bind it.
    local sfx="${base%.jar}"
    sfx="${sfx##*-}"
    padded="$(md5_of "$path")"
    unpadded=""
    for ((i = 0; i < ${#padded}; i += 2)); do
        byte="${padded:i:2}"
        unpadded+="${byte#0}"
    done
    if [ "$sfx" != "$unpadded" ] && [ "$sfx" != "$padded" ]; then
        [ "$os" = "macos" ] || return 1
        printf '%s\n' "$sfx" | grep -qxE '[0-9a-f]{16,32}' || return 1
    fi
    os="$(printf '%s' "$os" | sed 's/[].[^$*\/]/\\&/g')"
    arch="$(printf '%s' "$arch" | sed 's/[].[^$*\/]/\\&/g')"
    # The manifest row names the Licensee-checked classpath jar; its #entry
    # rows then bind every byte the stub carries — a stub-authored .sha256 only
    # proves self-consistency otherwise (a mutated native with a rewritten
    # in-jar sidecar would pair off but never saw the classpath artifact).
    local tmp n ok=1 found=0 want cpjar
    cpjar="$(grep -oE "skiko-awt-runtime-${os}-${arch}-[^ ]+\.jar\$" "$manifest" | head -1 || true)"
    [ -n "$cpjar" ] || return 1
    jar_stub_only "$path" || return 1
    dir="${path%/*}"
    # Retained natives keep their in-jar .sha256 pair; each is bound to the
    # classpath jar's recorded entry — natives signature-normalized (the macOS
    # stub's libskiko-macos-x64.dylib is signed in place, nightly 37868304885),
    # sidecar bytes exact.
    tmp="$(mktemp -d)"
    jar_stub_natives "$path" "$tmp" || ok=0
    for n in "$tmp"/*; do
        [ -e "$n" ] || break
        want="$(manifest_entry_hash "$manifest" "$cpjar" "${n##*/}")"
        if [ -z "$want" ]; then
            ok=0
            break
        fi
        case "$n" in
            *.sha256) [ "$(sha256 "$n")" = "$want" ] || ok=0 ;;
            *) [ "$(native_sha256 "$n")" = "$want" ] || ok=0 ;;
        esac
    done
    rm -rf "$tmp"
    [ "$ok" = 1 ] || return 1
    # The extracted native and its .sha256 file are bound the same way; the
    # sidecar consistency check keeps the documented Compose contract on top.
    for so in "$dir"/libskiko-*.so "$dir"/libskiko-*.dylib "$dir"/skiko-*.dll; do
        [ -f "$so" ] || continue
        found=1
        want="$(manifest_entry_hash "$manifest" "$cpjar" "${so##*/}")"
        if [ -z "$want" ] || [ "$(native_sha256 "$so")" != "$want" ]; then
            ok=0
            continue
        fi
        want="$(manifest_entry_hash "$manifest" "$cpjar" "${so##*/}.sha256")"
        if [ -z "$want" ] || [ ! -f "$so.sha256" ] \
            || [ "$(sha256 "$so.sha256")" != "$want" ] \
            || ! sidecar_sha256_matches "$so"; then
            ok=0
        fi
    done
    [ "$found" = 1 ] && [ "$ok" = 1 ]
}

# sidecar_sha256_matches <native> — the Compose-written .sha256 pins the bytes
# the classpath jar carried. A re-signed Mach-O can never reproduce that
# pre-sign pin byte-wise (nightly 37874042553), so for Mach-O files this check
# is vacuous — the caller already bound the native and the sidecar file
# byte-exact/normalized to the manifest's #entry rows, which is the real
# provenance gate. For anything else the raw bytes must equal the pin.
sidecar_sha256_matches() {
    local so="$1" want magic
    want="$(head -1 "$so.sha256" | cut -d' ' -f1)"
    [ "$(sha256 "$so")" = "$want" ] && return 0
    magic="$(od -An -tx1 -N4 "$so" 2>/dev/null | tr -d ' ')"
    case "$magic" in
        feedface|feedfacf|cefaedfe|cffaedfe|cafebabe|bebafeca) return 0 ;;
    esac
    return 1
}

check_jars() {
    local img="$1" manifest="$2" path hash base
    if [ ! -f "$manifest" ]; then
        fail "runtime-classpath manifest missing: $manifest (run :desktopApp:createDistributable first)"
        return
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        fail "the JAR classpath binding needs python3 (jar_content_hash)"
        return
    fi
    while IFS= read -r -d '' path; do
        case "$path" in
            */runtime/*) continue ;; # the JDK's own jars (jrt-fs.jar) are not classpath jars
        esac
        base="${path##*/}"
        # || true: a name with no row must reach the fail branch, not abort on
        # grep's exit status under pipefail.
        hash="$(grep -E "  $(printf '%s' "$base" | sed 's/[].[^$*\/]/\\&/g')\$" "$manifest" | cut -d' ' -f1 || true)"
        if [ -n "$hash" ]; then
            if [ "$(jar_content_hash "$path")" != "$hash" ]; then
                fail "JAR differs from the Licensee-checked classpath artifact: ${path#"$img"/}"
            fi
        elif ! is_skiko_stub "$path" "$manifest"; then
            fail "JAR not on the Licensee-checked runtime classpath: ${path#"$img"/}"
        fi
    done < <(find "$img" -name '*.jar' -print0)
}

# --- rule 5: the Python tree matches python-components.lock ---------------------
check_python_tree() {
    local img="$1" pytree
    pytree="$(find "$img" -type d -name 'python*' -o -type d -name 'engine' 2>/dev/null | head -1)"
    if [ -z "$pytree" ]; then
        if [ -f "$PY_LOCK" ] && grep -q '^\[\[component\]\]' "$PY_LOCK"; then
            fail "python-components.lock lists components but no engine/python tree is in the image"
        fi
        return
    fi
    if [ ! -f "$PY_LOCK" ]; then
        fail "engine/python tree present but youtube/ytdlp-desktop/python-components.lock is missing"
        return
    fi
    # The lock is TOML with [[component]] entries (01 Python and native components).
    # Each named component must be present in the tree, and the tree's top-level
    # entries must each come from a component (the trim manifest grows with MD3;
    # until then presence is the check that can bind).
    local missing=0 entry
    while IFS= read -r entry; do
        [ -z "$entry" ] && continue
        if ! find "$pytree" -name "$entry" -print -quit | grep -q .; then
            fail "python-components.lock component missing in image: $entry"
            missing=1
        fi
    done < <(awk '/^\[\[component\]\]/{c=1;next} /^\[/{c=0} c && /^name *=/{sub(/^[^=]*= *"?/,"");sub(/".*$/,"");print}' "$PY_LOCK")
    [ "$missing" = 0 ] && note "python tree matches python-components.lock components"
}

# --- rule 6: no GLIBC symbol version above 2.31 (Linux images) ------------------
GLIBC_FLOOR="GLIBC_2.31"

is_elf() { [ "$(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d ' ')" = "7f454c46" ]; }

check_glibc() {
    local root="$1" path bad=0
    local elves=()
    while IFS= read -r -d '' path; do
        is_elf "$path" && elves+=("$path")
    done < <(find "$root" -type f -print0)
    if [ "${#elves[@]}" -eq 0 ]; then
        return
    fi
    if ! command -v objdump >/dev/null 2>&1; then
        fail "ELF files present but objdump is not installed (glibc floor check, 11 Image scan rules)"
        return
    fi
    for path in "${elves[@]}"; do
        local ver
        while IFS= read -r ver; do
            [ -z "$ver" ] && continue
            if ver_gt "${ver#GLIBC_}" "${GLIBC_FLOOR#GLIBC_}"; then
                fail "ELF needs $ver above $GLIBC_FLOOR: ${path#"$root"/}"
                bad=1
            fi
        done < <(objdump -T "$path" 2>/dev/null | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' | sort -u)
    done
    [ "$bad" = 0 ] && note "glibc floor OK (${#elves[@]} ELF files)"
}

# --- rule 7: no test classes, fixtures or other entry points --------------------
check_no_tests() {
    local img="$1" path base bin_dir
    # Test names matched as segments or suffixes: `*Test*` (capital T — RootForTest
    # is a shipped Compose API, matched only inside our own ch.lkmc packages in the
    # JAR scan below), `test-`/`-test`/`tests`/`fixtures`/`testdata` segments. A
    # lowercase bare `*test*` would eat names like bytestring and latest.
    while IFS= read -r -d '' path; do
        base="${path##*/}"
        case "$base" in
            [Tt]est-*|[Tt]ests-*|*-[Tt]est-*|*-[Tt]ests-*|*-[Tt]est.*|*-[Tt]ests.*|[Tt]est*.*|[Ff]ixtures*|testdata*|__tests__|*TestKit*)
                fail "test class, fixture or test artefact in image: ${path#"$img"/}" ;;
        esac
        case "$path" in
            */test/*|*/tests/*|*/fixtures/*|*/testdata/*|*/__tests__/*)
                fail "test or fixture directory in image: ${path#"$img"/}" ;;
        esac
    done < <(find "$img" -mindepth 1 -print0)

    # Entries inside the JARs: test or fixture directories anywhere, or *Test*.class
    # inside our own ch.lkmc packages (upstream jars legitimately ship classes like
    # JNA's XTest and Compose's RootForTest; our test classes never belong to main).
    if command -v python3 >/dev/null 2>&1; then
        while IFS= read -r -d '' path; do
            case "$path" in
                */runtime/*|*.jar.exclude) continue ;;
            esac
            local bad
            bad="$(python3 - "$path" <<'PYEOF' 2>/dev/null
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        hits = [n for n in z.namelist()
                if '/test/' in n or '/tests/' in n or '/fixtures/' in n or '/testdata/' in n
                or (n.startswith('ch/lkmc/') and 'Test' in n and n.endswith('.class'))]
        print('\n'.join(hits[:5]))
except Exception:
    pass
PYEOF
)"
            if [ -n "$bad" ]; then
                fail "test entries inside JAR ${path#"$img"/}: $(echo "$bad" | head -1) ..."
            fi
        done < <(find "$img" -name '*.jar' -print0)
    fi

    # Entry points: bin/ holds exactly the one launcher (macOS holds it in Contents/MacOS).
    for bin_dir in "$img/bin" "$img/Contents/MacOS"; do
        if [ -d "$bin_dir" ]; then
            local count
            count="$(find "$bin_dir" -mindepth 1 -maxdepth 1 -type f | wc -l)"
            if [ "$count" -ne 1 ]; then
                fail "entry-point directory $bin_dir holds $count files, expected the one launcher"
            fi
        fi
    done
}

# --- rule 8: macOS signature -----------------------------------------------------
check_codesign() {
    local img="$1"
    case "$img" in
        *.app) ;;
        *) return ;;
    esac
    if ! command -v codesign >/dev/null 2>&1; then
        fail "a .app image needs codesign --verify --deep --strict (11 Image scan rules)"
        return
    fi
    if ! codesign --verify --deep --strict "$img"; then
        # The terse error does not name the broken entry; the verbose rerun
        # lists each sealed resource that fails ("file modified: …").
        codesign --verify --deep --strict --verbose=4 "$img" 2>&1 \
            | grep -iE 'invalid|missing|modified|mismatch' | head -10 || true
        fail "codesign --verify --deep --strict failed on $img"
    fi
}

# --- rule 9: Windows MSI ----------------------------------------------------------
check_msi() {
    local msi="$1"
    local ps=""
    if command -v powershell.exe >/dev/null 2>&1; then
        ps=powershell.exe
    elif command -v pwsh >/dev/null 2>&1; then
        ps=pwsh
    else
        fail "an MSI needs the Directory/Binary table checks (PowerShell, 11 Image scan rules)"
        return
    fi

    # INSTALLDIR must resolve under LocalAppDataFolder\Programs (11 Windows MSI and ZIP):
    # walk each Directory row's parent chain, prepend each DefaultDir's short name, and
    # require one resolved path of the form LocalAppDataFolder\Programs\<dir>.
    local dirs
    dirs="$("$ps" -NoProfile -Command "
        \$wi = New-Object -ComObject WindowsInstaller.Installer
        \$db = \$wi.GetType().InvokeMember('OpenDatabase','InvokeMethod',\$null,\$wi,@('$msi',0))
        \$v = \$db.GetType().InvokeMember('OpenView','InvokeMethod',\$null,\$db,@('SELECT Directory, Directory_Parent, DefaultDir FROM Directory'))
        \$v.GetType().InvokeMember('Execute','InvokeMethod',\$null,\$v,@(\$null)) | Out-Null
        while (\$r = \$v.GetType().InvokeMember('Fetch','InvokeMethod',\$null,\$v,@(\$null))) {
            \$r.GetType().InvokeMember('StringData','GetProperty',\$null,\$r,@(1)) + '|' +
            \$r.GetType().InvokeMember('StringData','GetProperty',\$null,\$r,@(2)) + '|' +
            \$r.GetType().InvokeMember('StringData','GetProperty',\$null,\$r,@(3))
        }
    " 2>/dev/null | tr -d '\r')"
    if ! printf '%s\n' "$dirs" | awk -F'|' '
        { parent[$1] = $2; def[$1] = $3 }
        END {
            for (d in def) {
                path = ""; cur = d; guard = 0
                while (cur != "" && guard++ < 20) {
                    dd = def[cur]; sub(/\|.*/, "", dd); if (dd == "") dd = cur
                    path = (path == "" ? dd : dd "\\" path)
                    if (parent[cur] == "" || parent[cur] == cur) break
                    cur = parent[cur]
                }
                print path
            }
        }' | grep -qF "LocalAppDataFolder\\Programs\\"; then
        fail "MSI INSTALLDIR does not resolve under LocalAppDataFolder\\Programs (Directory table)"
    fi

    # Every Binary-table entry must be on wix.lock's binaryTable list (fnmatch).
    local names name pat ok
    names="$("$ps" -NoProfile -Command "
        \$wi = New-Object -ComObject WindowsInstaller.Installer
        \$db = \$wi.GetType().InvokeMember('OpenDatabase','InvokeMethod',\$null,\$wi,@('$msi',0))
        \$v = \$db.GetType().InvokeMember('OpenView','InvokeMethod',\$null,\$db,@('SELECT Name FROM Binary'))
        \$v.GetType().InvokeMember('Execute','InvokeMethod',\$null,\$v,@(\$null)) | Out-Null
        while (\$r = \$v.GetType().InvokeMember('Fetch','InvokeMethod',\$null,\$v,@(\$null))) {
            \$r.GetType().InvokeMember('StringData','GetProperty',\$null,\$r,@(1))
        }
    " 2>/dev/null | tr -d '\r')"
    while IFS= read -r name; do
        [ -z "$name" ] && continue
        ok=0
        while IFS= read -r pat; do
            # shellcheck disable=SC2254
            case "$name" in
                $pat) ok=1 ;;
            esac
        done < <(grep -E '^binaryTable=' "$WIX_LOCK" | cut -d= -f2-)
        if [ "$ok" = 0 ]; then
            fail "MSI Binary table entry not in wix.lock: $name"
        fi
    done <<< "$names"
}

# --- rule 10: DEB and RPM metadata -------------------------------------------------
check_deb() {
    local deb="$1" member control_got control_want
    command -v ar >/dev/null 2>&1 || { fail "DEB check needs ar (binutils)"; return; }
    while IFS= read -r member; do
        case "$member" in
            debian-binary|control.tar.xz|data.tar.xz) ;;
            control.tar.*|data.tar.*) fail "DEB member is not xz-compressed: $member" ;;
            *) fail "unexpected DEB member: $member" ;;
        esac
    done < <(ar t "$deb")

    command -v dpkg-deb >/dev/null 2>&1 || { fail "DEB check needs dpkg-deb"; return; }
    control_got="$(dpkg-deb -f "$deb" Depends | sed 's/  */ /g')"
    control_want="$(grep -E '^Depends:' "$DEB_CONTROL" | cut -d: -f2- | sed 's/  */ /g; s/^ //')"
    if [ "$control_got" != "$control_want" ]; then
        fail "DEB Depends differs from packaging/deb/control: got '$control_got'"
    fi
}

check_rpm() {
    local rpmf="$1" want names extras
    if ! command -v rpm >/dev/null 2>&1; then
        fail "RPM check needs rpm(8) to read the package's Requires"
        return
    fi
    # Query the package once: a piped `rpm | grep -q` loses to the SIGPIPE race
    # — grep -q exits on the first match while rpm is still writing and
    # pipefail reports 141 as a spurious miss (proven by the stub fixtures).
    names="$(rpm -qp --queryformat '[%{REQUIRENAME}\n]' "$rpmf" 2>/dev/null || true)"
    while IFS= read -r want; do
        want="${want#Requires:}"
        want="${want// /}"
        [ -z "$want" ] && continue
        if ! printf '%s\n' "$names" | grep -xF "$want" >/dev/null; then
            fail "RPM is missing our Requires: $want"
        fi
    done < <(grep -E '^Requires:' "$RPM_SPEC")
    # rpmbuild must not have added its own discovered Requires (Autoreq: 0 in our spec).
    extras="$(printf '%s\n' "$names" \
        | grep -vxF -f <(grep -E '^Requires:' "$RPM_SPEC" | sed 's/^Requires: *//; s/  */ /g' | tr ' ' '\n') \
        | grep -vE '^(rpmlib|/bin/sh|/usr/bin/env|rtld|config\()' | grep -vE '^rpmlib' || true)"
    if [ -n "$extras" ]; then
        fail "RPM carries Requires outside our spec: $(echo "$extras" | head -3 | tr '\n' ' ')"
    fi
}

# --- dispatch ----------------------------------------------------------------------
scan_dir() {
    local img="$1"
    check_forbidden_names "$img"
    check_runtime_layout "$img"
    check_ffmpeg "$img"
    check_jars "$img" "$(manifest_for_image "$img")"
    check_python_tree "$img"
    check_glibc "$img"
    check_no_tests "$img"
    check_codesign "$img"
    if [ "$NO_ENGINE" = true ]; then
        local hit
        hit="$(find "$img" \( -name 'python*' -o -name 'yt-dlp*' -o -name '*ytdlp*.jar' \) -print -quit)"
        [ -z "$hit" ] || fail "--no-engine image carries engine content: ${hit#"$img"/}"
    fi
}

extract_and_scan() {
    local pkg="$1" fmt="$2" tmp
    # Inputs arrive relative to the caller's cwd (dist/…); the rpm extraction
    # happens inside a subshell cd'd to $tmp, where a relative path resolves
    # nowhere (nightly 37860407043: rpm2cpio dist/… → No such file).
    pkg="$(cd -- "$(dirname -- "$pkg")" && pwd -P)/$(basename -- "$pkg")"
    tmp="$(mktemp -d)"
    case "$fmt" in
        tar.gz) tar -xzf "$pkg" -C "$tmp" ;;
        zip)
            # A signed .app must come back byte-identical: python's zipfile turns
            # the jlink legal/ symlinks into regular files, which breaks the
            # sealed bundle (nightly 37860407043). ditto preserves them.
            if command -v ditto >/dev/null 2>&1; then
                ditto -x -k "$pkg" "$tmp"
            else
                python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$pkg" "$tmp"
            fi ;;
        deb) dpkg-deb -x "$pkg" "$tmp" ;;
        rpm)
            if command -v rpm2cpio >/dev/null 2>&1; then
                (cd "$tmp" && rpm2cpio "$pkg" | cpio -idm --quiet)
            else
                fail "extracting an RPM needs rpm2cpio"
                rm -rf "$tmp"
                return
            fi ;;
    esac
    local roots=() d
    while IFS= read -r d; do roots+=("$d"); done \
        < <(find "$tmp" -mindepth 1 -maxdepth 3 \( -name 'Neutrodyne' -o -name 'Neutrodyne.app' \) -type d)
    if [ "${#roots[@]}" -eq 0 ]; then
        roots=("$tmp")
    fi
    for d in "${roots[@]}"; do
        # DEB/RPM extracts land under <install-dir>/<pkg>; scan the app dir itself.
        if [ -d "$d/opt/neutrodyne" ]; then
            scan_dir "$d/opt/neutrodyne"
        else
            scan_dir "$d"
        fi
    done
    rm -rf "$tmp"
}

for input in "${INPUTS[@]}"; do
    if [ ! -e "$input" ]; then
        fail "input does not exist: $input"
        continue
    fi
    case "$input" in
        *.deb) check_deb "$input"; extract_and_scan "$input" deb ;;
        *.rpm) check_rpm "$input"; extract_and_scan "$input" rpm ;;
        *.msi) check_msi "$input" ;;
        *.zip) extract_and_scan "$input" zip ;;
        *.tar.gz) extract_and_scan "$input" tar.gz ;;
        *)
            if [ -d "$input" ]; then
                scan_dir "$input"
            else
                fail "unsupported input: $input"
            fi
            ;;
    esac
done

# --- PB27 sizes --------------------------------------------------------------------
for input in "${INPUTS[@]}"; do
    if [ -d "$input" ]; then
        echo "size: installed $(dir_mib "$input") MiB ($input)"
    elif [ -f "$input" ]; then
        echo "size: package $(( $(file_bytes "$input") / 1024 / 1024 )) MiB ($(basename "$input"))"
    fi
done

if [ "$FAILURES" -gt 0 ]; then
    echo "check-desktop-image: $FAILURES violation(s)" >&2
    exit 1
fi
echo "check-desktop-image: OK"
