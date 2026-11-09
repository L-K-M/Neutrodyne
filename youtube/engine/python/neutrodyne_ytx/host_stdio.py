# SPDX-License-Identifier: Unlicense
"""Stdio host adapter: the desktop CPython child's wire (11 "Stdio protocol").

One JSON object per line on stdin; the saved real stdout carries only protocol
frames. Engine calls run on a small pool so a `cancel` line interrupts in-flight
work. `bootstrap.py` owns interpreter setup and stdout redirection; `serve()`
below is the frame loop and is also driven in-process by the unit tests.
"""

import json
import os
import platform
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from yt_dlp.networking._urllib import UrllibRH

from . import SHIM_API_VERSION
from .bridge import Bridge, current_call
from .errors import Code, ShimError

LINE_BYTES = 256 * 1024
WORKERS = 4
ORPHAN_CHECK_SECONDS = 5.0

# Handler keys this host accepts in probe(): its urllib adapter or the test RHs.
HANDLERS = ('NeutrodyneUrllib', 'Replay', 'Recording')

# Debug methods exist only under --debug-hooks (tests fault-inject through them).
DEBUG_METHODS = ('_hang', '_crash', '_echo', '_emit_raw')


class NeutrodyneUrllibRH(UrllibRH):
    """Per-call source_address so googlevideo's ip= matches the resolved family (A2).

    The ctx travels on the call, not the YoutubeDL params, and InstanceStoreMixin
    caches openers keyed by _get_instance kwargs: re-injecting the bind address
    there keeps a family flip from reusing the other family's opener.
    """

    _BIND = {'V4': '0.0.0.0', 'V6': '::'}

    def _send(self, request):
        self.source_address = self._BIND.get(current_call().ctx.get('ipFamily'))
        return super()._send(request)

    def _get_instance(self, **kwargs):
        return super()._get_instance(source_address=self.source_address, **kwargs)

    def _create_instance(self, proxies, cookiejar, legacy_ssl_support=None, source_address=None):
        # _get_instance folds the bind address into the cache key; _create_instance
        # reads self.source_address, which _send set on this same (locked) worker.
        return super()._create_instance(proxies, cookiejar, legacy_ssl_support)


def install(handler):
    """The engine permits exactly one transport handler, mirroring okhttp_rh.install."""
    from yt_dlp.networking import common
    if handler.RH_KEY not in HANDLERS:
        raise ShimError(Code.ENGINE_UNAVAILABLE)
    common._REQUEST_HANDLERS.clear()
    common._REQUEST_HANDLERS[handler.RH_KEY] = handler


def configure(cache_dir, replay_directory=None):
    """Install the exclusive transport, probe the bundle, return the shared Bridge."""
    if replay_directory is not None:
        from .replay import ReplayRH
        ReplayRH.configure(replay_directory)
        install(ReplayRH)
    else:
        install(NeutrodyneUrllibRH)
    from .selftest import probe
    if not probe(expected_handlers=HANDLERS)['ok']:
        raise ShimError(Code.ENGINE_UNAVAILABLE)
    return Bridge(cache_dir=cache_dir, host='stdio', expected_handlers=HANDLERS)


def _parent_alive(pid, platform_name=os.name):
    """Reparenting check: once the JVM dies, init adopts the child and getppid changes."""
    if platform_name == 'nt':
        import ctypes
        kernel = ctypes.windll.kernel32
        handle = kernel.OpenProcess(0x100000, False, pid)  # SYNCHRONIZE
        if not handle:
            return False
        try:
            return kernel.WaitForSingleObject(handle, 0) == 0x102  # WAIT_TIMEOUT: still running
        finally:
            kernel.CloseHandle(handle)
    return os.getppid() == pid


def _orphan_guard(parent_pid, interval):
    """A child that outlives its JVM must die instead of polling YouTube unsupervised."""
    while _parent_alive(parent_pid):
        time.sleep(interval)
    os._exit(1)


def _versions():
    from yt_dlp.version import __version__ as ytdlp
    try:
        from yt_dlp_ejs import version as ejs
    except Exception:
        ejs = None
    return ytdlp, ejs


def serve(out, bridge, *, parent_pid=None, engine_version=None, debug_hooks=False,
          stdin=None, orphan_check_seconds=ORPHAN_CHECK_SECONDS):
    """Frame loop until stdin EOF or a malformed line; returns the process exit code.

    `out` is the saved binary stdout (bootstrap redirects sys.stdout to stderr).
    All emits hold one lock so worker replies never interleave mid-line.
    """
    if parent_pid and orphan_check_seconds:
        threading.Thread(
            target=_orphan_guard, args=(parent_pid, orphan_check_seconds),
            name='nd-ytx-orphan', daemon=True).start()

    write_lock = threading.Lock()
    calls = {}
    counters = {'running': 0, 'queued': 0}
    state_lock = threading.Lock()
    ytdlp_version, ejs_version = _versions()

    def emit(obj):
        data = json.dumps(obj, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
        with write_lock:
            out.write(data.encode('utf-8') + b'\n')
            out.flush()

    def emit_raw(text):
        with write_lock:
            out.write(text.encode('utf-8', errors='replace') + b'\n')
            out.flush()

    ytdlp_version, ejs_version = _versions()
    emit({'t': 'hello', 'state': 'ready', 'shimApi': SHIM_API_VERSION, 'host': 'stdio',
          'python': platform.python_version(), 'ytDlp': ytdlp_version, 'ejs': ejs_version,
          'jsc': False, 'pid': os.getpid()})

    def run_call(call_id, method, payload_text, deadline, event):
        with state_lock:
            counters['queued'] -= 1
            counters['running'] += 1
        try:
            if method == '_hang' and debug_hooks:
                event.wait(3600)
                result = 'null'
            elif method == '_crash' and debug_hooks:
                os._exit(70)
            elif method == '_echo' and debug_hooks:
                result = payload_text
            elif method == '_emit_raw' and debug_hooks:
                emit_raw(json.loads(payload_text)['raw'])
                result = 'null'
            else:
                result = bridge.call(method, payload_text, deadline, call_id, event)
            # A cancelled or deadline-passed call's late reply is still emitted:
            # the JVM matches on id and discards it; killing the process here would
            # take healthy in-flight calls down with it.
            try:
                reply = json.loads(result)
            except ValueError:
                emit({'t': 'err', 'id': call_id, 'code': Code.EXTRACTION.value})
            else:
                emit({'t': 'ok', 'id': call_id, 'r': reply})
        except ShimError as error:
            emit({'t': 'err', 'id': call_id, 'code': error.code})
        finally:
            with state_lock:
                calls.pop(call_id, None)
                counters['running'] -= 1

    def dispatch(line):
        call_id = line.get('id')
        method = line.get('m')
        payload = line.get('p')
        deadline_ms = line.get('deadline')
        if (type(call_id) is not int or not isinstance(method, str)
                or not isinstance(payload, dict) or type(deadline_ms) not in (int, float)):
            return False
        remaining = deadline_ms / 1000 - time.time()
        deadline = time.monotonic() + max(0, remaining)
        event = threading.Event()
        with state_lock:
            if call_id in calls:
                emit({'t': 'err', 'id': call_id, 'code': Code.ENGINE_UNAVAILABLE.value})
                return True
            calls[call_id] = event
            counters['queued'] += 1
        pool.submit(run_call, call_id, method, json.dumps(payload, ensure_ascii=False),
                    deadline, event)
        return True

    pool = ThreadPoolExecutor(WORKERS, thread_name_prefix='nd-ytx')
    source = stdin if stdin is not None else sys.stdin.buffer
    try:
        while True:
            raw = source.readline(LINE_BYTES + 1)
            if not raw:
                return 0  # stdin closed: the shim exits on end of file (11 "Stdio protocol")
            if len(raw) > LINE_BYTES:
                return 1  # oversize frame: a malformed line is never dispatched
            try:
                line = json.loads(raw)
            except ValueError:
                return 1
            if not isinstance(line, dict):
                return 1
            kind = line.get('t')
            if kind == 'call':
                if not dispatch(line):
                    return 1
            elif kind == 'cancel':
                call_id = line.get('id')
                if type(call_id) is int:
                    with state_lock:
                        event = calls.get(call_id)
                    if event is not None:
                        event.set()
            elif kind == 'status':
                with state_lock:
                    running, queued = counters['running'], counters['queued']
                emit({'t': 'status', 'r': {
                    'pid': os.getpid(), 'running': running, 'queued': queued,
                    'engineVersion': engine_version, 'ejsVersion': ejs_version,
                    'shimApi': SHIM_API_VERSION, 'python': platform.python_version(),
                    'jsChallenges': False}})
            # Unknown line kinds are ignored: newer shimApi versions add kinds.
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
