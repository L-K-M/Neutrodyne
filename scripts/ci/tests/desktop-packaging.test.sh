#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# desktop-packaging.test.sh — the two desktop installer-path contracts of 11
# (nativeDistributions configuration, Windows MSI and ZIP):
#
#   - the RPM resource override must be named <linux-package-name>.spec, because
#     jpackage's --resource-dir lookup resolves a resource by the name of the
#     file it is about to write (SPECS/<package-name>.spec), not by jpackage's
#     default template.spec name. A template.spec is silently ignored and the
#     RPM is built with jpackage's default spec, none of our Requires (old
#     nightly 37860407043). The first case runs the real OverridableResource
#     from the pinned JDK 25 against a controlled resource dir and against the
#     repository's resource dir; the second asserts the Requires survive.
#   - the Windows installationPath must be a single directory name distinct
#     from the data root. A nested per-user path ("Programs/Neutrodyne")
#     authors an intermediate Directory that carries no RemoveFile component,
#     so WiX ICE64 fails light.exe with LGHT0204 (old nightly 37886973484);
#     the default single name "Neutrodyne" is the data directory the MSI
#     uninstaller deletes recursively.
#
# Run: bash scripts/ci/tests/desktop-packaging.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
PLUGIN="$REPO_ROOT/build-logic/convention/src/main/kotlin/DesktopApplicationConventionPlugin.kt"
RPM_DIR="$REPO_ROOT/desktopApp/packaging/rpm"
DESIGN="$REPO_ROOT/docs/design/11-desktop.md"
PLAN="$REPO_ROOT/docs/PLAN.md"

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

for f in "$PLUGIN" "$DESIGN" "$PLAN"; do
    [ -f "$f" ] || { echo "not ok: $f is missing" >&2; exit 1; }
done

# The Linux package name the plugin passes to jpackage. The RPM spec file name
# is derived from it, not hardcoded, so a rename of the package fails here.
package_name="$(sed -n 's/^internal const val LINUX_PACKAGE_NAME = "\([^"]*\)".*/\1/p' "$PLUGIN" | head -1)"
if [ -n "$package_name" ]; then
    t_ok "the Linux package name is $package_name"
else
    t_fail "the plugin does not declare LINUX_PACKAGE_NAME"
fi
expected_spec="$package_name.spec"

# --- 1. the RPM resource lookup uses <package-name>.spec -----------------------
#
# Locate a JDK 25 with jdk.jpackage. The pinned runtime is Temurin 25.0.4.1+1
# (desktopApp/runtime.lock); its OverridableResource is the lookup jpackage's
# LinuxRpmBundler uses. The CI static job installs JDK 25 beside JDK 21 and
# setup-java exposes it as JAVA_HOME_25_<arch>.
jdk25_home=""
for candidate in \
    "${JAVA_HOME_25_X64:-}" \
    "${JAVA_HOME_25_ARM64:-}" \
    "$HOME/.local/jdks/jdk25/Contents/Home" \
    "$HOME/.local/jdks/jdk25" \
    "/usr/lib/jvm/temurin-25-jdk-amd64" \
    "/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ] \
        && "$candidate/bin/java" -version 2>&1 | grep -q 'version "25'; then
        jdk25_home="$candidate"
        break
    fi
done
if [ -z "$jdk25_home" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] \
    && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25'; then
    jdk25_home="$JAVA_HOME"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ -z "$jdk25_home" ]; then
    t_fail "no JDK 25 with jdk.jpackage found for the RPM resource-lookup oracle"
else
    t_ok "found JDK 25 at $jdk25_home"
    cat > "$work/ResourceLookupProbe.java" <<'JAVA'
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public class ResourceLookupProbe {
    public static void main(String[] args) throws Exception {
        Path resourceDir = Path.of(args[0]);
        Path dest = Path.of(args[1]);
        Class<?> cls = Class.forName("jdk.jpackage.internal.OverridableResource");
        Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object resource = ctor.newInstance();
        Method setResourceDir = cls.getDeclaredMethod("setResourceDir", Path.class);
        setResourceDir.setAccessible(true);
        setResourceDir.invoke(resource, resourceDir);
        Method saveToFile = cls.getDeclaredMethod("saveToFile", Path.class);
        saveToFile.setAccessible(true);
        Object source = saveToFile.invoke(resource, dest);
        System.out.println("source=" + source);
        if (Files.exists(dest)) {
            System.out.println("content=");
            System.out.println(Files.readString(dest));
        } else {
            System.out.println("content=<absent>");
        }
    }
}
JAVA
    if "$jdk25_home/bin/javac" -d "$work/classes" "$work/ResourceLookupProbe.java" 2>"$work/javac.err"; then
        t_ok "the OverridableResource probe compiles against the pinned JDK 25"
    else
        t_fail "the OverridableResource probe did not compile: $(head -1 "$work/javac.err")"
    fi

    run_probe() { # <resource-dir> <dest>
        "$jdk25_home/bin/java" \
            --add-opens jdk.jpackage/jdk.jpackage.internal=ALL-UNNAMED \
            -cp "$work/classes" ResourceLookupProbe "$1" "$2" 2>&1
    }

    # A controlled resource dir: template.spec is jpackage's default name, but
    # the lookup key is the destination file name. It must be ignored.
    mkdir -p "$work/res-template" "$work/out-template/SPECS"
    printf 'TEMPLATE MARKER\nRequires: libc.so.6()(64bit)\n' > "$work/res-template/template.spec"
    out="$(run_probe "$work/res-template" "$work/out-template/SPECS/$expected_spec")"
    if printf '%s' "$out" | grep -q 'source=null' \
        && [ ! -f "$work/out-template/SPECS/$expected_spec" ]; then
        t_ok "the resource-dir lookup ignores template.spec when the destination is $expected_spec"
    else
        t_fail "the resource-dir lookup did not ignore template.spec: $out"
    fi

    # The repository's resource dir: after the fix it contains the required
    # spec, and the lookup must find it and write it to SPECS/<package>.spec.
    mkdir -p "$work/out-repo/SPECS"
    out="$(run_probe "$RPM_DIR" "$work/out-repo/SPECS/$expected_spec")"
    if printf '%s' "$out" | grep -q 'source=ResourceDir' \
        && [ -f "$work/out-repo/SPECS/$expected_spec" ] \
        && grep -q '^Requires: libc\.so\.6' "$work/out-repo/SPECS/$expected_spec" \
        && grep -q '^Requires: libasound\.so\.2' "$work/out-repo/SPECS/$expected_spec"; then
        t_ok "jpackage's resource lookup finds $expected_spec and the Requires survive"
    else
        t_fail "jpackage's resource lookup did not find $expected_spec with our Requires"
        printf '%s\n' "$out" | sed 's/^/    /' >&2
    fi

    if [ -e "$RPM_DIR/template.spec" ]; then
        t_fail "desktopApp/packaging/rpm/template.spec still exists; it is silently ignored"
    else
        t_ok "desktopApp/packaging/rpm/template.spec is gone"
    fi
fi

# --- 2. the Windows installationPath is flat and distinct from the data root ---
#
# The first installationPath in the plugin is the Windows block (the Linux
# block follows). Evaluate the Kotlin string: $DESKTOP_PACKAGE_NAME interpolates
# and \\ is an escaped backslash.
win_line="$(grep -m1 'installationPath = ' "$PLUGIN")"
win_value="$(printf '%s\n' "$win_line" | sed -n 's/.*installationPath = "\([^"]*\)".*/\1/p')"
data_root="$(sed -n 's/^internal const val DESKTOP_PACKAGE_NAME = "\([^"]*\)".*/\1/p' "$PLUGIN" | head -1)"
actual="${win_value//\$DESKTOP_PACKAGE_NAME/$data_root}"
actual="${actual//\\\\/\\}"

if [ -n "$actual" ] && ! printf '%s' "$actual" | grep -q '[/\\]'; then
    t_ok "the Windows installationPath is a single directory name ($actual)"
else
    t_fail "the Windows installationPath is not a single directory name: $actual"
fi
if [ -n "$actual" ] && [ "$actual" != "$data_root" ]; then
    t_ok "the Windows installationPath is distinct from the data root ($data_root)"
else
    t_fail "the Windows installationPath is the data root: $actual"
fi

# The docs and the PLAN must record the same value, so the checker and the
# install guidance cannot drift from the authored configuration. The design's
# nativeDistributions sketch must carry the same literal as the plugin.
docs_line="$(grep -m1 'installationPath = ' "$DESIGN")"
docs_value="$(printf '%s\n' "$docs_line" | sed -n 's/.*installationPath = "\([^"]*\)".*/\1/p')"
if [ -n "$actual" ] && [ "$docs_value" = "$actual" ] \
    && grep -Fq "$actual" "$DESIGN" && grep -Fq "$actual" "$PLAN"; then
    t_ok "the design and PLAN record the Windows install path ($actual)"
else
    t_fail "the design or PLAN does not record the Windows install path ($actual)"
fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "desktop-packaging.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "desktop-packaging.test: all cases pass"
