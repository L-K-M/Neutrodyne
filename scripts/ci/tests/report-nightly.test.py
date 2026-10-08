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
    def run_report(self, ref, mode=ReportMode.FAILURE, existing=False):
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
            issues = ([{"number": 7, "body": "<!-- nightly-job:test-job -->"}]
                      if existing else [])
            env = {k: v for k, v in os.environ.items()
                   if not k.startswith(("GITHUB_", "GH_", "CI"))}
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
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=30)
            calls = []
            if trace.exists():
                calls = [json.loads(line) for line in trace.read_text().splitlines()]
            return result, calls

    def test_main_failure_creates_an_issue(self):
        result, calls = self.run_report("refs/heads/main")
        self.assertEqual(0, result.returncode, result.stderr)
        creates = [c for c in calls if c[:2] == ["issue", "create"]]
        self.assertTrue(creates)
        # the created body (passed via --body argv) must carry the job marker
        self.assertIn("<!-- nightly-job:test-job -->", " ".join(creates[0]))

    def test_main_recovery_closes_its_issue(self):
        result, calls = self.run_report("refs/heads/main", ReportMode.RECOVERY, existing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(call[:3] == ["issue", "close", "7"] for call in calls))

    def test_main_failure_with_open_issue_comments_not_creates(self):
        result, calls = self.run_report("refs/heads/main", existing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(call[:3] == ["issue", "comment", "7"] for call in calls))
        self.assertFalse(any(call[:2] == ["issue", "create"] for call in calls))

    def test_main_recovery_without_open_issue_is_a_noop(self):
        result, calls = self.run_report("refs/heads/main", ReportMode.RECOVERY)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(call[:2] == ["issue", "close"] for call in calls))

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

    def test_pr_ref_makes_no_github_calls(self):
        result, calls = self.run_report("refs/pull/42/merge")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
