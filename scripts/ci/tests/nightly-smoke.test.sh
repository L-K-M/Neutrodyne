#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# nightly-smoke.test.sh — the desktop-matrix "Smoke start every package" step of
# .github/workflows/nightly.yml, executed for real: the step text is extracted
# from the YAML, `${{ matrix.target }}` is bound to linux-x64, and the runner
# commands (sudo, dpkg, xvfb-run, dbus-run-session, rpm2cpio, cpio) plus
# scripts/desktop/smoke-start.sh are stubs around it. A smoke-start that fails
# or stays silent must fail the step — a plain `bash -c` without pipefail lets
# tee mask the failure and a >=1 SMOKE count then lets it pass.
#
# Also asserted: every checkout in nightly.yml pins github.sha (the `ref` input
# is gone; a job may never check out anything but the run's head_sha).
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
if ! python3 - "$NIGHTLY" > "$WORK/step.sh" <<'PY'
import re, sys

lines = open(sys.argv[1]).read().splitlines()
indent = lambda s: len(s) - len(s.lstrip(' '))

job = next(i for i, l in enumerate(lines) if l.strip() == 'desktop-matrix:')
job_end = next((i for i in range(job + 1, len(lines))
                if lines[i].strip() and indent(lines[i]) <= indent(lines[job])),
               len(lines))
step = next(i for i in range(job, job_end)
            if lines[i].strip() == '- name: Smoke start every package')
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
then
    echo "not ok: cannot extract the 'Smoke start every package' literal run block" >&2
    exit 1
fi
# The matrix bind and the scratch path are environment details, not the boundary
# under test; everything else in the step runs as committed.
sed -i "s/\${{ matrix\.target }}/linux-x64/g; s|/tmp/smoke-img|$WORK/smoke-img|g" "$WORK/step.sh"

# --- stubs and fixture layout -----------------------------------------------------

STUBBIN="$WORK/bin"
RUNDIR="$WORK/run"
mkdir -p "$STUBBIN" "$RUNDIR/dist" "$RUNDIR/scripts/desktop" "$WORK/smoke-img"

stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$STUBBIN/$1"; chmod +x "$STUBBIN/$1"; }
stub sudo 'exec "$@"'
stub dpkg 'exit 0'
stub xvfb-run '[ "${1:-}" = "-a" ] && shift; exec "$@"'
stub dbus-run-session 'exec "$@"'
stub rpm2cpio 'exit 0'
stub cpio 'exit 0'

# The fake packaged app: exits 1 when its image dir matches SMOKE_FAIL_AT,
# succeeds silently on SMOKE_QUIET_AT, else prints its SMOKE line.
cat > "$RUNDIR/scripts/desktop/smoke-start.sh" <<'EOF'
#!/usr/bin/env bash
[ "$1" = "${SMOKE_FAIL_AT:-}" ] && exit 1
[ "$1" = "${SMOKE_QUIET_AT:-}" ] && exit 0
echo "SMOKE {\"image\":\"$1\"}"
exit 0
EOF
chmod +x "$RUNDIR/scripts/desktop/smoke-start.sh"

: > "$RUNDIR/dist/neutrodyne-0.1.0-linux-x64.deb"
: > "$RUNDIR/dist/neutrodyne-0.1.0-linux-x64.rpm"
mkdir -p "$WORK/tar-src" && tar -czf "$RUNDIR/dist/neutrodyne-0.1.0-linux-x64.tar.gz" -C "$WORK/tar-src" .

# run_step — execute the step text the way `shell: bash` does (the outer shell
# carries -eo pipefail; the step's own `set` line may add more).
run_step() {
    (cd "$RUNDIR" && PATH="$STUBBIN:$PATH" bash -eo pipefail "$WORK/step.sh" > "$WORK/step.out" 2>&1)
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
if SMOKE_FAIL_AT=/opt/neutrodyne run_step; then
    t_fail "a failing DEB smoke fails the step"
    sed 's/^/    /' "$WORK/step.out" >&2
else
    t_ok "a failing DEB smoke fails the step"
fi

# 3. A package smoke that produces no SMOKE line at all must not pass on the
#    strength of the other two — every package's own smoke counts.
rm -f "$RUNDIR/smoke.txt"
if SMOKE_QUIET_AT="$WORK/smoke-img/opt/neutrodyne" run_step; then
    t_fail "a silent RPM smoke fails the step"
    sed 's/^/    /' "$WORK/step.out" >&2
else
    t_ok "a silent RPM smoke fails the step"
fi

# 4. Checkout discipline: every actions/checkout in nightly.yml pins github.sha
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

echo
if [ "$FAILED" -gt 0 ]; then
    echo "nightly-smoke.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "nightly-smoke.test: all cases behave"
