#!/bin/sh
# SPDX-License-Identifier: Unlicense
#
# Neutrodyne-post-image.sh — jpackage's post-image hook for the macOS DMG
# (11 AOT cache, macOS DMG). JDK 25's packaging pipeline copies the app image
# into its private staging tree without COPY_ATTRIBUTES
# (PackagingPipeline.copyAppImage → PathGroup.copy), so every staged file's
# modification time becomes the packaging time and the trained archive's
# recorded classpath-JAR timestamps no longer match: a strict -XX:AOTMode=on
# launch would fail before main with "timestamp has changed". The supported
# <app name>-post-image.sh hook runs on that staging copy after the copy and
# before hdiutil builds the DMG; this script restores the fixed epoch
# trainAotCache wrote. Written against the pinned JDK 25 pipeline sources;
# jpackage's own templates are GPL-2.0+CE and never copied (D3). It fails
# closed: an invalid staging context or a timestamp that cannot be set aborts
# the packaging rather than ship an installer whose strict cache fails.
set -eu

# ScriptRunner runs the hook with jpackage's own environment plus JpAppImageDir;
# that environment varies with the packaging runner, so pin PATH and the
# timezone rather than inherit either. The epoch is UTC — 318211200000 ms
# (1980-02-01T00:00:00Z), the AOT_IMAGE_TIMESTAMP_MS the training task wrote;
# touch -t interprets its timestamp in TZ, so an explicit UTC0 keeps it correct
# in any packaging timezone.
PATH=/usr/bin:/bin
TZ=UTC0
export PATH TZ

die() { echo "Neutrodyne-post-image: $*" >&2; exit 1; }

[ -n "${JpAppImageDir:-}" ] || die "JpAppImageDir is not set"
[ ! -L "$JpAppImageDir" ] || die "JpAppImageDir is a symbolic link: $JpAppImageDir"
[ -d "$JpAppImageDir" ] || die "JpAppImageDir is not a staging directory: $JpAppImageDir"

# On macOS the packaging pipeline resolves the app image at the relative
# install dir's file name, so JpAppImageDir is the staged .app bundle itself
# (MacPackagingPipeline.runPostAppImageUserScript → BuildEnv.withAppImageDir),
# not the staging root. Require the trained layout — the launcher, its .cfg
# with the -XX:AOTCache read flag, the trained archive and the classpath JARs
# whose timestamps the archive recorded. Packaging without them would ship a
# strict-mode failure, so the hook refuses instead of silently passing through.
APP="$JpAppImageDir"
CFG="$APP/Contents/app/Neutrodyne.cfg"
AOT="$APP/Contents/app/neutrodyne.aot"

[ -d "$APP" ] || die "no staged app bundle at $APP"
[ -f "$APP/Contents/MacOS/Neutrodyne" ] || die "no staged launcher at $APP/Contents/MacOS/Neutrodyne"
[ -f "$CFG" ] || die "no staged launcher config at $CFG"
[ -f "$AOT" ] || die "no trained AOT archive at $AOT"
# shellcheck disable=SC2016 # the .cfg carries the literal $APPDIR token
grep -Fq 'java-options=-XX:AOTCache=$APPDIR/neutrodyne.aot' "$CFG" \
    || die "the staged .cfg carries no -XX:AOTCache read flag"
find -P "$APP/Contents/app" -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q . \
    || die "no classpath JARs under $APP/Contents/app"

# Restore the fixed epoch on every staged file and directory — the same value
# the trained image carries — so hdiutil's -srcfolder copy (the DMG's primary
# path) records it. Symbolic links are skipped entirely: their targets are
# never followed and their own timestamps stay untouched, matching
# trainAotCache's normalization. A find or touch failure propagates under
# set -e and aborts the packaging.
find -P "$APP" ! -type l -exec touch -t 198002010000 {} +
