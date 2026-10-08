#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise reporting with a fake gh; branch probes must not alter main's issues."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from enum import Enum


class ReportMode(Enum):
    FAILURE = "failure"
    RECOVERY = "recovery"


class NightlyReportTest(unittest.TestCase):
    def run_report(self, ref, mode=ReportMode.FAILURE):
        script = Path(__file__).resolve().parents[1] / "report-nightly.sh"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / "calls.jsonl"
            gh = root / "gh"
            gh.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "with open(os.environ['REPORT_TRACE'], 'a') as out:\n"
                "    out.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                "if sys.argv[1:3] == ['issue', 'list']:\n"
                "    print(os.environ['REPORT_ISSUES'])\n"
            )
            gh.chmod(0o755)
            issues = []
            if mode is ReportMode.RECOVERY:
                issues = [{"number": 7, "body": "<!-- nightly-job:test-job -->"}]
            env = dict(os.environ)
            env.update(
                PATH=str(root) + os.pathsep + os.environ["PATH"],
                GITHUB_REF=ref,
                GITHUB_REPOSITORY="L-K-M/Neutrodyne",
                GITHUB_RUN_ID="123",
                REPORT_TRACE=str(trace),
                REPORT_ISSUES=json.dumps(issues),
            )
            args = ["bash", str(script), "test-job"]
            if mode is ReportMode.RECOVERY:
                args.append("--close")
            result = subprocess.run(args, env=env, capture_output=True, text=True)
            calls = []
            if trace.exists():
                calls = [json.loads(line) for line in trace.read_text().splitlines()]
            return result, calls

    def test_main_failure_creates_an_issue(self):
        result, calls = self.run_report("refs/heads/main")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(call[:2] == ["issue", "create"] for call in calls))

    def test_main_recovery_closes_its_issue(self):
        result, calls = self.run_report("refs/heads/main", ReportMode.RECOVERY)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(call[:3] == ["issue", "close", "7"] for call in calls))

    def test_branch_failure_makes_no_github_calls(self):
        result, calls = self.run_report("refs/heads/impl/m0b-3-ci")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)

    def test_branch_recovery_does_not_close_main_issue(self):
        result, calls = self.run_report("refs/heads/impl/m0b-3-ci", ReportMode.RECOVERY)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)

    def test_tag_run_makes_no_github_calls(self):
        result, calls = self.run_report("refs/tags/v0.1.0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
