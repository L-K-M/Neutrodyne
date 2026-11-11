# SPDX-License-Identifier: Unlicense
"""Host shim task entry; require the committed, build-verified bundle.

--replay=contract runs the scenario cases by endpoint/InnerTube-client match
(the engine canary's blocking gate); the default strict mode fails on any
unrecorded request. After `bump-ytdlp.sh` the staged candidate is what
bundled.json names, so the bundle check reads it instead of a constant."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BUILD = ROOT / 'youtube/ytdlp/build/shim'


def prepare():
    BUILD.mkdir(parents=True, exist_ok=True)
    asset = ROOT / 'youtube/ytdlp/engine/yt-dlp'
    metadata = json.loads((asset.parent / 'bundled.json').read_text())
    if hashlib.sha256(asset.read_bytes()).hexdigest() != metadata['sha256']:
        raise RuntimeError('Bundled yt-dlp hash mismatch')
    return asset


class XmlResult(unittest.TextTestResult):
    def startTest(self, test):
        super().startTest(test)
        self.cases.append(ET.SubElement(self.xml, 'testcase', name=str(test)))

    def addFailure(self, test, err):
        super().addFailure(test, err)
        ET.SubElement(self.cases[-1], 'failure').text = self._exc_info_to_string(err, test)

    def addError(self, test, err):
        super().addError(test, err)
        ET.SubElement(self.cases[-1], 'error').text = self._exc_info_to_string(err, test)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay', choices=('strict', 'contract'), default='strict')
    args = parser.parse_args()
    os.environ['ND_REPLAY_MODE'] = args.replay
    preferred = shutil.which('python3.14')
    if preferred and sys.version_info[:2] != (3, 14) and not os.environ.get('ND_SHIM_PYTHON_SELECTED'):
        env = dict(os.environ, ND_SHIM_PYTHON_SELECTED='1')
        raise SystemExit(subprocess.call([preferred, '-B', __file__] + sys.argv[1:], env=env))
    runtime = f'CPython {sys.version.split()[0]}'
    if sys.version_info[:2] != (3, 14):
        runtime += ' (fallback: Python 3.14 unavailable)'
    print(runtime, flush=True)
    (BUILD).mkdir(parents=True, exist_ok=True)
    (BUILD / 'python-runtime.txt').write_text(runtime + '\n')
    sys.path[:0] = [str(prepare()), str(ROOT / 'youtube/engine/python')]
    # A real request from any accidental fallback fails even if the extractor catches it.
    import socket
    def denied(*args, **kwargs):
        raise AssertionError('Network forbidden in shimTest')
    socket.socket.connect = denied
    socket.socket.connect_ex = denied
    XmlResult.xml = ET.Element('testsuite', name='shimTest')
    XmlResult.cases = []
    suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern='test_*.py')
    result = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult).run(suite)
    XmlResult.xml.set('tests', str(result.testsRun))
    XmlResult.xml.set('failures', str(len(result.failures)))
    XmlResult.xml.set('errors', str(len(result.errors)))
    ET.ElementTree(XmlResult.xml).write(BUILD / 'TEST-shim.xml', encoding='utf-8', xml_declaration=True)
    raise SystemExit(0 if result.wasSuccessful() and result.testsRun else 1)
