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


def run(bridge, fetch_chunk):
    ctx = {'hl': 'en', 'gl': 'US', 'ua': 'Neutrodyne/YouTube-canary', 'ipFamily': None}
    results = []
    for index, handle in enumerate(('@mkbhd', '@NASA')):
        payload = json.dumps({'ctx': ctx, 'url': 'https://www.youtube.com/' + handle})
        value = json.loads(bridge.call('lookup', payload, time.monotonic() + 30, index + 1, threading.Event()))
        if not value.get('channelId'):
            raise ValueError('Channel lookup failed')
        results.append({'channelId': value['channelId']})
    value = json.loads(bridge.call('resolve', json.dumps({'ctx': ctx, 'videoId': '3iRUwVzRDZQ'}),
                                   time.monotonic() + 30, 3, threading.Event()))
    formats = [item for item in value['formats'] if item.get('protocol') in ('http', 'https')]
    if not formats:
        raise ValueError('No progressive audio')
    size = fetch_chunk(formats[0]['url'])
    if not 0 < size <= CHUNK:
        raise ValueError('Invalid chunk size')
    return {'ok': True, 'channels': results, 'chunkBytes': size}


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
    try:
        result = run(Bridge(cache_dir=False), fetch_chunk)
    except Exception:
        # The raw extractor or socket error can contain a signed media URL.
        result = {'ok': False, 'error': 'Engine probe failed; runner bot challenges are possible'}
    Path('youtube-engine-canary.json').write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
