#!/usr/bin/env bash
# trim-python.sh — trim a python-build-standalone install_only_stripped archive into the desktop
# engine's runtime tree (11 Trim list and checks). Usage: trim-python.sh <archive.tar.gz> <output-dir>
#
# The output directory receives:
#   python/        the trimmed tree (staged at <os>-<arch>/engine/python/ in the image)
#   cacert.pem     certifi's CA bundle, extracted from the archive's pip-vendored certifi before
#                  pip is removed (staged at common/engine/cacert.pem; 11 Networking and TLS)
#
# Removed per target: include/, share/, lib/pkgconfig/, lib/libpython3.14*.so* on Linux (the
# executable is statically linked there), every bin/ entry except the interpreter (idle*, pip*,
# pydoc*, *-config), the standard-library packages tkinter, idlelib, turtledemo, test, ensurepip,
# pydoc_data, __phello__, site-packages/pip*, config-3.14-*, all of Tcl/Tk (libtcl*, libtk*, tcl9*,
# tk9*, itcl*, thread*, Windows tcl\ and DLLs\tcl*.dll, DLLs\tk*.dll), _tkinter* and _dbm*
# (statically linked Berkeley DB 6.0.19 under the Sleepycat licence on Linux). The upstream
# LICENSE.txt stays. Never removes the interpreter, the standard library's ssl, sqlite3, json,
# http.client, xml.etree, zipimport or ctypes support, or the extension modules the engine needs.
set -euo pipefail

archive=${1:?usage: trim-python.sh <archive.tar.gz> <output-dir>}
output=${2:?usage: trim-python.sh <archive.tar.gz> <output-dir>}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tar -xzf "$archive" -C "$work"
root="$work/python"
if [ ! -d "$root" ]; then
  echo "trim-python.sh: $archive has no python/ root" >&2
  exit 1
fi

# Windows ships Lib/, DLLs/ and the interpreter at the root; POSIX ships lib/python3.14/, bin/, lib/.
# Detect Windows by DLLs/, not by Lib/: macOS's default APFS is case-insensitive, so Lib/ resolves
# to lib/ there.
if [ -d "$root/DLLs" ]; then
  pylib="$root/Lib"
  windows=1
else
  pylib=$(find "$root/lib" -maxdepth 1 -type d -name 'python3.*' | head -n 1)
  windows=0
fi
if [ -z "$pylib" ] || [ ! -d "$pylib" ]; then
  echo "trim-python.sh: cannot locate the standard library under $root" >&2
  exit 1
fi

cacert="$pylib/site-packages/pip/_vendor/certifi/cacert.pem"
if [ ! -f "$cacert" ]; then
  echo "trim-python.sh: missing pip-vendored certifi cacert.pem under $pylib" >&2
  exit 1
fi
mkdir -p "$output"
cp "$cacert" "$output/cacert.pem"

# Headers, pkg-config and man pages: not needed at run time.
rm -rf "$root/include" "$root/share"
[ -d "$root/lib/pkgconfig" ] && rm -rf "$root/lib/pkgconfig"

if [ "$windows" = "1" ]; then
  # Tcl/Tk's runtime and pip's script directory.
  rm -rf "$root/tcl" "$root/Scripts"
  rm -f "$root/DLLs"/_tkinter*.pyd "$root/DLLs"/_dbm*.pyd
  rm -f "$root/DLLs"/tcl*.dll "$root/DLLs"/tk*.dll
else
  # The Linux executable is statically linked; the shared libpython is not needed. macOS keeps
  # its libpython3.14.dylib (the interpreter and the extension modules link it).
  if [ -f "$root/lib/libpython3.14.so" ]; then
    rm -f "$root/lib"/libpython3.14*.so*
  fi
  # Every bin/ entry except the interpreter (idle*, pip*, pydoc*, *-config).
  find "$root/bin" -mindepth 1 -maxdepth 1 \
    \( -name 'idle*' -o -name 'pip*' -o -name 'pydoc*' -o -name '*-config' \) \
    -exec rm -rf {} +
  # Tcl/Tk's shared libraries.
  rm -rf "$root/lib"/libtcl* "$root/lib"/libtk* "$root/lib"/tcl9* "$root/lib"/tk9* \
    "$root/lib"/itcl* "$root/lib"/thread*
  rm -f "$pylib"/_tkinter*.so* "$pylib"/_dbm*.so* "$pylib"/lib-dynload/_tkinter*.so* \
    "$pylib"/lib-dynload/_dbm*.so*
fi

# The standard-library packages the engine never imports, and pip (its vendored certifi was
# extracted above). config-3.14-* is the build-time configuration directory.
rm -rf "$pylib"/tkinter "$pylib"/idlelib "$pylib"/turtledemo "$pylib"/test \
  "$pylib"/ensurepip "$pylib"/pydoc_data "$pylib"/__phello__
rm -rf "$pylib"/site-packages/pip*
rm -rf "$pylib"/config-3.14-*

# The trimmed tree keeps the upstream LICENSE.txt beside the standard library.
if [ "$windows" = "0" ] && [ ! -f "$pylib/LICENSE.txt" ]; then
  echo "trim-python.sh: the archive carries no LICENSE.txt under $pylib" >&2
  exit 1
fi

# Stage the trimmed tree as <output>/python/.
mkdir -p "$output"
mv "$root" "$output/python"
echo "trim-python.sh: staged $output/python and $output/cacert.pem"
