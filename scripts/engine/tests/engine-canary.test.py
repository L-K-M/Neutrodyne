#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Canary pipeline script tests — no live GitHub or YouTube traffic.

Every network tool (`curl`, `gpg`, `unzip`) is PATH-stubbed; signing tests
generate throwaway Ed25519 keys in temp dirs via the real openssl. The
workflow-shape tests parse the committed YAML, and the API-probe-failure
report test drives `scripts/ci/report-nightly.sh` through a fake `gh`, so no
test ever creates a real issue.
"""

import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / 'scripts/engine'


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), SCRIPTS / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


latest = load('latest-ytdlp-tag')
manifest_tool = load('make-engine-manifest')
sign_tool = load('sign-engine-manifest')
edsig = load('edsig')


class CandidateSelectionTests(unittest.TestCase):
    def test_stable_tag_parsed_from_redirect(self):
        location = 'https://github.com/yt-dlp/yt-dlp/releases/tag/2026.09.01'
        self.assertEqual('2026.09.01', latest.tag_from_location(location))
        self.assertEqual('2026.09.01', latest.latest_tag(lambda url: location))

    def test_rebuild_prerelease_and_foreign_locations_rejected(self):
        for location in (
            'https://github.com/yt-dlp/yt-dlp/releases/tag/2026.09.01.1',
            'https://github.com/yt-dlp/yt-dlp/releases/tag/2026.09.01-rc',
            'https://example.com/yt-dlp/releases/tag/2026.09.01',
            'https://github.com/yt-dlp/yt-dlp/releases/tag/nightly',
            '',
        ):
            with self.subTest(location=location):
                self.assertIsNone(latest.tag_from_location(location))

    def test_empty_redirect_means_no_candidate(self):
        self.assertIsNone(latest.latest_tag(lambda url: ''))


class BumpStub:
    """A PATH where curl serves fixtures and gpg only verifies 'GOOD' sigs."""

    FINGERPRINT = 'AC0CBBE6848D6A873464AF4E57CF65933B5A7581'

    def __init__(self, root):
        self.dir = Path(root)
        self.bin = self.dir / 'bin'
        self.bin.mkdir()
        self.served = self.dir / 'served'
        self.served.mkdir()
        self.downloads = {}

    def serve(self, name, content):
        path = self.served / name
        path.write_bytes(content if isinstance(content, bytes) else content.encode())
        self.downloads[name] = path
        return path

    def install(self, good_signature=b'GOOD', ejs='0.8.0'):
        curl = self.bin / 'curl'
        curl.write_text(
            '#!/bin/sh\n'
            'dest=""\n'
            'for last; do :; done\n'
            'while [ $# -gt 0 ]; do\n'
            '    [ "$1" = "-o" ] && { dest=$2; shift 2; continue; }\n'
            '    shift\n'
            'done\n'
            f'cp "{self.served}/$(basename "$last")" "$dest"\n'
        )
        curl.chmod(curl.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        gpg = self.bin / 'gpg'
        gpg.write_text(
            '#!/bin/sh\n'
            'case " $* " in\n'
            '    *" --import "*) exit 0 ;;\n'
            'esac\n'
            'sig=""; doc=""\n'
            'while [ $# -gt 0 ]; do\n'
            '    case "$1" in\n'
            '        --verify) sig=$2; doc=$3; shift 3 ;;\n'
            '        *) shift ;;\n'
            '    esac\n'
            'done\n'
            f'if grep -q GOOD "$sig" 2>/dev/null; then\n'
            f'    echo "[GNUPG:] VALIDSIG {self.FINGERPRINT} x"\n'
            '    exit 0\n'
            'fi\n'
            'exit 1\n'
        )
        gpg.chmod(gpg.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        unzip = self.bin / 'unzip'
        unzip.write_text(
            '#!/bin/sh\n'
            f'echo "__version__ = version = \'{ejs}\'"\n'
        )
        unzip.chmod(unzip.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    def env(self):
        env = dict(os.environ)
        env['PATH'] = f'{self.bin}:{os.environ["PATH"]}'
        env['GNUPGHOME'] = str(self.dir / 'gnupg')
        return env


class BumpTests(unittest.TestCase):
    """Signature and hash failures must never stage a candidate."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.stub = BumpStub(self.tmp)
        self.stub.install()
        # A minimal fake engine dir: bump overwrites it wholesale.
        self.engine = self.tmp / 'engine'
        self.engine.mkdir()
        self.engine.joinpath('bundled.json').write_text(
            json.dumps({'version': '2000.01.01', 'tag': '2000.01.01',
                        'sha256': '0' * 64, 'ejsVersion': '0.0.0'}))
        # The stub curl serves basename(url): expanded_assets/<tag> and the
        # three release assets land under these fixture names.
        self.lockfile = self.tmp / 'python-components.lock'
        self.lockfile.write_text(
            '[[component]]\nname = "yt-dlp"\nversion = "2000.01.01"\n'
            'origin = "https://github.com/yt-dlp/yt-dlp/releases/download/2000.01.01/yt-dlp"\n'
            'licence = "Unlicense"\nkind = "python"\n'
            '[[component]]\nname = "yt-dlp-ejs"\nversion = "0.0.0"\n'
            'origin = "https://github.com/yt-dlp/yt-dlp/releases/download/2000.01.01/yt-dlp"\n'
            'licence = "Unlicense"\nkind = "python"\n'
            '[[component]]\nname = "meriyah"\nversion = "6.1.4"\n'
            'origin = "https://github.com/yt-dlp/yt-dlp/releases/download/2000.01.01/yt-dlp"\n'
            'licence = "ISC"\nkind = "python"\n')
        self.stub.serve(
            '2026.09.01',
            '<a href="/yt-dlp/yt-dlp/releases/download/2026.09.01/yt-dlp">x</a>\n'
            '<a href="/yt-dlp/yt-dlp/releases/download/2026.09.01/SHA2-256SUMS">x</a>\n'
            '<a href="/yt-dlp/yt-dlp/releases/download/2026.09.01/SHA2-256SUMS.sig">x</a>\n')

    def run_bump(self, engine_dir=None):
        env = self.stub.env()
        env['ND_ENGINE_DIR'] = str(engine_dir or self.engine)
        script = SCRIPTS / 'bump-ytdlp.sh'
        # ND_ overrides keep the fixture out of the real checkout; the
        # lockfile path is derived from the engine dir's parent's lock name.
        return subprocess.run(
            ['sh', str(script), '2026.09.01', '--force'],
            capture_output=True, text=True, env=env,
        )

    def test_signature_failure_stages_nothing(self):
        archive = self.stub.serve('yt-dlp', 'archive-bytes')
        import hashlib
        digest = hashlib.sha256(b'archive-bytes').hexdigest()
        self.stub.serve('SHA2-256SUMS', f'{digest}  yt-dlp\n')
        self.stub.serve('SHA2-256SUMS.sig', 'FORGED')
        result = self.run_bump()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('does not verify', result.stderr)
        self.assertFalse((self.engine / 'yt-dlp').exists())

    def test_hash_failure_stages_nothing(self):
        self.stub.serve('yt-dlp', 'archive-bytes')
        self.stub.serve('SHA2-256SUMS', '0' * 64 + '  yt-dlp\n')
        self.stub.serve('SHA2-256SUMS.sig', 'GOOD')
        result = self.run_bump()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('does not match', result.stderr)
        self.assertFalse((self.engine / 'yt-dlp').exists())

    def test_candidate_selection_and_staging(self):
        self.stub.serve('yt-dlp', 'archive-bytes')
        import hashlib
        digest = hashlib.sha256(b'archive-bytes').hexdigest()
        self.stub.serve('SHA2-256SUMS', f'{digest}  yt-dlp\n')
        self.stub.serve('SHA2-256SUMS.sig', 'GOOD')
        result = self.run_bump()
        self.assertEqual(0, result.returncode, result.stderr)
        bundled = json.loads((self.engine / 'bundled.json').read_text())
        self.assertEqual('2026.09.01', bundled['version'])
        self.assertEqual(digest, bundled['sha256'])
        self.assertEqual('0.8.0', bundled['ejsVersion'])


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.keys = self.tmp / 'keys'
        self.keys.mkdir()
        subprocess.run(
            ['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(self.tmp / 'key.pem')],
            check=True, capture_output=True)
        self.public = edsig.public_key_of(self.tmp / 'key.pem')
        (self.keys / 'engine-manifest-ed25519.pub').write_text(self.public + '\n')
        self.pem = (self.tmp / 'key.pem').read_bytes()
        self.bundled = self.tmp / 'bundled.json'
        self.bundled.write_text(json.dumps({
            'version': '2026.09.01', 'tag': '2026.09.01',
            'sha256': 'a' * 64, 'ejsVersion': '0.8.0'}))

    def run_make(self, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPTS / 'make-engine-manifest.py'), *args],
            capture_output=True, text=True)

    def sign_doc(self, document):
        sig = self.tmp / 'doc.sig'
        subprocess.run(
            ['openssl', 'pkeyutl', '-sign', '-rawin', '-inkey', str(self.tmp / 'key.pem'),
             '-in', str(document), '-out', str(sig)], check=True, capture_output=True)
        return base64.b64encode(sig.read_bytes()).decode()

    def write_current(self, sequence=5, version='2026.08.19', revoked=None):
        current = self.tmp / 'current.json'
        current.write_text(json.dumps({
            'schema': 1, 'sequence': sequence, 'issuedAt': '2026-08-20T00:00:00Z',
            'ytdlp': {'version': version, 'tag': version, 'repo': 'yt-dlp/yt-dlp',
                      'asset': 'yt-dlp', 'sha256': 'b' * 64, 'ejsVersion': '0.8.0'},
            'shimApi': {'min': 1, 'max': 1}, 'revoked': revoked or []}))
        sig = self.tmp / 'current.json.sig'
        sig.write_text(self.sign_doc(current) + '\n')
        return current, sig

    def test_bootstrap_writes_sequence_one(self):
        out = self.tmp / 'm.json'
        result = self.run_make(
            '--bootstrap', '--bundled', str(self.bundled), '--out', str(out),
            '--now', '2026-09-02T00:00:00Z', '--keys-dir', str(self.keys))
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads(out.read_text())
        self.assertEqual(1, manifest['schema'])
        self.assertEqual(1, manifest['sequence'])
        self.assertEqual('2026-09-02T00:00:00Z', manifest['issuedAt'])
        self.assertEqual('2026.09.01', manifest['ytdlp']['version'])
        self.assertEqual('a' * 64, manifest['ytdlp']['sha256'])
        self.assertEqual({'min': 1, 'max': 1}, manifest['shimApi'])
        self.assertEqual([], manifest['revoked'])

    def test_sequence_increments_and_revoked_union(self):
        current, sig = self.write_current(revoked=['2026.01.01'])
        out = self.tmp / 'm.json'
        result = self.run_make(
            '--current', str(current), '--current-sig', str(sig),
            '--bundled', str(self.bundled), '--out', str(out),
            '--revoke', '2026.02.02', '--now', '2026-09-02T00:00:00Z',
            '--keys-dir', str(self.keys))
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads(out.read_text())
        self.assertEqual(6, manifest['sequence'])
        self.assertEqual(['2026.01.01', '2026.02.02'], manifest['revoked'])

    def test_tampered_current_manifest_is_rejected(self):
        current, _ = self.write_current()
        # A current manifest re-signed by nobody we pin: signature absent.
        unsigned = self.tmp / 'bad-sig'
        unsigned.write_text(base64.b64encode(b'0' * 64).decode() + '\n')
        out = self.tmp / 'm.json'
        result = self.run_make(
            '--current', str(current), '--current-sig', str(unsigned),
            '--bundled', str(self.bundled), '--out', str(out),
            '--keys-dir', str(self.keys))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('does not verify', result.stderr)
        self.assertFalse(out.exists())

    def test_candidate_below_bundled_floor_is_rejected(self):
        current, sig = self.write_current()
        result = self.run_make(
            '--current', str(current), '--current-sig', str(sig),
            '--bundled', str(self.bundled), '--out', str(self.tmp / 'm.json'),
            '--floor', '2026.10.01', '--keys-dir', str(self.keys))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('below', result.stderr)

    def test_revoke_only_keeps_current_candidate(self):
        current, sig = self.write_current()
        out = self.tmp / 'm.json'
        result = self.run_make(
            '--current', str(current), '--current-sig', str(sig),
            '--keep-candidate', '--revoke', '2026.08.19',
            '--out', str(out), '--now', '2026-09-02T00:00:00Z',
            '--keys-dir', str(self.keys))
        # Revoking the approved version itself is refused.
        self.assertNotEqual(0, result.returncode)
        current2, sig2 = self.write_current(version='2026.08.18')
        result = self.run_make(
            '--current', str(current2), '--current-sig', str(sig2),
            '--keep-candidate', '--revoke', '2026.09.01',
            '--out', str(out), '--now', '2026-09-02T00:00:00Z',
            '--keys-dir', str(self.keys))
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads(out.read_text())
        self.assertEqual('2026.08.18', manifest['ytdlp']['version'])
        self.assertEqual(['2026.09.01'], manifest['revoked'])

    def test_heartbeat_document(self):
        out = self.tmp / 'hb.json'
        result = self.run_make(
            '--heartbeat', '--sequence', '7', '--out', str(out),
            '--now', '2026-09-02T01:02:03Z')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {'kind': 'heartbeat', 'lastRunAt': '2026-09-02T01:02:03Z', 'sequence': 7},
            json.loads(out.read_text()))


class SigningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.keys = self.tmp / 'keys'
        self.keys.mkdir()
        subprocess.run(
            ['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(self.tmp / 'key.pem')],
            check=True, capture_output=True)
        (self.keys / 'engine-manifest-ed25519.pub').write_text(
            edsig.public_key_of(self.tmp / 'key.pem') + '\n')
        self.doc = self.tmp / 'm.json'
        self.doc.write_text('{"schema":1}\n')

    def run_sign(self, env_extra=None):
        env = dict(os.environ)
        env.pop('NEUTRODYNE_ENGINE_MANIFEST_KEY', None)
        env.pop('TEST_MANIFEST_KEY', None)
        env.update(env_extra or {})
        return subprocess.run(
            [sys.executable, str(SCRIPTS / 'sign-engine-manifest.py'),
             str(self.doc), '--key-env', 'TEST_MANIFEST_KEY',
             '--keys-dir', str(self.keys)],
            capture_output=True, text=True, env=env)

    def test_dry_run_without_secret(self):
        result = self.run_sign()
        self.assertEqual(77, result.returncode)
        self.assertIn('dry-run', result.stdout)
        self.assertFalse(self.doc.with_suffix('.json.sig').exists())

    def test_signs_and_verifies_against_committed_slot(self):
        pem_b64 = base64.b64encode((self.tmp / 'key.pem').read_bytes()).decode()
        result = self.run_sign({'TEST_MANIFEST_KEY': pem_b64})
        self.assertEqual(0, result.returncode, result.stderr)
        sig_file = Path(str(self.doc) + '.sig')
        self.assertTrue(sig_file.exists())
        signature = sig_file.read_text().strip()
        self.assertTrue(
            edsig.verify_detached(self.doc.read_bytes(), signature, self.keys))

    def test_unpinned_key_is_refused(self):
        other = self.tmp / 'other.pem'
        subprocess.run(
            ['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(other)],
            check=True, capture_output=True)
        result = self.run_sign({'TEST_MANIFEST_KEY': base64.b64encode(other.read_bytes()).decode()})
        self.assertEqual(1, result.returncode)
        self.assertIn('not one of the committed', result.stderr)
        self.assertFalse(Path(str(self.doc) + '.sig').exists())


class GateTests(unittest.TestCase):
    """canary.sh emits candidate outputs only when the gate is green."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.engine = self.tmp / 'engine'
        self.engine.mkdir()
        self.engine.joinpath('bundled.json').write_text(json.dumps({
            'version': '2026.09.01', 'tag': '2026.09.01',
            'sha256': 'c' * 64, 'ejsVersion': '0.8.0'}))
        self.out = self.tmp / 'candidate.env'

    def run_canary(self, gate):
        env = dict(os.environ)
        env['ND_ENGINE_DIR'] = str(self.engine)
        env['CANARY_GATE_CMD'] = gate
        return subprocess.run(
            ['sh', str(SCRIPTS / 'canary.sh'), '--staged', '--out', str(self.out)],
            capture_output=True, text=True, env=env)

    def test_green_gate_emits_candidate(self):
        result = self.run_canary('true')
        self.assertEqual(0, result.returncode, result.stderr)
        values = dict(
            line.split('=', 1) for line in self.out.read_text().splitlines())
        self.assertEqual('2026.09.01', values['version'])
        self.assertEqual('c' * 64, values['sha256'])
        self.assertEqual('0.8.0', values['ejsVersion'])

    def test_self_test_failure_blocks_the_candidate(self):
        result = self.run_canary('false')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.out.exists())

    def test_default_gate_runs_the_live_canary_against_the_staged_candidate(self):
        script = (SCRIPTS / 'canary.sh').read_text()
        self.assertIn('youtube-engine-canary.py', script)
        self.assertIn('-Preplay=contract', script)
        self.assertIn(':youtube:ytdlp-desktop:checkPythonLicences', script)


class ApiProbeFailureReportTests(unittest.TestCase):
    """The gate-failure path reports through the existing reporter with a fake gh."""

    def run_report(self):
        script = ROOT / 'scripts/ci/report-nightly.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / 'calls.jsonl'
            gh = root / 'gh'
            gh.write_text(
                '#!/usr/bin/env python3\n'
                'import json, os, sys\n'
                "with open(os.environ['REPORT_TRACE'], 'a') as out:\n"
                "    out.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                "if sys.argv[1:3] == ['issue', 'list']:\n"
                "    print('[]')\n"
            )
            gh.chmod(0o755)
            env = {k: v for k, v in os.environ.items()
                   if not k.startswith(('GITHUB_', 'GH_', 'CI'))}
            env.update(
                PATH=str(root) + os.pathsep + os.environ['PATH'],
                GITHUB_REF='refs/heads/main',
                GITHUB_REPOSITORY='L-K-M/Neutrodyne',
                GITHUB_RUN_ID='123',
                REPORT_TRACE=str(trace),
            )
            result = subprocess.run(
                ['bash', str(script), 'engine-canary', '--label', 'engine-canary'],
                env=env, capture_output=True, text=True, timeout=30)
            calls = []
            if trace.exists():
                calls = [json.loads(line) for line in trace.read_text().splitlines()]
            return result, calls

    def test_exactly_one_report_is_issued(self):
        result, calls = self.run_report()
        self.assertEqual(0, result.returncode, result.stderr)
        creates = [c for c in calls if c[:2] == ['issue', 'create']]
        self.assertEqual(1, len(creates))
        self.assertIn('<!-- nightly-job:engine-canary -->', ' '.join(creates[0]))


class WorkflowShapeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / '.github/workflows/engine-canary.yml').read_text()

    @classmethod
    def job_block(cls, name):
        """The job's YAML block, from its two-space header to the next job."""
        lines = cls.workflow.splitlines()
        start = next(
            (i for i, line in enumerate(lines) if line == f'  {name}:'), None
        )
        assert start is not None, name
        end = len(lines)
        for i in range(start + 1, len(lines)):
            if lines[i].startswith('  ') and not lines[i].startswith('   ') and lines[i].strip():
                end = i
                break
        return '\n'.join(lines[start:end])

    @classmethod
    def job_condition(cls, name):
        """The job-level `if:` expression, with a block scalar folded."""
        lines = cls.job_block(name).splitlines()
        for i, line in enumerate(lines):
            if line.startswith('    if:'):
                value = line.split('if:', 1)[1].strip()
                if value in ('>', '>-', '|', '|-'):
                    collected = []
                    for cont in lines[i + 1:]:
                        if cont.startswith('      ') and cont.strip():
                            collected.append(cont.strip())
                        elif not cont.strip():
                            continue
                        else:
                            break
                    value = ' '.join(collected)
                return value
        return None

    @classmethod
    def step_block(cls, job, name):
        """One step's YAML block, from its `- name:` line to the next step."""
        lines = cls.job_block(job).splitlines()
        start = next(
            (i for i, line in enumerate(lines) if line == f'      - name: {name}'),
            None,
        )
        assert start is not None, name
        end = len(lines)
        for i in range(start + 1, len(lines)):
            if lines[i].startswith('      - '):
                end = i
                break
        return '\n'.join(lines[start:end])

    @classmethod
    def step_condition(cls, job, name):
        for line in cls.step_block(job, name).splitlines():
            if line.startswith('        if:'):
                return line.split('if:', 1)[1].strip()
        return None

    def test_schedule_dispatch_and_concurrency(self):
        self.assertIn("cron: '23 */6 * * *'", self.workflow.replace('"', "'"))
        self.assertIn('workflow_dispatch:', self.workflow)
        self.assertIn('group: engine-canary', self.workflow)
        self.assertIn('cancel-in-progress: false', self.workflow)
        for name in ('tag', 'revoke', 'bootstrap'):
            self.assertRegex(self.workflow, rf'(?m)^\s+{name}:', name)

    def test_never_triggers_on_pull_requests(self):
        self.assertNotIn('pull_request', self.workflow)
        self.assertNotIn('youtube.com', self.workflow)

    def test_approve_needs_gate_and_uses_environment(self):
        self.assertRegex(self.workflow, r'approve:\s*\n(?:.*\n)*?\s+needs:.*gate')
        self.assertIn('environment: engine-approval', self.workflow)
        self.assertIn('NEUTRODYNE_ENGINE_MANIFEST_KEY', self.workflow)

    def test_detect_resolves_the_existing_index(self):
        self.assertIn('approval_release.py resolve', self.workflow)
        self.assertIn('edsig.verify_detached', self.workflow)

    def test_deploy_uses_the_immutable_publisher(self):
        self.assertIn('approval_release.py publish', self.workflow)
        self.assertIn('--snapshot-dir approved', self.workflow)
        self.assertIn('--expected-index-tag', self.workflow)
        self.assertNotIn('gh release upload', self.workflow)
        self.assertNotIn('gh release create', self.workflow)
        # approval_release.py reads GITHUB_TOKEN, never GH_TOKEN.
        self.assertIn('GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}', self.workflow)

    def test_deploy_only_runs_when_signed(self):
        condition = self.job_condition('deploy')
        self.assertIn("needs.approve.outputs.signed == 'true'", condition)
        self.assertIn("github.ref == 'refs/heads/main'", condition)

    def test_no_secret_dry_run_stays_green_and_publishes_nothing(self):
        # The sign step maps the missing secret to exit 77 and signed=false;
        # deploy's condition then keeps the publisher out of the run.
        sign = self.step_block('approve', 'Sign (or dry-run without the secret)')
        self.assertIn('[ "$rc" = 77 ]', sign)
        self.assertIn('signed=false', sign)
        condition = self.job_condition('deploy')
        self.assertIn("needs.approve.outputs.signed == 'true'", condition)
        self.assertIn("github.ref == 'refs/heads/main'", condition)

    def test_api_probe_failure_publishes_nothing(self):
        # approve never runs on a failed gate, so deploy cannot sign or publish.
        condition = self.job_condition('approve')
        self.assertIn("'success'", condition)
        self.assertIn("'skipped'", condition)
        self.assertNotIn("!= 'failure'", condition)
        # The only issue report on the gate-failure path is the shared reporter.
        self.assertIn('report-nightly.sh engine-canary --label engine-canary', self.workflow)

    def test_approve_signs_the_gated_candidate_not_the_checkout(self):
        # approve works on a clean checkout whose bundled.json is the old
        # floor; the manifest must be built from the gate's staged metadata.
        self.assertIn('name: engine-candidate', self.workflow)
        self.assertIn('--bundled candidate/bundled.json', self.workflow)

    def test_approve_verifies_the_current_manifest_before_trusting_it(self):
        step = self.step_block('approve', 'Write the new manifest')
        self.assertIn('--current current/ytdlp-approved.json --current-sig current/ytdlp-approved.json.sig',
                      step)

    def test_noop_run_reuses_the_current_manifest_pair(self):
        # A skipped gate must not invent a candidate: the verified current
        # manifest is staged as the base document and only the heartbeat is fresh.
        self.assertIn('Stage the current manifest pair', self.workflow)
        self.assertIn('cp current/ytdlp-approved.json approved/ytdlp-approved.json', self.workflow)
        self.assertIn("json.load(open('approved/ytdlp-approved.json'))['sequence']", self.workflow)

    def test_approve_uses_the_python_manifest_scripts(self):
        self.assertIn('make-engine-manifest.py', self.workflow)
        self.assertIn('sign-engine-manifest.py', self.workflow)
        self.assertNotIn('make-engine-manifest.sh', self.workflow)
        self.assertNotIn('sign-engine-manifest.sh', self.workflow)

    def test_approve_runs_on_a_skipped_gate_but_never_a_failed_one(self):
        condition = self.job_condition('approve')
        self.assertIn("'success'", condition)
        self.assertIn("'skipped'", condition)
        self.assertNotIn("!= 'failure'", condition)

    def test_privileged_jobs_require_main(self):
        for name in ('gate', 'approve', 'deploy'):
            with self.subTest(job=name):
                self.assertIn(
                    "github.ref == 'refs/heads/main'", self.job_condition(name)
                )

    def test_detect_is_read_only_and_has_no_environment(self):
        detect = self.job_block('detect')
        self.assertNotIn('environment:', detect)
        self.assertNotIn('NEUTRODYNE_ENGINE_MANIFEST_KEY', detect)
        self.assertNotIn('contents: write', detect)

    def test_gate_runs_only_for_a_changed_candidate(self):
        self.assertIn(
            "needs.detect.outputs.changed == 'true'", self.job_condition('gate')
        )

    def test_approve_requires_a_green_gate_for_a_change_and_a_skipped_gate_for_a_noop(self):
        condition = self.job_condition('approve')
        self.assertIn("needs.detect.outputs.changed == 'true'", condition)
        self.assertIn("needs.gate.result == 'success'", condition)
        self.assertIn("needs.detect.outputs.changed == 'false'", condition)
        self.assertIn("needs.gate.result == 'skipped'", condition)

    def test_approve_binds_the_gate_candidate_to_the_detected_candidate(self):
        approve = self.job_block('approve')
        self.assertIn('DETECT_TAG: ${{ needs.detect.outputs.candidate }}', approve)
        self.assertIn('GATE_TAG: ${{ needs.gate.outputs.tag }}', approve)
        self.assertIn("does not match detected candidate", approve)

    def test_approve_has_no_keep_candidate_fallback_for_a_changed_run(self):
        step = self.step_block('approve', 'Write the new manifest')
        self.assertIn('changed run without a gate candidate', step)
        self.assertNotIn('args+=(--keep-candidate)', step)
        self.assertNotIn('args+=(--keep-candidate)', self.job_block('approve'))

    def test_strict_drift_report_is_main_only_and_nonblocking(self):
        step = self.step_block('gate', 'Record the strict-mode drift')
        self.assertIn('continue-on-error: true', step)
        self.assertEqual(
            "steps.strict.outcome == 'failure' && github.ref == 'refs/heads/main'",
            self.step_condition('gate', 'Record the strict-mode drift'),
        )

    def test_current_manifest_downloads_are_bounded(self):
        detect = self.job_block('detect')
        self.assertIn('--max-filesize 65536', detect)
        self.assertIn('--max-filesize 4096', detect)

    def test_deploy_states_the_verified_index_precondition(self):
        step = self.step_block('deploy', 'Publish the immutable snapshot and advance the index')
        self.assertIn('PRESENT: ${{ needs.detect.outputs.present }}', step)
        self.assertIn('--expected-index-tag', step)
        self.assertIn('--expect-no-index', step)
        # The bootstrap path must not silently fall back to an unguarded publish.
        self.assertNotIn('if [ -n "$EXPECTED_INDEX_TAG" ]', step)

    def test_keepalive_reenables_the_canary(self):
        keepalive = (ROOT / '.github/workflows/keepalive.yml').read_text()
        self.assertIn('engine-canary.yml', keepalive)


if __name__ == '__main__':
    unittest.main()
