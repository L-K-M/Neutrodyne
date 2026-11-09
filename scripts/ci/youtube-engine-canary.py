#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Report-only live probe of the pinned shim, never invoked by deterministic tests."""
import hashlib
import json
from pathlib import Path
import sys
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'youtube/ytdlp/engine/yt-dlp'
CHUNK = 10 * 1024 * 1024

GENERIC_ERROR = 'Engine probe failed; runner bot challenges are possible'
UNKNOWN_STAGE = 'unknown'
UNEXPECTED_CODE = 'UNEXPECTED'
STAGES = ('lookup-mkbhd', 'lookup-nasa', 'resolve', 'chunk')
# Mirror of the shim's wire codes (Code in youtube/engine/python/neutrodyne_ytx/errors.py,
# pinned offline by the tests). These are the only failure codes a report may carry;
# no stage or code alone proves a runner bot challenge.
SAFE_CODES = frozenset({
    'RATE_LIMITED', 'AGE_RESTRICTED', 'MEMBERS_ONLY', 'PRIVATE', 'REGION_BLOCKED',
    'UPCOMING', 'LIVE', 'KIDS_ONLY', 'UNAVAILABLE', 'EXTRACTION', 'NETWORK',
    'TIMEOUT', 'CANCELLED', 'CURSOR_EXPIRED', 'ENGINE_UNAVAILABLE',
})


def run(bridge, fetch_chunk):
    ctx = {'hl': 'en', 'gl': 'US', 'ua': 'Neutrodyne/YouTube-canary', 'ipFamily': None}
    results = []
    stage = None
    try:
        for stage, handle, call_id in (('lookup-mkbhd', '@mkbhd', 1), ('lookup-nasa', '@NASA', 2)):
            payload = json.dumps({'ctx': ctx, 'url': 'https://www.youtube.com/' + handle})
            value = json.loads(bridge.call('lookup', payload, time.monotonic() + 30, call_id, threading.Event()))
            if not value.get('channelId'):
                raise ValueError('Channel lookup failed')
            results.append({'channelId': value['channelId']})
        stage = 'resolve'
        value = json.loads(bridge.call('resolve', json.dumps({'ctx': ctx, 'videoId': '3iRUwVzRDZQ'}),
                                       time.monotonic() + 30, 3, threading.Event()))
        formats = [item for item in value['formats'] if item.get('protocol') in ('http', 'https')]
        if not formats:
            raise ValueError('No progressive audio')
        stage = 'chunk'
        size = fetch_chunk(formats[0]['url'])
        if not 0 < size <= CHUNK:
            raise ValueError('Invalid chunk size')
    except Exception as error:
        error.canary_stage = stage
        raise
    return {'ok': True, 'channels': results, 'chunkBytes': size}


def report(error, shim_error=()):
    """Failure JSON: the fixed safe message plus bounded stage/code enumerations.

    The raw error can contain a signed media URL or credentials, so the report never
    carries the exception text, class or URL. ``shim_error`` is the exception type
    whose ``code`` is already a wire enum (neutrodyne_ytx.errors.ShimError); every
    other failure, and any code outside SAFE_CODES, reports UNEXPECTED.
    """
    stage = getattr(error, 'canary_stage', None)
    code = getattr(error, 'code', None) if isinstance(error, shim_error) else None
    return {'ok': False, 'error': GENERIC_ERROR,
            'stage': stage if stage in STAGES else UNKNOWN_STAGE,
            'code': code if code in SAFE_CODES else UNEXPECTED_CODE}


def fetch_chunk(url):
    request = urllib.request.Request(url, headers={'Range': f'bytes=0-{CHUNK - 1}'})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status != 206:
            raise ValueError('Media did not honor Range')
        return len(response.read(CHUNK + 1))


def main():
    metadata = json.loads((BUNDLE.parent / 'bundled.json').read_text())
    if hashlib.sha256(BUNDLE.read_bytes()).hexdigest() != metadata['sha256']:
        raise ValueError('Pinned engine hash mismatch')
    sys.path[:0] = [str(BUNDLE), str(ROOT / 'youtube/engine/python')]
    from neutrodyne_ytx.bridge import Bridge
    from neutrodyne_ytx.errors import ShimError
    try:
        result = run(Bridge(cache_dir=False), fetch_chunk)
    except Exception as error:
        # The raw extractor or socket error can contain a signed media URL.
        result = report(error, ShimError)
    Path('youtube-engine-canary.json').write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
