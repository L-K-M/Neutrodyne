#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Packaged auth/sync/export/revocation smoke through a real TLS proxy. Never print secrets."""
import argparse
import io
import json
import os
import pathlib
import ssl
import time
import urllib.error
import urllib.request
import uuid
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('mode', choices=['exercise', 'mutate', 'after-restore', 'events'])
parser.add_argument('--url', required=True)
parser.add_argument('--state', type=pathlib.Path, required=True)
parser.add_argument('--ca', type=pathlib.Path, required=True)
parser.add_argument('--seconds', type=int, default=300)
args = parser.parse_args()
context = ssl.create_default_context(cafile=str(args.ca))
# The smoke hostname resolves to loopback using curl/runner hosts configuration.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))


def request(path, body=None, token=None, status=200):
    headers = {'Neutrodyne-Sync-Protocol': '1'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(args.url + path, data=data, headers=headers)
    try:
        response = opener.open(req, timeout=130)
    except urllib.error.HTTPError as error:
        response = error
    require(response.status == status, f'{path}: expected HTTP {status}, got {response.status}')
    return response


if args.mode == 'exercise':
    invite = os.environ['SMOKE_INVITE']
    device = {'id': str(uuid.uuid4()), 'name': 'Compose smoke', 'platform': 'linux',
              'appVersion': '0.test', 'nodeId': '1111111111111111'}
    grant = json.load(request('/api/v1/auth/invite/redeem', {'inviteCode': invite, 'device': device}))
    token = grant['token']
    podcast = str(uuid.uuid4())
    # Match the protocol's 64-bit packed hexadecimal HLC and 16-digit node.
    clock = f'{int(time.time() * 1000) << 16:016x}-1111111111111111'
    changes = [
        {'coll': 'podcast', 'id': podcast, 'fields': {
            'feedUrl': {'v': 'https://example.org/smoke.xml', 'c': clock},
            'subscribed': {'v': True, 'c': clock}, 'title': {'v': 'Smoke library', 'c': clock}}},
        {'coll': 'episode', 'id': {'p': podcast, 'k': 'g:smoke'}, 'fields': {'fav': {'v': True, 'c': clock}}}]
    result = json.load(request('/api/v1/sync', {'since': None, 'changes': changes}, token))
    require(all(row['status'] == 'applied' for row in result['results']), result['results'])
    exported = request('/api/v1/account/export', token=token).read()
    with zipfile.ZipFile(io.BytesIO(exported)) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        require(len(archive.namelist()) == 6 and manifest['counts']['episodeLines'] == 1, 'Server validation failed')
        require(manifest['app']['abi'] == 'server', 'Server validation failed')
        require(json.loads(archive.read('episodes.jsonl'))['fav'], 'Server validation failed')
    args.state.write_text(json.dumps({'token': token, 'cursor': result['cursor'], 'podcast': podcast}))
    args.state.chmod(0o600)
elif args.mode == 'mutate':
    state = json.loads(args.state.read_text())
    clock = f'{int(time.time() * 1000) << 16:016x}-1111111111111111'
    body = {'since': None, 'changes': [{'coll': 'episode', 'id': {'p': state['podcast'], 'k': 'g:after-backup'},
                                      'fields': {'fav': {'v': True, 'c': clock}}}]}
    changed = json.load(request('/api/v1/sync', body, state['token']))
    require(changed['results'][0]['status'] == 'applied', 'Post-backup mutation was not applied')
elif args.mode == 'after-restore':
    state = json.loads(args.state.read_text())
    request('/api/v1/account', token=state['token'], status=401).close()
    device = {'id': str(uuid.uuid4()), 'name': 'Restore verification', 'platform': 'linux',
              'appVersion': '0.test', 'nodeId': '2222222222222222'}
    grant = json.load(request('/api/v1/auth/invite/redeem', {'inviteCode': os.environ['SMOKE_INVITE'], 'device': device}))
    token = grant['token']
    summary = json.load(request('/api/v1/account', token=token))
    require(summary['records'] == 2, 'Restore did not roll back the post-backup record')
    request('/api/v1/changes?since=' + state['cursor'], token=token, status=410).close()
    restored = json.load(request('/api/v1/changes', token=token))
    require(any(row['coll'] == 'episode' and row['fields'].get('fav', {}).get('v') is True
                for row in restored['records']), 'Restored episode state is missing')
else:
    state = json.loads(args.state.read_text())
    started = time.monotonic()
    lines = 0
    with request('/api/v1/events', token=state['token']) as response:
        require(response.headers.get_content_type() == 'text/event-stream', 'Server validation failed')
        require(response.headers.get('Content-Encoding') is None, 'Server validation failed')
        require(response.readline().startswith(b'retry:'), 'Server validation failed')
        while time.monotonic() - started < args.seconds:
            line = response.readline()
            require(line, 'proxy ended SSE early')
            if line.startswith(b':') or line.startswith(b'data:'):
                lines += 1
        require(lines >= max(1, args.seconds // 120), 'SSE did not flush heartbeats')
print('Packaged server check passed:', args.mode)
