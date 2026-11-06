#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise release.sh with local job responses and gh's actual jq evaluator."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "release.sh"
TIMEOUT_SECONDS = 20


class ReleaseNightlyGateTest(unittest.TestCase):
    def run_gate(self, jobs):
        gh_binary = shutil.which("gh")
        self.assertIsNotNone(gh_binary, "release gate fixtures require the existing gh CLI")
        payload = json.dumps({"jobs": jobs}).encode()

        class JobsHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/repos/example/fixture/actions/runs/42/jobs?per_page=100":
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), JobsHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                scripts = root / "scripts"
                scripts.mkdir()
                shutil.copyfile(SCRIPT, scripts / "release.sh")
                (root / "gradle.properties").write_text(
                    "neutrodyne.versionName=0.1.0\n"
                    "neutrodyne.versionCode=10095\n"
                    "neutrodyne.repoUrl=https://github.com/example/fixture\n"
                )
                binary = root / "bin"
                binary.mkdir()
                (binary / "git").write_text(
                    "#!/usr/bin/env python3\n"
                    "import sys\n"
                    "args = sys.argv[1:]\n"
                    "if args == ['symbolic-ref', '--short', '-q', 'HEAD']:\n"
                    "    print('main')\n"
                    "elif args == ['status', '--porcelain']:\n"
                    "    pass\n"
                    "elif args in (['rev-parse', 'HEAD'], ['rev-parse', 'origin/main']):\n"
                    "    print('a' * 40)\n"
                    "elif args[:3] == ['rev-parse', '--verify', '-q']:\n"
                    "    pass  # Existing-tag tripwire stops before any mutation.\n"
                    "else:\n"
                    "    raise SystemExit('Unexpected git operation: ' + repr(args))\n"
                )
                (binary / "gh").write_text(
                    "#!/usr/bin/env python3\n"
                    "import os, subprocess, sys\n"
                    "args = sys.argv[1:]\n"
                    "if args[:2] == ['run', 'list']:\n"
                    "    workflow = args[args.index('--workflow') + 1]\n"
                    "    print('1' if workflow == 'ci.yml' else '42')\n"
                    "elif args[:2] == ['issue', 'list']:\n"
                    "    print('0')\n"
                    "elif args[:1] == ['api']:\n"
                    "    url = os.environ['JOBS_URL'] + '/' + args[1]\n"
                    "    raise SystemExit(subprocess.call([os.environ['REAL_GH'], 'api', url, *args[2:]]))\n"
                    "else:\n"
                    "    raise SystemExit('Unexpected gh operation: ' + repr(args))\n"
                )
                for name in ("git", "gh"):
                    (binary / name).chmod(0o755)
                env = {key: value for key, value in os.environ.items()
                       if not key.startswith(("GH_", "GITHUB_"))}
                env.update(
                    PATH=str(binary) + os.pathsep + os.environ["PATH"],
                    HOME=str(root),
                    GH_CONFIG_DIR=str(root / "gh-config"),
                    GH_TOKEN="local-fixture-only",
                    GH_ENTERPRISE_TOKEN="local-fixture-only",
                    GH_PROMPT_DISABLED="1",
                    GH_NO_UPDATE_NOTIFIER="1",
                    REAL_GH=gh_binary,
                    JOBS_URL=f"http://127.0.0.1:{server.server_port}",
                )
                return subprocess.run(
                    ["bash", str(scripts / "release.sh"), "patch"],
                    env=env, capture_output=True, text=True, timeout=TIMEOUT_SECONDS,
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=TIMEOUT_SECONDS)

    def assert_gate_passed(self, jobs):
        result = self.run_gate(jobs)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("tag v0.1.1 already exists", result.stderr)
        self.assertNotIn("latest scheduled nightly.yml", result.stderr)

    def test_report_only_failures_do_not_block_required_success(self):
        for conclusion in ("failure", "timed_out", "cancelled", None):
            with self.subTest(conclusion=conclusion):
                self.assert_gate_passed([
                    {"name": "unit", "conclusion": "success"},
                    {"name": "repro", "conclusion": "failure"},
                    {"name": "youtube-canary", "conclusion": conclusion},
                ])

    def test_report_only_success_does_not_prove_required_execution(self):
        result = self.run_gate([
            {"name": "instrumented-full", "conclusion": "skipped"},
            {"name": "repro", "conclusion": "success"},
            {"name": "youtube-canary", "conclusion": "success"},
        ])
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("no gating job succeeded", result.stderr)

    def test_required_failure_still_blocks_a_release(self):
        result = self.run_gate([
            {"name": "instrumented-full", "conclusion": "failure"},
            {"name": "youtube-canary", "conclusion": "success"},
        ])
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("failed: instrumented-full", result.stderr)

    def test_empty_job_inventory_does_not_prove_execution(self):
        result = self.run_gate([])
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("no gating job succeeded", result.stderr)


if __name__ == "__main__":
    unittest.main()
