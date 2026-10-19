# SPDX-License-Identifier: Unlicense
"""Python half of W4's host. Importing it does not start Java, prepare or bind a service."""

import threading
import time

from .bridge import Bridge
from .errors import Code, ShimError
from .okhttp_rh import NeutrodyneOkHttpRH, install

_bridge = None
_calls = {}
_lock = threading.Lock()


def configure(cache_dir, py_http=None, replay_directory=None):
    """Host calls once after preparation, before worker admission. Replay must be test-armed."""
    global _bridge
    with _lock:
        if _bridge is not None:
            raise ShimError(Code.ENGINE_UNAVAILABLE)
        if replay_directory is not None:
            from .replay import ReplayRH
            ReplayRH.configure(replay_directory)
            install(ReplayRH)
        else:
            if py_http is None:
                from java import jclass
                py_http = jclass('ch.lkmc.neutrodyne.youtube.ytdlp.ytx.PyHttp')
            execute = getattr(py_http, 'execute', None)
            if not callable(execute):
                raise ShimError(Code.ENGINE_UNAVAILABLE)
            install(NeutrodyneOkHttpRH, execute)
        from .selftest import probe
        if not probe()['ok']:
            raise ShimError(Code.ENGINE_UNAVAILABLE)
        _bridge = Bridge(cache_dir=cache_dir)


def call(method, payload, remaining_ms, call_id):
    """W4 subtracts elapsedRealtime queue/bind time before passing remaining budget here."""
    entered = time.monotonic()
    with _lock:
        if _bridge is None or call_id in _calls:
            raise ShimError(Code.ENGINE_UNAVAILABLE)
        event = _calls[call_id] = threading.Event()
    try:
        return _bridge.call(method, payload, entered + max(0, remaining_ms) / 1000, call_id, event)
    finally:
        with _lock:
            _calls.pop(call_id, None)


def cancel(call_id):
    # Java also cancels this ID's in-flight sockets; Python stops generator work/next requests.
    with _lock:
        event = _calls.get(call_id)
        if event is not None:
            event.set()
