#!/usr/bin/env bash
#
# Build every Neutrodyne target this host can build, and print one summary.
# The single local entry point for development builds; signed release APKs,
# per-OS desktop installers and the release server JAR come from
# scripts/release.sh and the release workflow, not from here.
#
#   android — Android debug APK (gradlew :app:assembleDebug, applicationId
#             ch.lkmc.neutrodyne.debug) → dist/neutrodyne-<ver>-android-debug.apk.
#   desktop — Compose desktop app image (gradlew :desktopApp:createDistributable)
#             → dist/Neutrodyne.app on macOS, dist/Neutrodyne elsewhere.
#   server  — sync server fat jar (gradlew :sync:server:fatJar)
#             → dist/neutrodyne-server-*.jar.
#
# Usage:
#   scripts/build.sh                 # every target this host can build
#   scripts/build.sh android         # just this one (naming a target turns an
#                                    # infeasible target into an error, not a skip)
#   scripts/build.sh --install       # build the installable set (android + desktop)
#                                    # and install it: the APK goes to a connected
#                                    # device via adb, on macOS the app goes to
#                                    # /Applications/Neutrodyne.app and is revealed
#   scripts/build.sh --clean         # remove dist/
#   scripts/build.sh --check         # print resolved config; build nothing
#
# Exit status is non-zero if any requested target fails to build. Targets that
# simply can't build on this host (missing toolchain, wrong OS) are reported
# as skipped, and only fail the run when you named them explicitly.
set -uo pipefail

# Absolute path to this script — usage() must find it after the cd below.
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

VERSION="$(sed -nE 's/^neutrodyne\.versionName=([0-9.]+).*/\1/p' gradle.properties | head -n1)"
[ -n "$VERSION" ] || { echo "!! could not read neutrodyne.versionName from gradle.properties" >&2; exit 1; }

INSTALL=false
CHECK=false
declare -a REQUESTED=()
EXPLICIT=0

usage() {
  # Print the leading comment block (the file header), minus the shebang.
  awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --clean) rm -rf dist; echo "==> removed dist/"; exit 0 ;;
    --install) INSTALL=true; shift ;;
    --check) CHECK=true; shift ;;
    android|desktop|server) REQUESTED+=("$1"); EXPLICIT=1; shift ;;
    all) REQUESTED=(android desktop server); EXPLICIT=1; shift ;;
    *) echo "unknown argument: $1" >&2; usage 1 ;;
  esac
done

case "$(uname -s)" in
  Darwin) HOST="macos" ;;
  Linux) HOST="linux" ;;
  MINGW*|MSYS*|CYGWIN*) HOST="windows" ;;
  *) HOST="unknown" ;;
esac

# --install installs the APK on a device and the desktop app on a mac. With no
# explicit targets it builds just the installable set (soft-skipping whatever
# the host lacks); with explicit targets it installs those that support it.
if $INSTALL && [[ ${#REQUESTED[@]} -eq 0 ]]; then
  REQUESTED=(android desktop)
fi

# Default to all targets; feasibility is decided per-target below.
if [[ ${#REQUESTED[@]} -eq 0 ]]; then
  REQUESTED=(android desktop server)
fi

echo "Neutrodyne build"
echo "  host:    $HOST ($(uname -s))"
echo "  targets: ${REQUESTED[*]}"
echo "  version: $VERSION"
echo "  install: $INSTALL"
echo

have() { command -v "$1" >/dev/null 2>&1; }

declare -a RESULTS=()
record() { RESULTS+=("$1"); }   # "android: built -> …" | "desktop: skipped (…)" | "server: FAILED"

# Collect a finished artifact into dist/ (file or directory). The staged copy
# is what the summary points at — one folder with everything this run built.
DIST="$ROOT/dist"
STAGED=0
STAGED_APP=""
stage() {
  local src="$1" name="${2:-$(basename "$1")}"
  mkdir -p "$DIST" || return 1
  rm -rf "${DIST:?}/$name" || return 1
  cp -R "$src" "$DIST/$name" || return 1
  STAGED=$((STAGED + 1))
  echo "-- staged dist/$name"
}

# Was a given target explicitly named on the command line?
was_named() {
  [[ $EXPLICIT -eq 1 ]] || return 1
  local t
  for t in "${REQUESTED[@]}"; do [[ "$t" == "$1" ]] && return 0; done
  return 1
}

# Skip a target: a soft skip on a default run, a hard error when named.
skip_or_fail() {
  local target="$1" reason="$2"
  if was_named "$target"; then
    echo "!! $target: cannot build on this host — $reason" >&2
    record "$target: FAILED ($reason)"
    return 1
  fi
  echo ".. $target: skipped — $reason"
  record "$target: skipped ($reason)"
  return 0
}

need_java() { # <target> — every target runs through Gradle.
  local target="$1"
  if ! have java && [[ -z "${JAVA_HOME:-}" ]]; then
    skip_or_fail "$target" "JDK not found (set JAVA_HOME or install a JDK)"
    return 1
  fi
  return 0
}

# An installed Android SDK is what separates "can build the APK" from a long
# doomed Gradle run; probe the usual locations.
detect_android_sdk() {
  local c
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
           "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [[ -n "$c" && -d "$c/platforms" ]] && { echo "$c"; return 0; }
  done
  return 1
}

if $CHECK; then
  echo "-- android: ./gradlew :app:assembleDebug -> dist/neutrodyne-$VERSION-android-debug.apk"
  echo "-- desktop: ./gradlew :desktopApp:createDistributable -> dist/Neutrodyne(.app)"
  echo "-- server:  ./gradlew :sync:server:fatJar -> dist/neutrodyne-server-*.jar"
  if [[ -f local.properties ]]; then
    echo "-- sdk:     local.properties"
  else
    echo "-- sdk:     $(detect_android_sdk || echo 'not found')"
  fi
  exit 0
fi

# ---------------------------------------------------------------------------
build_android() {
  echo "== android (debug APK, ch.lkmc.neutrodyne.debug) =="
  need_java android || return
  if [[ ! -x gradlew ]]; then
    skip_or_fail android "gradlew missing or not executable"; return
  fi
  local sdk
  if [[ -f local.properties ]]; then
    sdk="local.properties"
  elif sdk="$(detect_android_sdk)"; then
    export ANDROID_HOME="$sdk"   # Gradle locates the SDK via this or local.properties
  else
    skip_or_fail android "no Android SDK found (set ANDROID_SDK_ROOT, or install one via Android Studio)"; return
  fi
  echo "-- Android SDK: $sdk"
  echo "-- gradlew :app:assembleDebug"
  if ! ./gradlew :app:assembleDebug; then
    echo "!! android: gradle build failed" >&2
    record "android: FAILED (gradle)"; return 1
  fi
  local apk=""
  for apk in app/build/outputs/apk/debug/*.apk; do break; done
  [[ -f "$apk" ]] || apk=""
  if [[ -z "$apk" ]] || ! stage "$apk" "neutrodyne-$VERSION-android-debug.apk"; then
    echo "!! android: APK not found to stage" >&2
    record "android: FAILED (APK not found to stage)"; return 1
  fi
  record "android: built -> dist/neutrodyne-$VERSION-android-debug.apk"

  if $INSTALL; then
    local adb="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}"
    [[ -n "$adb" && -x "$adb" ]] || adb="$(command -v adb || true)"
    if [[ -z "$adb" ]] || ! "$adb" devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then
      if was_named android; then
        echo "!! android: no device online — nothing to install to" >&2
        record "android: install FAILED (no adb device)"; return 1
      fi
      echo ".. android: install skipped — no connected device (adb devices)"
    else
      echo "-- adb install -r dist/neutrodyne-$VERSION-android-debug.apk"
      if "$adb" install -r "$DIST/neutrodyne-$VERSION-android-debug.apk"; then
        record "android: installed on $("$adb" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
      else
        echo "!! android: adb install failed" >&2
        record "android: install FAILED (adb)"; return 1
      fi
    fi
  fi
}

# ---------------------------------------------------------------------------
build_desktop() {
  echo "== desktop (Compose app image) =="
  need_java desktop || return
  if [[ ! -x gradlew ]]; then
    skip_or_fail desktop "gradlew missing or not executable"; return
  fi
  echo "-- gradlew :desktopApp:createDistributable"
  if ! ./gradlew :desktopApp:createDistributable; then
    echo "!! desktop: gradle build failed" >&2
    record "desktop: FAILED (gradle)"; return 1
  fi
  local img="desktopApp/build/compose/binaries/main/app/Neutrodyne" name="Neutrodyne"
  if [[ "$HOST" == "macos" ]]; then
    img="$img.app"; name="Neutrodyne.app"
  fi
  if [[ ! -e "$img" ]] || ! stage "$img" "$name"; then
    echo "!! desktop: image not found to stage" >&2
    record "desktop: FAILED (image not found to stage)"; return 1
  fi
  STAGED_APP="$DIST/$name"
  record "desktop: built -> dist/$name"

  # Installing the desktop app is a macOS convention: it goes to
  # /Applications. Elsewhere dist/ is the deliverable.
  if $INSTALL && [[ "$HOST" == "macos" ]]; then
    echo "-- installing /Applications/Neutrodyne.app"
    # Copy to a sibling path first so a failed ditto can't destroy the
    # previous install; the final rename is atomic on the same volume.
    local dst="/Applications/Neutrodyne.app" tmp="/Applications/.Neutrodyne.app.new.$$"
    rm -rf "$tmp"
    # ditto preserves the signature, resource forks, and permissions.
    if ditto "$STAGED_APP" "$tmp" && rm -rf "$dst" && mv "$tmp" "$dst"; then
      INSTALLED="$dst"
      record "desktop: installed -> /Applications/Neutrodyne.app"
      # Replacing a bundle in place leaves LaunchServices with the OLD
      # registration for the path; force a re-registration.
      LSREG="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
      [[ -x "$LSREG" ]] && "$LSREG" -f "$INSTALLED" >/dev/null 2>&1 || true
    else
      rm -rf "$tmp"
      record "desktop: install FAILED (ditto)"; return 1
    fi
  fi
}

# ---------------------------------------------------------------------------
build_server() {
  echo "== server (sync server fat jar) =="
  need_java server || return
  if [[ ! -x gradlew ]]; then
    skip_or_fail server "gradlew missing or not executable"; return
  fi
  echo "-- gradlew :sync:server:fatJar"
  if ! ./gradlew :sync:server:fatJar; then
    echo "!! server: gradle build failed" >&2
    record "server: FAILED (gradle)"; return 1
  fi
  local jar=""
  for jar in sync/server/build/libs/neutrodyne-server-*.jar; do break; done
  [[ -f "$jar" ]] || jar=""
  if [[ -z "$jar" ]] || ! stage "$jar"; then
    echo "!! server: jar not found to stage" >&2
    record "server: FAILED (jar not found to stage)"; return 1
  fi
  record "server: built -> dist/$(basename "$jar")"
}

# ---------------------------------------------------------------------------
FAILED=0
INSTALLED=""
for target in "${REQUESTED[@]}"; do
  case "$target" in
    android) build_android || FAILED=1 ;;
    desktop) build_desktop || FAILED=1 ;;
    server)  build_server  || FAILED=1 ;;
  esac
  echo
done

echo "Summary"
for line in "${RESULTS[@]}"; do echo "  $line"; done

# Reveal the result: the installed copy when --install ran, else the dist/
# folder with everything this run produced (macOS only — elsewhere the paths
# in the summary are the deliverable).
if [[ -n "$INSTALLED" ]]; then
  echo "  installed: $INSTALLED"
  [[ "$HOST" == "macos" ]] && open -R "$INSTALLED"
elif [[ "$STAGED" -gt 0 ]]; then
  echo "  artifacts: $DIST"
  # Highlight the app when one was staged — dist/ also holds other artifacts.
  if [[ "$HOST" == "macos" ]]; then
    if [[ -n "$STAGED_APP" ]]; then open -R "$STAGED_APP"; else open "$DIST"; fi
  fi
fi

exit "$FAILED"
