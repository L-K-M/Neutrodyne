#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-desktop-image.sh [--no-engine] <image dir|package file ...>
#
# The image scan rules of 11 on a packaged app image (11 Image scan rules; 09 Build-output
# checks). This slice implements the MD3 AC5 rules: the forbidden bundled components, the
# required interpreter/CA/stdlib layout of the bundled python-build-standalone runtime, the
# target/pin consistency with python-components.lock, the actual resource tree and archive
# contents, and the macOS ad-hoc signature over the nested Python binaries.
#
#   <dir>      a jpackage app image (Neutrodyne/ or Neutrodyne.app): every rule
#   <file>     a release package: .zip, .tar.gz — the directory rules on its extracted
#              contents (the .deb/.rpm/.msi package rules are a later slice)
#   --no-engine  additionally asserts that no Python tree, yt-dlp file or
#              :youtube:ytdlp-desktop JAR is present (emergency build, 01)
#
# Inputs besides the image: desktopApp/runtime.lock, the Licensee-checked runtime-classpath
# manifest (desktopApp/build/desktop-packaging/runtime-classpath.txt, written by
# :desktopApp:writeDesktopRuntimeClasspath) and youtube/ytdlp-desktop/python-components.lock.
#
# Exit 0 = all checks pass; 1 = at least one violation (all violations are printed);
# 2 = usage error. Prints the installed and package sizes for PB27 at the end.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
RUNTIME_LOCK="$REPO_ROOT/desktopApp/runtime.lock"
PY_LOCK="$REPO_ROOT/youtube/ytdlp-desktop/python-components.lock"
CLASSPATH_MANIFEST="$REPO_ROOT/desktopApp/build/desktop-packaging/runtime-classpath.txt"

NO_ENGINE=false
INPUTS=()
for arg in "$@"; do
    case "$arg" in
        --no-engine) NO_ENGINE=true ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
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

dir_mib() {
    local kb
    kb="$(du -sk "$1" | cut -f1)"
    echo $((kb / 1024))
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
# `itcl*`/`thread*` are Tcl/Tk's lib/itcl* and lib/thread* packages, not the stdlib's
# threading.py or concurrent/futures/thread.py, so they are matched as paths below.
FORBIDDEN_BASENAME_GLOBS=(
    '_dbm*' 'libdb*' '_gdbm*' 'libreadline*' '_readline*'
    '_tkinter*' 'libtcl*' 'libtk*' 'tcl9*' 'tk9*'
    'ensurepip' 'mutagen*' 'bgutil*'
    'qjs' 'qjs.exe' 'deno' 'node' 'bun'
    'AppRun' 'libfuse*' 'proguard*' 'jextract*' 'javafx*' 'vlcj*'
    'gstreamer*' 'libavfilter*' 'libswscale*' 'libpostproc*' 'libmpv*'
)
# Path fragments that are forbidden wherever they appear.
FORBIDDEN_PATH_GLOBS=(
    '*/site-packages/pip*' '*/site-packages/ensurepip*'
    '*/lib/itcl*' '*/lib/thread*'
)

# libedit may carry readline's ABI; CPython's own pure-Python _pyrepl/readline.py
# is part of the lockfile's CPython component (09's deviations note, 2026-10-06).
allowed_name() {
    case "$1" in
        libedit*) return 0 ;;
        *) return 1 ;;
    esac
}

allowed_path() {
    case "$1" in
        */_pyrepl/readline.py|*/_pyrepl/readline.pyc) return 0 ;;
        *) return 1 ;;
    esac
}

check_forbidden_names() {
    local root="$1" path base pat
    while IFS= read -r -d '' path; do
        base="${path##*/}"
        if allowed_name "$base" || allowed_path "$path"; then
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
# Compose's mangle <base>-<md5>.jar. Packaging recompresses the image JARs, so the
# row pins canonical jar content (each entry's name and uncompressed content SHA-256,
# sorted by name), not the container bytes. An image jar passes when a row names it
# exactly and its canonical content hash equals the row's.
#
# The one documented off-manifest jar is Compose's skiko stub (11 Image scan rules):
# Compose ships the native-bearing skiko-awt-runtime-<os>-<arch>-<ver> classpath JAR
# as a stub named after the stub's own md5 and extracts the libskiko native beside it
# with a .sha256 sidecar. The stub is accepted only when the manifest carries a
# skiko-awt-runtime-<os>-<arch>-*.jar entry and the stub's entries are only metadata
# and the other targets' natives with their sidecars.
jar_content_hash() {
    python3 - "$1" <<'PYEOF'
import struct, sys, zipfile, hashlib
path = sys.argv[1]
try:
    z = zipfile.ZipFile(path)
except zipfile.BadZipFile:
    print(hashlib.sha256(open(path, 'rb').read()).hexdigest())
    sys.exit(0)

# macOS packaging signs every Mach-O it finds, inside jars too, and re-signing
# is not byte-reversible — codesign --remove-signature leaves a re-signed file
# different from the published one. Both sides therefore hash the normalized
# form: ncmds/sizeofcmds minus the signature slots, each LC_CODE_SIGNATURE
# command zeroed, __LINKEDIT's filesize minus the blob bytes inside it and its
# vmsize as the architecture-page-rounded non-signature extent, and the
# [dataoff, dataoff+datasize) blob region cut. Everything else stays
# byte-exact, so tampered content still differs.
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

is_skiko_stub() {
    local jar="$1" manifest="$2" base
    base="$(basename "$jar")"
    case "$base" in
        skiko-awt-runtime-*) ;;
        *) return 1 ;;
    esac
    if ! grep -qE '^skiko-awt-runtime-[^ ]+\.jar$|^[0-9a-f]{64}  skiko-awt-runtime-' "$manifest"; then
        return 1
    fi
    command -v python3 >/dev/null 2>&1 || return 1
    python3 - "$jar" <<'PYEOF' 2>/dev/null
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

check_jars() {
    local img="$1" manifest="$2" path base hash
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
# The engine resources arrive at <resources>/engine/ (Compose merges common/ and
# <os>-<arch>/). The check binds the tree to the lock: the interpreter, the CA bundle
# and the standard library must exist, the stdlib's minor must equal the lock's CPython
# version, and the image's target must be one of the lock's targets.
python_tree_of() {
    local img="$1" dir
    for dir in "$img/Contents/app/resources/engine" "$img/app/resources/engine" "$img/lib/app/resources/engine" "$img/engine"; do
        if [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    done
    return 1
}

image_target_of() {
    # A packaged image carries os/arch in build-info.properties (inside the app jar);
    # a dev image does not, so the target is inferred from the image's shape.
    local img="$1" jar os arch
    jar="$(find "$img" -name 'desktopApp-*.jar' -print -quit 2>/dev/null)"
    if [ -n "$jar" ] && command -v python3 >/dev/null 2>&1; then
        read -r os arch < <(python3 - "$jar" <<'PYEOF' 2>/dev/null
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        props = {}
        for line in z.read("build-info.properties").decode("utf-8", "replace").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                props[key.strip()] = value.strip()
        print(props.get("os", ""), props.get("arch", ""))
except Exception:
    print("", "")
PYEOF
)
        if [ -n "$os" ] && [ -n "$arch" ]; then
            echo "$os-$arch"
            return 0
        fi
    fi
    if [ -d "$img/Contents" ]; then
        echo "macos-arm64"
        return 0
    fi
    if [ -d "$img/app" ] && [ -d "$img/runtime" ]; then
        echo "windows-x64"
        return 0
    fi
    return 1
}

check_python_tree() {
    local img="$1" engine python target lock_python lock_targets tree_minor
    if ! engine="$(python_tree_of "$img")"; then
        if [ -f "$PY_LOCK" ] && grep -q '^\[\[component\]\]' "$PY_LOCK"; then
            fail "python-components.lock lists components but no engine/python tree is in the image"
        fi
        return
    fi
    if [ ! -f "$PY_LOCK" ]; then
        fail "engine/python tree present but youtube/ytdlp-desktop/python-components.lock is missing"
        return
    fi
    python="$engine/python"

    # The interpreter: python/bin/python3 off Windows, python/python.exe on Windows.
    local interpreter
    if [ -f "$python/python.exe" ]; then
        interpreter="$python/python.exe"
    elif [ -f "$python/bin/python3" ]; then
        interpreter="$python/bin/python3"
    else
        fail "no Python interpreter in image (expected $python/bin/python3 or $python/python.exe)"
    fi
    if [ -n "$interpreter" ] && [ ! -x "$interpreter" ]; then
        fail "the bundled Python interpreter is not executable: ${interpreter#"$img"/}"
    fi

    # The CA bundle the engine's TLS trusts (11 Networking and TLS).
    if [ ! -f "$engine/cacert.pem" ]; then
        fail "the bundled CA bundle is missing: $engine/cacert.pem (11 Networking and TLS)"
    fi

    # The standard library: lib/python<major>.<minor> off Windows, Lib/ on Windows.
    # `lib` is checked before `Lib`: macOS's default volume is case-insensitive,
    # so $python/Lib would otherwise resolve to lib and report the wrong layout.
    tree_minor=""
    local stdlib
    if [ -d "$python/lib" ]; then
        for stdlib in "$python"/lib/python*; do
            if [ -d "$stdlib" ]; then
                tree_minor="$(basename "$stdlib")"
                tree_minor="${tree_minor#python}"
                break
            fi
        done
    elif [ -d "$python/Lib" ]; then
        # Windows: the minor is not part of the path; the DLL name carries it.
        local dll
        dll="$(find "$python" -maxdepth 1 -name 'python3*.dll' -print -quit 2>/dev/null)"
        if [ -n "$dll" ]; then
            tree_minor="$(basename "$dll")"
            tree_minor="${tree_minor#python}"
            tree_minor="${tree_minor%.dll}"
        fi
    fi
    if [ -z "$tree_minor" ]; then
        fail "no Python standard library in image (expected $python/lib/python<major>.<minor> or $python/Lib)"
        return
    fi

    # The lock's CPython version and the tree's minor must agree.
    lock_python="$(grep -E '^python *=' "$PY_LOCK" | tail -1 | sed -E 's/^[^=]*= *"([^"]*)".*/\1/')"
    if [ -z "$lock_python" ]; then
        fail "python-components.lock carries no python version"
    elif [ "${lock_python%.*}" != "$tree_minor" ]; then
        fail "Python stdlib minor '$tree_minor' != python-components.lock python '$lock_python'"
    fi

    # The image's target must be one of the lock's targets.
    if target="$(image_target_of "$img")"; then
        lock_targets="$(awk '/^\[\[target\]\]/{t=1;next} /^\[/{t=0} t && /^id *=/{sub(/^[^=]*= *"?/,"");sub(/".*$/,"");print}' "$PY_LOCK")"
        if ! printf '%s\n' "$lock_targets" | grep -qxF "$target"; then
            fail "image target '$target' is not one of python-components.lock's targets"
        fi
    fi

    # The tree itself must not carry a forbidden component (rule 1 already walks the
    # whole image; this repeats the match inside the tree so a fixture or a package
    # dropped into engine/python cannot hide behind the resources merge).
    check_forbidden_names "$engine"

    # The yt-dlp archive is a relevant archive: its entries must not carry a forbidden
    # component either (verifyBundledYtDlp checks the release's own inventory; this
    # binds the shipped bytes to the same forbidden list).
    local ytdlp="$engine/ytdlp/yt-dlp"
    if [ -f "$ytdlp" ] && command -v python3 >/dev/null 2>&1; then
        local bad
        bad="$(python3 - "$ytdlp" <<'PYEOF' 2>/dev/null
import sys, zipfile
# A forbidden component is a whole path segment, never a word inside a name:
# `bun` must not eat bunnycdn.py and `thread` must not eat threading.py. The
# runtimes (qjs/deno/node/bun) are matched as exact file names — yt-dlp ships
# bun.py/deno.py/node.py wrapper modules, which are not the runtimes.
MODULES = {'_dbm', 'libdb', '_gdbm', 'libreadline', 'readline', '_tkinter',
           'libtcl', 'libtk', 'tcl9', 'tk9', 'itcl', 'thread', 'ensurepip',
           'mutagen', 'bgutil'}
RUNTIMES = {'qjs', 'qjs.exe', 'deno', 'node', 'bun'}
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        hits = []
        for n in z.namelist():
            segments = n.split('/')
            base = segments[-1]
            if base in RUNTIMES:
                hits.append(n)
            elif any(seg in MODULES or seg.split('.')[0] in MODULES for seg in segments):
                hits.append(n)
        print('\n'.join(hits[:5]))
except Exception:
    pass
PYEOF
)"
        if [ -n "$bad" ]; then
            fail "forbidden entry in the bundled yt-dlp archive: $(echo "$bad" | head -1)"
        fi
    fi
    note "python tree matches python-components.lock (CPython $tree_minor, target ${target:-unbound})"
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
    # Documented seams carry `Test` in a production name and are exempt:
    # StdioYtxTestArming (the --replay/--debug-hooks arming flag, 11's MD3 notes),
    # EngineSelfTestRunner (the post-update self-test, 04's engine updates), and
    # the Android host's YtxTestArming/YtxTestHooks (never in a desktop image).
    if command -v python3 >/dev/null 2>&1; then
        while IFS= read -r -d '' path; do
            case "$path" in
                */runtime/*|*.jar.exclude) continue ;;
            esac
            local bad
            bad="$(python3 - "$path" <<'PYEOF' 2>/dev/null
import sys, zipfile
SEAMS = ('StdioYtxTestArming', 'EngineSelfTestRunner', 'YtxTestArming', 'YtxTestHooks')
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        hits = [n for n in z.namelist()
                if '/test/' in n or '/tests/' in n or '/fixtures/' in n or '/testdata/' in n
                or (n.startswith('ch/lkmc/') and 'Test' in n and n.endswith('.class')
                    and not any(s in n for s in SEAMS))]
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
    if ! codesign --verify --deep --strict "$img" 2>/dev/null; then
        # The terse error does not name the broken entry; the verbose rerun
        # lists each sealed resource that fails ("file modified: …").
        codesign --verify --deep --strict --verbose=4 "$img" 2>&1 \
            | grep -iE 'invalid|missing|modified|mismatch' | head -10 || true
        fail "codesign --verify --deep --strict failed on $img"
    fi
}

# --- dispatch ----------------------------------------------------------------------
scan_dir() {
    local img="$1"
    check_forbidden_names "$img"
    check_runtime_layout "$img"
    check_ffmpeg "$img"
    check_jars "$img" "$CLASSPATH_MANIFEST"
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
    # Inputs arrive relative to the caller's cwd (dist/…); the extraction happens
    # inside a subshell cd'd to $tmp, where a relative path resolves nowhere.
    pkg="$(cd -- "$(dirname -- "$pkg")" && pwd -P)/$(basename -- "$pkg")"
    tmp="$(mktemp -d)"
    case "$fmt" in
        tar.gz) tar -xzf "$pkg" -C "$tmp" ;;
        zip)
            # A signed .app must come back byte-identical: python's zipfile turns
            # the jlink legal/ symlinks into regular files, which breaks the
            # sealed bundle. ditto preserves them.
            if command -v ditto >/dev/null 2>&1; then
                ditto -x -k "$pkg" "$tmp"
            else
                python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$pkg" "$tmp"
            fi ;;
    esac
    local roots=() d
    while IFS= read -r d; do roots+=("$d"); done \
        < <(find "$tmp" -mindepth 1 -maxdepth 3 \( -name 'Neutrodyne' -o -name 'Neutrodyne.app' \) -type d)
    if [ "${#roots[@]}" -eq 0 ]; then
        roots=("$tmp")
    fi
    for d in "${roots[@]}"; do
        scan_dir "$d"
    done
    rm -rf "$tmp"
}

for input in "${INPUTS[@]}"; do
    if [ ! -e "$input" ]; then
        fail "input does not exist: $input"
        continue
    fi
    case "$input" in
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
