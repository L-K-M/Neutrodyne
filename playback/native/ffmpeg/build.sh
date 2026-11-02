#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# build.sh <target> — the minimal FFmpeg build of :playback:native (11 FFmpeg build,
# docs/design/11-desktop.md).
#
# One script, four targets, run by the `buildFfmpeg` task on each target's own
# runner (jpackage cannot cross-package and neither can this):
#
#   windows-x64   MSYS2 bash with MSVC (cl.exe on PATH; build.sh sets it up from
#                 vcvars64.bat when it is missing) and NASM
#   macos-arm64   Apple clang, macOS 13 deployment target, @rpath install names
#   linux-x64     gcc inside the manylinux_2_28 container (glibc 2.28 baseline)
#   linux-arm64   the same container image for aarch64
#
# Everything the build needs is downloaded and checksum-verified from
# native-components.lock: the FFmpeg source tarball with its upstream signature
# and miniaudio's single header. Nothing is vendored into the repository.
#
# Outputs, under playback/native/build/ffmpeg/<target>/:
#   lib/            the four shared libraries with their upstream names
#   include/        the headers ffoffsets.c was compiled against
#   ffmpeg-layout.json   struct offsets, sizes and constants (ffoffsets.c)
#   ffmpeg-license.txt   avcodec_license() of the built library
#   ffmpeg-protocols.txt every protocol the build registered (must be empty)
#   ffmpeg-components.txt  the enabled demuxer/decoder/parser/bsf inventory
#   ffmpeg-sizes.txt       the four libraries' installed sizes in bytes
#   ffmpeg-build.txt       the configure line, toolchain and versions used

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
SCRIPT_PATH="$SCRIPT_DIR/build.sh"
NATIVE_DIR="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd -P)"
# The lock names its files the way the repository does, so the signing key is
# resolved from the repository root, not from playback/native.
REPO_ROOT="$(cd -- "$NATIVE_DIR/../.." >/dev/null 2>&1 && pwd -P)"
LOCK="$NATIVE_DIR/native-components.lock"
CACHE="$NATIVE_DIR/build/ffmpeg-cache"

TARGET="${1:-}"
case "$TARGET" in
    windows-x64 | macos-arm64 | linux-x64 | linux-arm64) ;;
    *) echo "build.sh: usage: build.sh {windows-x64|macos-arm64|linux-x64|linux-arm64}" >&2; exit 2 ;;
esac

log() { echo "build.sh[$TARGET]: $*"; }
die() { echo "build.sh[$TARGET]: $*" >&2; exit 1; }

# ---------------------------------------------------------------- lock reading

lock_value() { # lock_value <section> <key>
    # `index` rather than a regex with `\[`: some awks (MSYS2's) reject the
    # escape and read `[ffmpeg]` as a character class, which matches no section.
    awk -v section="$1" -v key="$2" '
        /^[[:space:]]*#/ { next }
        /^[[:space:]]*\[/ { in_section = (index($0, "[" section "]") > 0); next }
        in_section && $1 == key {
            sub(/^[^=]*=[[:space:]]*"/, ""); sub(/"[[:space:]]*$/, ""); print; exit
        }
    ' "$LOCK"
}

FFMPEG_VERSION="$(lock_value ffmpeg version)"
SOURCE_URL="$(lock_value ffmpeg sourceUrl)"
SOURCE_URL_FALLBACK="$(lock_value ffmpeg sourceUrlFallback)"
SOURCE_SHA256="$(lock_value ffmpeg sha256)"
SIGNATURE_URL="$(lock_value ffmpeg signatureUrl)"
SIGNATURE_URL_FALLBACK="$(lock_value ffmpeg signatureUrlFallback)"
SIGNATURE_SHA256="$(lock_value ffmpeg signatureSha256)"
SIGNING_KEY="$(lock_value ffmpeg signingKey)"
SIGNING_KEY_FINGERPRINT="$(lock_value ffmpeg signingKeyFingerprint)"

[ -n "$FFMPEG_VERSION" ] || die "native-components.lock has no [ffmpeg] version"
[ -n "$SOURCE_URL" ] || die "native-components.lock has no [ffmpeg] sourceUrl"
[ -n "$SOURCE_SHA256" ] || die "native-components.lock has no [ffmpeg] sha256"

SOURCE_TARBALL="$CACHE/ffmpeg-$FFMPEG_VERSION.tar.xz"
SOURCE_SIGNATURE="$SOURCE_TARBALL.asc"
SOURCE_DIR="$CACHE/ffmpeg-$FFMPEG_VERSION"
OUT="$NATIVE_DIR/build/ffmpeg/$TARGET"
BUILD="$CACHE/build-$TARGET"
mkdir -p "$CACHE"

# ------------------------------------------------------------------- downloads

sha256_of() { # sha256_of <file>
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
    fi
}

verify_checksum() { # verify_checksum <file> <expected>
    local actual
    actual="$(sha256_of "$1")"
    [ "$actual" = "$2" ] || die "checksum mismatch for $1: expected $2, got $actual"
}

# bounded <seconds> <command...> — a hard bound on every network and gpg call
# so a stalling download or a hanging gpg fails the build with a message
# instead of sitting silent until the job's timeout. Both Windows legs that
# died in silence (runs 41479793169 and 41500707088) produced no output for
# the rest of the job somewhere in the download → gpg window. GNU timeout
# (coreutils on MSYS2 and manylinux); macOS uses Perl's alarm before exec,
# avoiding a watchdog child that keeps Gradle's output pipe open after failure.
if command -v timeout >/dev/null 2>&1 && timeout --version >/dev/null 2>&1; then
    bounded() { timeout --kill-after=5 "$1" "${@:2}"; }
else
    bounded() { perl -e 'alarm shift; exec @ARGV or die $!;' "$@"; }
fi

download() { # download <file> <url> [fallback-url]
    if [ -s "$1" ]; then
        log "reusing $1"
        return 0
    fi
    log "downloading $2"
    # A dying ffmpeg.org accepts connections and then trickles nothing, which
    # curl's own retry loop would sit through for hours. --speed-limit aborts a
    # stalled transfer within the stall window, and the outer watchdog bounds
    # the whole call so the lock's fallback stays reachable well inside the
    # job's timeout.
    if
        bounded 600 curl -fsSL --retry 1 --connect-timeout 20 --max-time 480 \
            --speed-limit 1024 --speed-time 60 -o "$1" "$2"
    then
        return 0
    fi
    [ -n "${3:-}" ] || die "download failed: $2"
    # ffmpeg.org serves from a single host with no official mirror; the lock's
    # fallback serves identical bytes, which verify_checksum pins exactly.
    log "download failed, trying the lock's fallback $3"
    bounded 600 curl -fsSL --retry 1 --connect-timeout 20 --max-time 480 \
        --speed-limit 1024 --speed-time 60 -o "$1" "$3" ||
        die "download failed: $2 and $3"
}

verify_signature() {
    command -v gpg >/dev/null 2>&1 || die "gpg is required to verify the FFmpeg source signature"
    command -v gpgv >/dev/null 2>&1 || die "gpgv is required to verify the FFmpeg source signature"
    # Verification needs no secret keys or agent. Importing a public key starts
    # gpg-agent on some runners, whose Unix socket cannot fit a long checkout
    # path on macOS and whose MSYS2 startup can hang on Windows.
    local home keyring fingerprint
    home="$CACHE/gnupg-home"
    mkdir -p "$home"
    chmod 700 "$home"
    keyring="$home/ffmpeg-release.gpg"
    GNUPGHOME="$home" bounded 120 gpg --no-options --batch --yes --no-autostart \
        --dearmor --output "$keyring" "$NATIVE_DIR/${SIGNING_KEY#playback/native/}" ||
        die "could not dearmor the FFmpeg release signing key"
    fingerprint="$(GNUPGHOME="$home" bounded 120 gpg --no-options --batch --no-autostart \
        --with-colons --show-keys "$keyring" | awk -F: '$1 == "fpr" { print $10; exit }')" ||
        die "could not read the FFmpeg signing key's fingerprint"
    [ "$fingerprint" = "$SIGNING_KEY_FINGERPRINT" ] ||
        die "the FFmpeg signing key is $fingerprint, expected $SIGNING_KEY_FINGERPRINT"
    GNUPGHOME="$home" bounded 120 gpgv --keyring "$keyring" "$SOURCE_SIGNATURE" "$SOURCE_TARBALL" ||
        die "the FFmpeg source signature did not verify"

    log "verified the FFmpeg source signature with $SIGNING_KEY_FINGERPRINT"
}

# Download and extract inside the Linux target container too, so every build
# verifies its cached archive rather than trusting a previously extracted tree.
case "$TARGET" in
    linux-x64 | linux-arm64)
        if [ "${MANYLINUX_CONTAINER:-}" != "1" ]; then
            command -v docker >/dev/null 2>&1 ||
                die "docker is required for the Linux FFmpeg build; run inside the manylinux_2_28 container instead"
            IMAGE="quay.io/pypa/manylinux_2_28_$([ "$TARGET" = linux-x64 ] && echo x86_64 || echo aarch64)"
            log "re-running inside $IMAGE"
            docker run --rm \
                -e MANYLINUX_CONTAINER=1 \
                -v "$NATIVE_DIR:/native" \
                -w /native \
                "$IMAGE" \
                bash ffmpeg/build.sh "$TARGET" || die "the container build failed"
            exit 0
        fi
        ;;
esac

download "$SOURCE_TARBALL" "$SOURCE_URL" "$SOURCE_URL_FALLBACK"
verify_checksum "$SOURCE_TARBALL" "$SOURCE_SHA256"
log "checksum verified: $(basename "$SOURCE_TARBALL")"
if [ -n "$SIGNATURE_URL" ]; then
    download "$SOURCE_SIGNATURE" "$SIGNATURE_URL" "$SIGNATURE_URL_FALLBACK"
    if [ -n "$SIGNATURE_SHA256" ]; then
        verify_checksum "$SOURCE_SIGNATURE" "$SIGNATURE_SHA256"
        log "checksum verified: $(basename "$SOURCE_SIGNATURE")"
    fi
    verify_signature
fi
log "extracting FFmpeg $FFMPEG_VERSION"
rm -rf "$SOURCE_DIR" "$SOURCE_DIR.tmp"
mkdir -p "$SOURCE_DIR.tmp"
tar -xJf "$SOURCE_TARBALL" -C "$SOURCE_DIR.tmp" --strip-components=1
mv "$SOURCE_DIR.tmp" "$SOURCE_DIR"

# ------------------------------------------------------------------- toolchain

# Where the target's shared libraries land. FFmpeg's configure puts a Windows
# build's DLLs in $prefix/bin and every other target's in $prefix/lib
# (configure: `win32|win64) ... shlibdir_default="$bindir_default"`), so the
# probes and the size inventory have to look in the target's own directory.
LIBDIR="$OUT/lib"

# The shared configure line of 11 FFmpeg build: four libraries, no programs, no
# network, no autodetection, and only the demuxers, decoders, parsers and the one
# bitstream filter D86 names. No --enable-gpl, --enable-version3 or
# --enable-nonfree ever appears here.
COMMON_CONFIGURE=(
    --prefix="$OUT"
    --disable-everything
    --disable-programs
    --disable-doc
    --disable-network
    --disable-autodetect
    --enable-shared
    --disable-static
    --disable-avdevice
    --disable-avfilter
    --disable-swscale
    --enable-swresample
    --enable-demuxer=mov,matroska,ogg,mp3,flac,wav,aac
    --enable-decoder=aac,aac_fixed,mp3float,mp3,opus,vorbis,flac,alac,pcm_s16le,pcm_s24le,pcm_f32le,pcm_u8
    --enable-parser=aac,mpegaudio,opus,vorbis,flac
    --enable-bsf=aac_adtstoasc
)

# FFmpeg's documented MSVC environment is an MSYS2 shell with GNU make, NASM and
# gpg (doc/platform.texi, "Microsoft Visual C++ ... for Windows"). Gradle invokes
# whichever bash it finds first, which on a Windows runner is Git Bash and has
# none of the three, so the script re-execs itself under MSYS2 when it can. A
# developer without MSYS2 keeps their own bash and must provide the tools.
# `uname -s` is MINGW* under Git Bash and MSYS* under MSYS2 itself, which is how
# the two are told apart.
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*)
        for candidate in /c/msys64/usr/bin/bash.exe \
            "/c/Program Files/MSYS2/usr/bin/bash.exe" \
            "${MSYS2_ROOT:+$MSYS2_ROOT/usr/bin/bash.exe}"; do
            if [ -n "$candidate" ] && [ -x "$candidate" ]; then
                log "re-running under $candidate (FFmpeg's documented MSVC shell)"
                MSYS_IS_REEXEC=1 exec "$candidate" -euo pipefail "$SCRIPT_PATH" "$@"
            fi
        done
        log "warning: MSYS2 not found; make, nasm and gpg must already be on PATH"
        ;;
esac

case "$TARGET" in
    windows-x64)
        LIBDIR="$OUT/bin"
        # MSVC from the runner's Visual Studio, driven through the MSYS2 shell.
        if ! command -v cl >/dev/null 2>&1; then
            VSWHERE="${PROGRAMFILES:-/c/Program Files} (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
            [ -x "$VSWHERE" ] || VSWHERE="/c/Program Files/Microsoft Visual Studio/Installer/vswhere.exe"
            [ -x "$VSWHERE" ] || die "vswhere.exe not found: install the Visual Studio build tools"
            VS_PATH="$("$VSWHERE" -latest -products '*' \
                -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 \
                -property installationPath | tr -d '\r')"
            [ -n "$VS_PATH" ] || die "vswhere found no MSVC x64 toolchain"
            VCVARS="$VS_PATH/VC/Auxiliary/Build/vcvars64.bat"
            [ -f "$VCVARS" ] || die "vcvars64.bat not found at $VCVARS"
            log "loading the MSVC x64 environment from vcvars64.bat"
            # The wrapper .bat and its env dump live in the build tree, and cmd
            # runs the .bat by bare name from its own directory: no temp path
            # crosses the MSYS2/cmd boundary. (The earlier mktemp-based .bat
            # reached cmd as a path it could not find, because the runner's
            # TMPDIR form is not under MSYS2's control; a quoted vcvars path
            # reached it as a literal command name for the same reason.)
            VCVARS_WORK="$CACHE/vcvars-$TARGET"
            mkdir -p "$VCVARS_WORK"
            printf '@set "OLDCD=%%CD%%"\r\ncall "%s" >vcvars.log 2>&1\r\n@if errorlevel 1 exit /b 1\r\n@cd /d "%%OLDCD%%"\r\nset > "env.txt"\r\n' \
                "$(cygpath -w "$VCVARS")" > "$VCVARS_WORK/vcvars-env.bat"
            (cd "$VCVARS_WORK" && cmd //c vcvars-env.bat) ||
                { cat "$VCVARS_WORK/vcvars.log"; die "vcvars64.bat failed"; }
            rm -f "$VCVARS_WORK/vcvars-env.bat"
            ENV_FILE="$VCVARS_WORK/env.txt"
            [ -s "$ENV_FILE" ] || die "vcvars64.bat produced no environment"
            # cmd's `set` writes CRLF lines and names such as
            # CommonProgramFiles(x86) that bash cannot export (the first
            # Windows leg died exactly there). Such names are skipped —
            # bash never needs them, and native tools get their own values
            # through the environment cmd already set — and the CR is
            # stripped so no value ends in one.
            while IFS= read -r line; do
                line="${line%$'\r'}"
                case "$line" in
                    [A-Za-z_]*=*) ;;
                    *) continue ;;
                esac
                env_name="${line%%=*}"
                case "$env_name" in
                    *[!A-Za-z0-9_]*) continue ;;
                esac
                case "$env_name" in
                    [Pp][Aa][Tt][Hh]) export PATH="$(cygpath -up "${line#*=}")" ;;
                    *) export "$env_name=${line#*=}" ;;
                esac
            done < "$ENV_FILE"
            command -v cl >/dev/null 2>&1 || die "cl is still not on PATH after vcvars64.bat"
        fi
        command -v nasm >/dev/null 2>&1 || log "warning: NASM is not on PATH; x86 assembly is disabled"
        TARGET_FLAGS=(--toolchain=msvc --target-os=win64 --arch=x86_64)
        MAKE=(make)
        ;;
    macos-arm64)
        # Apple clang; the deployment target keeps the app on D88's macOS 13 floor.
        TARGET_FLAGS=(
            --arch=arm64
            --install_name_dir=@rpath
            --extra-cflags=-mmacosx-version-min=13.0
            --extra-ldflags=-mmacosx-version-min=13.0
            # Every dylib carries an rpath of its own directory, so a library
            # loaded by absolute path still finds its FFmpeg siblings.
            --extra-ldflags=-Wl,-rpath,@loader_path
        )
        MAKE=(make)
        ;;
    linux-x64 | linux-arm64)
        # The build runs inside the manylinux_2_28 container (glibc 2.28, below
        # D88's 2.31 floor). build.sh re-execs itself in the container when it is
        # not already inside one, so `buildFfmpeg` needs no container knowledge.
        if ! command -v nasm >/dev/null 2>&1; then
            log "installing NASM into the container"
            (microdnf install -y nasm || dnf install -y nasm || yum install -y nasm) >/dev/null 2>&1 ||
                log "warning: could not install NASM; x86 assembly is disabled"
        fi
        TARGET_FLAGS=(
            --cc=gcc
            # Every shared library carries an rpath of its own directory.
            # Make collapses $$ to $ in the recipe and the recipe's shell then
            # needs \$ to keep the dollar literal, so configure must receive
            # \$$ORIGIN for RUNPATH to hold $ORIGIN.
            --extra-ldflags=-Wl,-rpath,'\$$ORIGIN'
        )
        MAKE=(make)
        ;;
esac

CONFIGURE_LINE="${COMMON_CONFIGURE[*]} ${TARGET_FLAGS[*]}"
log "configure: $CONFIGURE_LINE"

# ---------------------------------------------------------------------- build

rm -rf "$BUILD"
mkdir -p "$BUILD"
cd "$SOURCE_DIR"
make distclean >/dev/null 2>&1 || true

./configure "${COMMON_CONFIGURE[@]}" "${TARGET_FLAGS[@]}" >"$BUILD/configure.log" 2>&1 ||
    { tail -40 "$BUILD/configure.log"; die "configure failed (full log in $BUILD/configure.log)"; }

log "building (this takes a few minutes)"
"${MAKE[@]}" -j"$(nproc 2>/dev/null || echo 4)" >"$BUILD/make.log" 2>&1 ||
    { tail -40 "$BUILD/make.log"; die "make failed (full log in $BUILD/make.log)"; }
"${MAKE[@]}" install >"$BUILD/install.log" 2>&1 ||
    { tail -40 "$BUILD/install.log"; die "make install failed (full log in $BUILD/install.log)"; }

# The libraries' RUNPATH must hold $ORIGIN so each one finds its FFmpeg
# siblings in its own directory. The configure flag that asks for it crosses
# one eval in configure and one expansion in make, so what the linker actually
# recorded is verified here and repaired with patchelf when it is wrong.
case "$TARGET" in
    linux-x64 | linux-arm64)
        if ! command -v patchelf >/dev/null 2>&1; then
            log "installing patchelf for the RUNPATH check"
            (microdnf install -y patchelf || dnf install -y patchelf || yum install -y patchelf) >/dev/null 2>&1 || true
        fi
        for lib in "$LIBDIR"/*.so.*; do
            [ -f "$lib" ] || continue
            if command -v patchelf >/dev/null 2>&1; then
                patchelf --set-rpath '$ORIGIN' "$lib"
            fi
            log "$(basename "$lib"): $(readelf -d "$lib" | grep -E 'NEEDED|RPATH|RUNPATH' | tr '\n' ';')"
        done
        ;;
    macos-arm64)
        for lib in "$LIBDIR"/*.dylib; do
            [ -f "$lib" ] || continue
            log "$(basename "$lib"): $(otool -l "$lib" | grep -A2 -E 'LC_RPATH|LC_LOAD_DYLIB' | grep -E 'name|path' | tr '\n' ';')"
        done
        ;;
esac

# ------------------------------------------------------------------ artefacts

# The layout probe: compiled against the very headers this build installed, and
# handed the point release from the lock as its first argument, so the layout
# records the exact source it came from (11 FFM bindings).
log "generating ffmpeg-layout.json"
CC_BIN="${CC:-cc}"
case "$TARGET" in
    windows-x64) CC_BIN="cl" ;;
    *) command -v gcc >/dev/null 2>&1 && CC_BIN="gcc" || CC_BIN="cc" ;;
esac
if [ "$CC_BIN" = "cl" ]; then
    cl -nologo -O2 -I"$(cygpath -w "$OUT/include")" \
        "$(cygpath -w "$SCRIPT_DIR/ffoffsets.c")" \
        -Fe"$(cygpath -w "$BUILD/ffoffsets.exe")" -Fo"$(cygpath -w "$BUILD/ffoffsets.obj")" \
        >"$BUILD/ffoffsets-build.log" 2>&1 ||
        { cat "$BUILD/ffoffsets-build.log"; die "ffoffsets.c did not compile"; }
    (cd "$LIBDIR" && "$(cygpath -u "$BUILD")/ffoffsets.exe" "$FFMPEG_VERSION" \
        >"$(cygpath -u "$OUT")/ffmpeg-layout.json")
else
    "$CC_BIN" -O2 -I"$OUT/include" "$SCRIPT_DIR/ffoffsets.c" -o "$BUILD/ffoffsets" ||
        die "ffoffsets.c did not compile"
    (cd "$LIBDIR" && "$BUILD/ffoffsets" "$FFMPEG_VERSION" >"$OUT/ffmpeg-layout.json")
fi
# The Windows runner reaches python as `python`, MSYS2 as `python3`.
PYTHON="$(command -v python3 || command -v python || true)"
[ -n "$PYTHON" ] || die "python3 is required to validate ffmpeg-layout.json"
"$PYTHON" - "$OUT/ffmpeg-layout.json" <<'PY' || die "ffmpeg-layout.json is not valid JSON"
import json, sys
json.load(open(sys.argv[1]))
PY

# The runtime probe: the licence string, every registered protocol and the four
# library versions, read from the libraries this build produced.
log "running the licence and protocol probe"
cat > "$BUILD/probe.c" <<'PROBE'
#include <stdio.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libswresample/swresample.h>

int main(void)
{
    const char *name;
    void *opaque = NULL;
    printf("license %s\n", avcodec_license());
    printf("version avcodec %d avformat %d avutil %d swresample %d\n",
           LIBAVCODEC_VERSION_MAJOR, LIBAVFORMAT_VERSION_MAJOR,
           LIBAVUTIL_VERSION_MAJOR, LIBSWRESAMPLE_VERSION_MAJOR);
    printf("protocols");
    while ((name = avio_enum_protocols(&opaque, 0)))
        printf(" %s", name);
    while ((name = avio_enum_protocols(&opaque, 1)))
        printf(" %s", name);
    printf("\n");
    return 0;
}
PROBE
# The probe links against the four libraries this build installed, through the
# linker's own search path: their file names carry the major version, which
# differs per FFmpeg release, so `-l` with the library's base name is what stays
# stable. FFmpeg installs the unversioned symlink the linker looks for next to
# each versioned library.
if [ "$CC_BIN" = "cl" ]; then
    # MSVC takes the import libraries, which a Windows build installs in $OUT/lib.
    cl -nologo -O2 -I"$(cygpath -w "$OUT/include")" "$(cygpath -w "$BUILD/probe.c")" \
        "$(cygpath -w "$OUT/lib/avcodec.lib")" "$(cygpath -w "$OUT/lib/avformat.lib")" \
        "$(cygpath -w "$OUT/lib/avutil.lib")" "$(cygpath -w "$OUT/lib/swresample.lib")" \
        -Fe"$(cygpath -w "$BUILD/probe.exe")" -Fo"$(cygpath -w "$BUILD/probe.obj")" \
        >"$BUILD/probe-build.log" 2>&1 ||
        { cat "$BUILD/probe-build.log"; die "probe.c did not compile"; }
    PROBE_OUT="$(cd "$LIBDIR" && "$(cygpath -u "$BUILD")/probe.exe")" || die "probe.exe failed"
else
    log "linking the probe against $LIBDIR"
    set +e
    "$CC_BIN" -O2 -I"$OUT/include" "$BUILD/probe.c" -o "$BUILD/probe" \
        -L"$LIBDIR" -Wl,-rpath,"$LIBDIR" \
        -lavcodec -lavformat -lavutil -lswresample >"$BUILD/probe-build.log" 2>&1
    PROBE_STATUS=$?
    set -e
    log "the probe linked with status $PROBE_STATUS"
    if [ "$PROBE_STATUS" -ne 0 ]; then
        cat "$BUILD/probe-build.log"
        die "probe.c did not compile"
    fi
    PROBE_OUT="$(cd "$LIBDIR" && LD_LIBRARY_PATH="$LIBDIR" "$BUILD/probe")" ||
        die "probe failed (status $?)"
    log "the probe reported: $(printf '%s' "$PROBE_OUT" | tr '\n' '|')"
fi
PROBE_LICENSE="$(printf '%s\n' "$PROBE_OUT" | sed -n 's/^license //p')"
PROBE_VERSION="$(printf '%s\n' "$PROBE_OUT" | sed -n 's/^version //p')"
PROBE_PROTOCOLS="$(printf '%s\n' "$PROBE_OUT" | sed -n 's/^protocols//p' | sed 's/^ *//')"
printf '%s\n' "$PROBE_LICENSE" >"$OUT/ffmpeg-license.txt"
printf '%s\n' "$PROBE_PROTOCOLS" >"$OUT/ffmpeg-protocols.txt"

# The build's own config.h, copied next to the libraries so the licence check and
# the image scan can read it from the install tree without the build directory.
cp "$SOURCE_DIR/config.h" "$OUT/ffmpeg-config.h" || die "config.h was not produced by configure"

log "collecting the enabled-component inventory"
# The enabled-component inventory, read from the build's own headers. Since
# FFmpeg 5.1 configure splits the component defines (demuxers, decoders,
# parsers, bitstream filters) into config_components.h; config.h keeps the
# arch, toolchain and external-library macros the licence probe reads. A grep
# with no match exits 1, which under `pipefail` and `set -e` would end the
# build without a word, so the inventory is collected first and its size is
# logged.
CONFIG_HEADERS=("$SOURCE_DIR/config.h")
if [ -f "$SOURCE_DIR/config_components.h" ]; then
    CONFIG_HEADERS+=("$SOURCE_DIR/config_components.h")
fi
DEMUXER_DEFINES="$(grep -h 'DEMUXER' "${CONFIG_HEADERS[@]}" || true)"
log "config.h defines $(grep -c '^#define' "$SOURCE_DIR/config.h" || true) macros; the headers name "\
    "$(printf '%s\n' "$DEMUXER_DEFINES" | grep -c '[^[:space:]]' || true) demuxer defines"
log "a demuxer define reads: $(printf '%s\n' "$DEMUXER_DEFINES" | grep -m1 . || true)"
COMPONENTS="$(grep -hE '^#define CONFIG_[A-Z0-9_]+_(DEMUXER|DECODER|PARSER|BSF|MUXER|ENCODER) 1' \
    "${CONFIG_HEADERS[@]}" | sed 's/^#define CONFIG_//; s/ 1$//' | sort -u || true)"
log "the build enabled $(printf '%s\n' "$COMPONENTS" | grep -c '[^[:space:]]') components"
{
    echo "# enabled components of the $TARGET build (from config_components.h and config.h)"
    printf '%s\n' "$COMPONENTS"
} >"$OUT/ffmpeg-components.txt"

log "measuring the four libraries"
# Sizes: the four shared libraries only, symlink targets counted once.
size_of() { # size_of <pattern> — the real file behind a versioned symlink
    local file
    for file in "$LIBDIR/"$1; do
        [ -e "$file" ] || continue
        if [ -L "$file" ]; then continue; fi
        stat -c '%s' "$file" 2>/dev/null || stat -f '%z' "$file"
        return 0
    done
    echo 0
}
{
    for library in avcodec avformat avutil swresample; do
        case "$TARGET" in
            windows-x64) pattern="$library-*.dll" ;;
            macos-arm64) pattern="lib$library.*.dylib" ;;
            *) pattern="lib$library.so.*" ;;
        esac
        printf '%s %s\n' "$library" "$(size_of "$pattern")"
    done
} >"$OUT/ffmpeg-sizes.txt"

log "writing the build record"
{
    echo "target $TARGET"
    echo "ffmpeg $FFMPEG_VERSION"
    echo "source $SOURCE_URL"
    echo "sourceSha256 $SOURCE_SHA256"
    echo "signature $SIGNATURE_URL"
    echo "signatureSha256 $SIGNATURE_SHA256"
    echo "signingKey $SIGNING_KEY_FINGERPRINT"
    echo "configure $CONFIGURE_LINE"
    echo "compiler $("$CC_BIN" --version 2>/dev/null | head -1 || echo unknown)"
    echo "versions $PROBE_VERSION"
    echo "license $PROBE_LICENSE"
} >"$OUT/ffmpeg-build.txt"

log "wrote $OUT"
log "sizes: $(tr '\n' ' ' <"$OUT/ffmpeg-sizes.txt")"
