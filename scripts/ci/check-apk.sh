#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-apk.sh [--published|--no-engine|--alignment-only] <apk ...>
#
# Build-output checks for the Android APKs (09 Build-output checks). Runs in ci.yml's
# `assemble` job (on the release APKs), in release.yml's `android` job (--published),
# in the nightly `no-engine-build` (--no-engine, M9a) and in `api37-16k`
# (--alignment-only). Inputs besides the APKs: app/policy/locales.txt,
# youtube/ytdlp/python-components.lock, gradle.properties (version and
# neutrodyne.youtubeEngine) and the expected certificate from public-cert-sha256.sh.
#
#   default mode      sections 1-3, 5, 6a: size/set, 16 KB, content, locales, hygiene
#   --published       adds section 4 (manifest facts of a published build) and the
#                     signer check of section 6
#   --no-engine       the --published checks plus the no-engine content rules; also
#                     entered automatically when gradle.properties says
#                     neutrodyne.youtubeEngine=false (emergency build, 01)
#   --alignment-only  only section 2 (zipalign + ELF LOAD alignment)
#
# Exit 0 = all APKs pass; 1 = at least one violation (all violations are printed).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
LOCALES_FILE="$REPO_ROOT/app/policy/locales.txt"
PYLOCK="$REPO_ROOT/youtube/ytdlp/python-components.lock"
GRADLE_PROPERTIES="$REPO_ROOT/gradle.properties"

PUBLISHED_ABIS=(arm64-v8a x86_64 armeabi-v7a) # 01 Build variants and ABIs, D77: never a universal APK

# Forbidden names (09 item 3): a zip entry, nested asset-zip entry or dex class
# descriptor matching one of these fails. `readline`/`libreadline` cover GNU
# readline (GPL); `_pyrepl/readline.pyc` is CPython's own pure-Python
# readline replacement, part of the lockfile's CPython component, and is exempt
# (recorded in 09's deviations note, 2026-10-06).
FORBIDDEN_RE='mutagen|org/schabi/newpipe|org/mozilla/javascript|(^|/)libreadline|(^|/)readline[./]'

# Debug code that must never reach a published APK (09 item 3, PLAN M0 AC9):
# LeakCanary/shark classes, Compose's PreviewActivity, the ui-test-manifest host
# activity and classes built only from app/src/debug or app/src/benchmarkRelease.
DEBUG_DEX_RE='Lleakcanary/|Lshark/|Landroidx/compose/ui/tooling/PreviewActivity|BenchmarkSeedReceiver'
DEBUG_MANIFEST_RE='androidx\.compose\.ui\.tooling\.PreviewActivity|androidx\.activity\.ComponentActivity'

MODE=default
APKS=()
for arg in "$@"; do
    case "$arg" in
        --published) MODE=published ;;
        --no-engine) MODE=no-engine ;;
        --alignment-only) MODE=alignment ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) APKS+=("$arg") ;;
    esac
done

# absolute paths: dex extraction below runs inside $ND_TMP
for i in "${!APKS[@]}"; do
    if [ -f "${APKS[i]}" ]; then
        APKS[i]="$(cd -- "$(dirname -- "${APKS[i]}")" && pwd -P)/$(basename -- "${APKS[i]}")"
    fi
done

if [ "${#APKS[@]}" -eq 0 ]; then
    echo "usage: $0 [--published|--no-engine|--alignment-only] <apk ...>" >&2
    exit 2
fi

# --- inputs -------------------------------------------------------------------

prop() { grep -E "^$1=" "$GRADLE_PROPERTIES" | tail -1 | cut -d= -f2-; }

VERSION_NAME="$(prop neutrodyne.versionName)"
VERSION_CODE="$(prop neutrodyne.versionCode)"
YOUTUBE_ENGINE="$(prop neutrodyne.youtubeEngine)"
if [ "$YOUTUBE_ENGINE" = "false" ]; then
    MODE=no-engine # an emergency build is checked with the no-engine rules (09)
fi

# --- tool resolution -----------------------------------------------------------

# build-tools 36.0.0 per 01's version table; fall back to the newest installed dir
# so the script also runs on machines that only carry another build-tools release.
BUILD_TOOLS=""
for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$root" ] || continue
    if [ -d "$root/build-tools/36.0.0" ]; then
        BUILD_TOOLS="$root/build-tools/36.0.0"
        break
    fi
    newest="$(find "$root/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
    if [ -n "$newest" ]; then
        BUILD_TOOLS="$newest"
        break
    fi
done

tool() { # tool <name>: $BUILD_TOOLS first, then PATH
    local name="$1"
    if [ -n "$BUILD_TOOLS" ] && [ -x "$BUILD_TOOLS/$name" ]; then
        printf '%s\n' "$BUILD_TOOLS/$name"
    elif command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
    else
        return 1
    fi
}

AAPT2="$(tool aapt2 || true)"
ZIPALIGN="$(tool zipalign || true)"
APKSIGNER="$(tool apksigner || true)"
DEXDUMP="$(tool dexdump || true)"

if [ "$MODE" = alignment ]; then
    NEED=(zipalign python3)
else
    NEED=(aapt2 zipalign apksigner dexdump python3)
fi
for t in "${NEED[@]}"; do
    case "$t" in
        aapt2)     [ -n "$AAPT2" ]     || { echo "check-apk: aapt2 not found (set ANDROID_HOME)" >&2; exit 2; } ;;
        zipalign)  [ -n "$ZIPALIGN" ]  || { echo "check-apk: zipalign not found (set ANDROID_HOME)" >&2; exit 2; } ;;
        apksigner) [ -n "$APKSIGNER" ] || { echo "check-apk: apksigner not found (set ANDROID_HOME)" >&2; exit 2; } ;;
        dexdump)   [ -n "$DEXDUMP" ]   || { echo "check-apk: dexdump not found (set ANDROID_HOME)" >&2; exit 2; } ;;
        python3)   command -v python3 >/dev/null 2>&1 || { echo "check-apk: python3 not found" >&2; exit 2; } ;;
    esac
done

# apksigner and the other build-tools launchers exec `java`; make the JDK on
# JAVA_HOME (or PATH) reachable so they work inside the slim release container.
if ! command -v java >/dev/null 2>&1 && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null 2>&1 || { echo "check-apk: java not found (set JAVA_HOME)" >&2; exit 2; }

# ELF reader for the 16 KB check: llvm-readelf (09; NDK toolchain or PATH) first,
# binutils readelf next, and — because the slim python:3.14-slim-trixie release
# container carries neither — an embedded Python parser as the documented fallback.
READELF=""
if command -v llvm-readelf >/dev/null 2>&1; then
    READELF="$(command -v llvm-readelf)"
elif command -v readelf >/dev/null 2>&1; then
    READELF="$(command -v readelf)"
fi

# ELF LOAD-segment check. Prints nothing on success; "UNALIGNED <file> ..." per
# misaligned LOAD. Usage: check_elf <file>
check_elf() {
    local f="$1"
    if [ -n "$READELF" ]; then
        "$READELF" -lW "$f" | awk '
            /LOAD/ {
                align = strtonum($NF)
                if (align < 16384) printf "UNALIGNED %s LOAD align=%s\n", FILENAME, $NF
            }' FILENAME="$f"
        return
    fi
    python3 - "$f" <<'PYEOF'
import struct, sys

data = open(sys.argv[1], "rb").read()
if len(data) < 52 or data[:4] != b"\x7fELF":
    print(f"NOTELF {sys.argv[1]}")
    sys.exit(0)
is64 = data[4] == 2
le = data[5] == 1
end = "<" if le else ">"
if is64:
    phoff = struct.unpack_from(end + "Q", data, 32)[0]
    phentsize = struct.unpack_from(end + "H", data, 54)[0]
    phnum = struct.unpack_from(end + "H", data, 56)[0]
else:
    phoff = struct.unpack_from(end + "I", data, 28)[0]
    phentsize = struct.unpack_from(end + "H", data, 42)[0]
    phnum = struct.unpack_from(end + "H", data, 44)[0]
for i in range(phnum):
    off = phoff + i * phentsize
    p_type = struct.unpack_from(end + "I", data, off)[0]
    p_align = (struct.unpack_from(end + "Q" if is64 else "I", data,
                                  off + (48 if is64 else 28))[0])
    if p_type == 1 and p_align < 0x4000:
        print(f"UNALIGNED {sys.argv[1]} LOAD align=0x{p_align:x}")
PYEOF
}

# --- shared inspection data ---------------------------------------------------

ND_TMP="$(mktemp -d)"
trap 'rm -rf "$ND_TMP"' EXIT

FAILURES=()
fail() { FAILURES+=("$1"); echo "check-apk: FAIL  $1"; }
warn() { echo "check-apk: note  $1"; }
info() { echo "check-apk:       $1"; }

# zip_info <apk>: prints python-derived facts as KEY=VALUE lines:
#   ABI=<detected>            ABIS=<all lib abis, comma list>
#   UNIVERSAL=1 when >1 lib abi
#   ENGINE_BYTES=<n>          stored+compressed bytes under assets/chaquopy/ + engine lib .so
#   FORBIDDEN=<name>          one line per forbidden zip/nested-zip entry
#   BADLIB=<name>             lib/*.so outside the native allow-list
#   BADPY=<zip>!<entry>       top-level Python package outside the lockfile's list
#   V7A_PYLIB=<name>          Python/Chaquopy .so under lib/armeabi-v7a/
#   UNSTORED=<name>           lib/*.so stored compressed (16 KB needs STORED)
#   LOCALECONFIG=<name>       res/*.xml candidates for the locale config (aapt2 decides)
#   PSEUDO=<name>             composeResources pseudo-locale entry
zip_info() {
    python3 - "$1" "$PYLOCK" <<'PYEOF'
import io, re, sys, zipfile

apk, lockfile = sys.argv[1], sys.argv[2]

# The Python packages a built APK may carry (09 item 3, 01 Python and native
# components): the shim `neutrodyne_ytx` (the lockfile's kind="python" component)
# plus every package in the lock's `pip` list (M9a adds yt_dlp + yt_dlp_ejs).
allowed_pkgs = {"neutrodyne_ytx"}
lock = open(lockfile, encoding="utf-8").read()
m = re.search(r"(?ms)^pip\s*=\s*\[(.*?)\]", lock)
if m:
    for p in re.findall(r'"([^"]+)"', m.group(1)):
        allowed_pkgs.add(p.replace("-", "_"))
        allowed_pkgs.add(p)
BOOKKEEPING = re.compile(r"^([A-Za-z0-9_.]+\.dist-info|[A-Za-z0-9_.]+\.data|bin|__pycache__|.*\.pth)$")

# Gradle-shipped native libraries: Licensee-controlled classpath artifacts, named
# here so a new native dependency trips this check (09 deviations, 2026-10-06).
ALLOWED_LIB = re.compile(
    r"^(libpython3\.\d+\.so"          # CPython
    r"|libcrypto_(python|chaquopy)\.so"   # OpenSSL (Chaquopy target)
    r"|libssl_(python|chaquopy)\.so"
    r"|libsqlite3_(python|chaquopy)\.so"  # SQLite (Chaquopy target)
    r"|libchaquopy_java\.so"          # Chaquopy runtime
    r"|libsqliteJni\.so"              # androidx sqlite-bundled
    r"|libdatastore_shared_counter\.so"   # androidx datastore
    r"|libandroidx\.graphics\.path\.so"   # androidx graphics
    r"|libc\+\+_shared\.so"           # NDK libc++, when a dep ships it
    r"|libquickjs.*\.so)$")           # quickjs-kt, when the JS provider ships

PY_LIB_V7A = re.compile(r"(python|chaquopy|crypto|ssl)", re.I)
FORBIDDEN = re.compile(
    r"mutagen|org/schabi/newpipe|org/mozilla/javascript|(^|/)libreadline|(^|/)readline[./]",
    re.I)

z = zipfile.ZipFile(apk)
names = z.namelist()

lib_abis = sorted({n.split("/")[1] for n in names
                   if n.startswith("lib/") and len(n.split("/")) >= 3 and n.endswith(".so")})
if len(lib_abis) > 1:
    print("UNIVERSAL=1")
print("ABIS=" + ",".join(lib_abis))
if lib_abis:
    print("ABI=" + lib_abis[0])

engine_bytes = 0
for info in z.infolist():
    n = info.filename
    if n.startswith("assets/chaquopy/") or re.search(
            r"lib/[^/]+/(libpython|libchaquopy|libcrypto_|libssl_|libsqlite3_)", n):
        engine_bytes += info.compress_size

for info in z.infolist():
    n = info.filename
    if n.startswith("lib/") and n.endswith(".so") and info.compress_type != zipfile.ZIP_STORED:
        print("UNSTORED=" + n)

for n in names:
    if re.search(r"mutagen|org/schabi/newpipe|org/mozilla/javascript", n, re.I) or \
            re.search(r"(^|/)libreadline", n, re.I) or \
            (re.search(r"(^|/)readline[./]", n, re.I) and "_pyrepl/" not in n):
        print("FORBIDDEN=" + n)
    if n.endswith(".so") and n.startswith("lib/"):
        base = n.rsplit("/", 1)[-1]
        if not ALLOWED_LIB.match(base):
            print("BADLIB=" + n)
        abi = n.split("/")[1]
        if abi == "armeabi-v7a" and PY_LIB_V7A.search(base):
            print("V7A_PYLIB=" + n)
    if "version-control-info" in n:
        print("VCSINFO=" + n)
    if "assets/composeResources/" in n and re.search(r"values-(en|ar)-r?X[AB]", n):
        print("PSEUDO=" + n)
    if n.startswith("res/") and n.endswith(".xml"):
        print("LOCALECONFIG=" + n)

# Nested asset zips (Chaquopy .imy archives and any other packaged zip):
# forbidden names, ELF candidates and the Python top-level package rule.
for n in names:
    if not (n.startswith("assets/") and n.endswith((".imy", ".zip"))):
        continue
    try:
        inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
    except zipfile.BadZipFile:
        continue
    inames = inner.namelist()
    for i in inames:
        if FORBIDDEN.search(i) and "_pyrepl/" not in i:
            print(f"FORBIDDEN={n}!{i}")
        if i.endswith(".so"):
            print(f"ELFNESTED={n}!{i}")
    base = n.rsplit("/", 1)[-1]
    if base == "app.imy" or base.startswith("requirements-"):
        for i in inames:
            top = i.split("/")[0]
            if top and top not in allowed_pkgs and not BOOKKEEPING.match(top):
                print(f"BADPY={n}!{top}")

print(f"ENGINE_BYTES={engine_bytes}")
PYEOF
}

# check_alignment <apk> <apkname>: zipalign -P 16, then the ELF LOAD check on every
# .so — including those inside Chaquopy's asset zips, which zipalign never sees
# (Chaquopy extracts them at run time; 09 item 2).
check_alignment() {
    local apk="$1" apkname="$2"
    local zout
    if ! zout="$("$ZIPALIGN" -c -P 16 -v 4 "$apk" 2>&1)"; then
        fail "$apkname: zipalign 16 KB check failed: $(echo "$zout" | tail -5)"
        return
    fi
    info "$apkname: zipalign -c -P 16 OK"

    local dir="$ND_TMP/elf-$apkname"
    mkdir -p "$dir"
    local list="$dir/list.tsv"
    python3 - "$apk" "$dir" "$list" <<'PYEOF'
import io, os, sys, zipfile

apk, outdir, listfile = sys.argv[1], sys.argv[2], sys.argv[3]
z = zipfile.ZipFile(apk)
rows = []
count = 0
def emit(data, label):
    global count
    dest = os.path.join(outdir, f"e{count}.so")
    with open(dest, "wb") as f:
        f.write(data)
    rows.append(f"{dest}\t{label}")
    count += 1

for n in z.namelist():
    if n.endswith(".so"):
        emit(z.read(n), n)
    elif n.startswith("assets/") and n.endswith((".imy", ".zip")):
        try:
            inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
        except zipfile.BadZipFile:
            continue
        for i in inner.namelist():
            if i.endswith(".so"):
                emit(inner.read(i), f"{n}!{i}")
with open(listfile, "w") as f:
    # one record per line, final line included — `while read` below drops an
    # unterminated last record, which would exempt the last library entirely
    f.write("".join(f"{row}\n" for row in rows))
PYEOF
    local bad=0 f orig out line
    while IFS=$'\t' read -r f orig; do
        [ -n "$f" ] || continue
        out="$(check_elf "$f")"
        [ -z "$out" ] && continue
        while IFS= read -r line; do
            fail "$apkname: $orig: ELF ${line#* "$f" }"
        done <<< "$out"
        bad=1
    done < "$list"
    [ "$bad" -eq 0 ] && info "$apkname: every ELF LOAD segment aligned >= 16 KB"
}

manifest_report() {
    # manifest_report <apk> <apkname> — aapt2 badging + xmltree facts.
    local apk="$1" apkname="$2" badging xml
    badging="$($AAPT2 dump badging "$apk")"
    xml="$($AAPT2 dump xmltree --file AndroidManifest.xml "$apk")"

    if ! grep -q "package: name='ch.lkmc.neutrodyne'" <<< "$badging"; then
        fail "$apkname: package name is not ch.lkmc.neutrodyne: $(grep -m1 'package:' <<< "$badging")"
    fi
    if ! grep -q "versionCode='$VERSION_CODE'" <<< "$badging"; then
        fail "$apkname: versionCode != gradle.properties $VERSION_CODE: $(grep -m1 'package:' <<< "$badging")"
    fi
    if ! grep -q "versionName='$VERSION_NAME'" <<< "$badging"; then
        fail "$apkname: versionName != gradle.properties $VERSION_NAME: $(grep -m1 'package:' <<< "$badging")"
    fi
    if grep -q 'application-debuggable\|testOnly' <<< "$badging"; then
        fail "$apkname: debuggable or testOnly flag set: $(grep -m1 'application' <<< "$badging")"
    fi
    if grep -q 'android:debuggable\|android:testOnly' <<< "$xml"; then
        fail "$apkname: manifest sets android:debuggable or android:testOnly"
    fi
    if grep -Eq "$DEBUG_MANIFEST_RE" <<< "$xml"; then
        fail "$apkname: debug-code activity in the merged manifest: $(grep -E "$DEBUG_MANIFEST_RE" <<< "$xml" | head -3)"
    fi
    if [ "$MODE" = no-engine ]; then
        if grep -q 'YtxService' <<< "$xml"; then
            fail "$apkname: no-engine build still declares YtxService"
        fi
    elif ! grep -q 'YtxService' <<< "$xml"; then
        fail "$apkname: engine build without the YtxService declaration (01 S7)"
    fi
}

signer_report() {
    local apk="$1" apkname="$2" out digest expected
    out="$("$APKSIGNER" verify --verbose --print-certs --min-sdk-version 26 "$apk")"
    grep -q '^Verifies$' <<< "$out" || fail "$apkname: apksigner did not verify"
    grep -q 'v1 scheme (JAR signing): false' <<< "$out" || fail "$apkname: v1 signing is on"
    grep -q 'v2 scheme (APK Signature Scheme v2): true' <<< "$out" || fail "$apkname: v2 signing missing"
    grep -q 'v3 scheme (APK Signature Scheme v3): true' <<< "$out" || fail "$apkname: v3 signing missing"
    grep -q 'Number of signers: 1' <<< "$out" || fail "$apkname: expected exactly one signer"
    digest="$(grep -m1 'Signer #1 certificate SHA-256 digest:' <<< "$out" | awk '{print $NF}')"
    expected="$("$SCRIPT_DIR/public-cert-sha256.sh")"
    if [ "$digest" != "$expected" ]; then
        fail "$apkname: signer SHA-256 $digest != committed certificate $expected"
    fi
}

locale_report() {
    local apk="$1" apkname="$2" res cfg got want
    # The generated locale-config file name is build-tool dependent (seen obfuscated
    # as res/Ed.xml); find it by scanning res XMLs for the locale-config element.
    while IFS= read -r res; do
        if grep -q 'E: locale-config' <<< "$($AAPT2 dump xmltree --file "$res" "$apk" 2>/dev/null)"; then
            got="$($AAPT2 dump xmltree --file "$res" "$apk" | grep -o 'android:name.*Raw: "[^"]*"' | sed 's/.*Raw: "//;s/"//' | sort -u)"
            want="$(grep -vE '^\s*(#|$)' "$LOCALES_FILE" | sort -u)"
            if [ "$got" != "$want" ]; then
                fail "$apkname: localeConfig locales [$(echo "$got" | tr '\n' ' ')] != locales.txt [$(echo "$want" | tr '\n' ' ')]"
            fi
        fi
    done < <(python3 - "$apk" <<'PYEOF'
import sys, zipfile
for n in zipfile.ZipFile(sys.argv[1]).namelist():
    if n.startswith("res/") and n.endswith(".xml"):
        print(n)
PYEOF
)
    cfg="$($AAPT2 dump configurations "$apk" 2>/dev/null || true)"
    if grep -qE 'en-rXA|ar-rXB' <<< "$cfg"; then
        fail "$apkname: pseudo-locale resource configuration: $(grep -E 'en-rXA|ar-rXB' <<< "$cfg" | head -3)"
    fi
    if grep -qE 'application-label-(en|ar)-(r)?X[AB]' <<< "$($AAPT2 dump badging "$apk")"; then
        fail "$apkname: pseudo-locale application label present"
    fi
}

# --- run ----------------------------------------------------------------------

size_budget() {
    case "$1" in
        arm64-v8a|x86_64) echo 41943040 ;;   # 40 MB, PB12
        armeabi-v7a)     echo 31457280 ;;    # 30 MB, PB13
        *)               echo 0 ;;
    esac
}

declare -A SEEN_ABI
for apk in "${APKS[@]}"; do
    apkname="$(basename "$apk")"
    [ -f "$apk" ] || { fail "$apkname: file not found"; continue; }
    info "checking $apkname"

    facts="$(zip_info "$apk")"
    abi="$(grep -m1 '^ABI=' <<< "$facts" | cut -d= -f2-)"
    # fall back to the file name when the APK carries no lib/ entries
    if [ -z "$abi" ]; then
        for a in "${PUBLISHED_ABIS[@]}"; do
            case "$apkname" in *"-$a.apk"|*"-$a-release.apk"|*"-$a-debug.apk") abi="$a" ;; esac
        done
    fi
    if [ -z "$abi" ]; then
        fail "$apkname: cannot determine ABI (no lib/ entries, no -<abi>.apk name)"
    elif ! grep -qx "$abi" <<< "$(printf '%s\n' "${PUBLISHED_ABIS[@]}")"; then
        fail "$apkname: ABI '$abi' is not a published ABI"
    else
        SEEN_ABI[$abi]=1
    fi
    grep -q '^UNIVERSAL=1' <<< "$facts" && fail "$apkname: carries multiple ABIs (universal APKs are never shipped)"

    # section 2: 16 KB alignment — always on (also the whole of --alignment-only)
    check_alignment "$apk" "$apkname"
    [ "$MODE" = alignment ] && continue

    # section 1: per-ABI size budgets, plus the engine share for the summary
    size="$(stat -c %s "$apk")"
    budget="$(size_budget "$abi")"
    engine="$(grep -m1 '^ENGINE_BYTES=' <<< "$facts" | cut -d= -f2-)"
    info "$apkname: size $size B (budget $budget B), engine share ${engine:-0} B"
    [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "| $apkname | $size | $budget | ${engine:-0} |" >> "$GITHUB_STEP_SUMMARY" || true
    if [ "$budget" -gt 0 ] && [ "$size" -ge "$budget" ]; then
        fail "$apkname: $size B over the $budget B budget"
    fi

    # section 3: content
    while IFS= read -r line; do
        case "$line" in
            FORBIDDEN=*) fail "$apkname: forbidden file ${line#FORBIDDEN=}" ;;
            BADLIB=*)    fail "$apkname: native library outside the allow-list: ${line#BADLIB=}" ;;
            BADPY=*)     fail "$apkname: Python package outside the lockfile list: ${line#BADPY=}" ;;
            V7A_PYLIB=*) fail "$apkname: Python/Chaquopy native library in armeabi-v7a: ${line#V7A_PYLIB=}" ;;
            UNSTORED=*)  fail "$apkname: native library stored compressed (16 KB needs STORED): ${line#UNSTORED=}" ;;
            VCSINFO=*)   fail "$apkname: ${line#VCSINFO=} must not be shipped (vcsInfo off, 09 Hygiene)" ;;
            PSEUDO=*)    fail "$apkname: pseudo-locale Compose resource: ${line#PSEUDO=}" ;;
        esac
    done <<< "$facts"

    if [ "$abi" = "armeabi-v7a" ]; then
        vb="$(python3 - "$apk" <<'PYEOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
print(sum(i.compress_size for i in z.infolist() if i.filename.startswith("assets/chaquopy/")))
PYEOF
)"
        warn "$apkname: $vb B of unusable Chaquopy/Python assets (tolerated only while PB13 holds; 01 open question 13)"
    fi

    # dex descriptor scan (obfuscation off in the published build, 09 item 3);
    # engine presence is judged across all dexes, not per dex file
    engine_found=0
    while IFS= read -r dex; do
        dexname="$(basename "$dex")"
        dout="$("$DEXDUMP" "$dex" 2>/dev/null || true)"
        if grep -Eq "$FORBIDDEN_RE" <<< "$dout"; then
            fail "$apkname: forbidden class in $dexname: $(grep -oE "L[^;']*(mutagen|readline|newpipe|mozilla)[^;']*" <<< "$dout" | head -3)"
        fi
        if grep -Eq "$DEBUG_DEX_RE" <<< "$dout"; then
            fail "$apkname: debug-code class in $dexname: $(grep -oE "L[^;']*(leakcanary|shark|PreviewActivity|BenchmarkSeedReceiver)[^;']*" <<< "$dout" | head -3)"
        fi
        # classes compiled from app/src/debug or app/src/benchmarkRelease
        while IFS= read -r src; do
            pkg="$(grep -m1 '^package ' "$src" | sed 's/package //;s/[[:space:]]//g')"
            base="$(basename "$src" .kt)"
            if [ -z "$pkg" ]; then continue; fi
            desc="L${pkg//.//}/$base"
            if grep -q "$desc" <<< "$dout"; then
                fail "$apkname: class from a non-published source set in $dexname: $desc ($src)"
            fi
        done < <(find "$REPO_ROOT/app/src/debug" "$REPO_ROOT/app/src/benchmarkRelease" -name '*.kt' 2>/dev/null)
        grep -q 'Lch/lkmc/neutrodyne/youtube/ytdlp/' <<< "$dout" && engine_found=1
    done < <(cd "$ND_TMP" && python3 - "$apk" <<'PYEOF'
import sys, zipfile, os
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if n.startswith("classes") and n.endswith(".dex"):
        with open(n.replace("/", "_"), "wb") as f:
            f.write(z.read(n))
        print(os.path.abspath(n.replace("/", "_")))
PYEOF
)

    # engine presence: the ytdlp package across all dexes (04; YtxService keeps
    # its name as a manifest component even with final obfuscation, but the
    # package assertion is the stronger pre-obfuscation signal — a 64-bit APK
    # without any :youtube:ytdlp class means the engine was stripped)
    if [ "$MODE" = no-engine ]; then
        [ "$engine_found" -eq 0 ] || fail "$apkname: no-engine build still contains :youtube:ytdlp classes"
    elif [ -n "$abi" ] && [ "$abi" != "armeabi-v7a" ]; then
        [ "$engine_found" -eq 1 ] || fail "$apkname: engine classes missing from a 64-bit APK"
    fi

    # section 5: locale config
    [ -f "$LOCALES_FILE" ] || { fail "$apkname: $LOCALES_FILE missing (09-owned locale list)"; }
    [ -f "$LOCALES_FILE" ] && locale_report "$apk" "$apkname"

    # sections 4 and 6: published-build facts and signer
    if [ "$MODE" = published ] || [ "$MODE" = no-engine ]; then
        manifest_report "$apk" "$apkname"
        signer_report "$apk" "$apkname"
    fi
done

# the published set: exactly the three ABIs, never a universal APK
if [ "$MODE" != alignment ] && [ "${#APKS[@]}" -gt 1 ]; then
    for a in "${PUBLISHED_ABIS[@]}"; do
        [ "${SEEN_ABI[$a]:-}" ] || fail "set: published APK for $a missing"
    done
    if [ "${#SEEN_ABI[@]}" -ne "${#PUBLISHED_ABIS[@]}" ]; then
        fail "set: expected exactly ${PUBLISHED_ABIS[*]}, got ${!SEEN_ABI[*]}"
    fi
fi

echo
if [ "${#FAILURES[@]}" -gt 0 ]; then
    echo "check-apk: ${#FAILURES[@]} violation(s)"
    exit 1
fi
echo "check-apk: PASS (${#APKS[@]} APK(s), mode $MODE)"
