#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-runtime-sources.sh --image <dir> [--jdk <dir>] [--smoke <file>]
# check-runtime-sources.sh --release <dir>
#
# The runtime exception's checks (11 Runtime exception obligations and checks,
# 09 Build-output checks), all against desktopApp/runtime.lock (and wix.lock and
# python-components.lock where they apply).
#
# --image <dir> — a jpackage app image (Neutrodyne/ or Neutrodyne.app):
#   * runtime/release carries only JAVA_VERSION and MODULES (a jlink'd release file)
#     and JAVA_VERSION equals runtime.lock's javaVersion
#   * runtime/legal/ is present and non-empty
#   * java.vendor, java.vendor.version and java.runtime.version of the smoke-mode
#     SMOKE line equal the lock — taken from --smoke's file, or produced here by
#     running scripts/desktop/smoke-start.sh on the image
#   * every file under runtime/ exists in the pinned Temurin tree and every native
#     file matches it: byte-identical; on Linux also byte-identical after applying
#     the same `objcopy -g` jlink's --strip-debug applies (or, failing that, on
#     the loader metadata plus the phdr-defined file ranges elf_bind emits); on macOS on
#     signature-normalized copies of both files (jpackage re-signs some natives
#     when it seals the .app, and codesign --remove-signature cannot restore
#     the published bytes — the same norm_macho spec check-desktop-image.sh
#     hashes).
#     --jdk overrides the pinned tree; by default the single jdk/ under
#     desktopApp/build/bundled-runtime/*/ is used.
#
# --release <dir> — a directory holding a release's assets:
#   * openjdk-{version}-temurin-sources.tar.gz exists and hashes to
#     runtime.lock's sourceTarball.sha256
#   * RUNTIME-SOURCES.md names the lock's vendor, vendorVersion, version and the
#     source tarball's name and SHA-256
#   * when the directory holds an .msi, wix-{version}-src.tar.gz exists and hashes
#     to wix.lock's source.sha256
#   * when youtube/ytdlp-desktop/python-components.lock exists, the desktop engine
#     ships: python-build-standalone-{pbsTag}-src.tar.gz must exist and match its
#     pbsSource.sha256 (MD3; skipped while no lock exists)
#
# Exit 0 = all checks pass; 1 = a violation; 2 = usage error.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
RUNTIME_LOCK="$REPO_ROOT/desktopApp/runtime.lock"
WIX_LOCK="$REPO_ROOT/desktopApp/wix.lock"
PY_LOCK="$REPO_ROOT/youtube/ytdlp-desktop/python-components.lock"
SMOKE_START="$REPO_ROOT/scripts/desktop/smoke-start.sh"

MODE="" DIR="" JDK="" SMOKE_FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --image) MODE=image; DIR="$2"; shift 2 ;;
        --release) MODE=release; DIR="$2"; shift 2 ;;
        --jdk) JDK="$2"; shift 2 ;;
        --smoke) SMOKE_FILE="$2"; shift 2 ;;
        -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ -z "$MODE" ] || [ -z "$DIR" ] || [ ! -d "$DIR" ]; then
    echo "usage: $0 --image <dir> [--jdk <dir>] [--smoke <file>] | --release <dir>" >&2
    exit 2
fi

FAILURES=0
fail() {
    echo "FAIL: $1" >&2
    FAILURES=$((FAILURES + 1))
}
note() { echo "note: $1"; }

lock_prop() { grep -E "^$2=" "$1" | tail -1 | cut -d= -f2-; }

# toml_section_prop <file> <section> <key> — first `key = "v"` inside a `[section]`
# block of a TOML file (python-components.lock).
toml_section_prop() {
    awk -v sec="$2" -v key="$3" '
        $0 ~ "^\\[" sec "\\]" { in_sec = 1; next }
        /^\[/ { in_sec = 0 }
        in_sec && $0 ~ "^" key " *=" {
            sub(/^[^=]*= *"?/, "", $0); sub(/".*$/, "", $0); print; exit
        }
    ' "$1"
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

is_elf() { [ "$(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d ' ')" = "7f454c46" ]; }
is_macho() {
    local magic
    magic="$(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d ' ')"
    case "$magic" in
        feedface|feedfacf|cefaedfe|cffaedfe|cafebabe) return 0 ;;
        *) return 1 ;;
    esac
}
is_pe() {
    # MZ header plus the PE signature at the offset the header names (0x3c).
    [ "$(od -An -tx1 -N2 "$1" 2>/dev/null | tr -d ' ')" = "4d5a" ] || return 1
    local off
    off="$(od -An -j60 -tu4 -N4 "$1" 2>/dev/null)"
    off=$((off + 0))
    [ "$(od -An -j"$off" -tx1 -N4 "$1" 2>/dev/null | tr -d ' ')" = "50450000" ]
}

runtime_dir() {
    # Inside a .app bundle jlink nests the JDK home one level deeper at
    # Contents/runtime/Contents/Home (release, legal/, the natives all live
    # there) — same probe order as check-desktop-image.sh's runtime_dir.
    local dir
    for dir in "$1/lib/runtime" "$1/Contents/runtime/Contents/Home" "$1/Contents/runtime" "$1/runtime"; do
        if [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    done
    return 1
}

# --- --image ---------------------------------------------------------------------

image_check_release_file() {
    local rt="$1" want
    if [ ! -f "$rt/release" ]; then
        fail "runtime/release missing"
        return
    fi
    # A jlink'd release file holds only JAVA_VERSION and MODULES; any other key means
    # the runtime was re-tagged (11 Runtime exception obligations and checks).
    local extra
    extra="$(cut -d= -f1 "$rt/release" | grep -vxE 'JAVA_VERSION|MODULES' || true)"
    if [ -n "$extra" ]; then
        fail "runtime/release carries keys a jlink image never writes: $(echo "$extra" | tr '\n' ' ')"
    fi
    want="$(lock_prop "$RUNTIME_LOCK" javaVersion)"
    local got
    got="$(grep -E '^JAVA_VERSION=' "$rt/release" | tail -1 | cut -d'"' -f2)"
    [ "$got" = "$want" ] || fail "runtime/release JAVA_VERSION '$got' != runtime.lock javaVersion '$want'"
}

image_check_legal() {
    local rt="$1"
    if [ ! -d "$rt/legal" ] || [ -z "$(ls -A "$rt/legal" 2>/dev/null)" ]; then
        fail "runtime/legal/ missing or empty (11 Keep the notices)"
    fi
}

image_check_smoke() {
    local img="$1" line
    if [ -n "$SMOKE_FILE" ]; then
        line="$(grep '^SMOKE {' "$SMOKE_FILE" | tail -1 || true)"
    else
        if [ ! -x "$SMOKE_START" ]; then
            fail "no --smoke file and scripts/desktop/smoke-start.sh is missing"
            return
        fi
        line="$("$SMOKE_START" "$img" || true)"
    fi
    if [ -z "$line" ]; then
        fail "no SMOKE line from the packaged app (smoke mode is where vendor and version come from)"
        return
    fi
    local vendor vendor_version runtime_version
    vendor="$(printf '%s' "$line" | grep -o '"javaVendor":"[^"]*"' | cut -d'"' -f4)"
    vendor_version="$(printf '%s' "$line" | grep -o '"javaVendorVersion":"[^"]*"' | cut -d'"' -f4)"
    runtime_version="$(printf '%s' "$line" | grep -o '"javaRuntimeVersion":"[^"]*"' | cut -d'"' -f4)"
    [ "$vendor" = "$(lock_prop "$RUNTIME_LOCK" vendor)" ] \
        || fail "smoke java.vendor '$vendor' != runtime.lock vendor"
    [ "$vendor_version" = "$(lock_prop "$RUNTIME_LOCK" vendorVersion)" ] \
        || fail "smoke java.vendor.version '$vendor_version' != runtime.lock vendorVersion"
    [ "$runtime_version" = "$(lock_prop "$RUNTIME_LOCK" javaRuntimeVersion)" ] \
        || fail "smoke java.runtime.version '$runtime_version' != runtime.lock javaRuntimeVersion"
}

# macho_norm <file> — the file's signature-normalized bytes on stdout
# (norm_thin/norm_macho, the same spec check-desktop-image.sh's native_sha256
# applies and DesktopPackaging.kt's normalizeMacho mirrors): emit the effective
# command count/table size, zero the LC_CODE_SIGNATURE command bodies, bind
# __LINKEDIT's vmsize to its two legal signed shapes and its filesize minus the
# enclosed signature blob, validate fat slice offsets against lipo's packing,
# and cut the blob bytes. Malformed input passes through raw and simply fails
# the byte compare.
macho_norm() {
    python3 - "$1" <<'PYEOF'
import struct, sys

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
    # arm64/arm64_32 pages are 16K, other slices 4K — codesign rounds the
    # linkedit VM reservation to the target's page.
    page = 0x4000 if struct.unpack(e + 'I', b[4:8])[0] in (0x0100000c, 0x0200000c) else 0x1000
    # Effective (non-signature) header shape stays bound: ncmds/sizeofcmds
    # minus the signature slots re-signing may insert over header padding.
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
                # vmsize is not byte-reversible, but it is not free either:
                # only the file extent or its page-rounded form is legal —
                # emit the rounded non-signature extent, bound either way.
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
            # lipo packs slices back to back, each at its 2^align boundary —
            # the offset is then a function of bound fields, not a free one.
            if align > 30 or off != -(-prev_end // (1 << align)) * (1 << align):
                return data
            if off + size > len(data):
                return data
            emit, sigsize = norm_thin(data[off:off + size])
            if sigsize > size:
                return data
            # Slice offset validated against deterministic packing; the
            # effective (pre-signature) slice length stays bound.
            out += data[ro:ro + 8] + b'\0' * 4 + struct.pack('>I', size - sigsize) + data[ro + 16:ro + 20]
            out += emit
            prev_end = off + size
        return bytes(out)
    return norm_thin(data)[0]

sys.stdout.buffer.write(norm_macho(open(sys.argv[1], 'rb').read()))
PYEOF
}

# elf_bind <file> — what the kernel loader actually maps, on stdout: the ELF
# header verbatim except the section-table fields strip-debug rewrites
# (e_shoff, e_shnum, e_shstrndx zeroed), the whole program header table, then
# the file bytes every phdr's p_offset..p_offset+p_filesz defines. Exit nonzero
# on anything unparseable. Section bytes are never emitted: objcopy -O binary
# dumps sections, so a section header pointing at pristine bytes elsewhere
# hides mutated LOAD ranges, and bytes a segment maps but no section covers
# are invisible to it (review probe of 58fca12 accepted both shapes).
elf_bind() {
    python3 - "$1" <<'PYEOF'
import struct, sys

def bind(b):
    if len(b) < 52 or b[:4] != b'\x7fELF':
        return None
    if b[4] not in (1, 2) or b[5] not in (1, 2) or b[6] != 1:
        return None
    is64 = b[4] == 2
    e = '>' if b[5] == 2 else '<'
    hdr = 64 if is64 else 52
    if len(b) < hdr:
        return None
    m = bytearray(b[:hdr])
    if is64:
        phoff = struct.unpack(e + 'Q', b[32:40])[0]
        phentsize, phnum = struct.unpack(e + 'HH', b[54:58])
        struct.pack_into(e + 'Q', m, 40, 0)
        struct.pack_into(e + 'HH', m, 60, 0, 0)
    else:
        phoff = struct.unpack(e + 'I', b[28:32])[0]
        phentsize, phnum = struct.unpack(e + 'HH', b[42:46])
        struct.pack_into(e + 'I', m, 32, 0)
        struct.pack_into(e + 'HH', m, 48, 0, 0)
    # PN_XNUM keeps the real count in the section table this emit ignores.
    if phnum == 0 or phnum == 0xffff:
        return None
    if phoff + phentsize * phnum > len(b):
        return None
    out = bytearray(m)
    out += b[phoff:phoff + phentsize * phnum]
    for i in range(phnum):
        o = phoff + i * phentsize
        # The phdr fields define the loaded file range; a section header is
        # allowed to lie about where its bytes live.
        if is64:
            poff, pfsz = struct.unpack(e + 'QQ', b[o + 8:o + 16] + b[o + 32:o + 40])
        else:
            poff, pfsz = struct.unpack(e + 'II', b[o + 4:o + 8] + b[o + 16:o + 20])
        if poff + pfsz > len(b):
            return None
        out += b[poff:poff + pfsz]
    return bytes(out)

m = bind(open(sys.argv[1], 'rb').read())
if m is None:
    sys.exit(1)
sys.stdout.buffer.write(m)
PYEOF
}

# Compare one image runtime file with the pinned Temurin copy (11's per-file rules).
image_compare_file() {
    local img_file="$1" ref_file="$2" rel="$3" work=""
    if cmp -s "$img_file" "$ref_file"; then
        return 0
    fi
    work="$(mktemp -d)"
    if is_elf "$ref_file"; then
        # jlink's --strip-debug runs objcopy -g on native files (Linux). Compare the
        # archive's copy after the same treatment; fall back to the loader
        # metadata plus the phdr-defined file ranges when the toolchains differ.
        if command -v objcopy >/dev/null 2>&1; then
            cp "$ref_file" "$work/ref"
            objcopy -g "$work/ref" 2>/dev/null || true
            if cmp -s "$img_file" "$work/ref"; then
                rm -rf "$work"
                return 0
            fi
            # Toolchain-difference fallback: bind what the loader maps, not what
            # sections claim. elf_bind emits the phdr-defined file ranges plus
            # the loader metadata, so only bytes no segment loads (debug and
            # other non-allocated sections, the file tail) stay unbound.
            if command -v python3 >/dev/null 2>&1 \
                && elf_bind "$ref_file" > "$work/ref.bind" 2>/dev/null \
                && elf_bind "$img_file" > "$work/img.bind" 2>/dev/null \
                && cmp -s "$work/ref.bind" "$work/img.bind"; then
                rm -rf "$work"
                return 0
            fi
        else
            fail "objcopy unavailable for the ELF comparison of $rel"
        fi
    elif is_macho "$ref_file"; then
        # jpackage re-signs some bundled natives when it seals the .app, and
        # codesign --remove-signature cannot restore the published bytes —
        # re-signing grows the LC_CODE_SIGNATURE command and __LINKEDIT and may
        # insert the command over header padding. The comparison therefore runs
        # on signature-normalized copies (the same norm_thin/norm_macho spec
        # check-desktop-image.sh's native_sha256 applies).
        if command -v python3 >/dev/null 2>&1; then
            macho_norm "$ref_file" > "$work/ref" && macho_norm "$img_file" > "$work/img" \
                && cmp -s "$work/ref" "$work/img" && {
                    rm -rf "$work"
                    return 0
                }
        else
            fail "python3 unavailable for the Mach-O comparison of $rel"
        fi
    fi
    rm -rf "$work"
    fail "runtime file differs from the pinned Temurin archive: $rel"
    return 0
}

image_check_native_files() {
    local rt="$1" img="$2" jdk="$3" path rel ref
    # bundled-runtime/*/jdk is the JDK root — the macOS tarball's tree sits one
    # level deeper at Contents/Home (SetupBundledRuntime), the same nesting
    # runtime_dir unwraps on the image side.
    [ -d "$jdk/Contents/Home" ] && jdk="$jdk/Contents/Home"
    while IFS= read -r -d '' path; do
        rel="${path#"$rt"/}"
        ref="$jdk/$rel"
        if [ ! -e "$ref" ]; then
            fail "runtime file has no counterpart in the pinned Temurin archive: $rel"
            continue
        fi
        if is_elf "$ref" || is_macho "$ref" || is_pe "$ref"; then
            image_compare_file "$path" "$ref" "$rel"
        fi
    done < <(find "$rt" -type f -print0)
}

locate_jdk() {
    local found=()
    if [ -n "$JDK" ]; then
        [ -d "$JDK" ] || { echo ""; return; }
        echo "$JDK"
        return
    fi
    local d
    for d in "$REPO_ROOT"/desktopApp/build/bundled-runtime/*/jdk; do
        [ -d "$d" ] && found+=("$d")
    done
    if [ "${#found[@]}" -eq 1 ]; then
        echo "${found[0]}"
    fi
}

if [ "$MODE" = image ]; then
    rt="$(runtime_dir "$DIR" || true)"
    if [ -z "$rt" ]; then
        fail "no runtime directory in image (lib/runtime or Contents/runtime)"
    else
        image_check_release_file "$rt"
        image_check_legal "$rt"
        image_check_smoke "$DIR"
        jdk="$(locate_jdk || true)"
        if [ -z "$jdk" ]; then
            fail "no pinned Temurin tree under desktopApp/build/bundled-runtime/*/jdk — pass --jdk"
        else
            image_check_native_files "$rt" "$DIR" "$jdk"
        fi
    fi
fi

# --- --release -------------------------------------------------------------------

if [ "$MODE" = release ]; then
    version="$(lock_prop "$RUNTIME_LOCK" version)"
    vendor="$(lock_prop "$RUNTIME_LOCK" vendor)"
    vendor_version="$(lock_prop "$RUNTIME_LOCK" vendorVersion)"
    src_name="$(lock_prop "$RUNTIME_LOCK" sourceTarball.name)"
    src_sha="$(lock_prop "$RUNTIME_LOCK" sourceTarball.sha256)"

    tarball="$DIR/openjdk-$version-temurin-sources.tar.gz"
    if [ ! -f "$tarball" ]; then
        fail "release lacks openjdk-$version-temurin-sources.tar.gz (the runtime exception's source duty)"
    elif [ "$(sha256 "$tarball")" != "$src_sha" ]; then
        fail "openjdk-$version-temurin-sources.tar.gz SHA-256 differs from runtime.lock sourceTarball.sha256"
    fi

    rs_md="$DIR/RUNTIME-SOURCES.md"
    if [ ! -f "$rs_md" ]; then
        fail "release lacks RUNTIME-SOURCES.md"
    else
        for needle in "$vendor" "$vendor_version" "$version" "$src_name" "$src_sha"; do
            grep -qF "$needle" "$rs_md" || fail "RUNTIME-SOURCES.md does not name: $needle"
        done
    fi

    # The WiX source ships whenever an MSI does (MS-RL duty, PO-48's named case).
    if compgen -G "$DIR/*.msi" >/dev/null; then
        wix_ver="$(lock_prop "$WIX_LOCK" version)"
        wix_src_sha="$(lock_prop "$WIX_LOCK" source.sha256)"
        wix_tarball="$DIR/wix-$wix_ver-src.tar.gz"
        if [ ! -f "$wix_tarball" ]; then
            fail "release ships an MSI but lacks wix-$wix_ver-src.tar.gz"
        elif [ "$(sha256 "$wix_tarball")" != "$wix_src_sha" ]; then
            fail "wix-$wix_ver-src.tar.gz SHA-256 differs from wix.lock source.sha256"
        fi
    fi

    # The PBS source ships whenever the desktop engine does (its MPL-2.0 build
    # patches, MD3). The lock is TOML; the engine ships once a [pbsSource] section
    # names the source tarball — until then the engine is not in the image.
    if [ -f "$PY_LOCK" ] && grep -q '^\[pbsSource\]' "$PY_LOCK"; then
        pbs_tag="$(toml_section_prop "$PY_LOCK" pbsSource tag)"
        pbs_sha="$(toml_section_prop "$PY_LOCK" pbsSource sha256)"
        pbs_tarball="$DIR/python-build-standalone-$pbs_tag-src.tar.gz"
        if [ ! -f "$pbs_tarball" ]; then
            fail "desktop engine ships but the release lacks python-build-standalone-$pbs_tag-src.tar.gz"
        elif [ "$(sha256 "$pbs_tarball")" != "$pbs_sha" ]; then
            fail "python-build-standalone-$pbs_tag-src.tar.gz SHA-256 differs from python-components.lock"
        fi
    else
        note "no pbsSource in python-components.lock — the desktop engine is not shipping yet (MD3)"
    fi
fi

if [ "$FAILURES" -gt 0 ]; then
    echo "check-runtime-sources: $FAILURES violation(s)" >&2
    exit 1
fi
echo "check-runtime-sources: OK"
