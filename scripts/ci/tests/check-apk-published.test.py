#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""AC13's no-debug-tooling check on published APKs (09 Build-output checks).

`check-apk.sh --published` already carries the checks AC13 names: the merged
manifest must not be debuggable or testOnly, no debug-code activity may be
declared, the dex scan rejects LeakCanary/shark/PreviewActivity/
BenchmarkSeedReceiver and every class compiled from app/src/debug or
app/src/benchmarkRelease, and the signer must be the committed public key. This
test locks that behaviour so a later refactor cannot drop it silently; the real
run happens in ci.yml's `assemble` job and release.yml's `android` job.
"""

from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[3]
CHECK_APK = REPO / "scripts/ci/check-apk.sh"
CI = REPO / ".github/workflows/ci.yml"
RELEASE = REPO / ".github/workflows/release.yml"


class CheckApkPublishedTest(unittest.TestCase):
    def setUp(self):
        self.script = CHECK_APK.read_text(encoding="utf-8")

    def test_published_mode_gates_the_release_facts(self):
        self.assertIn("--published", self.script)
        self.assertIn("MODE=published", self.script)
        # The manifest facts and the signer check run only for a published build.
        self.assertIn('if [ "$MODE" = published ] || [ "$MODE" = no-engine ]; then', self.script)
        self.assertIn('manifest_report "$apk" "$apkname"', self.script)
        self.assertIn('signer_report "$apk" "$apkname"', self.script)

    def test_debug_tooling_is_rejected(self):
        match = re.search(r"DEBUG_DEX_RE='([^']+)'", self.script)
        self.assertIsNotNone(match)
        debug_re = match.group(1)
        for needle in ("leakcanary", "shark", "PreviewActivity", "BenchmarkSeedReceiver"):
            with self.subTest(needle=needle):
                self.assertIn(needle, debug_re)
        # Classes compiled from the debug and benchmark source sets are matched
        # by package and file name, not only by the fixed regex.
        self.assertIn('find "$REPO_ROOT/app/src/debug" "$REPO_ROOT/app/src/benchmarkRelease"', self.script)
        self.assertIn("non-published source set", self.script)

    def test_manifest_facts_reject_debuggable_and_test_only(self):
        self.assertIn("application-debuggable", self.script)
        self.assertIn("android:testOnly", self.script)
        self.assertIn("debug-code activity in the merged manifest", self.script)

    def test_published_checks_run_in_ci_and_release(self):
        ci = CI.read_text(encoding="utf-8")
        self.assertIn("scripts/ci/check-apk.sh --published", ci)
        release = RELEASE.read_text(encoding="utf-8")
        self.assertIn("scripts/ci/check-apk.sh --published", release)


if __name__ == "__main__":
    unittest.main()
