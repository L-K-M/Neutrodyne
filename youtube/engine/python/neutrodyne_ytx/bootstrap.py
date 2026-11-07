# SPDX-License-Identifier: Unlicense
"""Desktop CPython child entry point (11 "Process model").

Spawned as `python -I -X utf8 -B <shim>/bootstrap.py --lib <lib> --shim <shim>
--parent-pid <pid> [--replay <dir>] [--debug-hooks]`. The JVM owns the process:
this file puts the shim package and the prepared engine lib on sys.path, moves
stdout to stderr so only framed protocol lines leave on fd 1, compiles a fresh
engine lib once (announcing `compiling`), then runs host_stdio.serve().

<shim> is the neutrodyne_ytx package directory itself; its parent goes on
sys.path so `import neutrodyne_ytx.*` resolves. <lib> is the prepared
versions/<version>/lib directory holding the extracted yt-dlp tree.
"""

import argparse
import os
import sys


def main(argv):
    parser = argparse.ArgumentParser(prog='neutrodyne_ytx.bootstrap')
    parser.add_argument('--lib', required=True)
    parser.add_argument('--shim', required=True)
    parser.add_argument('--parent-pid', type=int, default=0)
    parser.add_argument('--engine-version')
    parser.add_argument('--replay')
    parser.add_argument('--cache')
    parser.add_argument('--debug-hooks', action='store_true')
    args = parser.parse_args(argv)

    real_stdout = os.dup(1)  # protocol fd: everything after this goes to stderr
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    sys.path.insert(0, os.path.abspath(args.lib))
    sys.stdout = sys.stderr
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    out = os.fdopen(real_stdout, 'wb', buffering=0)
    lib = os.path.abspath(args.lib)
    # The JVM writes .compiled-<major>.<minor> after the ready hello; the child
    # also wants the sentinel .pyc so a torn tree recompiles instead of crashing.
    # A file (zipimported bundle, tests) needs no compilation.
    marker = os.path.join(lib, '.compiled-%d.%d' % sys.version_info[:2])
    sentinel = os.path.join(lib, 'yt_dlp', '__init__.pyc')
    compiled = not os.path.isdir(lib) or (os.path.isfile(marker) and os.path.isfile(sentinel))
    if not compiled:
        # Before the marker exists the lib is still source: announce and compile
        # once, then delete the .py so a tampered tree cannot shadow bytecode.
        out.write(b'{"t":"hello","state":"compiling"}\n')
        out.flush()
        from neutrodyne_ytx.prepare import compile_bundle
        compile_bundle(lib)

    from neutrodyne_ytx import host_stdio
    engine_version = args.engine_version
    if not engine_version:
        engine_version = os.path.basename(os.path.dirname(lib))
    bridge = host_stdio.configure(args.cache or False, args.replay)
    return host_stdio.serve(
        out, bridge, parent_pid=args.parent_pid or None,
        engine_version=engine_version, debug_hooks=args.debug_hooks)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
