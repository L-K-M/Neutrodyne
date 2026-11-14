#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Exercise release packaging with format-specific payload/classpath identities.

Real run 42457761741 rejected the DEB/RPM app JARs against the final tar
manifest. The fake builder models that independent observed content change;
the shipped orchestration must check each package against its own manifest.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class DesktopPackageVariantsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        destination = self.root / "scripts/ci/build-desktop-release.sh"
        destination.parent.mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/ci/build-desktop-release.sh", destination)
        self.write("gradlew", '''#!/usr/bin/env python3
from pathlib import Path
import sys
kind = next(arg.split('=',1)[1] for arg in sys.argv if arg.startswith('-Pneutrodyne.installKind='))
Path('manifest-kind').write_text(kind)
with Path('build-kinds').open('a') as log: log.write(kind+'\\n')
Path('desktopApp/build/compose/binaries/main/app/Neutrodyne').mkdir(parents=True,exist_ok=True)
folder, name = {
 'deb': ('deb','fixture.deb'), 'rpm': ('rpm','fixture.rpm'),
 'tar.gz': ('tar-gz','neutrodyne-9.9.9-linux-x64.tar.gz')
}[kind]
path = Path('desktopApp/build/desktop-packaging')/folder/name
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(kind)
''')
        self.write("scripts/ci/check-desktop-image.sh", '''#!/usr/bin/env python3
from pathlib import Path
import os,sys
expected = Path('manifest-kind').read_text()
for arg in sys.argv[1:]:
 path = Path(arg)
 if path.is_dir(): continue
 actual = path.read_text()
 if actual != expected:
  raise SystemExit('package identity '+actual+' differs from manifest '+expected)
 with Path('checked-kinds').open('a') as log: log.write(actual+'\\n')
 if actual == os.environ.get('FAIL_CHECK_KIND'): raise SystemExit('rejected package')
''')
        self.write("scripts/ci/check-runtime-sources.sh", "#!/bin/sh\nexit 0\n")
        self.write("scripts/desktop/smoke-start.sh", "#!/bin/sh\necho 'SMOKE fixture'\n")
        self.write("bin/sudo", "#!/bin/sh\nexit 0\n")
        self.write("bin/xvfb-run", '#!/bin/sh\nshift\nexec "$@"\n')
        self.write("bin/dbus-run-session", '#!/bin/sh\nexec "$@"\n')
        self.write("bin/rpm2cpio", "#!/bin/sh\necho fixture\n")
        self.write("bin/cpio", "#!/bin/sh\ncat >/dev/null\n")
        self.write("bin/tar", "#!/bin/sh\nexit 0\n")
        # The shipped script creates this shared directory; our fakes never write
        # into it. Remove it only if this case created it and it remains empty.
        shared = Path("/tmp/neutrodyne-smoke-img")
        if not shared.exists():
            def remove_empty():
                try:
                    shared.rmdir()
                except (FileNotFoundError, OSError):
                    pass
            self.addCleanup(remove_empty)

    def write(self, relative, contents):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        path.chmod(0o755)

    def run_release(self, **extra):
        return subprocess.run(
            ["bash", "scripts/ci/build-desktop-release.sh", "linux-x64", "9.9.9", "mac-zip"],
            cwd=self.root, env={**os.environ, "PATH": str(self.bin)+os.pathsep+os.environ["PATH"], **extra},
            capture_output=True, text=True, timeout=20,
        )

    def test_each_format_is_bound_to_its_own_manifest(self):
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertEqual((self.root/"checked-kinds").read_text().splitlines(), ["deb", "rpm", "tar.gz"])

    def test_rejected_format_stops_before_the_next_build(self):
        result = self.run_release(FAIL_CHECK_KIND="rpm")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("rejected package", result.stderr)
        self.assertEqual((self.root/"build-kinds").read_text().splitlines(), ["deb", "rpm"])

    def test_dmg_smoke_copy_preserves_the_artifact_timestamps(self):
        """The DMG smoke copy must not re-stamp the payload: cp -R drops the
        trained JARs' mtimes and would hide a drifted DMG from the strict check."""
        text = (ROOT / "scripts/ci/build-desktop-release.sh").read_text()
        self.assertIn("ditto /tmp/neutrodyne-dmg-mnt/Neutrodyne.app", text)
        self.assertNotIn("cp -R /tmp/neutrodyne-dmg-mnt/Neutrodyne.app", text)

    def test_macos_dmg_leg_supplies_finite_y_to_licence_prompt(self):
        """The real build-desktop-release.sh DMG leg must confirm a bundled
        licence prompt on stdin (EOF cancels attach) while retaining
        nobrowse, ditto copy and detach cleanup behaviour."""
        # Relocate the script's fixed staging paths into this fixture before
        # its real commands run; the test must not write into shared /tmp paths.
        script = self.root / "scripts/ci/build-desktop-release.sh"
        script.write_text(script.read_text()
                          .replace("/tmp/neutrodyne-dmg-mnt", str(self.root / "dmg-mount"))
                          .replace("/tmp/neutrodyne-smoke-img", str(self.root / "smoke-image")))
        # macOS layout expected by the script.
        self.write("scripts/ci/check-desktop-image.sh", "#!/bin/sh\nexit 0\n")
        self.write("scripts/ci/check-runtime-sources.sh", "#!/bin/sh\nexit 0\n")
        self.write("scripts/desktop/smoke-start.sh", "#!/bin/sh\necho 'SMOKE fixture'\n")
        self.write("bin/ditto", "#!/bin/sh\nmkdir -p \"$2\"\necho copied > \"$2/copied\"\n")
        self.write("bin/codesign", "#!/bin/sh\nexit 0\n")
        self.write("gradlew", """#!/usr/bin/env python3
from pathlib import Path
import sys
if 'packageDmg' not in sys.argv: raise SystemExit('expected packageDmg')
Path('desktopApp/build/compose/binaries/main/app/Neutrodyne.app').mkdir(parents=True, exist_ok=True)
dmg = Path('desktopApp/build/compose/binaries/main/dmg/fixture.dmg')
dmg.parent.mkdir(parents=True, exist_ok=True)
dmg.write_bytes(b'DMG')
""")
        self.write("bin/hdiutil", r"""#!/usr/bin/env bash
set -euo pipefail
log="${FAKE_HDIUTIL_LOG:?}"
case "${1:-}" in
  attach)
    shift
    mnt="" nb=0
    while [ $# -gt 0 ]; do
      case "$1" in
        -nobrowse) nb=1; shift ;;
        -mountpoint) mnt="$2"; shift 2 ;;
        -*) shift ;;
        *) shift ;;
      esac
    done
    [ "$nb" = 1 ] || { echo "missing -nobrowse" >&2; exit 1; }
    [ -n "$mnt" ] || exit 1
    echo "SOFTWARE LICENCE AGREEMENT"
    if IFS= read -r answer; then
      case "$answer" in
        [Yy]|[Yy][Ee][Ss]) echo "attach-accepted" >>"$log" ;;
        *) echo "attach canceled" >&2; exit 1 ;;
      esac
    else
      echo "attach canceled" >&2
      exit 1
    fi
    mkdir -p "$mnt/Neutrodyne.app"
    echo "attach $mnt" >>"$log"
    ;;
  detach)
    echo "detach $2" >>"$log"
    ;;
  *) exit 1 ;;
esac
""")
        log = self.root / "hdiutil.log"
        result = subprocess.run(
            ["bash", "scripts/ci/build-desktop-release.sh", "macos-arm64", "9.9.9", "dmg"],
            cwd=self.root,
            env={**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                 "FAKE_HDIUTIL_LOG": str(log)},
            capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        entries = log.read_text().splitlines()
        self.assertIn("attach-accepted", entries)
        self.assertIn("attach " + str(self.root / "dmg-mount"), entries)
        self.assertIn("detach " + str(self.root / "dmg-mount"), entries)
        self.assertTrue((self.root / "smoke-image/Neutrodyne.app/copied").is_file())


if __name__ == "__main__":
    unittest.main()
