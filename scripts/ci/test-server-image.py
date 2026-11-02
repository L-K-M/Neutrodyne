#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
import copy
import importlib.util
import io
import json
import pathlib
import tarfile
import tempfile
import unittest
import subprocess
import os
import sys

SPEC = importlib.util.spec_from_file_location('server_image', pathlib.Path(__file__).with_name('server-image.py'))
IMAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMAGE)


class ServerImageTest(unittest.TestCase):
    def setUp(self):
        self.lock = IMAGE.load_lock()
        self.version = '0.1.0'
        self.files = {}
        for p in self.lock['architectures']['amd64']['packages']:
            self.files['var/lib/dpkg/status.d/' + p['package']] = (
                f'Package: {p["package"]}\nVersion: {p["version"]}\n'
                f'Source: {p["source"]} ({p["sourceVersion"]})\n').encode()
        self.files['usr/lib/jvm/temurin/release'] = f'JAVA_RUNTIME_VERSION="{self.lock["runtime"]["version"]}"'.encode()
        self.files['app/SOURCES.md'] = (
            f'ghcr.io/l-k-m/neutrodyne-server:{self.version}-sources '
            f'https://github.com/L-K-M/Neutrodyne/releases/tag/v{self.version} '
            f'{self.lock["runtime"]["name"]} neutrodyne-server-image-sources-{self.version}.tar.xz '
            'Source offer for three years').encode()
        self.files['app/THIRD_PARTY_NOTICES.md'] = b'Licences and retained notices. ' * 10
        self.names = set(self.files) | {'app/neutrodyne-server.jar', 'usr/lib/jvm/temurin/legal/java.base/LICENSE'}
        self.config = {'Config': {'User': '65532:65532', 'Labels': {
            'org.opencontainers.image.version': self.version,
            'ch.lkmc.neutrodyne.sources': f'ghcr.io/l-k-m/neutrodyne-server:{self.version}-sources'}}}

    def check(self):
        IMAGE.check_inventory(self.files, self.names, self.config, 'amd64', self.lock, self.version)

    def test_matching_inventory_passes(self):
        self.check()

    def test_root_is_rejected(self):
        self.config['Config']['User'] = '0'
        with self.assertRaisesRegex(ValueError, 'nonroot'):
            self.check()

    def test_shell_is_rejected(self):
        self.names.add('bin/sh')
        with self.assertRaisesRegex(ValueError, 'shell'):
            self.check()

    def test_unknown_package_or_version_is_rejected(self):
        self.files['var/lib/dpkg/status.d/unexpected'] = b'Package: unexpected\nVersion: 1\n'
        with self.assertRaisesRegex(ValueError, 'package/source'):
            self.check()

    def test_wrong_runtime_is_rejected(self):
        self.files['usr/lib/jvm/temurin/release'] = b'JAVA_RUNTIME_VERSION="21"'
        with self.assertRaisesRegex(ValueError, 'runtime mismatch'):
            self.check()

    def test_missing_offer_or_legal_notice_is_rejected(self):
        self.names.remove('usr/lib/jvm/temurin/legal/java.base/LICENSE')
        with self.assertRaisesRegex(ValueError, 'legal notices'):
            self.check()

    def test_source_archive_hash_and_inventory_are_verified(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            runtime = b'unmodified-runtime-source'
            source = b'exact-base-source'
            lock = copy.deepcopy(self.lock)
            lock['runtime'].update(name='runtime.tar.gz', sha256=IMAGE.sha256(runtime))
            lock['sources'] = [{'package': 'base', 'version': '1', 'files': [
                {'name': 'base.dsc', 'size': len(source), 'sha256': IMAGE.sha256(source)}]}]
            (root / 'runtime.tar.gz').write_bytes(runtime)
            (root / 'RUNTIME-SOURCES.md').write_text('runtime.tar.gz')
            def write_bundle(payload):
                with tarfile.open(root / 'neutrodyne-server-image-sources-0.1.0.tar.xz', 'w:xz') as tar:
                    for name, data in [('inventory.json', json.dumps(lock).encode()), ('base/base.dsc', payload)]:
                        item = tarfile.TarInfo(name)
                        item.size = len(data)
                        tar.addfile(item, io.BytesIO(data))
            write_bundle(source)
            IMAGE.check_sources(lock, root, self.version)
            write_bundle(b'wrong-base-source')
            with self.assertRaises(ValueError):
                IMAGE.check_sources(lock, root, self.version)
            write_bundle(source)
            (root / 'runtime.tar.gz').write_bytes(b'wrong-runtime')
            with self.assertRaisesRegex(ValueError, 'runtime source mismatch'):
                IMAGE.check_sources(lock, root, self.version)

    def test_server_update_manifest_accepts_lowercase_registry_owner(self):
        self.manifest_case('ghcr.io/l-k-m/neutrodyne-server@sha256:' + 'a' * 64, 0)

    def test_server_update_manifest_rejects_wrong_registry_or_digest(self):
        self.manifest_case('ghcr.io/unrelated/neutrodyne-server:latest', 1)

    def manifest_case(self, image, expected):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            props = dict(line.split('=', 1) for line in (IMAGE.ROOT / 'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
            version = props['neutrodyne.versionName']
            for name in ('arm64-v8a.apk', 'x86_64.apk', 'armeabi-v7a.apk'):
                (root / ('neutrodyne-' + version + '-' + name)).write_bytes(b'fixture')
            (root / ('neutrodyne-server-' + version + '.jar')).write_bytes(b'fixture-server')
            environment = dict(os.environ, NEUTRODYNE_SERVER_IMAGE=image)
            result = subprocess.run([str(IMAGE.ROOT / 'scripts/ci/make-update-json.sh'), 'v' + version, str(root)],
                                    env=environment, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr.decode())
            checked = subprocess.run([str(IMAGE.ROOT / 'scripts/ci/check-update-json.sh'),
                                      str(root / 'neutrodyne-update.json'), str(root), 'v' + version], capture_output=True)
            self.assertEqual(expected, checked.returncode, checked.stderr.decode())

    def test_missing_required_suites_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run([sys.executable, str(IMAGE.ROOT / 'scripts/ci/assert-server-tests.py'), temp,
                                     '--seeds', '1000'], capture_output=True)
            self.assertNotEqual(0, result.returncode)

    def test_skipped_convergence_cannot_count_as_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            (pathlib.Path(temp) / 'TEST-server.xml').write_text(
                '<testsuite name="x.ServerConvergenceTest"><testcase name="seeded"><skipped/></testcase>'
                '<testcase name="regression"/><system-out>seeds=1000 start=0 </system-out></testsuite>')
            result = subprocess.run([sys.executable, str(IMAGE.ROOT / 'scripts/ci/assert-server-tests.py'), temp,
                                     '--seeds', '1000', '--convergence-only'], capture_output=True)
            self.assertNotEqual(0, result.returncode)

    def test_old_patch_cannot_roll_minor_or_latest_back(self):
        self.assertEqual(['1.2.3'], IMAGE.promotion_tags('1.2.3', ['v1.2.3', 'v1.2.4'], 'v1.2.4', True))

    def test_stale_preview_does_not_move_latest(self):
        self.assertEqual(['1.2.3', '1.2'], IMAGE.promotion_tags('1.2.3', ['v1.2.3', 'v1.3.0'], 'v1.3.0', True))

    def test_newest_published_release_can_promote(self):
        self.assertEqual(['1.3.0', '1.3', 'latest'], IMAGE.promotion_tags('1.3.0', ['v1.2.3', 'v1.3.0'], 'v1.3.0', True))

    def test_draft_failure_cannot_move_any_image_tags(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / 'gh').write_text('#!/bin/sh\ncat <<EOF\n' + json.dumps({'draft': True, 'prerelease': False, 'immutable': False}) + '\nEOF\n')
            (root / 'gh').chmod(0o755)
            for name in ('docker', 'oras'):
                (root / name).write_text('#!/bin/sh\necho called >> "' + str(root / 'moved') + '"\n')
                (root / name).chmod(0o755)
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                               GITHUB_REPOSITORY='L-K-M/Neutrodyne', PUBLISH_MAKE_LATEST='true')
            result = subprocess.run([str(IMAGE.ROOT / 'scripts/ci/promote-server-image.sh'), 'v0.1.0',
                                     'ghcr.io/l-k-m/neutrodyne-server@sha256:' + 'a' * 64, str(root)],
                                    env=environment, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / 'moved').exists())

    def test_untrusted_archive_paths_are_rejected(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as tar:
            item = tarfile.TarInfo('../escape')
            tar.addfile(item)
        buffer.seek(0)
        with tarfile.open(fileobj=buffer) as tar, self.assertRaisesRegex(ValueError, 'unsafe'):
            IMAGE.selected_files(tar)


if __name__ == '__main__':
    unittest.main()
