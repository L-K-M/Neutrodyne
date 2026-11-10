#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# package-bootstrap.sh — the bounded package-manager bootstrap shared by
# playback/native/ffmpeg/build.sh and scripts/ci/tests/native-ffmpeg-build.test.sh.
#
# The manylinux_2_28 container may lack NASM (the x86-64 build's assembly) and
# patchelf (the optional RUNPATH repair). A package manager that accepts a
# connection and then stalls would otherwise sit silent until the job's own
# timeout, which is what PR448's desktop-smoke run did at "installing NASM".
# Every manager call is bounded and its output retained, so a stall or a failure
# is visible in the build log instead of a silent disable.

# bounded <seconds> <command...> — a hard bound on every network, gpg and
# package-manager call so a stalling download or a hanging gpg fails the build
# with a message instead of sitting silent until the job's timeout. Both Windows
# legs that died in silence (runs 41479793169 and 41500707088) produced no
# output for the rest of the job somewhere in the download → gpg window. GNU
# timeout (coreutils on MSYS2 and manylinux) kills the whole process group;
# macOS uses Perl's alarm before exec, avoiding a watchdog child that keeps
# Gradle's output pipe open after failure.
if command -v timeout >/dev/null 2>&1 && timeout --version >/dev/null 2>&1; then
    bounded() { timeout --kill-after=5 "$1" "${@:2}"; }
else
    bounded() { perl -e 'alarm shift; exec @ARGV or die $!;' "$@"; }
fi

# build.sh defines log with its own prefix and the tests define their own; the
# fallback keeps the library sourceable on its own.
if ! declare -F log >/dev/null 2>&1; then
    log() { echo "$*"; }
fi

# bootstrap_package <required|optional> <budget-seconds> <package> <log-dir> <tool> <manager...>
#
# Install <package> with the first <manager> that provides <tool>, under a total
# hard budget of <budget-seconds> shared by every fallback. Each manager's
# stdout and stderr are retained in <log-dir>/<tool>-<manager>.log, so the CI
# artifact carries the evidence of a stall or a failure. `required` returns 1
# with the log tails on stderr when no manager works; `optional` returns 1 with
# a warning, keeping the caller's best-effort semantics.
bootstrap_package() {
    local mode="$1" budget="$2" package="$3" log_dir="$4" tool="$5"
    shift 5
    local managers=("$@")
    local manager manager_log status started remaining
    command -v "$tool" >/dev/null 2>&1 && return 0
    started="$SECONDS"
    for manager in "${managers[@]}"; do
        command -v "$manager" >/dev/null 2>&1 || continue
        remaining=$((budget - (SECONDS - started)))
        [ "$remaining" -gt 0 ] || break
        manager_log="$log_dir/$tool-$manager.log"
        log "installing $package with $manager (budget ${remaining}s)"
        status=0
        bounded "$remaining" "$manager" install -y "$package" >"$manager_log" 2>&1 || status=$?
        if [ "$status" -eq 0 ] && command -v "$tool" >/dev/null 2>&1; then
            log "installed $package with $manager"
            return 0
        fi
        log "$manager failed with status $status (log: $manager_log)"
    done
    if [ "$mode" = required ]; then
        log "could not install $package: no package manager provided $tool"
        for manager in "${managers[@]}"; do
            manager_log="$log_dir/$tool-$manager.log"
            [ -f "$manager_log" ] || continue
            echo "--- $manager_log ---" >&2
            tail -20 "$manager_log" >&2
        done
        return 1
    fi
    log "warning: could not install $package; continuing without $tool"
    return 1
}
