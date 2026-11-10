#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fail closed on missing or incomplete E12 aggregate evidence."""
from datetime import datetime
import json
from pathlib import Path
import re
import sys


def require_report(value):
    def require(condition):
        if not condition:
            raise ValueError('incomplete cross-device evidence')

    require(isinstance(value, dict))
    require(type(value.get('schema')) is int and value['schema'] == 1)
    require(value.get('ok') is True and value.get('cleanup') is True)
    require('error' not in value)
    require(bool(re.fullmatch(r'e12-[0-9a-f]{32}', value.get('runId', ''))))
    require(bool(re.fullmatch(r'[0-9a-f]{40}', value.get('commit', ''))))
    require(type(value.get('seed')) is int)
    started = datetime.fromisoformat(value['startedAt'])
    completed = datetime.fromisoformat(value['completedAt'])
    require(started.tzinfo is not None and completed.tzinfo is not None and completed >= started)
    artifacts = value['artifacts']
    require(set(artifacts) == {'android', 'androidTest', 'server'})
    require(all(isinstance(digest, str) and re.fullmatch(r'[0-9a-f]{64}', digest)
                for digest in artifacts.values()))
    proof = value['proof']
    for field, subscriptions in (('baseline', 50), ('afterOfflineEdits', 50), ('afterApproval', 39)):
        counts = proof[field]
        require(counts['podcasts'] == subscriptions and type(counts['podcasts']) is int)
        # Public summaries may include additional safe counters, but all must
        # be actual nonnegative integers and no pending sync work may remain.
        require(all(type(number) is int and number >= 0 for number in counts.values()))
        require(all(counts[name] == 0 for name in ('outbox', 'parked', 'held')))
        require(counts['groups'] == 2 and counts['episodes'] >= subscriptions)
    require(proof['heldBeforeApproval'] == {'podcasts': 50, 'held': 1, 'removals': 11})
    require(all(type(count) is int for count in proof['heldBeforeApproval'].values()))
    require(set(proof['randomEdits']) == {'android', 'desktop'})
    require(all(type(count) is int and count >= 9 for count in proof['randomEdits'].values()))
    require(proof['resumePositionMs'] == 123_456 and proof['positionToleranceMs'] == 1000)
    require(proof['outage'] == 'sync-server gateway returned 503; radios were not disabled')
    require(proof['playback'] == 'persistent position projection; foreground playback timing remains a device check')
    require(set(value['gateway']) == {'requests', 'refused', 'errors'})
    require(all(type(count) is int and count >= 0 for count in value['gateway'].values()))
    require(value['gateway']['requests'] > 0)


def main():
    try:
        if len(sys.argv) != 2:
            raise ValueError()
        path = Path(sys.argv[1])
        if not path.is_file() or path.stat().st_size > 64 * 1024:
            raise ValueError()
        require_report(json.loads(path.read_bytes()))
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        print('cross-device evidence: missing, malformed or incomplete', file=sys.stderr)
        return 1
    print('cross-device evidence: PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
