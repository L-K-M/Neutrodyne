#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Owned loopback fixtures for the cross-device sync journey; no user data."""
import hashlib
from html.parser import HTMLParser
import http.client
import http.cookiejar
import http.server
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.sax.saxutils
from email.utils import formatdate


PODCASTS = 50
BASE_MS = 1_730_000_000_000
MAX_WIRE_BYTES = 2 * 1024 * 1024  # ProtocolLimits.MAX_REQUEST_BYTES.
MAX_RESPONSE_BYTES = 16 * 1024 * 1024
OWNER_FILE = '.cross-device-owner.json'
MAX_SERVER_LOG_BYTES = 1024 * 1024
HOP_HEADERS = {'connection', 'keep-alive', 'proxy-authenticate',
               'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'}


class HarnessError(Exception):
    """Messages are fixed stage/reason strings, never transport payloads."""


def require_run_id(run_id):
    if not isinstance(run_id, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', run_id):
        raise HarnessError('config: invalid run identity')


def new_workspace(parent, run_id):
    require_run_id(run_id)
    parent = Path(parent)
    parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='nd-cross-device-', dir=parent))
    root.chmod(0o700)
    private_json(root / OWNER_FILE, {'runId': run_id})
    return root


def require_workspace(root, run_id):
    require_run_id(run_id)
    root = Path(root)
    try:
        marker = root / OWNER_FILE
        if root.is_symlink() or marker.is_symlink() or not root.is_dir():
            raise ValueError()
        value = json.loads(marker.read_text())
        if value != {'runId': run_id}:
            raise ValueError()
    except (OSError, ValueError, TypeError):
        raise HarnessError('workspace: ownership does not match') from None
    return root


def private_json(path, value):
    path = Path(path)
    # A caller cannot redirect a private config write through an existing link.
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    with os.fdopen(os.open(path, flags, 0o600), 'w') as output:
        json.dump(value, output, separators=(',', ':'))
        output.write('\n')


def fixture_token(run_id, key):
    require_run_id(run_id)
    return hashlib.sha256(f'{run_id}:{key}'.encode()).hexdigest()[:32]


def feed_xml(run_id, index, base_url):
    if index not in range(PODCASTS):
        raise HarnessError('fixture: unknown podcast')
    token = fixture_token(run_id, f'episode:{index}')
    base = base_url.rstrip('/')
    esc = xml.sax.saxutils.escape
    published = formatdate((BASE_MS - index * 1000) / 1000, usegmt=True)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<rss version="2.0"><channel>'
        f'<title>Cross Device Feed {index}</title>'
        f'<link>{esc(base)}/feed-{index}</link><description>Local fixture</description>'
        f'<item><title>Cross Device Episode {index}</title><guid>{token}</guid>'
        f'<pubDate>{published}</pubDate><link>{esc(base)}/episode-{index}</link>'
        f'<enclosure url="{esc(base)}/episode-{index}.mp3" type="audio/mpeg" '
        f'length="{1_000_000 + index}"/></item></channel></rss>'
    ).encode()


class _FixtureHttpServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def handle_error(self, request, client_address):
        # BaseServer otherwise prints exceptions and request details to stderr.
        self.fixture.record_error()


class FixtureGateway:
    """Local RSS plus a controllable sync outage in front of an owned server.

    The target is a numeric loopback port, never a caller-provided upstream URL.
    Request bodies and credentials are not retained in counters or logs. SSE
    responses stream; changing to offline closes existing upstream connections.
    """

    def __init__(self, run_id):
        require_run_id(run_id)
        self.run_id = run_id
        self._lock = threading.Lock()
        self._connections = {}
        self._online = True
        self._backend_port = None
        self._requests = 0
        self._refused = 0
        self._errors = 0
        self._server = _FixtureHttpServer(('127.0.0.1', 0), _Handler)
        self._server.fixture = self
        self.url = f'http://127.0.0.1:{self._server.server_port}'
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._started = False

    @property
    def port(self):
        return self._server.server_port

    def set_backend(self, port):
        if type(port) is not int or not 1 <= port <= 65535:
            raise HarnessError('gateway: invalid loopback port')
        with self._lock:
            self._backend_port = port

    def start(self):
        self._thread.start()
        self._started = True
        return self

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.close()

    def set_online(self, online):
        with self._lock:
            self._online = bool(online)
            connections = list(self._connections.items()) if not online else []
        for connection, owned_socket in connections:
            self._close_connection(connection, owned_socket)

    def snapshot(self):
        with self._lock:
            return {'requests': self._requests, 'refused': self._refused, 'errors': self._errors}

    def record_error(self):
        with self._lock:
            self._errors += 1

    def connect(self):
        with self._lock:
            self._requests += 1
            if not self._online or self._backend_port is None:
                self._refused += 1
                return None
            connection = http.client.HTTPConnection('127.0.0.1', self._backend_port, timeout=2)
            try:
                # Connect while holding the gate, so going offline cannot miss a
                # request that was admitted but has not opened its socket yet.
                connection.connect()
                connection.sock.settimeout(30)
                owned_socket = connection.sock.dup()
            except OSError:
                connection.close()
                self._errors += 1
                self._refused += 1
                return None
            # HTTPConnection detaches/closes its socket for Connection: close,
            # while HTTPResponse still owns a file over it. Keep our own handle
            # to shut down that stream independently of the connection object.
            self._connections[connection] = owned_socket
            return connection

    def disconnect(self, connection):
        with self._lock:
            owned_socket = self._connections.pop(connection, None)
        self._close_connection(connection, owned_socket)

    @staticmethod
    def _close_connection(connection, owned_socket):
        if owned_socket is not None:
            try:
                owned_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            owned_socket.close()
        connection.close()

    def close(self):
        self.set_online(False)
        if self._started:
            self._server.shutdown()
        self._server.server_close()
        if self._started:
            self._thread.join(5)
            if self._thread.is_alive():
                raise HarnessError('gateway: listener did not stop')


class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_GET(self):
        feed = re.fullmatch(r'/feed-(\d+)\.xml', self.path)
        if feed:
            index = int(feed[1])
            if index not in range(PODCASTS):
                return self.reply(404, b'')
            return self.reply(200, feed_xml(self.server.fixture.run_id, index,
                                            self.server.fixture.url), 'application/rss+xml')
        return self.forward()

    def do_POST(self):
        return self.forward()

    def do_PUT(self):
        return self.forward()

    def do_DELETE(self):
        return self.forward()

    def reply(self, status, body, content_type='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        self.wfile.write(body)

    def forward(self):
        if not self.path.startswith('/') or urllib.parse.urlsplit(self.path).netloc:
            return self.reply(400, b'')
        lengths = self.headers.get_all('Content-Length') or []
        try:
            if len(lengths) > 1 or self.headers.get('Transfer-Encoding'):
                raise ValueError()
            length = int(lengths[0]) if lengths else 0
            if length < 0:
                raise ValueError()
        except ValueError:
            return self.reply(400, b'')
        if length > MAX_WIRE_BYTES:
            return self.reply(413, b'{"code":"payload_too_large"}')
        self.connection.settimeout(30)
        body = self.rfile.read(length) if length else None
        if body is not None and len(body) != length:
            return self.reply(400, b'')
        fixture = self.server.fixture
        connection = fixture.connect()
        if connection is None:
            return self.reply(503, b'{"code":"fixture_offline"}')
        response = None
        try:
            headers = {key: value for key, value in self.headers.items()
                       if key.lower() not in HOP_HEADERS | {'host', 'content-length'}}
            headers['Host'] = urllib.parse.urlsplit(fixture.url).netloc
            headers['Connection'] = 'close'
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP_HEADERS | {'server', 'date'}:
                    self.send_header(key, value)
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            streaming = 'text/event-stream' in (response.getheader('Content-Type') or '')
            total = 0
            while chunk := response.read1(8192):
                total += len(chunk)
                if not streaming and total > MAX_RESPONSE_BYTES:
                    raise HarnessError('gateway: upstream response exceeds fixture bound')
                self.wfile.write(chunk)
                self.wfile.flush()
        except (OSError, http.client.HTTPException, HarnessError):
            fixture.record_error()
            self.close_connection = True
        finally:
            if response is not None:
                response.close()
            fixture.disconnect(connection)


class _CsrfInput(HTMLParser):
    def __init__(self):
        super().__init__()
        self.values = []

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == 'input' and attributes.get('name') == 'csrf':
            self.values.append(attributes.get('value', ''))


class _SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, origin):
        self.origin = origin

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if (parsed.scheme, parsed.netloc) != self.origin:
            raise HarnessError('server: unexpected redirect origin')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def server_environment(tmp):
    # ServerConfig gives environment variables precedence over its properties.
    # In particular, never inherit a real deployment's NEUTRODYNE_SERVER_DATA_DIR
    # or Java option injection into an isolated fixture process.
    result = {'PATH': os.environ.get('PATH', ''), 'LANG': 'C.UTF-8',
              'TMPDIR': str(tmp), 'TMP': str(tmp), 'TEMP': str(tmp)}
    for name in ('SYSTEMROOT', 'SystemRoot', 'WINDIR'):
        if name in os.environ:
            result[name] = os.environ[name]
    return result


class OwnedSyncServer:
    """Launch the real server JAR over new data and use its normal setup form."""

    def __init__(self, workspace, run_id, jar, java):
        self.root = require_workspace(workspace, run_id)
        self.run_id = run_id
        self.jar = Path(jar).resolve()
        self.java = str(Path(java).resolve())
        if not self.jar.is_file() or not Path(self.java).is_file():
            raise HarnessError('server: runtime or JAR is missing')
        self.data = self.root / 'server-data'
        self._process = None
        self._reader = None
        self._log_failed = False
        self.url = None
        self._opener = None

    @property
    def returncode(self):
        return self._process.poll() if self._process is not None else None

    def start(self, gateway, timeout=60):
        if self._process is not None or self.data.exists():
            raise HarnessError('server: fixture data already exists')
        tmp = self.root / 'server-tmp'
        tmp.mkdir(mode=0o700)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        gateway.set_backend(port)
        self.url = gateway.url
        parsed = urllib.parse.urlsplit(self.url)
        self._opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            _SameOriginRedirect((parsed.scheme, parsed.netloc)),
        )
        config = self.root / 'server.properties'
        escaped_data = str(self.data).replace('\\', '\\\\')
        text = (f'data.dir={escaped_data}\nlisten=127.0.0.1:{port}\n'
                f'public.url={self.url}\nlog.level=warn\nupdate.check=false\n')
        if '\r' in str(self.root) or '\n' in str(self.root):
            raise HarnessError('workspace: invalid configuration path')
        with os.fdopen(os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
            output.write(text)
        self._process = subprocess.Popen(
            [self.java, '-Xmx384m', f'-Djava.io.tmpdir={tmp}', '-jar', str(self.jar),
             'serve', '--insecure-lan', '--config', str(config)],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            env=server_environment(tmp), start_new_session=(os.name == 'posix'),
        )
        try:
            private_json(self.root / 'server-child.json',
                         {'pid': self._process.pid, 'runId': self.run_id, 'config': str(config)})
            self._reader = threading.Thread(target=self._drain_log, daemon=True)
            self._reader.start()
            deadline = time.monotonic() + timeout
            while True:
                if self.returncode is not None:
                    raise HarnessError('server: child exited before readiness')
                try:
                    value = json.loads(self.request('/.well-known/neutrodyne-sync', timeout=1))
                    if isinstance(value, dict) and 'protocol' in value and (self.data / 'setup-token').is_file():
                        return self
                except (HarnessError, ValueError):
                    pass
                if time.monotonic() >= deadline:
                    raise HarnessError('server: readiness timed out')
                time.sleep(0.05)
        except BaseException:
            self.stop()
            raise

    def request(self, path, body=None, headers=None, timeout=30):
        if self._process is None or self.returncode is not None:
            raise HarnessError('server: child is not running')
        if not path.startswith('/'):
            raise HarnessError('server: invalid local path')
        request = urllib.request.Request(self.url + path, data=body, headers=headers or {})
        try:
            with self._opener.open(request, timeout=timeout) as response:
                result = response.read(MAX_RESPONSE_BYTES + 1)
                if len(result) > MAX_RESPONSE_BYTES:
                    raise HarnessError('server: fixture response exceeds bound')
                return result
        except urllib.error.HTTPError as error:
            error.close()
            raise HarnessError(f'server: HTTP {error.code}') from None
        except (OSError, urllib.error.URLError):
            raise HarnessError('server: local request failed') from None

    def create_account(self, username, password):
        if not re.fullmatch(r'[a-z0-9_-]{1,40}', username) or not 10 <= len(password) <= 256:
            raise HarnessError('setup: invalid fixture credentials')
        token_file = self.data / 'setup-token'
        if token_file.is_symlink() or not token_file.is_file() or token_file.stat().st_size > 128:
            raise HarnessError('setup: private setup token is unavailable')
        code = token_file.read_text().strip()
        page = self.request('/setup')
        parser = _CsrfInput()
        parser.feed(page.decode('utf-8'))
        if len(parser.values) != 1 or not parser.values[0]:
            raise HarnessError('setup: expected one CSRF input')
        form = urllib.parse.urlencode({'csrf': parser.values[0], 'code': code,
                                       'username': username, 'password': password}).encode()
        self.request('/setup', form, {'Content-Type': 'application/x-www-form-urlencoded',
                                      'Origin': self.url})
        if token_file.exists():
            raise HarnessError('setup: token was not consumed')
        discovery = json.loads(self.request('/.well-known/neutrodyne-sync'))
        if 'password' not in discovery.get('features', []):
            raise HarnessError('setup: password login was not enabled')

    def _drain_log(self):
        try:
            path = self.root / 'server-private.log'
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as log:
                total = 0
                while block := self._process.stdout.read(8192):
                    remaining = MAX_SERVER_LOG_BYTES - total
                    if remaining > 0:
                        log.write(block[:remaining])
                        total += min(remaining, len(block))
        except OSError:
            self._log_failed = True

    def stop(self):
        if self._process is None:
            return
        if self.returncode is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=5)
        if self._reader is not None and self._reader.ident is not None:
            self._reader.join(5)
        self._process.stdout.close()
        marker = self.root / 'server-child.json'
        try:
            saved = json.loads(marker.read_text())
            if saved.get('pid') == self._process.pid and saved.get('runId') == self.run_id:
                marker.unlink()
        except (OSError, ValueError, TypeError):
            pass
        if (self._reader is not None and self._reader.is_alive()) or self._log_failed:
            raise HarnessError('server: private log cleanup failed')
