#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Reject absent, failed, skipped or zero-case MS1 suites, including missing seed runs."""
import argparse
import pathlib
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=pathlib.Path)
parser.add_argument('--seeds', type=int, required=True)
parser.add_argument('--start', type=int, default=0)
parser.add_argument('--convergence-only', action='store_true')
args = parser.parse_args()
require(args.seeds > 0, 'Server validation failed')
suites = [ET.parse(p).getroot() for p in args.directory.glob('TEST-*.xml')]
required = {'ServerConvergenceTest': 2}
if not args.convergence_only:
    required['ServerConformanceTest'] = len(list(pathlib.Path('sync/protocol/vectors').glob('*.json')))
for name, minimum in required.items():
    matches = [s for s in suites if s.get('name', '').endswith('.' + name)]
    require(matches, f'required suite {name} is absent')
    cases = [c for s in matches for c in s.findall('testcase')]
    require(len(cases) >= minimum, f'{name}: expected {minimum} cases, got {len(cases)}')
    require(not any(c.find('skipped') is not None or c.find('failure') is not None or c.find('error') is not None for c in cases), f'{name}: failed/skipped required case')
    if name == 'ServerConvergenceTest':
        require(any(f'seeds={args.seeds} start={args.start} ' in (s.findtext('system-out') or '') for s in matches), 'required real-server seed range did not execute')
print('MS1 required suites and real-server seed range executed without skips')
