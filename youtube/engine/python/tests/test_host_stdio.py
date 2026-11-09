# SPDX-License-Identifier: Unlicense
"""host_stdio framing, dispatch and bootstrap behaviour (11 "Stdio protocol")."""

import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest

from neutrodyne_ytx import host_stdio
from neutrodyne_ytx.errors import Code, ShimError
from neutrodyne_ytx.host_stdio import NeutrodyneUrllibRH, serve
from neutrodyne_ytx.replay import ReplayRH
from neutrodyne_ytx.selftest import probe
from make_scenarios import CTX, FIXTURES

ROOT = Path(__file__).resolve().parents[4]
PACKAGE = ROOT / 'youtube/engine/python/neutrodyne_ytx'
BOOTSTRAP = PACKAGE / 'bootstrap.py'
YT_DLP_ZIP = ROOT / 'youtube/ytdlp/engine/yt-dlp'


def deadline_ms():
    return int(time.time() * 1000) + 30_000


def line(obj):
    return (json.dumps(obj) + '\n').encode()


def call_frame(call_id, method='ping', payload=None, **extra):
    frame = {'t': 'call', 'id': call_id, 'm': method,
             'p': payload if payload is not None else {'ctx': CTX},
             'deadline': deadline_ms()}
    frame.update(extra)
    return line(frame)


def wait_for(predicate, seconds=10):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.02)
    return predicate()


class FakeBridge:
    def __init__(self, result='{"ready":true}', error=None, hold=None):
        self.result, self.error, self.hold = result, error, hold
        self.calls = []

    def call(self, method, payload, deadline, call_id, cancel):
        self.calls.append((method, json.loads(payload), call_id, cancel))
        # Unbounded: the duplicate-id test depends on the call staying in flight until
        # released; a timeout under a loaded runner would free the id and hide the
        # rejection. close()'s join deadline is the wedge guard.
        if self.hold is not None:
            self.hold.wait()
        if cancel.is_set():
            raise ShimError(Code.CANCELLED)
        if self.error is not None:
            raise self.error
        return self.result


def drive(bridge, request_lines, **kwargs):
    """Fixed-input serve(): for malformed input or pure EOF, where no reply is awaited."""
    out = io.BytesIO()
    stdin = io.BytesIO(b''.join(request_lines))
    code = serve(out, bridge, stdin=stdin, orphan_check_seconds=None, **kwargs)
    lines = [json.loads(raw) for raw in out.getvalue().split(b'\n') if raw]
    return code, lines


class Session:
    """Pipe-backed serve() session: the reader blocks like a real pipe, so calls
    and replies interleave exactly as they do across the JVM's streams."""

    def __init__(self, bridge, **kwargs):
        self.out = io.BytesIO()
        read_fd, write_fd = os.pipe()
        self.writer = os.fdopen(write_fd, 'wb')
        reader = os.fdopen(read_fd, 'rb')
        self.code = None
        self.thread = threading.Thread(
            target=self._run, args=(bridge, reader, kwargs), daemon=True)
        self.thread.start()

    def _run(self, bridge, reader, kwargs):
        self.code = serve(self.out, bridge, stdin=reader,
                          orphan_check_seconds=None, **kwargs)

    def send(self, raw):
        self.writer.write(raw)
        self.writer.flush()

    def lines(self):
        return [json.loads(raw) for raw in self.out.getvalue().split(b'\n') if raw]

    def wait_lines(self, count, seconds=10):
        self.lines_now = None
        if wait_for(lambda: len(self.lines()) >= count, seconds):
            self.lines_now = self.lines()
        return self.lines_now or []

    def close(self, seconds=15):
        self.writer.close()
        self.thread.join(seconds)
        if self.thread.is_alive():
            raise AssertionError('serve() did not exit on stdin EOF')
        return self.code


class ServeTest(unittest.TestCase):
    def test_hello_then_ok_reply(self):
        session = Session(FakeBridge())
        try:
            session.send(call_frame(1))
            lines = session.wait_lines(2)
            self.assertEqual('ready', lines[0]['state'])
            self.assertEqual('stdio', lines[0]['host'])
            self.assertEqual({'t': 'ok', 'id': 1, 'r': {'ready': True}}, lines[1])
        finally:
            self.assertEqual(0, session.close())

    def test_shim_error_maps_to_err_code(self):
        session = Session(FakeBridge(error=ShimError(Code.NETWORK)))
        try:
            session.send(call_frame(7))
            self.assertEqual({'t': 'err', 'id': 7, 'code': 'NETWORK'},
                             session.wait_lines(2)[-1])
        finally:
            session.close()

    def test_unknown_method_surfaces_extraction(self):
        session = Session(FakeBridge(error=ShimError(Code.EXTRACTION)))
        try:
            session.send(call_frame(3, 'bogus'))
            self.assertEqual({'t': 'err', 'id': 3, 'code': 'EXTRACTION'},
                             session.wait_lines(2)[-1])
        finally:
            session.close()

    def test_cancel_sets_the_call_event(self):
        hold = threading.Event()
        bridge = FakeBridge(hold=hold)
        session = Session(bridge)
        try:
            session.send(call_frame(9))
            self.assertTrue(wait_for(lambda: bool(bridge.calls)))
            session.send(line({'t': 'cancel', 'id': 9}))
            self.assertTrue(wait_for(lambda: bridge.calls[0][3].is_set()))
            hold.set()
            self.assertEqual({'t': 'err', 'id': 9, 'code': 'CANCELLED'},
                             session.wait_lines(2)[-1])
        finally:
            session.close()

    def test_cancel_unknown_id_is_ignored(self):
        session = Session(FakeBridge())
        try:
            session.send(line({'t': 'cancel', 'id': 42}))
            session.send(call_frame(1))
            self.assertEqual({'t': 'ok', 'id': 1, 'r': {'ready': True}},
                             session.wait_lines(2)[-1])
        finally:
            session.close()

    def test_malformed_json_line_exits(self):
        code, _ = drive(FakeBridge(), [b'{not json\n'])
        self.assertEqual(1, code)

    def test_non_object_line_exits(self):
        code, _ = drive(FakeBridge(), [b'[1,2]\n'])
        self.assertEqual(1, code)

    def test_oversize_line_exits(self):
        code, _ = drive(FakeBridge(), [b' ' * (host_stdio.LINE_BYTES + 2) + b'\n'])
        self.assertEqual(1, code)

    def test_missing_call_fields_exit(self):
        code, _ = drive(FakeBridge(), [b'{"t":"call","id":1}\n'])
        self.assertEqual(1, code)

    def test_bad_id_type_exits(self):
        code, _ = drive(FakeBridge(), [call_frame('x')])
        self.assertEqual(1, code)

    def test_unknown_kind_is_ignored(self):
        session = Session(FakeBridge())
        try:
            session.send(b'{"t":"jsc","x":1}\n')
            session.send(call_frame(2))
            self.assertEqual({'t': 'ok', 'id': 2, 'r': {'ready': True}},
                             session.wait_lines(2)[-1])
        finally:
            self.assertEqual(0, session.close())

    def test_duplicate_id_rejected(self):
        hold = threading.Event()
        bridge = FakeBridge(hold=hold)
        session = Session(bridge)
        try:
            session.send(call_frame(4))
            self.assertTrue(wait_for(lambda: bool(bridge.calls)))
            session.send(call_frame(4))
            # Keep the first call in flight until the reader rejects the duplicate.
            # Sending a line only flushes the pipe; it does not mean serve() read it.
            errors = [x for x in session.wait_lines(2) if x.get('t') == 'err']
            self.assertEqual(1, len(errors))
            self.assertEqual({'t': 'err', 'id': 4, 'code': 'ENGINE_UNAVAILABLE'}, errors[0])
            self.assertEqual(1, len(bridge.calls))
            hold.set()
            replies = [x for x in session.wait_lines(3) if x.get('t') == 'ok']
            self.assertEqual([{'t': 'ok', 'id': 4, 'r': {'ready': True}}], replies)
        finally:
            hold.set()
            session.close()

    def test_status_reply(self):
        session = Session(FakeBridge(), engine_version='2026.08.19')
        try:
            session.send(line({'t': 'status'}))
            status = [x for x in session.wait_lines(2) if x.get('t') == 'status']
            self.assertEqual('2026.08.19', status[0]['r']['engineVersion'])
            self.assertEqual(1, status[0]['r']['shimApi'])
            self.assertEqual(0, status[0]['r']['running'])
        finally:
            session.close()

    def test_eof_returns_cleanly(self):
        code, lines = drive(FakeBridge(), [])
        self.assertEqual(0, code)
        self.assertEqual(1, len(lines))  # hello only

    def test_debug_methods_absent_without_flag(self):
        bridge = FakeBridge(error=ShimError(Code.EXTRACTION))
        session = Session(bridge)
        try:
            session.send(call_frame(5, '_echo', payload={'x': 1}))
            self.assertEqual({'t': 'err', 'id': 5, 'code': 'EXTRACTION'},
                             session.wait_lines(2)[-1])
            self.assertEqual(1, len(bridge.calls))  # went to the bridge, not the hook
        finally:
            session.close()

    def test_debug_echo_returns_payload(self):
        session = Session(FakeBridge(), debug_hooks=True)
        try:
            session.send(call_frame(6, '_echo', payload={'x': 1}))
            self.assertEqual({'t': 'ok', 'id': 6, 'r': {'x': 1}},
                             session.wait_lines(2)[-1])
        finally:
            session.close()


class HandlerTest(unittest.TestCase):
    def tearDown(self):
        # The shared handler registry is per-process; leave it the way the other
        # shim suites expect: strict Replay armed on the resolve fixture.
        ReplayRH.configure(FIXTURES / 'resolve', mode='strict')
        host_stdio.install(ReplayRH)

    def test_probe_accepts_the_stdio_handler(self):
        host_stdio.install(NeutrodyneUrllibRH)
        result = probe(expected_handlers=host_stdio.HANDLERS)
        self.assertNotIn('exclusive_handler', result['missing'])
        result = probe()  # Chaquopy default rejects the urllib adapter
        self.assertIn('exclusive_handler', result['missing'])
        host_stdio.install(ReplayRH)
        ReplayRH.configure(FIXTURES / 'resolve', mode='strict')
        self.assertTrue(probe(expected_handlers=host_stdio.HANDLERS)['ok'])

    def test_install_rejects_unapproved_handler(self):
        from yt_dlp.networking._urllib import UrllibRH
        with self.assertRaises(ShimError):
            host_stdio.install(UrllibRH)


class BootstrapTest(unittest.TestCase):
    """The real child: bootstrap argv, hello handshake, framed calls, EOF exit."""

    def spawn(self, *extra):
        return subprocess.Popen(
            [sys.executable, '-I', '-X', 'utf8', '-B', str(BOOTSTRAP),
             '--lib', str(YT_DLP_ZIP), '--shim', str(PACKAGE),
             '--parent-pid', str(os.getpid()), *extra],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def read_line(self, proc, seconds=30):
        self.assertTrue(wait_for(lambda: proc.stdout.peek(1), seconds),
                        'child produced no output')
        return json.loads(proc.stdout.readline())

    def test_hello_ping_version_eof(self):
        proc = self.spawn()
        try:
            hello = self.read_line(proc)
            self.assertEqual('ready', hello['state'])
            self.assertEqual('stdio', hello['host'])
            self.assertEqual(proc.pid, hello['pid'])
            proc.stdin.write(call_frame(1))
            proc.stdin.flush()
            self.assertEqual({'ready': True}, self.read_line(proc)['r'])
            proc.stdin.write(call_frame(2, 'version'))
            proc.stdin.flush()
            version = self.read_line(proc)['r']
            self.assertEqual('stdio', version['host'])
            self.assertEqual(1, version['shimApi'])
            proc.stdin.close()
            self.assertEqual(0, proc.wait(15))
            self.assertNotIn(b'Traceback', proc.stderr.read())
        finally:
            proc.kill()

    def test_replay_resolve_matches_recorded_vector(self):
        scenario = FIXTURES / 'resolve'
        request = json.loads((scenario / 'request.json').read_text())
        expected = json.loads((scenario / 'result.json').read_text())
        proc = self.spawn('--replay', str(scenario))
        try:
            self.assertEqual('ready', self.read_line(proc)['state'])
            proc.stdin.write(call_frame(1, request['method'], request['payload']))
            proc.stdin.flush()
            reply = self.read_line(proc)
            self.assertEqual('ok', reply['t'])
            # availableAt is time.time()-derived; the fixture pins the generation clock.
            for fmt, expected_fmt in zip(reply['r']['formats'], expected['formats']):
                expected_fmt['availableAt'] = fmt['availableAt']
            self.assertEqual(expected, reply['r'])
            proc.stdin.close()
            self.assertEqual(0, proc.wait(15))
        finally:
            proc.kill()

    def test_malformed_input_kills_the_child(self):
        proc = self.spawn()
        try:
            self.read_line(proc)
            proc.stdin.write(b'{garbage\n')
            proc.stdin.flush()
            proc.stdin.close()
            self.assertEqual(1, proc.wait(15))
        finally:
            proc.kill()

    def test_debug_crash_exits_the_process(self):
        proc = self.spawn('--debug-hooks')
        try:
            self.read_line(proc)
            proc.stdin.write(call_frame(1, '_crash'))
            proc.stdin.flush()
            proc.stdin.close()
            self.assertEqual(70, proc.wait(15))
        finally:
            proc.kill()

    def test_compiling_hello_then_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            lib = Path(tmp) / 'versions' / 'test' / 'lib'
            lib.mkdir(parents=True)
            (lib / 'yt_dlp').mkdir()
            (lib / 'yt_dlp' / '__init__.py').write_text('__version__ = "0"\n')
            proc = subprocess.Popen(
                [sys.executable, '-I', '-X', 'utf8', '-B', str(BOOTSTRAP),
                 '--lib', str(lib), '--shim', str(PACKAGE), '--replay',
                 str(FIXTURES / 'resolve'), '--parent-pid', '0'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                first = self.read_line(proc)
                self.assertEqual('compiling', first['state'])
                # The full yt_dlp import will fail on the stub; the point is the
                # compiling hello and that compile ran (sources were consumed).
                self.assertTrue(wait_for(lambda: not (lib / 'yt_dlp/__init__.py').exists()))
            finally:
                proc.kill()


if __name__ == '__main__':
    unittest.main()
