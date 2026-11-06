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
# desktopApp/packaging/deb/control, desktopApp/packaging/rpm/template.spec, the
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
RPM_SPEC="$REPO_ROOT/desktopApp/packaging/rpm/template.spec"
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
# Compose renames every jar to <name>-<md5>.jar where the md5 is rendered per byte
# with %x (leading zeros dropped: md5 ...b2 03 3e... renders ...b233e...). An image
# jar proves its identity when its md5 — in either rendering — matches its suffix
# and <name>.jar is on the manifest (the Licensee-checked list written by
# :desktopApp:writeDesktopRuntimeClasspath). A jar without a matching suffix must
# hash-identically match its manifest row.
check_jars() {
    local img="$1" manifest="$2" path hash base want padded unpadded i byte
    if [ ! -f "$manifest" ]; then
        fail "runtime-classpath manifest missing: $manifest (run :desktopApp:createDistributable first)"
        return
    fi
    while IFS= read -r -d '' path; do
        case "$path" in
            */runtime/*) continue ;; # the JDK's own jars (jrt-fs.jar) are not classpath jars
        esac
        base="${path##*/}"
        padded="$(md5_of "$path")"
        unpadded=""
        for ((i = 0; i < ${#padded}; i += 2)); do
            byte="${padded:i:2}"
            unpadded+="${byte#0}"
        done
        case "$base" in
            *-"$unpadded".jar) want="${base%-"$unpadded".jar}.jar" ;;
            *-"$padded".jar) want="${base%-"$padded".jar}.jar" ;;
            *) want="$base" ;;
        esac
        hash="$(grep -E "  $(printf '%s' "$want" | sed 's/[].[^$*\/]/\\&/g')\$" "$manifest" | cut -d' ' -f1)"
        if [ -z "$hash" ]; then
            fail "JAR not on the Licensee-checked runtime classpath: ${path#"$img"/}"
        elif [ "$want" = "$base" ] && [ "$(sha256 "$path")" != "$hash" ]; then
            fail "JAR differs from the Licensee-checked classpath artifact: ${path#"$img"/}"
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
    if ! codesign --verify --deep --strict "$img" 2>/dev/null; then
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
    local rpmf="$1" want missing=0
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
            missing=1
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
    local pkg="$1" fmt="$2" tmp
    tmp="$(mktemp -d)"
    case "$fmt" in
        tar.gz) tar -xzf "$pkg" -C "$tmp" ;;
        zip) python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$pkg" "$tmp" ;;
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
