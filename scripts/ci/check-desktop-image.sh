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
#   <file>     a release package: .deb, .rpm, .msi, .zip, .tar.gz, .dmg — the
#              package's own rules plus the directory rules on its extracted or
#              read-only-mounted contents
#   --no-engine  additionally asserts that no Python tree, yt-dlp file or
#              :youtube:ytdlp-desktop JAR is present (emergency build, 01)
#
# Inputs besides the image: desktopApp/runtime.lock, desktopApp/wix.lock,
# desktopApp/packaging/deb/control, desktopApp/packaging/rpm/neutrodyne.spec, the
# Licensee-checked runtime-classpath manifest
# (desktopApp/build/desktop-packaging/runtime-classpath.txt, written by
# :desktopApp:writeDesktopRuntimeClasspath), the derived skiko stub manifest
# (desktopApp/build/desktop-packaging/runtime-classpath-stubs.txt, same task) and,
# once the engine ships (MD3), desktopApp/python-components.lock.
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

# DMG payloads are scanned from a read-only mount (hdiutil attach -nobrowse
# -readonly — nothing is browsed or opened). Every successful attach is recorded
# so the EXIT trap still detaches it when a check aborts the script mid-scan; a
# failed detach is reported by the caller.
DMG_MOUNTS=()
dmg_detach_all() {
    local m rc=0
    # bash 3.2-safe iteration over a possibly empty array under set -u.
    for m in ${DMG_MOUNTS[@]+"${DMG_MOUNTS[@]}"}; do
        if ! hdiutil detach "$m" -quiet >/dev/null 2>&1 \
            && ! hdiutil detach "$m" -force -quiet >/dev/null 2>&1; then
            rc=1
        fi
    done
    DMG_MOUNTS=()
    return $rc
}
trap dmg_detach_all EXIT

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
# eats `sqlite-bundled`. The python names are the tree pieces trim-python.sh removes:
# `itcl*`/`thread*` are Tcl/Tk's lib/itcl* and lib/thread* packages, not the stdlib's
# threading.py or concurrent/futures/thread.py, so they are matched as paths below.
FORBIDDEN_BASENAME_GLOBS=(
    '_dbm*' 'libdb*' '_gdbm*' 'libreadline*' 'readline*' '_readline*'
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
# is a shipped CPython component, not the GPL readline library (11 item 1).
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
    # The jlink'd runtime sits at lib/runtime on Linux and Windows and at
    # Contents/runtime inside a .app bundle.
    local dir
    for dir in "$1/lib/runtime" "$1/Contents/runtime" "$1/runtime"; do
        if [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    done
    return 1
}

# The runtime *home* under a runtime root: flat on Linux and Windows, one level
# deeper inside a jpackage .app (Contents/runtime is the JDK bundle root and its
# release/legal/lib live in Contents/Home).
runtime_home() {
    if [ -f "$1/release" ]; then
        echo "$1"
    else
        echo "$1/Contents/Home"
    fi
}

check_runtime_layout() {
    local img="$1" rt rthome
    if ! rt="$(runtime_dir "$img")"; then
        fail "no bundled runtime directory in image (expected lib/runtime or Contents/runtime)"
        return
    fi
    rthome="$(runtime_home "$rt")"
    if [ ! -d "$rthome/legal" ] || [ -z "$(ls -A "$rthome/legal" 2>/dev/null)" ]; then
        fail "runtime/legal/ missing or empty in image (11 Keep the notices)"
    fi
    if [ ! -f "$rthome/release" ]; then
        fail "runtime/release missing in image"
        return
    fi
    local want got
    want="$(lock_prop "$RUNTIME_LOCK" javaVersion)"
    got="$(grep -E '^JAVA_VERSION=' "$rthome/release" | tail -1 | cut -d'"' -f2 || true)"
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
# The manifest records `<canonical sha256>  <image name>` (writeDesktopRuntimeClasspath):
# the name is Compose's mangled <base>-<md5>.jar, the hash canonical jar content
# (entry name + uncompressed content SHA-256, sorted by name) -- packaging
# recompresses and re-signs image jars, so raw bytes and the md5 stamp change.
# Membership is the jar's basename. The skiko stub is a repacked derivative whose image
# name the build cannot predict, so it is bound by the stub manifest
# (runtime-classpath-stubs.txt): `<expected stub hash>  <source name>
# <extracted file>=<hash>,…`, derived from the Licensee-checked source artifact and the
# target extraction rule at build time -- never from the image. A name prefix or a
# matching version row alone is not acceptance.
skiko_stub_record() {
    local base="$1" stub_manifest="$2" rest os arch version stamp src
    case "$base" in
        skiko-awt-runtime-*-*-*-*.jar) ;;
        *) return 1 ;;
    esac
    # The image name is the source name with Compose's content md5 inserted before
    # ".jar"; parse from the right so a version may itself contain dashes.
    stamp="${base%.jar}"
    stamp="${stamp##*-}"
    rest="${base#skiko-awt-runtime-}"
    os="${rest%%-*}"
    rest="${rest#"$os"-}"
    arch="${rest%%-*}"
    rest="${rest#"$arch"-}"
    version="${rest%-*}"
    # The stamp is Compose's unpadded lowercase hex md5: reject names whose suffix is
    # not a plausible mangled stamp (an ambiguous or hand-written name).
    [ -n "$stamp" ] || return 1
    case "$stamp" in
        *[!0-9a-f]*) return 1 ;;
    esac
    src="skiko-awt-runtime-${os}-${arch}-${version}.jar"
    [ -f "$stub_manifest" ] || return 1
    awk -v want="$src" '$2 == want { print; found = 1 }
        END { exit(found ? 0 : 1) }' "$stub_manifest"
}

jar_content_hash() {
    python3 "$REPO_ROOT/scripts/ci/canonical-jar-hash.py" "$1"
}

content_hash() {
    python3 "$REPO_ROOT/scripts/ci/canonical-jar-hash.py" --content "$1"
}

check_jars() {
    local img="$1" manifest="$2" path hash base record want_hash rest src_name specs actual seen="|" spec appdir name chash file rt
    if [ ! -f "$manifest" ]; then
        fail "runtime-classpath manifest missing: $manifest (run :desktopApp:createDistributable first)"
        return
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        fail "the JAR classpath binding needs python3 (canonical-jar-hash.py)"
        return
    fi
    local stub_manifest="${manifest%/*}/runtime-classpath-stubs.txt"
    # Only the resolved runtime root's own jars (jrt-fs.jar) are exempt. A
    # /runtime/ directory anywhere else is an ordinary image directory: its
    # JARs must bind the Licensee-checked classpath like any other.
    rt="$(runtime_dir "$img" || true)"
    while IFS= read -r -d '' path; do
        if [ -n "$rt" ]; then
            case "$path" in
                "$rt"/*) continue ;;
            esac
        fi
        base="${path##*/}"
        # Field-exact lookup (awk misses return empty, never nonzero): the
        # mangled names carry regex-hostile characters.
        hash="$(WANT="$base" awk '$2 == ENVIRON["WANT"] { print $1 }' "$manifest")"
        if [ -n "$hash" ]; then
            actual="$(jar_content_hash "$path" 2>/dev/null || true)"
            if [ -z "$actual" ]; then
                fail "cannot read JAR content: ${path#"$img"/}"
            elif [ "$actual" != "$hash" ]; then
                fail "JAR differs from the Licensee-checked classpath artifact: ${path#"$img"/}"
            fi
        elif record="$(skiko_stub_record "$base" "$stub_manifest")"; then
            want_hash="${record%%  *}"
            rest="${record#"$want_hash"  }"
            src_name="${rest%%  *}"
            specs="${rest#"$src_name"  }"
            case "$seen" in
                *"|${src_name}|"*) fail "duplicate skiko stub JAR: ${path#"$img"/}" ;;
            esac
            seen="${seen}${src_name}|"
            actual="$(jar_content_hash "$path" 2>/dev/null || true)"
            if [ -z "$actual" ]; then
                fail "cannot read skiko stub content: ${path#"$img"/}"
            elif [ "$actual" != "$want_hash" ]; then
                fail "skiko stub content differs from the Licensee-checked source artifact: ${path#"$img"/}"
            else
                appdir="$(dirname -- "$path")"
                if [ -n "$specs" ]; then
                    IFS=',' read -r -a spec_arr <<<"$specs"
                    for spec in "${spec_arr[@]}"; do
                        name="${spec%%=*}"
                        chash="${spec#*=}"
                        file="$appdir/$name"
                        if [ ! -f "$file" ]; then
                            fail "skiko extracted native missing from the image: ${file#"$img"/}"
                        else
                            actual="$(content_hash "$file" 2>/dev/null || true)"
                            if [ -z "$actual" ]; then
                                fail "cannot read skiko extracted native: ${file#"$img"/}"
                            elif [ "$actual" != "$chash" ]; then
                                fail "skiko extracted native differs from the Licensee-checked source artifact: ${file#"$img"/}"
                            fi
                        fi
                    done
                fi
                note "skiko stub JAR bound to the Licensee-checked source artifact: ${path#"$img"/}"
            fi
        else
            fail "JAR not on the Licensee-checked runtime classpath: ${path#"$img"/}"
        fi
    done < <(find "$img" -name '*.jar' -print0)
}

# --- rule 5: the Python tree matches python-components.lock ---------------------
# The lock's component names are logical (CPython, OpenSSL, ...), not filenames, so
# the tree is bound to the lock by the trimmed layout the lock describes: the
# interpreter, the CA bundle, the standard library's minor version and the image's
# target (01 Python and native components, 11 Image scan rules). The engine
# resources arrive at <resources>/engine/ (Compose merges common/ and <os>-<arch>/).
python_tree_of() {
    local img="$1" dir
    for dir in "$img/Contents/app/resources/engine" "$img/app/resources/engine" \
        "$img/lib/app/resources/engine" "$img/engine"; do
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

    lock_python="$(grep -E '^python *=' "$PY_LOCK" | tail -1 | sed -E 's/^[^=]*= *"([^"]*)".*/\1/')"

    # The interpreter selects the layout: Lib and lib alias on case-insensitive
    # filesystems. Bind Windows to the versioned DLL, not the stable python3.dll.
    tree_minor=""
    local stdlib lock_minor
    if [ -f "$python/python.exe" ]; then
        lock_minor="${lock_python%.*}"
        if [ -n "$lock_minor" ] && [ -d "$python/Lib" ] && [ -f "$python/python${lock_minor//./}.dll" ]; then
            tree_minor="$lock_minor"
        fi
    elif [ -d "$python/lib" ]; then
        for stdlib in "$python"/lib/python*; do
            if [ -d "$stdlib" ]; then
                tree_minor="$(basename "$stdlib")"
                tree_minor="${tree_minor#python}"
                break
            fi
        done
    fi
    if [ -z "$tree_minor" ]; then
        fail "no Python standard library in image (expected $python/lib/python<major>.<minor> or $python/Lib)"
        return
    fi

    # The lock's CPython version and the tree's minor must agree.
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
    local img="$1" path base bin_dir rt
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
    # Only the resolved runtime root's own jars are exempt; a /runtime/ directory
    # anywhere else is an ordinary image directory and its JARs are scanned.
    rt="$(runtime_dir "$img" || true)"
    if command -v python3 >/dev/null 2>&1; then
        while IFS= read -r -d '' path; do
            if [ -n "$rt" ]; then
                case "$path" in
                    "$rt"/*) continue ;;
                esac
            fi
            case "$path" in
                *.jar.exclude) continue ;;
            esac
            local bad
            bad="$(python3 - "$path" <<'PYEOF' 2>/dev/null
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        # This inert production interface defines instrumentation injection;
        # its implementations and test classes still fail the ordinary rule.
        replay_contract = 'ch/lkmc/neutrodyne/youtube/engine/YtxTestArming.class'
        hits = [n for n in z.namelist()
                if any(p in {'test', 'tests', 'fixtures', 'testdata', '__tests__'} for p in n.split('/'))
                or (n.startswith('ch/lkmc/') and 'Test' in n and n.endswith('.class')
                    and n != replay_contract)]
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

    # Windows: the launcher is Neutrodyne.exe at the image root, beside non-entry
    # DLL/data files and the runtime/ tree (a Windows tree is recognised by
    # runtime/bin/java.exe, 11 Resources layout). Only executables are entry points.
    if [ -f "$img/runtime/bin/java.exe" ]; then
        local exe_count
        exe_count="$(find "$img" -mindepth 1 -maxdepth 1 -type f -name '*.exe' | wc -l)"
        if [ "$exe_count" -ne 1 ]; then
            fail "Windows image root holds $exe_count entry-point executables, expected the one launcher"
        fi
    fi
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
        fail "codesign --verify --deep --strict failed on $img"
    fi
}

# --- rule 9: Windows MSI ----------------------------------------------------------
# One Windows Installer table query through the COM API. Path and SQL travel in
# the environment as data, never interpolated into -Command (spaces/apostrophes
# stay data); a failure exits nonzero, so it can never pass as an empty table.
# shellcheck disable=SC2016 # PowerShell script; $env: is its syntax, not shell expansion
msi_query() {
    ND_MSI="$2" ND_SQL="$3" ND_COLS="$4" "$1" -NoProfile -Command '
        $wi = New-Object -ComObject WindowsInstaller.Installer
        $db = $wi.GetType().InvokeMember("OpenDatabase","InvokeMethod",$null,$wi,@($env:ND_MSI,0))
        $v = $db.GetType().InvokeMember("OpenView","InvokeMethod",$null,$db,@($env:ND_SQL))
        $v.GetType().InvokeMember("Execute","InvokeMethod",$null,$v,@($null)) | Out-Null
        while ($r = $v.GetType().InvokeMember("Fetch","InvokeMethod",$null,$v,@($null))) {
            $row = @()
            for ($i = 1; $i -le [int]$env:ND_COLS; $i++) {
                $row += $r.GetType().InvokeMember("StringData","GetProperty",$null,$r,@($i))
            }
            [string]::Join("|", $row)
        }
    ' 2>/dev/null | tr -d '\r'
}

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

    # The COM API needs a native path; convert the runner's MSYS form once.
    local msi_win="$msi"
    if command -v cygpath >/dev/null 2>&1; then
        msi_win="$(cygpath -w "$msi")"
    fi

    # INSTALLDIR must resolve to LocalAppDataFolder\Neutrodyne-App (11 Windows MSI and
    # ZIP): one directory under the per-user root, distinct from the Neutrodyne data
    # directory. Only INSTALLDIR's own chain decides; DefaultDir sfn|lfn names it long.
    local dirs
    if ! dirs="$(msi_query "$ps" "$msi_win" \
            'SELECT Directory, Directory_Parent, DefaultDir FROM Directory' 3)"; then
        fail "MSI Directory table query failed (PowerShell/COM error)"
    elif ! printf '%s\n' "$dirs" | awk -F'|' '
        { parent[$1] = $2; def[$1] = $3
          for (i = 4; i <= NF; i++) def[$1] = def[$1] "|" $i }
        END {
            if (!("INSTALLDIR" in def)) exit 1
            cur = "INSTALLDIR"; path = ""; guard = 0
            while (1) {
                if (guard++ >= 20) exit 1
                n = split(def[cur], seg, /\|/)
                dd = (n >= 2 && seg[2] != "") ? seg[2] : seg[1]
                if (dd == "" || dd == ".") dd = cur
                p = parent[cur]
                if (p == "" || p == cur) break
                if (!(p in def)) exit 1
                path = (path == "" ? dd : dd "\\" path)
                cur = p
            }
            exit(path == "LocalAppDataFolder\\Neutrodyne-App" \
                && parent["INSTALLDIR"] == "LocalAppDataFolder" ? 0 : 1)
        }'; then
        fail "MSI INSTALLDIR does not resolve under LocalAppDataFolder\\Neutrodyne-App (Directory table)"
    fi

    # Every Binary-table entry must be on wix.lock's binaryTable list (fnmatch);
    # a failed or empty query never reaches the allowlist.
    local names name pat ok
    if ! names="$(msi_query "$ps" "$msi_win" 'SELECT Name FROM Binary' 1)"; then
        fail "MSI Binary table query failed (PowerShell/COM error)"
    elif [ -z "$names" ]; then
        fail "MSI Binary table query returned no entries"
    else
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
    fi
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
    local rpmf="$1" want
    if ! command -v rpm >/dev/null 2>&1; then
        fail "RPM check needs rpm(8) to read the package's Requires"
        return
    fi
    while IFS= read -r want; do
        want="${want#Requires:}"
        want="${want// /}"
        [ -z "$want" ] && continue
        if ! rpm -qp --queryformat '[%{REQUIRENAME}\n]' "$rpmf" 2>/dev/null | grep -qxF "$want"; then
            fail "RPM is missing our Requires: $want"
        fi
    done < <(grep -E '^Requires:' "$RPM_SPEC")
    # rpmbuild must not have added its own discovered Requires (Autoreq: 0 in our spec).
    local extras
    extras="$(rpm -qp --queryformat '[%{REQUIRENAME}\n]' "$rpmf" 2>/dev/null \
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
    local pkg="$1" fmt="$2" tmp mnt=""
    tmp="$(mktemp -d)"
    case "$fmt" in
        tar.gz) tar -xzf "$pkg" -C "$tmp" ;;
        zip)
            if [ "$(uname -s)" = Darwin ]; then
                # Python's extractor drops executable modes and framework symlinks and
                # exposes AppleDouble metadata as payload. Preserve the signed app bundle.
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
        dmg)
            if ! command -v hdiutil >/dev/null 2>&1; then
                fail "scanning a DMG needs hdiutil (macOS)"
                rm -rf "$tmp"
                return
            fi
            if ! hdiutil attach -nobrowse -readonly -mountpoint "$tmp" "$pkg" >/dev/null; then
                fail "hdiutil could not attach $pkg read-only"
                rm -rf "$tmp"
                return
            fi
            mnt="$tmp"
            DMG_MOUNTS+=("$mnt")
            ;;
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
    if [ -n "$mnt" ]; then
        # The mount leaves on the normal path too; the EXIT trap covers aborts.
        dmg_detach_all || fail "the DMG mount did not detach: $mnt"
        rm -rf "$tmp" 2>/dev/null || true
    else
        rm -rf "$tmp"
    fi
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
        *.dmg) extract_and_scan "$input" dmg ;;
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
