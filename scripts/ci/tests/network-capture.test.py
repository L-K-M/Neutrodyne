#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fixture-based tests for the v1.0 gate network-capture checker (09 v1.0 gate row 8).

No live capture: the checker runs against a recorded host list with the maintainer's
declarations, the extractor runs against a fake tshark, and the proxy is exercised over
loopback sockets only.
"""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

REPO = Path(__file__).resolve().parents[3]
CAPTURE = REPO / "scripts/ci/network-capture.py"
PROXY = REPO / "scripts/ci/network-capture-proxy.py"
SHELL = REPO / "scripts/ci/network-capture.sh"
PRIVACY = REPO / "PRIVACY.md"
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "network-capture"


def run_capture(*args, env=None):
    return subprocess.run(
        [sys.executable, str(CAPTURE), *map(str, args)],
        capture_output=True,
        text=True,
        env=env,
    )


def read_declarations():
    return [
        line.strip()
        for line in (FIXTURES / "expect.txt").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def load_capture_module():
    spec = importlib.util.spec_from_file_location("network_capture", CAPTURE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NetworkCaptureCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.hosts = FIXTURES / "hosts.txt"
        self.declarations = read_declarations()

    def check(self, *extra, hosts=None, declarations=None):
        args = ["check", "--hosts", str(hosts or self.hosts), "--privacy", str(PRIVACY)]
        for declaration in self.declarations if declarations is None else declarations:
            args += ["--expect", declaration]
        return run_capture(*args, *extra)

    def test_recorded_host_list_maps_to_the_inventory(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, (FIXTURES / "expected.tsv").read_text(encoding="utf-8"))
        self.assertIn("16 hosts mapped, 0 unmapped", result.stderr)

    def test_an_undeclared_user_chosen_host_fails(self):
        declarations = [item for item in self.declarations if not item.startswith("cdn.example.com=")]
        result = self.check(declarations=declarations)
        self.assertEqual(result.returncode, 1)
        self.assertIn("unmapped host: cdn.example.com", result.stderr)

    def test_rest_api_host_maps_to_engine_updates(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("api.github.com\tyoutube-engine\n", result.stdout)
        self.assertNotIn("api.github.com\tapp-updates", result.stdout)

    def test_an_unknown_inventory_id_is_rejected(self):
        result = self.check(declarations=["cdn.example.com=not-an-id"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not an inventory ID", result.stderr)

    def test_sync_server_flag_maps_the_linked_server(self):
        declarations = [item for item in self.declarations if not item.startswith("sync.example.org=")]
        result = self.check("--sync-server", "sync.example.org", declarations=declarations)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("sync.example.org\tsync-server", result.stdout)

    def test_require_observes_the_expected_ids(self):
        for identifier in (
            "feeds",
            "media",
            "artwork",
            "add-input",
            "apple",
            "fyyd",
            "podcastindex",
            "youtube-subscriptions",
            "youtube-streams",
            "youtube-engine",
            "app-updates",
            "sync-server",
            "links",
            "issue-tracker",
            "server-updates",
        ):
            with self.subTest(identifier=identifier):
                result = self.check("--require", identifier)
                self.assertEqual(result.returncode, 0, result.stderr)
        result = self.check("--require", "chapters")
        self.assertEqual(result.returncode, 1)
        self.assertIn("required inventory ID not observed: chapters", result.stderr)

    def test_every_fixed_pattern_id_exists_in_privacy_md(self):
        module = load_capture_module()
        inventory = set(module.inventory_ids(PRIVACY))
        for _, identifiers in module.FIXED_HOST_PATTERNS:
            for identifier in identifiers:
                self.assertIn(identifier, inventory)

        hosts = self.root / "fixed-hosts.txt"
        hosts.write_text(
            "\n".join(
                ("rr3" + pattern if pattern.startswith(".") else pattern)
                for pattern, _ in module.FIXED_HOST_PATTERNS
            )
            + "\n"
            + module.default_engine_host()
            + "\n",
            encoding="utf-8",
        )
        result = self.check(hosts=hosts, declarations=[])
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_host_extraction_normalizes_dns_sni_and_proxy_lines(self):
        tools = self.root / "bin"
        tools.mkdir()
        fake = tools / "tshark"
        fake.write_text(
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                field=""
                while [[ $# -gt 0 ]]; do
                    case "$1" in
                        -e) field="$2"; shift 2 ;;
                        *) shift ;;
                    esac
                done
                case "$field" in
                    dns.qry.name) printf 'Feeds.Example.COM.\\ncdn.example.com\\n' ;;
                    tls.handshake.extensions_server_name) printf 'CDN.example.com:443\\n' ;;
                esac
                """,
            ),
            encoding="utf-8",
        )
        fake.chmod(0o755)

        pcap = self.root / "capture.pcap"
        pcap.write_bytes(b"fixture, not a real pcap: the fake tshark ignores it")
        proxy_log = self.root / "proxy.log"
        proxy_log.write_text("Sync.Example.ORG:443\nsync.example.org\n", encoding="utf-8")
        output = self.root / "hosts.txt"
        env = {**os.environ, "PATH": f"{tools}{os.pathsep}{os.environ.get('PATH', '')}"}
        result = run_capture(
            "hosts",
            "--pcap",
            pcap,
            "--proxy-log",
            proxy_log,
            "--output",
            output,
            env=env,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            output.read_text(encoding="utf-8"),
            "cdn.example.com\nfeeds.example.com\nsync.example.org\n",
        )

    def test_proxy_logs_connect_targets_and_tunnels_bytes(self):
        import socket

        upstream = socket.socket()
        upstream.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        upstream.bind(("127.0.0.1", 0))
        upstream.listen(1)
        upstream.settimeout(10)
        self.addCleanup(upstream.close)
        port_file = self.root / "proxy.port"

        proxy = subprocess.Popen(
            [sys.executable, str(PROXY), "--listen", "127.0.0.1", "--port", "0", "--port-file", str(port_file)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.addCleanup(proxy.kill)
        for _ in range(100):
            if port_file.is_file() and port_file.read_text(encoding="utf-8").strip():
                break
            time.sleep(0.05)
        self.assertTrue(port_file.is_file() and port_file.read_text(encoding="utf-8").strip())

        client = socket.create_connection(("127.0.0.1", int(port_file.read_text(encoding="utf-8"))), timeout=10)
        client.settimeout(10)
        self.addCleanup(client.close)
        client.sendall(f"CONNECT 127.0.0.1:{upstream.getsockname()[1]} HTTP/1.1\r\n\r\n".encode())
        self.assertIn(b"200", client.recv(1024))
        client.sendall(b"ping")
        accepted, _ = upstream.accept()
        self.addCleanup(accepted.close)
        self.assertEqual(accepted.recv(1024), b"ping")
        accepted.sendall(b"pong")
        self.assertEqual(client.recv(1024), b"pong")

        proxy.kill()
        stdout, stderr = proxy.communicate(timeout=10)
        self.assertIn("127.0.0.1", stdout)
        self.assertEqual(stderr, "")

    def test_shell_script_usage_and_unknown_option(self):
        result = subprocess.run(["bash", str(SHELL), "--help"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--android", result.stdout)
        result = subprocess.run(["bash", str(SHELL), "--bogus"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_a_failed_desktop_app_cannot_complete_a_capture(self):
        status, stdout, stderr = self.desktop_capture_fixture(app_exit=7)
        self.assertNotEqual(status, 0, stdout + stderr)
        self.assertNotIn("capture complete:", stdout)
        self.assertIn("desktop app", stderr)

    def test_a_failed_packet_capture_cannot_reuse_its_partial_file(self):
        status, stdout, stderr = self.desktop_capture_fixture(app_exit=0, capture_exit=9)
        self.assertNotEqual(status, 0, stdout + stderr)
        self.assertNotIn("capture complete:", stdout)
        self.assertIn("desktop packet capture", stderr)

    def test_a_desktop_app_failure_during_shutdown_is_not_discarded(self):
        status, stdout, stderr = self.desktop_capture_fixture(app_exit="term:7")
        self.assertNotEqual(status, 0, stdout + stderr)
        self.assertNotIn("capture complete:", stdout)
        self.assertIn("desktop app", stderr)

    def test_a_normal_desktop_app_exit_keeps_the_capture_valid(self):
        status, stdout, stderr = self.desktop_capture_fixture(app_exit=0)
        self.assertEqual(status, 0, stdout + stderr)
        self.assertIn("capture complete:", stdout)
        self.assertIn("github.com\t", stdout)

    def test_a_reused_output_directory_uses_the_new_proxy_port(self):
        status, stdout, stderr = self.desktop_capture_fixture(app_exit=0, stale_port="1")
        self.assertEqual(status, 0, stdout + stderr)
        port = (self.root / "capture" / "proxy.port").read_text().strip()
        options = (self.root / "app-failed").read_text()
        self.assertNotEqual(port, "1", "the fixture proxy must replace the stale file")
        self.assertIn(f"-Dhttps.proxyPort={port}", options)

    def desktop_capture_fixture(self, app_exit, capture_exit=None, stale_port=None):
        tools = self.root / "bin"
        tools.mkdir()
        tshark = tools / "tshark"
        tshark.write_text(
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                if [[ "$1" == "-r" ]]; then
                    printf 'github.com\\n'
                    exit 0
                fi
                while [[ $# -gt 0 ]]; do
                    if [[ "$1" == "-w" ]]; then
                        printf 'fixture' > "$2"
                        break
                    fi
                    shift
                done
                if [[ -n "${ND_CAPTURE_FIXTURE_EXIT:-}" ]]; then
                    exit "$ND_CAPTURE_FIXTURE_EXIT"
                fi
                trap 'exit 0' TERM
                while :; do sleep 0.1; done
                """,
            ),
            encoding="utf-8",
        )
        tshark.chmod(0o755)
        marker = self.root / "app-failed"
        app = tools / "app"
        app.write_text(
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                if [[ "$2" == term:* ]]; then
                    trap 'exit 7' TERM
                    printf '%s' "$JAVA_TOOL_OPTIONS" > "$1"
                    while :; do sleep 0.1; done
                fi
                printf '%s' "$JAVA_TOOL_OPTIONS" > "$1"
                exit "$2"
                """,
            ),
            encoding="utf-8",
        )
        app.chmod(0o755)
        output = self.root / "capture"
        env = {**os.environ, "PATH": f"{tools}{os.pathsep}{os.environ.get('PATH', '')}"}
        if stale_port is not None:
            output.mkdir()
            (output / "proxy.port").write_text(stale_port + "\n")
            # Make the old-port race deterministic without changing production code.
            python = tools / "python3"
            python.write_text(
                "#!/usr/bin/env bash\n"
                "exec \"$ND_CAPTURE_FIXTURE_PYTHON\" -c "
                "'import runpy,sys,time; sys.argv=sys.argv[1:]; "
                "time.sleep(0.4) if sys.argv[0].endswith(\"network-capture-proxy.py\") else None; "
                "runpy.run_path(sys.argv[0],run_name=\"__main__\")' \"$@\"\n",
                encoding="utf-8",
            )
            python.chmod(0o755)
            env["ND_CAPTURE_FIXTURE_PYTHON"] = sys.executable
        if capture_exit is not None:
            env["ND_CAPTURE_FIXTURE_EXIT"] = str(capture_exit)
        process = subprocess.Popen(
            [
                "bash", str(SHELL), "--desktop", "--interface", "fixture0",
                "--app-cmd", str(app), "--app-arg", str(marker), "--app-arg", str(app_exit),
                "--output", str(output),
            ],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, env=env,
        )
        try:
            for _ in range(100):
                if marker.exists() and (output / "desktop.pcap").exists():
                    port_file = output / "proxy.port"
                    if stale_port is None or (
                        port_file.exists() and port_file.read_text().strip() != stale_port
                    ):
                        break
                time.sleep(0.05)
            self.assertTrue(marker.exists(), "the fixture app must actually run")
            time.sleep(0.1)
            stdout, stderr = process.communicate("\n", timeout=10)
            return process.returncode, stdout, stderr
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=10)


if __name__ == "__main__":
    unittest.main()
