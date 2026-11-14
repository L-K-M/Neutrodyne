#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Real loopback boundaries for the cross-device journey fixtures."""
import contextlib
import http.client
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location(
    'cross_device_fixtures', Path(__file__).parents[1] / 'cross_device_fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


class Backend(http.server.ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self):
        super().__init__(('127.0.0.1', 0), BackendHandler)
        self.received = []
        self.stream_release = threading.Event()
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stream_release.set()
        self.shutdown()
        self.server_close()
        self.thread.join(5)


class BackendHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.do_POST()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        self.server.received.append((self.path, dict(self.headers), body))
        self.send_response(200)
        if self.path == '/api/v1/events':
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            self.wfile.write(b'data: ready\n\n')
            self.wfile.flush()
            self.server.stream_release.wait(15)
        else:
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', '11')
            self.end_headers()
            self.wfile.write(b'{"ok":true}')


def request(gateway, path, body=None, headers=None):
    client = http.client.HTTPConnection('127.0.0.1', gateway.port, timeout=5)
    try:
        client.request('POST' if body is not None else 'GET', path, body, headers or {})
        response = client.getresponse()
        return response.status, response.read()
    finally:
        client.close()


class WorkspaceTests(unittest.TestCase):
    def test_log_thread_start_failure_stops_the_owned_child(self):
        with tempfile.TemporaryDirectory() as directory:
            root = fixtures.new_workspace(directory, 'run-1')
            jar = root / 'fixture.jar'
            jar.write_bytes(b'not executed')
            gateway = fixtures.FixtureGateway('run-1')
            child = mock.Mock()
            child.pid = 12345
            child.stdout = io.BytesIO()
            state = {'returncode': None}
            child.poll.side_effect = lambda: state['returncode']
            child.terminate.side_effect = lambda: state.update(returncode=-15)
            child.wait.return_value = -15
            reader = mock.Mock()
            reader.ident = None
            reader.is_alive.return_value = False
            reader.start.side_effect = RuntimeError('synthetic thread start failure')
            server = fixtures.OwnedSyncServer(root, 'run-1', jar, sys.executable)
            try:
                with mock.patch.object(fixtures.subprocess, 'Popen', return_value=child), \
                        mock.patch.object(fixtures.threading, 'Thread', return_value=reader):
                    with self.assertRaises(RuntimeError):
                        server.start(gateway)
                self.assertEqual(-15, child.poll(), 'startup failure must stop its child')
                self.assertTrue(child.stdout.closed)
                reader.join.assert_not_called()
            finally:
                gateway.close()

    def test_server_cannot_inherit_a_real_deployment_or_java_options(self):
        with mock.patch.dict(os.environ, {'NEUTRODYNE_SERVER_DATA_DIR': '/not-the-fixture',
                                          'JAVA_TOOL_OPTIONS': '-Dnot.a.fixture=true',
                                          '_JAVA_OPTIONS': '-Dnot.a.fixture=true',
                                          'GH_TOKEN': 'test-only-sentinel'}):
            environment = fixtures.server_environment('/owned-tmp')
        for name in ('NEUTRODYNE_SERVER_DATA_DIR', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'GH_TOKEN'):
            self.assertNotIn(name, environment)
        self.assertEqual('/owned-tmp', environment['TMPDIR'])

    def test_ownership_and_private_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = fixtures.new_workspace(directory, 'run-1')
            self.assertEqual(root, fixtures.require_workspace(root, 'run-1'))
            with self.assertRaises(fixtures.HarnessError):
                fixtures.require_workspace(root, 'different-run')
            if os.name == 'posix':
                self.assertEqual(0o700, root.stat().st_mode & 0o777)
                self.assertEqual(0o600, (root / fixtures.OWNER_FILE).stat().st_mode & 0o777)

    def test_existing_config_is_never_truncated_or_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / 'config.json'
            original.write_text('preserve')
            with self.assertRaises(FileExistsError):
                fixtures.private_json(original, {'password': 'dummy'})
            self.assertEqual('preserve', original.read_text())

    def test_unowned_directory_and_invalid_run_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(fixtures.HarnessError):
                fixtures.require_workspace(directory, 'run-1')
            with self.assertRaises(fixtures.HarnessError):
                fixtures.new_workspace(directory, '../escape')


class GatewayTests(unittest.TestCase):
    def test_all_fifty_feeds_have_their_seeded_identity(self):
        with fixtures.FixtureGateway('run-1') as gateway:
            guids = set()
            for index in range(50):
                status, data = request(gateway, f'/feed-{index}.xml')
                self.assertEqual(200, status)
                item = ET.fromstring(data).find('channel/item')
                self.assertEqual(f'Cross Device Episode {index}', item.findtext('title'))
                self.assertEqual(gateway.url + f'/episode-{index}.mp3', item.find('enclosure').attrib['url'])
                guids.add(item.findtext('guid'))
            self.assertEqual(50, len(guids))
            self.assertEqual(404, request(gateway, '/feed-50.xml')[0])

    def test_forwarding_preserves_body_auth_and_public_host_without_logging(self):
        with Backend() as backend, fixtures.FixtureGateway('run-1') as gateway:
            gateway.set_backend(backend.server_port)
            output = io.StringIO()
            payload = b'{"password":"test-only-sentinel"}'
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                status, response = request(gateway, '/api/v1/auth/login', payload,
                                           {'Authorization': 'Bearer test-only-token',
                                            'Content-Type': 'application/json'})
            self.assertEqual((200, b'{"ok":true}'), (status, response))
            path, headers, body = backend.received[0]
            self.assertEqual('/api/v1/auth/login', path)
            self.assertEqual(payload, body)
            self.assertEqual('Bearer test-only-token', headers['Authorization'])
            self.assertEqual(f'127.0.0.1:{gateway.port}', headers['Host'])
            self.assertEqual('', output.getvalue())
            self.assertEqual({'requests': 1, 'refused': 0, 'errors': 0}, gateway.snapshot())

    def test_offline_refuses_sync_without_reaching_the_backend_then_recovers(self):
        with Backend() as backend, fixtures.FixtureGateway('run-1') as gateway:
            gateway.set_backend(backend.server_port)
            gateway.set_online(False)
            self.assertEqual(503, request(gateway, '/api/v1/sync', b'{}')[0])
            self.assertEqual([], backend.received)
            gateway.set_online(True)
            self.assertEqual(200, request(gateway, '/api/v1/sync', b'{}')[0])
            self.assertEqual(1, len(backend.received))

    def test_oversize_request_does_not_reach_the_backend(self):
        with Backend() as backend, fixtures.FixtureGateway('run-1') as gateway:
            gateway.set_backend(backend.server_port)
            self.assertEqual(413, request(gateway, '/api/v1/sync', b'',
                                          {'Content-Length': str(2 * 1024 * 1024 + 1)})[0])
            self.assertEqual([], backend.received)

    def test_offline_closes_an_existing_stream_even_with_connection_close(self):
        with Backend() as backend, fixtures.FixtureGateway('run-1') as gateway:
            gateway.set_backend(backend.server_port)
            client = http.client.HTTPConnection('127.0.0.1', gateway.port, timeout=20)
            client.request('GET', '/api/v1/events')
            response = client.getresponse()
            self.assertEqual(b'data: ready\n\n', response.read(13))
            ended = threading.Event()

            def read_until_eof():
                try:
                    response.read()
                except OSError:
                    pass
                finally:
                    ended.set()

            reader = threading.Thread(target=read_until_eof, daemon=True)
            reader.start()
            try:
                gateway.set_online(False)
                self.assertTrue(ended.wait(5), 'the owned stream stayed alive after the outage began')
            finally:
                backend.stream_release.set()
                reader.join(5)
                response.close()
                client.close()


@unittest.skipUnless(os.environ.get('ND_E12_TEST_SERVER_JAR'), 'set ND_E12_TEST_SERVER_JAR for real server proof')
class RealServerTests(unittest.TestCase):
    def test_normal_setup_and_password_login_through_the_gateway(self):
        jar = os.environ['ND_E12_TEST_SERVER_JAR']
        java = os.environ.get('ND_E12_TEST_JAVA_BIN')
        if not java:
            java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
        with tempfile.TemporaryDirectory() as directory, fixtures.FixtureGateway('server-proof') as gateway:
            root = fixtures.new_workspace(directory, 'server-proof')
            server = fixtures.OwnedSyncServer(root, 'server-proof', jar, java)
            try:
                server.start(gateway)
                server.create_account('fixtureadmin', 'test-only-password-123')
                payload = json.dumps({
                    'username': 'fixtureadmin', 'password': 'test-only-password-123',
                    'device': {'id': 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'name': 'Fixture proof',
                               'platform': 'linux', 'appVersion': '0.1.0', 'nodeId': 'a000000000000001'},
                }).encode()
                value = json.loads(server.request('/api/v1/auth/login', payload,
                                                  {'Content-Type': 'application/json',
                                                   'Neutrodyne-Sync-Protocol': '1'}))
                self.assertEqual('fixtureadmin', value['username'])
                self.assertEqual('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', value['deviceId'])
                self.assertTrue(value['token'])
            finally:
                server.stop()
            self.assertIsNotNone(server.returncode)


if __name__ == '__main__':
    unittest.main()
