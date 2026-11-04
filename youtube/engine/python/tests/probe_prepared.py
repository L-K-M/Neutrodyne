# SPDX-License-Identifier: Unlicense
"""Exercise a real store-extracted bundle through the host adapter, without live traffic."""

import json
from pathlib import Path
import socket
import sys

ROOT = Path(__file__).resolve().parents[4]
prepared = Path(sys.argv[1])
lib = prepared / (prepared / "lib-path.txt").read_text()
payload = (prepared / "call.json").read_text()
sys.path[:0] = [str(lib), str(ROOT / 'youtube/engine/python')]


def denied(*args, **kwargs):
    raise AssertionError('Network forbidden in preparation probe')


socket.socket.connect = denied
socket.socket.connect_ex = denied
from neutrodyne_ytx import host_chaquopy
from yt_dlp import version

assert Path(version.__file__).is_relative_to(Path(lib))
host_chaquopy.configure(False, replay_directory=ROOT / 'youtube/engine/src/test/resources/recorded/ping')
assert json.loads(host_chaquopy.call('ping', payload, 5000, 1)) == {'ready': True}
assert json.loads(host_chaquopy.call('selftest', payload, 5000, 2)) == {'ok': True, 'missing': []}
print(host_chaquopy.call('version', payload, 5000, 3))

# The store's extracted files must also serve the same recorded method corpus.
from unittest.mock import patch
from neutrodyne_ytx.replay import ReplayRH

fixtures = ROOT / 'youtube/engine/src/test/resources/recorded'
for directory in sorted(path for path in fixtures.iterdir() if path.is_dir()):
    host_chaquopy._bridge = None
    host_chaquopy.configure(False, replay_directory=directory)
    request = json.loads((directory / 'request.json').read_text())
    expected = json.loads((directory / 'result.json').read_text())
    with patch('time.time', return_value=1792368000):
        actual = json.loads(host_chaquopy.call(request['method'], json.dumps(request['payload']), 5000, 4))
    if directory.name == 'version':
        expected['python'] = sys.version.split()[0]
    assert actual == expected, directory.name
    assert ReplayRH.misses == 0
    assert not host_chaquopy._calls
    print('prepared replay:', directory.name)
