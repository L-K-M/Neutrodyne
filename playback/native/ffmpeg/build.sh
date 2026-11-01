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
NATIVE_DIR="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd -P)"
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
    awk -v section="$1" -v key="$2" '
        /^[[:space:]]*#/ { next }
        /^[[:space:]]*\[/ { in_section = ($0 ~ "^[[:space:]]*\\[" section "\\][[:space:]]*$"); next }
        in_section && $1 == key {
            sub(/^[^=]*=[[:space:]]*"/, ""); sub(/"[[:space:]]*$/, ""); print; exit
        }
    ' "$LOCK"
}

FFMPEG_VERSION="$(lock_value ffmpeg version)"
SOURCE_URL="$(lock_value ffmpeg sourceUrl)"
SOURCE_SHA256="$(lock_value ffmpeg sha256)"
SIGNATURE_URL="$(lock_value ffmpeg signatureUrl)"
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

download() { # download <url> <file>
    if [ -s "$2" ]; then
        log "reusing $2"
        return 0
    fi
    log "downloading $1"
    curl -fsSL --retry 3 --connect-timeout 30 -o "$2" "$1" || die "download failed: $1"
}

if [ ! -d "$SOURCE_DIR" ]; then
    download "$SOURCE_URL" "$SOURCE_TARBALL"
    verify_checksum "$SOURCE_TARBALL" "$SOURCE_SHA256"
    if [ -n "$SIGNATURE_URL" ]; then
        download "$SIGNATURE_URL" "$SOURCE_SIGNATURE"
        if [ -n "$SIGNATURE_SHA256" ]; then
            verify_checksum "$SOURCE_SIGNATURE" "$SIGNATURE_SHA256"
        fi
        verify_signature
    fi
    log "extracting FFmpeg $FFMPEG_VERSION"
    rm -rf "$SOURCE_DIR" "$SOURCE_DIR.tmp"
    mkdir -p "$SOURCE_DIR.tmp"
    tar -xJf "$SOURCE_TARBALL" -C "$SOURCE_DIR.tmp" --strip-components=1
    mv "$SOURCE_DIR.tmp" "$SOURCE_DIR"
fi

verify_signature() {
    command -v gpg >/dev/null 2>&1 || die "gpg is required to verify the FFmpeg source signature"
    local keyring home
    home="$(mktemp -d)"
    keyring="$home/ffmpeg-release-signing-key.asc"
    cp "$NATIVE_DIR/$SIGNING_KEY" "$keyring"
    GNUPGHOME="$home" gpg --quiet --batch --import "$keyring" >/dev/null 2>&1 ||
        die "could not import the FFmpeg release signing key"
    local fingerprint
    fingerprint="$(GNUPGHOME="$home" gpg --quiet --with-colons --show-keys "$keyring" |
        awk -F: '$1 == "fpr" { print $10; exit }')"
    [ "$fingerprint" = "$SIGNING_KEY_FINGERPRINT" ] ||
        die "the FFmpeg signing key is $fingerprint, expected $SIGNING_KEY_FINGERPRINT"
    GNUPGHOME="$home" gpg --quiet --batch --verify "$SOURCE_SIGNATURE" "$SOURCE_TARBALL" ||
        die "the FFmpeg source signature did not verify"
    rm -rf "$home"
    log "verified the FFmpeg source signature with $SIGNING_KEY_FINGERPRINT"
}

# ------------------------------------------------------------------- toolchain

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

case "$TARGET" in
    windows-x64)
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
            ENV_FILE="$(mktemp)"
            cmd //c "\"$(cygpath -w "$VCVARS")\" > NUL && set > \"$(cygpath -w "$ENV_FILE")\"" ||
                die "vcvars64.bat failed"
            while IFS= read -r line; do
                case "$line" in
                    [A-Za-z_]*=*) export "${line%%=*}=${line#*=}" ;;
                esac
            done < "$ENV_FILE"
            rm -f "$ENV_FILE"
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
        if ! command -v nasm >/dev/null 2>&1; then
            log "installing NASM into the container"
            (microdnf install -y nasm || dnf install -y nasm || yum install -y nasm) >/dev/null 2>&1 ||
                log "warning: could not install NASM; x86 assembly is disabled"
        fi
        TARGET_FLAGS=(
            --cc=gcc
            # Every shared library carries an rpath of its own directory.
            --extra-ldflags=-Wl,-rpath,'$ORIGIN'
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

# ------------------------------------------------------------------ artefacts

# The layout probe: compiled against the very headers this build installed.
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
    (cd "$OUT/lib" && "$(cygpath -u "$BUILD")/ffoffsets.exe" >"$(cygpath -u "$OUT")/ffmpeg-layout.json")
else
    "$CC_BIN" -O2 -I"$OUT/include" "$SCRIPT_DIR/ffoffsets.c" -o "$BUILD/ffoffsets" ||
        die "ffoffsets.c did not compile"
    (cd "$OUT/lib" && "$BUILD/ffoffsets" >"$OUT/ffmpeg-layout.json")
fi
python3 - "$OUT/ffmpeg-layout.json" <<'PY' || die "ffmpeg-layout.json is not valid JSON"
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
if [ "$CC_BIN" = "cl" ]; then
    cl -nologo -O2 -I"$(cygpath -w "$OUT/include")" "$(cygpath -w "$BUILD/probe.c")" \
        -Fe"$(cygpath -w "$BUILD/probe.exe")" -Fo"$(cygpath -w "$BUILD/probe.obj")" \
        >"$BUILD/probe-build.log" 2>&1 ||
        { cat "$BUILD/probe-build.log"; die "probe.c did not compile"; }
    PROBE_OUT="$(cd "$OUT/lib" && "$(cygpath -u "$BUILD")/probe.exe")" || die "probe.exe failed"
else
    "$CC_BIN" -O2 -I"$OUT/include" "$BUILD/probe.c" -o "$BUILD/probe" -L"$OUT/lib" \
        -Wl,-rpath,"$OUT/lib" || die "probe.c did not compile"
    PROBE_OUT="$(cd "$OUT/lib" && LD_LIBRARY_PATH="$OUT/lib" "$BUILD/probe")" || die "probe failed"
fi
PROBE_LICENSE="$(printf '%s\n' "$PROBE_OUT" | sed -n 's/^license //p')"
PROBE_VERSION="$(printf '%s\n' "$PROBE_OUT" | sed -n 's/^version //p')"
PROBE_PROTOCOLS="$(printf '%s\n' "$PROBE_OUT" | sed -n 's/^protocols//p' | sed 's/^ *//')"
printf '%s\n' "$PROBE_LICENSE" >"$OUT/ffmpeg-license.txt"
printf '%s\n' "$PROBE_PROTOCOLS" >"$OUT/ffmpeg-protocols.txt"

# The build's own config.h, copied next to the libraries so the licence check and
# the image scan can read it from the install tree without the build directory.
cp "$SOURCE_DIR/config.h" "$OUT/ffmpeg-config.h"

# The enabled-component inventory, read from the build's own config.h.
{
    echo "# enabled components of the $TARGET build (from config.h)"
    grep -E '^#define CONFIG_[A-Z0-9_]+_(DEMUXER|DECODER|PARSER|BSF|MUXER|ENCODER) 1' \
        "$SOURCE_DIR/config.h" | sed 's/^#define CONFIG_//; s/ 1$//' | sort
} >"$OUT/ffmpeg-components.txt"

# Sizes: the four shared libraries only, symlink targets counted once.
size_of() { # size_of <pattern> — the real file behind a versioned symlink
    local file
    for file in "$OUT/lib/"$1; do
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

{
    echo "target $TARGET"
    echo "ffmpeg $FFMPEG_VERSION"
    echo "source $SOURCE_URL"
    echo "sourceSha256 $SOURCE_SHA256"
    echo "signature $SIGNATURE_URL"
    echo "signatureSha256 $SIGNATURE_SHA256"
    echo "signingKey $SIGNING_KEY_FINGERPRINT"
    echo "configure $CONFIGURE_LINE"
    echo "compiler $($CC_BIN --version 2>/dev/null | head -1 || echo unknown)"
    echo "versions $PROBE_VERSION"
    echo "license $PROBE_LICENSE"
} >"$OUT/ffmpeg-build.txt"

log "wrote $OUT"
log "sizes: $(tr '\n' ' ' <"$OUT/ffmpeg-sizes.txt")"
