#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# nightly-smoke.test.sh — the desktop-matrix "Smoke start every package" step of
# .github/workflows/nightly.yml, executed for real: the step text is extracted
# from the YAML, `${{ matrix.target }}` is bound to linux-x64, and the runner
# commands (sudo, dpkg, dpkg-deb, xvfb-run, dbus-run-session, rpm2cpio, cpio)
# plus scripts/desktop/smoke-start.sh are stubs around it. A smoke-start that
# fails or stays silent must fail the step — a plain `bash -c` without pipefail
# lets tee mask the failure and a >=1 SMOKE count then lets it pass.
#
# The installed DEB is also asserted smoke-safe: `dpkg -i` lands a root-owned
# /opt/neutrodyne that smoke-start.sh's .cfg append cannot write (nightly
# 37831500506, linux-x64), so the step must smoke a CI-owned package-payload
# extract and must never pass the installed path to the launcher.
#
# Also asserted: every checkout in nightly.yml pins github.sha (the `ref` input
# is gone; a job may never check out anything but the run's head_sha); the
# linux-arm64 leg excludes the Android host-test tasks (their aapt2/aidl/
# Robolectric pieces are x86_64-only); the "Image and runtime checks" step
# selects its image dir per target (an `ls -d A B | head -1` pick exits
# nonzero under the step's pipefail when only one glob matches — nightly
# 37858231006); the windows-x64 smoke leg surfaces msiexec's real exit code
# and resolves the per-user install root through cygpath; and every
# scripts/**.sh invoked directly by the workflows carries the executable bit
# (a 644 fixture script dies with exit 126 at the step's first line).
#
# Run: bash scripts/ci/tests/nightly-smoke.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
NIGHTLY="$REPO_ROOT/.github/workflows/nightly.yml"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

# --- extract the step ------------------------------------------------------------

# stdlib-only YAML handling: the step's `run: |` literal block is located by
# indentation, so the test runs anywhere bash+python3 do — no PyYAML. A missing
# step or a non-literal `run:` is an extraction failure, not an empty pass.
extract_step() { # <step name> <out file> — the step's `run: |` literal, unbound
    python3 - "$NIGHTLY" "$1" <<'PY'
import re, sys

lines = open(sys.argv[1]).read().splitlines()
want = sys.argv[2]
indent = lambda s: len(s) - len(s.lstrip(' '))

job = next(i for i, l in enumerate(lines) if l.strip() == 'desktop-matrix:')
job_end = next((i for i in range(job + 1, len(lines))
                if lines[i].strip() and indent(lines[i]) <= indent(lines[job])),
               len(lines))
step = next(i for i in range(job, job_end)
            if lines[i].strip() == '- name: ' + want)
step_end = next((i for i in range(step + 1, job_end)
                 if lines[i].strip().startswith('- ')
                 and indent(lines[i]) <= indent(lines[step])), job_end)
run = next(i for i in range(step, step_end)
           if re.fullmatch(r'\s*run:\s*\|\s*', lines[i]))
run_indent = indent(lines[run])
body = []
for l in lines[run + 1:]:
    if l.strip() and indent(l) <= run_indent:
        break
    body.append(l)
base = min(indent(l) for l in body if l.strip())
sys.stdout.write(''.join(l[base:] + '\n' for l in body))
PY
}

if ! extract_step 'Smoke start every package' > "$WORK/step-raw.sh"; then
    echo "not ok: cannot extract the 'Smoke start every package' literal run block" >&2
    exit 1
fi
if ! extract_step 'Image and runtime checks' > "$WORK/imgstep-raw.sh"; then
    echo "not ok: cannot extract the 'Image and runtime checks' literal run block" >&2
    exit 1
fi
# The matrix bind and the scratch path are environment details, not the boundary
# under test; everything else in the step runs as committed.
sed "s/\${{ matrix\.target }}/linux-x64/g; s|/tmp/smoke-img|$WORK/smoke-img|g" "$WORK/step-raw.sh" > "$WORK/step.sh"

# --- stubs and fixture layout -----------------------------------------------------

STUBBIN="$WORK/bin"
RUNDIR="$WORK/run"
mkdir -p "$STUBBIN" "$RUNDIR/dist" "$RUNDIR/scripts/desktop" "$WORK/smoke-img"

stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$STUBBIN/$1"; chmod +x "$STUBBIN/$1"; }
stub sudo 'exec "$@"'
stub dpkg 'touch "$DID_INSTALL"'
# The step smokes the package payload extracted under its own scratch dir;
# the stub materialises the installed layout so the launcher lookup matches.
stub dpkg-deb 'mkdir -p "$3/opt/neutrodyne"'
stub xvfb-run '[ "${1:-}" = "-a" ] && shift; exec "$@"'
stub dbus-run-session 'exec "$@"'
stub rpm2cpio 'exit 0'
stub cpio 'exit 0'
# windows-x64 leg stubs: powershell prints the stubbed msiexec ExitCode
# (PS_EXIT_CODE) and writes a marker msiexec-install.log the diagnostics grep;
# cygpath -u passes the (already POSIX) fixture root through and -w's value is
# only embedded in the PowerShell command; python's `zipfile -e` is a no-op —
# the smoke stub accepts any image dir.
stub powershell '{ echo "Property(C): INSTALLDIR = C:\\Users\\runneradmin\\AppData\\Local\\Programs\\Neutrodyne"; echo "MSI (s) (00:00): Product: Neutrodyne -- Installation completed successfully."; } > msiexec-install.log; echo "${PS_EXIT_CODE:-0}"'
stub cygpath '[ "$1" = -u ] && { echo "$2"; exit; }; echo "WINPATH"'
stub python 'exit 0'
stub codesign 'exit 0'

# The fake packaged app: exits 1 when its image dir matches SMOKE_FAIL_AT,
# succeeds silently on SMOKE_QUIET_AT, else prints its SMOKE line. Every image
# dir it was pointed at is logged so the DEB leg's target can be checked.
cat > "$RUNDIR/scripts/desktop/smoke-start.sh" <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$SMOKE_ARGS"
[ "$1" = "${SMOKE_FAIL_AT:-}" ] && exit 1
[ "$1" = "${SMOKE_QUIET_AT:-}" ] && exit 0
echo "SMOKE {\"image\":\"$1\"}"
exit 0
EOF
chmod +x "$RUNDIR/scripts/desktop/smoke-start.sh"

# Stubs for the "Image and runtime checks" step: record argv so the resolved
# image dir can be asserted; the real checkers are exercised elsewhere.
mkdir -p "$RUNDIR/scripts/ci"
cat > "$RUNDIR/scripts/ci/check-desktop-image.sh" <<'EOF'
#!/usr/bin/env bash
echo "$@" >> "$IMG_CHECK_ARGS"
exit 0
EOF
cat > "$RUNDIR/scripts/ci/check-runtime-sources.sh" <<'EOF'
#!/usr/bin/env bash
prev=""; for a in "$@"; do [ "$prev" = --image ] && echo "$a" >> "$IMG_ARG"; prev="$a"; done
exit 0
EOF
chmod +x "$RUNDIR/scripts/ci/"check-*.sh

: > "$RUNDIR/dist/neutrodyne-0.1.0-linux-x64.deb"
: > "$RUNDIR/dist/neutrodyne-0.1.0-linux-x64.rpm"
mkdir -p "$WORK/tar-src" && tar -czf "$RUNDIR/dist/neutrodyne-0.1.0-linux-x64.tar.gz" -C "$WORK/tar-src" .

# run_step — execute the step text the way `shell: bash` does (the outer shell
# carries -eo pipefail; the step's own `set` line may add more).
run_step() {
    (cd "$RUNDIR" && DID_INSTALL="$WORK/did-install" SMOKE_ARGS="$WORK/smoke-args" \
        PATH="$STUBBIN:$PATH" bash -eo pipefail "$WORK/step.sh" > "$WORK/step.out" 2>&1)
}

smoke_lines() { grep -c '^SMOKE {' "$RUNDIR/smoke.txt" 2>/dev/null || true; }

# --- cases -------------------------------------------------------------------------

# 1. All three package smokes pass: step succeeds and smoke.txt holds one SMOKE
#    line per package.
rm -f "$RUNDIR/smoke.txt"
if run_step && [ "$(smoke_lines)" -eq 3 ]; then
    t_ok "all package smokes succeeding passes the step"
else
    t_fail "all package smokes succeeding passes the step"
    sed 's/^/    /' "$WORK/step.out" >&2
fi

# 2. The DEB smoke fails: the step must fail even though the RPM and tar.gz
#    smokes still print their lines (the nested bash needs its own pipefail).
rm -f "$RUNDIR/smoke.txt"
if SMOKE_FAIL_AT="$WORK/smoke-img/deb-payload/opt/neutrodyne" run_step; then
    t_fail "a failing DEB smoke fails the step"
    sed 's/^/    /' "$WORK/step.out" >&2
else
    t_ok "a failing DEB smoke fails the step"
fi

# 3. The installed /opt/neutrodyne is root-owned — smoke-start.sh appends its
#    smoke java-option to the image .cfg, so the DEB leg must smoke the
#    extracted package payload instead. `dpkg -i` still runs (install coverage)
#    and no launcher argument may be the installed path.
rm -f "$RUNDIR/smoke.txt" "$WORK/smoke-args" "$WORK/did-install"
run_step
if [ ! -f "$WORK/did-install" ]; then
    t_fail "the DEB package is still installed before its smoke"
elif grep -qxF '/opt/neutrodyne' "$WORK/smoke-args"; then
    t_fail "the DEB smoke never targets the read-only installed image"
    sed 's/^/    /' "$WORK/smoke-args" >&2
else
    t_ok "the DEB smoke never targets the read-only installed image"
fi

# 4. A package smoke that produces no SMOKE line at all must not pass on the
#    strength of the other two — every package's own smoke counts.
rm -f "$RUNDIR/smoke.txt"
if SMOKE_QUIET_AT="$WORK/smoke-img/opt/neutrodyne" run_step; then
    t_fail "a silent RPM smoke fails the step"
    sed 's/^/    /' "$WORK/step.out" >&2
else
    t_ok "a silent RPM smoke fails the step"
fi

# 5. The release path shares the installed-DEB rule: build-desktop-release.sh
#    keeps `dpkg -i` install coverage but must smoke the extracted payload,
#    never the root-owned /opt/neutrodyne.
REL="$REPO_ROOT/scripts/ci/build-desktop-release.sh"
if grep -q 'dpkg -i' "$REL" \
    && grep -q 'dpkg-deb -x' "$REL" \
    && ! grep -nE '\bx?smoke +["'"'"']?/opt/neutrodyne' "$REL"; then
    t_ok "the release DEB smoke also spares the installed image"
else
    t_fail "the release DEB smoke also spares the installed image"
    grep -nE 'dpkg|smoke' "$REL" >&2
fi

# 6. Checkout discipline: every actions/checkout in nightly.yml pins github.sha
#    (its step's first `ref:` under `with:`) and the removed `ref` input is
#    neither declared under workflow_dispatch nor referenced. Line-scan, no YAML
#    library.
if python3 - "$NIGHTLY" <<'PY'
import re, sys

text = open(sys.argv[1]).read()
lines = text.splitlines()
indent = lambda s: len(s) - len(s.lstrip(' '))

problems = []
for i, l in enumerate(lines):
    s = l.strip()
    if not (s.startswith('- uses:') and 'actions/checkout@' in s):
        continue
    step_indent, ref = indent(l), None
    for l2 in lines[i + 1:]:
        s2 = l2.strip()
        if s2 and indent(l2) <= step_indent:
            break
        m = re.match(r'ref:\s*([^#]+?)\s*$', s2)
        if m:
            ref = m.group(1)
            break
    if ref != '${{ github.sha }}':
        problems.append('checkout step at line %d: ref=%r' % (i + 1, ref))

wd = next((i for i, l in enumerate(lines) if l.strip() == 'workflow_dispatch:'), None)
if wd is not None:
    end = next((i for i in range(wd + 1, len(lines))
                if lines[i].strip() and indent(lines[i]) <= indent(lines[wd])),
               len(lines))
    for l in lines[wd + 1:end]:
        if re.match(r'ref\s*:', l.strip()):
            problems.append("workflow_dispatch still declares the removed 'ref' input")
            break
if 'inputs.ref' in text:
    problems.append('inputs.ref is still referenced')
for p in problems:
    print('problem:', p, file=sys.stderr)
sys.exit(1 if problems else 0)
PY
then
    t_ok "every checkout pins github.sha and inputs.ref is gone"
else
    t_fail "every checkout pins github.sha and inputs.ref is gone"
fi

# 7. The linux-arm64 leg cannot run the Android host tests: the SDK build-tools
#    are x86_64 binaries and Robolectric has no linux/aarch64 runtime (nightly
#    37831500506). The desktop-matrix unit-test step must exclude exactly those
#    tasks on linux-arm64 while every other leg runs the full `allTests test`.
if python3 - "$NIGHTLY" <<'PY'
import re, sys

lines = open(sys.argv[1]).read().splitlines()
indent = lambda s: len(s) - len(s.lstrip(' '))

job = next(i for i, l in enumerate(lines) if l.strip() == 'desktop-matrix:')
job_end = next((i for i in range(job + 1, len(lines))
                if lines[i].strip() and indent(lines[i]) <= indent(lines[job])),
               len(lines))
step = next(i for i in range(job, job_end)
            if lines[i].strip() == '- name: Unit tests on this host')
step_end = next((i for i in range(step + 1, job_end)
                 if lines[i].strip().startswith('- ')
                 and indent(lines[i]) <= indent(lines[step])), job_end)
body = '\n'.join(lines[step:step_end])

problems = []
if 'linux-arm64' not in body:
    problems.append('the step has no linux-arm64 branch')
for task in ('testAndroidHostTest', 'testDebugUnitTest'):
    if not re.search(r'linux-arm64[^`]*-x %s\b' % task, body, re.S):
        problems.append('linux-arm64 branch does not exclude -x %s' % task)
arm, _, rest = body.partition('linux-arm64')
if not re.search(r'gradlew allTests test\b(?!.*-x test)', rest, re.S):
    problems.append('the non-arm64 leg lost the unfiltered `allTests test` line')
for p in problems:
    print('problem:', p, file=sys.stderr)
sys.exit(1 if problems else 0)
PY
then
    t_ok "linux-arm64 excludes only the Android host test tasks"
else
    t_fail "linux-arm64 excludes only the Android host test tasks"
fi

# 8. "Image and runtime checks" must select the image dir the target actually
#    produces — dist/Neutrodyne on linux/windows, dist/Neutrodyne.app on macOS.
#    An `ls -d A B | head -1` pick exits nonzero under the step's pipefail when
#    only one glob matches (nightly 37858231006: rc2 before any checker ran).
run_imgstep() { # <target> — bind the extracted step to a target, run it
    sed "s/\${{ matrix\.target }}/$1/g" "$WORK/imgstep-raw.sh" > "$WORK/imgstep.sh"
    (cd "$RUNDIR" && IMG_ARG="$WORK/img-arg" IMG_CHECK_ARGS="$WORK/img-check-args" \
        PATH="$STUBBIN:$PATH" bash -eo pipefail "$WORK/imgstep.sh" > "$WORK/imgstep.out" 2>&1)
}
mkdir -p "$RUNDIR/dist/Neutrodyne"
rm -f "$WORK/img-arg"
if run_imgstep linux-x64 && [ "$(cat "$WORK/img-arg" 2>/dev/null)" = "dist/Neutrodyne" ]; then
    t_ok "image checks pick dist/Neutrodyne on linux-x64"
else
    t_fail "image checks pick dist/Neutrodyne on linux-x64"
    sed 's/^/    /' "$WORK/imgstep.out" >&2
fi
rm -rf "$RUNDIR/dist/Neutrodyne"
mkdir -p "$RUNDIR/dist/Neutrodyne.app"
rm -f "$WORK/img-arg"
if run_imgstep macos-arm64 && [ "$(cat "$WORK/img-arg" 2>/dev/null)" = "dist/Neutrodyne.app" ]; then
    t_ok "image checks pick dist/Neutrodyne.app on macos-arm64"
else
    t_fail "image checks pick dist/Neutrodyne.app on macos-arm64"
    sed 's/^/    /' "$WORK/imgstep.out" >&2
fi
rm -rf "$RUNDIR/dist/Neutrodyne.app"
if run_imgstep linux-x64; then
    t_fail "a missing image dir fails with a clear message"
elif grep -q 'dist/Neutrodyne.*missing' "$WORK/imgstep.out"; then
    t_ok "a missing image dir fails with a clear message"
else
    t_fail "a missing image dir fails with a clear message"
    sed 's/^/    /' "$WORK/imgstep.out" >&2
fi

# 9. windows-x64 leg: msiexec's verdict must be explicit — a nonzero ExitCode
#    fails the step with the installer log tailed, never smoking a missing
#    install dir; the per-user root reaches bash through cygpath -u (nightly
#    37858231006: hidden install result → smoke-start usage, rc2, zero SMOKE).
LAPP="$WORK/lappdata"
mkdir -p "$LAPP/Programs/Neutrodyne"
sed "s/\${{ matrix\.target }}/windows-x64/g; s|/tmp/smoke-img|$WORK/smoke-img|g" \
    "$WORK/step-raw.sh" > "$WORK/step-win.sh"
run_win() {
    (cd "$RUNDIR" && LOCALAPPDATA="$LAPP" SMOKE_ARGS="$WORK/smoke-args-win" \
        PS_EXIT_CODE="${PS_EXIT_CODE:-0}" \
        PATH="$STUBBIN:$PATH" bash -eo pipefail "$WORK/step-win.sh" > "$WORK/step-win.out" 2>&1)
}
rm -f "$WORK/smoke-args-win" "$RUNDIR/smoke.txt"
if run_win \
    && grep -qxF "$LAPP/Programs/Neutrodyne" "$WORK/smoke-args-win" \
    && grep -qxF "$WORK/smoke-img/Neutrodyne" "$WORK/smoke-args-win" \
    && [ "$(grep -c . "$WORK/smoke-args-win")" -eq 2 ]; then
    t_ok "windows leg gates msiexec, smokes the install root and the ZIP extract"
else
    t_fail "windows leg gates msiexec, smokes the install root and the ZIP extract"
    sed 's/^/    /' "$WORK/step-win.out" >&2
fi
rm -f "$WORK/smoke-args-win" "$RUNDIR/smoke.txt"
if PS_EXIT_CODE=1603 run_win; then
    t_fail "a failed msiexec fails the step without smoking the install dir"
elif grep -q 'ExitCode=1603' "$WORK/step-win.out" \
      && ! grep -q 'Programs/Neutrodyne' "$WORK/smoke-args-win" 2>/dev/null; then
    t_ok "a failed msiexec fails the step without smoking the install dir"
else
    t_fail "a failed msiexec fails the step without smoking the install dir"
    sed 's/^/    /' "$WORK/step-win.out" >&2
fi
# ExitCode 0 without the install root is still a failure — and it must carry the
# installer log's evidence (nightly 37860407043: ExitCode=0, Programs/ absent).
rm -f "$WORK/smoke-args-win" "$RUNDIR/smoke.txt"
rm -rf "$LAPP/Programs/Neutrodyne"
if run_win; then
    t_fail "ExitCode 0 without the install root fails with the msiexec log"
elif grep -q 'MSI succeeded but .* is missing' "$WORK/step-win.out" \
      && grep -q 'INSTALLDIR' "$WORK/step-win.out" \
      && ! grep -q 'Programs/Neutrodyne' "$WORK/smoke-args-win" 2>/dev/null; then
    t_ok "ExitCode 0 without the install root fails with the msiexec log"
else
    t_fail "ExitCode 0 without the install root fails with the msiexec log"
    sed 's/^/    /' "$WORK/step-win.out" >&2
fi
mkdir -p "$LAPP/Programs/Neutrodyne"

# 10. Every scripts/**.sh a workflow invokes directly must carry the executable
#     bit in the checkout — a 644 file dies with exit 126 at the step's first
#     line (ci 37858163173's fixture step; nightly 37858231006's mac-zip.sh).
probs=""
for wf in nightly.yml release.yml ci.yml; do
    while IFS= read -r s; do
        [ -x "$REPO_ROOT/$s" ] || probs="$probs $wf:$s"
    done < <(grep -oE 'scripts/[a-zA-Z0-9/_-]+\.sh' \
              "$REPO_ROOT/.github/workflows/$wf" | sort -u)
done
if [ -z "$probs" ]; then
    t_ok "every directly-invoked script is executable"
else
    t_fail "every directly-invoked script is executable —$probs"
fi

# 11. The `ls | head` image pick must not come back in either CI caller.
if grep -l 'ls -d dist/Neutrodyne dist/Neutrodyne.app' "$NIGHTLY" \
        "$REPO_ROOT/scripts/ci/build-desktop-release.sh" 2>/dev/null | grep -q .; then
    t_fail "the pipefail-unsafe image pick is gone"
else
    t_ok "the pipefail-unsafe image pick is gone"
fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "nightly-smoke.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "nightly-smoke.test: all cases behave"
