#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Report-only live probe of the pinned shim on both hosts.

The embedded leg imports the shim in process (the Android host's adapter); the
stdio leg launches the desktop child through bootstrap.py and speaks the documented
JSON-lines protocol. The pinned bundle hash is checked once for both legs. Each
leg's sanitized result is reported independently: a green embedded result never
conceals a stdio failure, and the exit status is failure when either leg fails.
Deterministic tests never invoke the live probes.
"""
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'youtube/ytdlp/engine/yt-dlp'
PACKAGE = ROOT / 'youtube/engine/python/neutrodyne_ytx'
BOOTSTRAP = PACKAGE / 'bootstrap.py'
CHUNK = 10 * 1024 * 1024

# Wire limits mirrored from the shim/host (11 "Stdio protocol"): one framed line
# inbound or outbound, one call payload. The canary never sends more.
LINE_BYTES = 256 * 1024
PAYLOAD_BYTES = 16 * 1024
RESULT_BYTES = 128 * 1024
SHIM_API = 1
START_CALL_ID = -1
HELLO_TIMEOUT = 30.0
HANG_GRACE = 5.0
EOF_TIMEOUT = 10.0
REAP_TIMEOUT = 5.0

CTX = {'hl': 'en', 'gl': 'US', 'ua': 'Neutrodyne/YouTube-canary', 'ipFamily': None}

GENERIC_ERROR = 'Engine probe failed; runner bot challenges are possible'
UNKNOWN_STAGE = 'unknown'
UNEXPECTED_CODE = 'UNEXPECTED'
STAGES = ('lookup-mkbhd', 'lookup-nasa', 'resolve', 'chunk')
# Mirror of the shim's wire codes (Code in youtube/engine/python/neutrodyne_ytx/errors.py,
# pinned offline by the tests). These are the only failure codes a report may carry;
# no stage or code alone proves a runner bot challenge.
SAFE_CODES = frozenset({
    'RATE_LIMITED', 'AGE_RESTRICTED', 'MEMBERS_ONLY', 'PRIVATE', 'REGION_BLOCKED',
    'UPCOMING', 'LIVE', 'KIDS_ONLY', 'UNAVAILABLE', 'EXTRACTION', 'NETWORK',
    'TIMEOUT', 'CANCELLED', 'CURSOR_EXPIRED', 'ENGINE_UNAVAILABLE',
})


def run(bridge, fetch_chunk):
    results = []
    stage = None
    try:
        for stage, handle, call_id in (('lookup-mkbhd', '@mkbhd', 1), ('lookup-nasa', '@NASA', 2)):
            payload = json.dumps({'ctx': CTX, 'url': 'https://www.youtube.com/' + handle})
            value = json.loads(bridge.call('lookup', payload, time.monotonic() + 30, call_id, threading.Event()))
            if not value.get('channelId'):
                raise ValueError('Channel lookup failed')
            results.append({'channelId': value['channelId']})
        stage = 'resolve'
        value = json.loads(bridge.call('resolve', json.dumps({'ctx': CTX, 'videoId': '3iRUwVzRDZQ'}),
                                       time.monotonic() + 30, 3, threading.Event()))
        formats = [item for item in value['formats'] if item.get('protocol') in ('http', 'https')]
        if not formats:
            raise ValueError('No progressive audio')
        stage = 'chunk'
        size = fetch_chunk(formats[0]['url'])
        if not 0 < size <= CHUNK:
            raise ValueError('Invalid chunk size')
    except Exception as error:
        error.canary_stage = stage
        raise
    return {'ok': True, 'channels': results, 'chunkBytes': size}


def report(error, shim_error=()):
    """Failure JSON: the fixed safe message plus bounded stage/code enumerations.

    The raw error can contain a signed media URL or credentials, so the report never
    carries the exception text, class or URL. ``shim_error`` is the exception type
    whose ``code`` is already a wire enum (neutrodyne_ytx.errors.ShimError); every
    other failure, and any code outside SAFE_CODES, reports UNEXPECTED.
    """
    stage = getattr(error, 'canary_stage', None)
    code = getattr(error, 'code', None) if isinstance(error, shim_error) else None
    return {'ok': False, 'error': GENERIC_ERROR,
            'stage': stage if stage in STAGES else UNKNOWN_STAGE,
            'code': code if code in SAFE_CODES else UNEXPECTED_CODE}


class StdioError(Exception):
    """A sanitized stdio-leg failure; ``code`` is a wire enum or UNEXPECTED.

    The exception text is never a wire value and report() carries only ``code``;
    raw extractor or socket errors can contain signed media URLs.
    """

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class _Pending:
    __slots__ = ('event', 'result', 'error')

    def __init__(self):
        self.event = threading.Event()
        self.result = None
        self.error = None


class StdioHost:
    """The canary's own child-process adapter for the stdio host.

    This is not a transport: it implements only the handshake and call shape the
    canary needs. The child is launched from an argv list (never a shell) and is
    always killed and reaped by close() -- on success, failure, hang or parent
    cancellation. stderr is drained and discarded: it can carry signed media URLs
    and extractor exceptions, so nothing from it is logged or reported.
    """

    def __init__(self, bootstrap, package, lib, engine_version, replay=None,
                 python=None, debug_hooks=False, start_timeout=HELLO_TIMEOUT,
                 hang_grace=HANG_GRACE, eof_timeout=EOF_TIMEOUT):
        self.bootstrap = str(bootstrap)
        self.package = str(package)
        self.lib = str(lib)
        self.engine_version = engine_version
        self.replay = str(replay) if replay else None
        self.python = python or sys.executable
        self.debug_hooks = debug_hooks
        self.start_timeout = start_timeout
        self.hang_grace = hang_grace
        self.eof_timeout = eof_timeout
        self.hello = None
        self.returncode = None
        self._proc = None
        self._lock = threading.Lock()
        self._io_lock = threading.Lock()
        self._reap_lock = threading.Lock()
        self._pending = {}
        self._closed = False
        self._failure = None
        self._hello_queue = queue.Queue()
        self._frame_sent = threading.Event()

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc, traceback):
        self.close()
        return False

    def start(self):
        if self._proc is not None:
            raise StdioError(UNEXPECTED_CODE)
        argv = [self.python, '-I', '-X', 'utf8', '-B', self.bootstrap,
                '--lib', self.lib, '--shim', self.package,
                '--parent-pid', str(os.getpid()),
                '--engine-version', self.engine_version]
        if self.replay:
            argv += ['--replay', self.replay]
        if self.debug_hooks:
            argv.append('--debug-hooks')
        self._proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            threading.Thread(target=self._drain_stderr, daemon=True).start()
            threading.Thread(target=self._read_loop, daemon=True).start()
            self._await_handshake()
        except BaseException:
            # Parent cancellation during the handshake must not leave the child alive.
            self._fail_all(UNEXPECTED_CODE)
            self._reap(kill=True, timeout=REAP_TIMEOUT)
            self._close_streams()
            raise
        return self

    def _await_handshake(self):
        """Enforce hello, then the version call and the pinned engine/API identity."""
        hello = self._next_hello()
        while isinstance(hello, dict) and hello.get('state') == 'compiling':
            # A source tree announces compiling, then ready; the pinned zip does not.
            hello = self._next_hello()
        if (not isinstance(hello, dict) or hello.get('t') != 'hello'
                or hello.get('state') != 'ready' or hello.get('host') != 'stdio'
                or hello.get('shimApi') != SHIM_API
                or hello.get('pid') != self._proc.pid):
            raise StdioError(UNEXPECTED_CODE)
        reply = self.call('version', json.dumps({'ctx': CTX}),
                          time.monotonic() + self.start_timeout, START_CALL_ID)
        try:
            value = json.loads(reply)
        except ValueError:
            raise StdioError(UNEXPECTED_CODE) from None
        if (not isinstance(value, dict) or value.get('host') != 'stdio'
                or value.get('shimApi') != SHIM_API
                or value.get('ytDlp') != self.engine_version):
            raise StdioError(UNEXPECTED_CODE)
        self.hello = hello

    def _next_hello(self):
        try:
            hello = self._hello_queue.get(timeout=self.start_timeout)
        except queue.Empty:
            raise StdioError(UNEXPECTED_CODE) from None
        if hello is None:
            raise self._failure or StdioError(UNEXPECTED_CODE)
        return hello

    def call(self, method, payload, deadline, call_id, cancel=None):
        """One framed call; returns the JSON text of ``r`` or raises StdioError."""
        text = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
        if len(text.encode('utf-8')) > PAYLOAD_BYTES:
            raise StdioError('EXTRACTION')
        if type(call_id) is not int:
            raise StdioError(UNEXPECTED_CODE)
        try:
            request = json.loads(text)
        except ValueError:
            raise StdioError('EXTRACTION') from None
        pending = _Pending()
        with self._lock:
            if self._closed:
                raise self._failure or StdioError('ENGINE_UNAVAILABLE')
            if call_id in self._pending:
                raise StdioError('ENGINE_UNAVAILABLE')
            self._pending[call_id] = pending
        try:
            frame = {'t': 'call', 'id': call_id, 'm': method, 'p': request,
                     'deadline': int(time.time() * 1000
                                     + (deadline - time.monotonic()) * 1000)}
            self._send_frame(frame)
            wait = deadline - time.monotonic() + self.hang_grace
            if wait <= 0:
                self._hang_kill()
                raise StdioError('TIMEOUT')
            if not pending.event.wait(wait):
                self._hang_kill()
                raise StdioError('TIMEOUT')
            if cancel is not None and cancel.is_set():
                raise StdioError('CANCELLED')
            if pending.error is not None:
                raise pending.error
            if pending.result is None:
                raise StdioError('ENGINE_UNAVAILABLE')
            return pending.result
        finally:
            with self._lock:
                self._pending.pop(call_id, None)

    def _send_frame(self, message):
        data = (json.dumps(message, ensure_ascii=False, separators=(',', ':'))
                + '\n').encode('utf-8')
        if len(data) > LINE_BYTES:
            raise StdioError(UNEXPECTED_CODE)
        with self._lock:
            if self._closed or self._proc is None:
                raise self._failure or StdioError('ENGINE_UNAVAILABLE')
            with self._io_lock:
                try:
                    self._proc.stdin.write(data)
                    self._proc.stdin.flush()
                except (OSError, ValueError):
                    raise StdioError('ENGINE_UNAVAILABLE') from None
            self._frame_sent.set()

    def _read_loop(self):
        while True:
            try:
                raw = self._proc.stdout.readline(LINE_BYTES + 1)
            except (OSError, ValueError):
                break
            if not raw:
                break
            if len(raw) > LINE_BYTES:
                self._violation()
                return
            try:
                message = json.loads(raw)
            except ValueError:
                self._violation()
                return
            if not isinstance(message, dict):
                self._violation()
                return
            self._dispatch(message)
        self._fail_all('ENGINE_UNAVAILABLE')
        self._hello_queue.put(None)
        self._reap(kill=False, timeout=REAP_TIMEOUT)
        self._close_streams()

    def _dispatch(self, message):
        kind = message.get('t')
        if kind == 'hello':
            self._hello_queue.put(message)
            return
        if kind == 'ok':
            call_id = message.get('id')
            if type(call_id) is not int or 'r' not in message:
                self._violation()
                return
            result = json.dumps(message['r'], ensure_ascii=False, separators=(',', ':'))
            if len(result.encode('utf-8')) > RESULT_BYTES:
                self._violation()
                return
            with self._lock:
                pending = self._pending.get(call_id)
            if pending is not None:
                pending.result = result
                pending.event.set()
            return
        if kind == 'err':
            call_id = message.get('id')
            code = message.get('code')
            if type(call_id) is not int or not isinstance(code, str):
                self._violation()
                return
            with self._lock:
                pending = self._pending.get(call_id)
            if pending is not None:
                # An error frame is a rejected call, never a success; report()
                # allowlists the code.
                pending.error = StdioError(code)
                pending.event.set()
            return
        # status and unknown kinds are forward-compatible diagnostics; ignore them.

    def _violation(self):
        self._fail_all(UNEXPECTED_CODE)
        self._hello_queue.put(None)
        self._reap(kill=True, timeout=REAP_TIMEOUT)
        self._close_streams()

    def _fail_all(self, code):
        with self._lock:
            if self._failure is None:
                self._failure = StdioError(code)
            self._closed = True
            pending = list(self._pending.values())
            self._pending.clear()
        for item in pending:
            item.error = self._failure
            item.event.set()
        self._hello_queue.put(None)

    def _hang_kill(self):
        # The shim's hang semantics: the hung call reports TIMEOUT, the child dies.
        self._fail_all('TIMEOUT')
        self._reap(kill=True, timeout=REAP_TIMEOUT)
        self._close_streams()

    def _reap(self, kill, timeout):
        with self._reap_lock:
            proc = self._proc
            if proc is None:
                return None
            if proc.poll() is None:
                if kill:
                    try:
                        proc.kill()
                    except OSError:
                        pass
                try:
                    proc.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    return None
            self.returncode = proc.poll()
            return self.returncode

    def _drain_stderr(self):
        # Drain only: the child's stderr can carry signed URLs and extractor
        # exceptions, so it is never stored, logged or reported.
        try:
            while self._proc.stderr.read1(8192):
                pass
        except (OSError, ValueError):
            pass

    def close(self):
        """Always close, kill and reap this child; safe to call more than once."""
        if self._proc is None:
            return
        with self._lock:
            self._closed = True
            pending = list(self._pending.values())
            self._pending.clear()
        for item in pending:
            item.error = StdioError('ENGINE_UNAVAILABLE')
            item.event.set()
        with self._io_lock:
            try:
                self._proc.stdin.close()
            except (OSError, ValueError):
                pass
        if pending or self._failure is not None:
            self._reap(kill=True, timeout=REAP_TIMEOUT)
        elif self._reap(kill=False, timeout=self.eof_timeout) is None:
            self._reap(kill=True, timeout=REAP_TIMEOUT)
        self._close_streams()

    def _close_streams(self):
        if self._proc is None:
            return
        with self._io_lock:
            try:
                self._proc.stdin.close()
            except (OSError, ValueError):
                pass
        for stream in (self._proc.stdout, self._proc.stderr):
            try:
                stream.close()
            except (OSError, ValueError):
                pass


def run_embedded(fetch_chunk):
    """The existing in-process probe (the Android host's adapter)."""
    sys.path[:0] = [str(BUNDLE), str(ROOT / 'youtube/engine/python')]
    try:
        from neutrodyne_ytx.bridge import Bridge
        from neutrodyne_ytx.errors import ShimError
    except Exception as error:
        return report(error, ())
    try:
        return run(Bridge(cache_dir=False), fetch_chunk)
    except Exception as error:
        # The raw extractor or socket error can contain a signed media URL.
        return report(error, ShimError)


def run_stdio_leg(fetch_chunk, metadata, replay=None):
    """The same probe through the desktop stdio child, reported independently."""
    try:
        with StdioHost(BOOTSTRAP, PACKAGE, BUNDLE, metadata['version'],
                       replay=replay) as host:
            return run(host, fetch_chunk)
    except Exception as error:
        return report(error, StdioError)


def combine(embedded, stdio):
    """Both legs' sanitized results; a green leg never conceals a red one."""
    legs = {'embedded': embedded, 'stdio': stdio}
    if embedded.get('ok') and stdio.get('ok'):
        return {'ok': True, 'legs': legs}
    failure = embedded if not embedded.get('ok') else stdio
    return {'ok': False, 'error': GENERIC_ERROR,
            'stage': failure.get('stage', UNKNOWN_STAGE),
            'code': failure.get('code', UNEXPECTED_CODE),
            'legs': legs}


def fetch_chunk(url):
    request = urllib.request.Request(url, headers={'Range': f'bytes=0-{CHUNK - 1}'})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status != 206:
            raise ValueError('Media did not honor Range')
        return len(response.read(CHUNK + 1))


def main():
    metadata = json.loads((BUNDLE.parent / 'bundled.json').read_text())
    if hashlib.sha256(BUNDLE.read_bytes()).hexdigest() != metadata['sha256']:
        raise ValueError('Pinned engine hash mismatch')
    embedded = run_embedded(fetch_chunk)
    stdio = run_stdio_leg(fetch_chunk, metadata)
    result = combine(embedded, stdio)
    Path('youtube-engine-canary.json').write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
