#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Build libndmedia for one target (11 ndmedia interface). One .c file plus the
# pinned miniaudio header — small enough to compile directly with cc/cl, no
# build system. Usage: ndmedia/build.sh <target>
#   windows-x64 | macos-arm64 | linux-x64 | linux-arm64
set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }

TARGET="${1:?usage: ndmedia/build.sh <target>}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
NATIVE_ROOT="$(cd "$ROOT/.." && pwd)"
BUILD_ROOT="$NATIVE_ROOT/build/ndmedia/$TARGET"
CACHE="${ND_CACHE:-$HOME/.cache/neutrodyne-native}"
mkdir -p "$BUILD_ROOT" "$CACHE"

# Pinned inputs from native-components.lock ([miniaudio] section, INI style).
LOCK="$NATIVE_ROOT/native-components.lock"
ini() { awk -F' *= *' -v sec="$1" -v key="$2" '
        /^\[/ { insec = ($0 == "[" sec "]") }
        insec && $1 == key { gsub(/"/, "", $2); print $2; exit }' "$LOCK"; }
MINIAUDIO_VERSION="$(ini miniaudio version)"
MINIAUDIO_URL="$(ini miniaudio sourceUrl)"
MINIAUDIO_SHA256="$(ini miniaudio sha256)"
[ -n "$MINIAUDIO_VERSION" ] && [ -n "$MINIAUDIO_URL" ] && [ -n "$MINIAUDIO_SHA256" ] ||
    die "miniaudio section incomplete in $LOCK"

# sha256sum is absent on macOS runners; shasum covers BSD, python the rest.
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
    fi
}

INCLUDE_DIR="$CACHE/miniaudio-$MINIAUDIO_VERSION"
HEADER="$INCLUDE_DIR/miniaudio.h"
mkdir -p "$INCLUDE_DIR"
if [ ! -f "$HEADER" ]; then
    fetch_ok=0
    for try in 1 2 3; do
        if curl -fsSL --retry 2 --connect-timeout 20 --max-time 120 -o "$HEADER.part" "$MINIAUDIO_URL" && \
           [ "$(sha256_of "$HEADER.part")" = "$MINIAUDIO_SHA256" ]; then
            mv "$HEADER.part" "$HEADER"; fetch_ok=1; break
        fi
        rm -f "$HEADER.part"; sleep $((try * 5))
    done
    [ "$fetch_ok" = 1 ] || die "could not fetch $MINIAUDIO_URL with sha256 $MINIAUDIO_SHA256"
fi

[ "$(sha256_of "$HEADER")" = "$MINIAUDIO_SHA256" ] || die "cached miniaudio checksum mismatch"

SRC="$ROOT/ndmedia.c"
CFLAGS="-O2 -fvisibility=hidden -I$INCLUDE_DIR -I$ROOT"
# GCC otherwise copies GPL runtime atomic helpers into this permissive library.
# ARMv8's inline LL/SC instructions satisfy the same baseline without them.
[ "$TARGET" != linux-arm64 ] || CFLAGS="$CFLAGS -mno-outline-atomics"

case "$TARGET" in
    linux-x64|linux-arm64)
        OUT="$BUILD_ROOT/libndmedia.so"
        # Same manylinux glibc floor as the FFmpeg build.
        IMAGE="quay.io/pypa/manylinux_2_28_x86_64"
        [ "$TARGET" = linux-arm64 ] && IMAGE="quay.io/pypa/manylinux_2_28_aarch64"
        if [ -n "${ND_IN_MANYLINUX:-}" ]; then
            cc $CFLAGS -fPIC -shared "$SRC" -o "$OUT" -lpthread -lm -ldl
        elif command -v docker >/dev/null 2>&1; then
            # ffmpeg/build.sh's convention: everything after the container
            # exec happens inside it, so the host never writes beside the
            # container's root-owned outputs.
            docker run --rm -e ND_IN_MANYLINUX=1 -e ND_CACHE=/cache \
                -v "$NATIVE_ROOT":/src -v "$CACHE":/cache -w /src \
                "$IMAGE" bash ndmedia/build.sh "$TARGET"
            exit 0
        else
            # Without docker the host compiler still proves the code; the
            # manylinux floor is asserted by the desktop-matrix runners.
            cc $CFLAGS -fPIC -shared "$SRC" -o "$OUT" -lpthread -lm -ldl
        fi
        ;;
    macos-arm64)
        OUT="$BUILD_ROOT/libndmedia.dylib"
        cc $CFLAGS -fPIC -dynamiclib -arch arm64 -mmacosx-version-min=13.0 \
            -install_name "@rpath/libndmedia.dylib" \
            "$SRC" "$ROOT/session_mac.m" -fobjc-arc -o "$OUT" \
            -framework AppKit -framework MediaPlayer -framework AudioToolbox -framework CoreAudio -framework CoreFoundation
        ;;
    windows-x64)
        OUT="$BUILD_ROOT/ndmedia.dll"
        # MSVC from the runner's Visual Studio, driven through the MSYS2 shell —
        # the same env-load mechanism ffmpeg/build.sh uses.
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
            # The wrapper .bat and its env dump live in the build tree, and cmd
            # runs the .bat by bare name from its own directory: no temp path
            # crosses the MSYS2/cmd boundary (the mktemp-based .bat reached cmd
            # as a path it could not find). vcvars may change the directory, so
            # the .bat restores it before dumping the environment.
            VCVARS_WORK="$NATIVE_ROOT/build/ndmedia/vcvars-$TARGET"
            mkdir -p "$VCVARS_WORK"
            printf '@set "OLDCD=%%CD%%"\r\ncall "%s" >vcvars.log 2>&1\r\n@if errorlevel 1 exit /b 1\r\n@cd /d "%%OLDCD%%"\r\nset > "env.txt"\r\n' \
                "$(cygpath -w "$VCVARS")" > "$VCVARS_WORK/vcvars-env.bat"
            (cd "$VCVARS_WORK" && cmd //c vcvars-env.bat) ||
                { cat "$VCVARS_WORK/vcvars.log"; die "vcvars64.bat failed"; }
            rm -f "$VCVARS_WORK/vcvars-env.bat"
            ENV_FILE="$VCVARS_WORK/env.txt"
            [ -s "$ENV_FILE" ] || die "vcvars64.bat produced no environment"
            # cmd's `set` writes CRLF lines and names such as
            # CommonProgramFiles(x86) that bash cannot export (this task died
            # exactly there on the first wrapper-based leg). Such names are
            # skipped — bash never needs them, and native tools get their own
            # values through the environment cmd already set — and the CR is
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
        (cd "$BUILD_ROOT" && cl -nologo -O2 -std:c11 -experimental:c11atomics -MD -c \
            -I"$(cygpath -w "$INCLUDE_DIR")" -I"$(cygpath -w "$ROOT")" \
            "$(cygpath -w "$SRC")" -Fondmedia.obj && \
            cl -nologo -O2 -EHsc -std:c++17 -LD -MD -I"$(cygpath -w "$ROOT")" \
            "$(cygpath -w "$ROOT/session_win.cpp")" ndmedia.obj \
            /link /OUT:ndmedia.dll ole32.lib uuid.lib advapi32.lib user32.lib shell32.lib runtimeobject.lib) ||
            die "cl failed"
        rm -f "$BUILD_ROOT/ndmedia.lib" "$BUILD_ROOT/ndmedia.exp" "$BUILD_ROOT/ndmedia.obj"
        ;;
    *) die "unknown target $TARGET" ;;
esac

# A pure native boundary test runs on the same compiler, outside the output
# directory so it cannot enter a future shipping native image.
TEST_PROGRAM="$CACHE/json-check-$TARGET"
if [ "$TARGET" = windows-x64 ]; then
    TEST_PROGRAM="$TEST_PROGRAM.exe"
    cl -nologo -std:c11 -MD -I"$(cygpath -w "$ROOT")" \
        "$(cygpath -w "$ROOT/tests/json_test.c")" \
        -Fe"$(cygpath -w "$TEST_PROGRAM")" -Fo"$(cygpath -w "$CACHE/json-check.obj")" || die "JSON test compile failed"
else
    cc -std=c11 -I"$ROOT" "$ROOT/tests/json_test.c" -o "$TEST_PROGRAM" || die "JSON test compile failed"
fi
"$TEST_PROGRAM" || die "device-name JSON boundary test failed"

[ -f "$OUT" ] || die "expected $OUT"
BYTES=$(stat -c %s "$OUT" 2>/dev/null || stat -f %z "$OUT")
{
    echo "target=$TARGET"
    echo "miniaudio=$MINIAUDIO_VERSION"
    echo "miniaudio_sha256=$MINIAUDIO_SHA256"
    echo "output=$(basename "$OUT")"
    echo "bytes=$BYTES"
} > "$BUILD_ROOT/ndmedia-build.txt"
echo "built $OUT ($BYTES bytes)"
