#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""D95 image inventory, reproducible exact sources and fail-closed release checks."""
import argparse
import email
import hashlib
import io
import json
import pathlib
import re
import shutil
import tarfile
import urllib.request


def require(condition, message):
    if not condition:
        raise ValueError(message)


ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = ROOT / 'sync/server/deploy/image-sources.lock.json'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def load_lock():
    lock = json.loads(LOCK.read_text())
    require(re.fullmatch(r'gcr.io/distroless/java25-debian13:nonroot@sha256:[a-f0-9]{64}', lock['base']), 'Server validation failed')
    require(set(lock['architectures']) == {'amd64', 'arm64'}, 'Server validation failed')
    expected = {(p['source'], p['sourceVersion']) for a in lock['architectures'].values()
                for p in a['packages'] if p['source'] != 'temurin-25-jre'}
    require(expected == {(s['package'], s['version']) for s in lock['sources']}, 'incomplete source lock')
    for source in lock['sources']:
        require(source['files'] and any(f['name'].endswith('.dsc') for f in source['files']), 'Server validation failed')
        for item in source['files']:
            require(pathlib.PurePosixPath(item['name']).name == item['name'], 'Server validation failed')
            require(re.fullmatch(r'[a-f0-9]{64}', item['sha256']), 'Server validation failed')
    return lock


def package_rows(files):
    rows = []
    for name, data in files.items():
        if not name.startswith('var/lib/dpkg/status.d/') or name.endswith('.md5sums'):
            continue
        field = email.message_from_bytes(data)
        source = re.fullmatch(r'(\S+)(?: \(([^)]+)\))?', field.get('Source', field['Package']))
        rows.append({'package': field['Package'], 'version': field['Version'], 'source': source[1],
                     'sourceVersion': source[2] or re.sub(r'\+b\d+$', '', field['Version'])})
    return sorted(rows, key=lambda p: p['package'])


def selected_files(archive):
    files, names = {}, set()
    for member in archive:
        name = member.name.removeprefix('./').lstrip('/')
        require('..' not in pathlib.PurePosixPath(name).parts, 'unsafe image path')
        names.add(name)
        if member.isfile() and (name.startswith('var/lib/dpkg/status.d/') or name.endswith('/release')
                                or name in ('app/SOURCES.md', 'app/THIRD_PARTY_NOTICES.md')):
            require(member.size <= 4 * 1024 * 1024, 'oversized metadata')
            files[name] = archive.extractfile(member).read()
    return files, names


def check_inventory(files, names, config, arch, lock, version):
    require(arch in lock['architectures'], 'Server validation failed')
    require(config['Config']['User'] == '65532:65532', 'image must run as nonroot')
    require(not names.intersection({'bin/sh', 'bin/bash', 'usr/bin/sh', 'usr/bin/bash'}), 'shell in image')
    require('app/neutrodyne-server.jar' in names, 'Server validation failed')
    require(any('/legal/java.base/LICENSE' in n for n in names), 'runtime legal notices absent')
    require(package_rows(files) == lock['architectures'][arch]['packages'], 'base package/source version mismatch')
    releases = [v.decode() for n, v in files.items() if n.endswith('/release') and 'jvm/' in n]
    require(len(releases) == 1 and f'JAVA_RUNTIME_VERSION="{lock["runtime"]["version"]}"' in releases[0], 'runtime mismatch')
    labels = config['Config']['Labels']
    require(labels['org.opencontainers.image.version'] == version, 'Server validation failed')
    offer = files['app/SOURCES.md'].decode()
    require(f':{version}-sources' in offer and lock['runtime']['name'] in offer, 'Server validation failed')
    require(f'neutrodyne-server-image-sources-{version}.tar.xz' in offer, 'Server validation failed')
    require('three years' in offer and '/releases/tag/v' + version in offer, 'Server validation failed')
    require(labels['ch.lkmc.neutrodyne.sources'].split()[0] in offer, 'Server validation failed')
    require(len(files['app/THIRD_PARTY_NOTICES.md']) > 100, 'classpath notices absent')


def download(item, destination):
    if destination.exists() and sha256(destination.read_bytes()) == item['sha256']:
        return
    temporary = destination.with_suffix(destination.suffix + '.tmp')
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        request = urllib.request.Request(item['url'], headers={'User-Agent': 'Neutrodyne-source-collector'})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        require(sha256(temporary.read_bytes()) == item['sha256'], f'source checksum mismatch: {item["name"]}')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def runtime_name(lock):
    return lock['runtime']['name']


def collect(lock, output, version):
    output.mkdir(parents=True, exist_ok=True)
    cache = output / '.server-source-cache'
    cache.mkdir(exist_ok=True)
    download(lock['runtime'], output / runtime_name(lock))
    for source in lock['sources']:
        for item in source['files']:
            download(item, cache / source['package'] / item['name'])
    bundle = output / f'neutrodyne-server-image-sources-{version}.tar.xz'
    temporary = bundle.with_suffix('.tmp')
    with tarfile.open(temporary, 'w:xz') as tar:
        def add(name, data):
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), 0o644, 0
            tar.addfile(info, io.BytesIO(data))
        add('inventory.json', json.dumps(lock, sort_keys=True, indent=2).encode())
        for source in lock['sources']:
            for item in source['files']:
                add(f'{source["package"]}/{item["name"]}', (cache / source['package'] / item['name']).read_bytes())
    temporary.replace(bundle)
    (output / 'RUNTIME-SOURCES.md').write_text(
        '# Server runtime sources\n\n'
        f'Image base: `{lock["base"]}`. Runtime: Temurin `{lock["runtime"]["version"]}`.\n\n'
        f'Exact, unmodified runtime source: `{runtime_name(lock)}`; SHA-256 `{lock["runtime"]["sha256"]}`.\n\n'
        f'Base package sources: `{bundle.name}`; `inventory.json` records package and source versions '
        'for both supported architectures and the SHA-256 of every source file. '
        'Runtime legal notices remain in the image. See `/app/SOURCES.md` for the GHCR source offer.\n')
    check_sources(lock, output, version)


def check_sources(lock, directory, version):
    require(sha256((directory / runtime_name(lock)).read_bytes()) == lock['runtime']['sha256'], 'runtime source mismatch')
    with tarfile.open(directory / f'neutrodyne-server-image-sources-{version}.tar.xz', 'r:xz') as tar:
        inventory = json.load(tar.extractfile('inventory.json'))
        require(inventory == lock, 'source inventory mismatch')
        expected = {'inventory.json'}
        for source in lock['sources']:
            for item in source['files']:
                name = f'{source["package"]}/{item["name"]}'
                expected.add(name)
                member = tar.getmember(name)
                require(member.isfile() and member.size == item['size'], 'source file size mismatch')
                require(sha256(tar.extractfile(member).read()) == item['sha256'], 'source file mismatch')
        require(len(tar.getnames()) == len(expected) and set(tar.getnames()) == expected, 'source bundle entry mismatch')
    require(runtime_name(lock) in (directory / 'RUNTIME-SOURCES.md').read_text(), 'Server validation failed')


def prepare(lock, jar, context, version, notices):
    require(re.fullmatch(r'\d+\.\d+\.\d+(?:[a-zA-Z0-9.+-]*)', version), 'invalid version')
    context.mkdir(parents=True, exist_ok=True)
    for name in ('Dockerfile',):
        shutil.copyfile(ROOT / 'sync/server/deploy' / name, context / name)
    (context / 'data').mkdir(exist_ok=True)
    (context / 'data/.keep').touch()
    shutil.copyfile(jar, context / f'neutrodyne-server-{version}.jar')
    shutil.copyfile(notices, context / 'THIRD_PARTY_NOTICES.md')
    (context / 'SOURCES.md').write_text(
        '# Corresponding sources\n\n'
        f'Exact runtime source: {runtime_name(lock)}.\n'
        f'Exact base package sources: neutrodyne-server-image-sources-{version}.tar.xz.\n'
        f'Both archives are available from ghcr.io/l-k-m/neutrodyne-server:{version}-sources '
        f'and https://github.com/L-K-M/Neutrodyne/releases/tag/v{version}.\n\n'
        'For three years after distribution, we offer the complete corresponding source '
        'for this image, including the runtime and base components, at no more than the '
        'cost of distribution. Request it at https://github.com/L-K-M/Neutrodyne/issues.\n'
        'The immutable inventory records exact package versions and file hashes. '
        'Runtime and base licence notices are retained in the image.\n')


def promotion_tags(version, releases, latest, make_latest):
    require(re.fullmatch(r'\d+\.\d+\.\d+', version), 'Server validation failed')
    minor = version.rsplit('.', 1)[0]
    candidates = [tag[1:] for tag in releases if re.fullmatch(r'v\d+\.\d+\.\d+', tag)
                  and tag[1:].rsplit('.', 1)[0] == minor]
    require(version in candidates, 'release must be published before image promotion')
    tags = [version]
    if max(candidates, key=lambda value: tuple(map(int, value.split('.')))) == version:
        tags.append(minor)
    if make_latest and latest == 'v' + version:
        tags.append('latest')
    return tags


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['collect', 'check-sources', 'check-rootfs', 'prepare', 'promotion-tags'])
    parser.add_argument('--version', required=True)
    parser.add_argument('--directory', type=pathlib.Path, required=True)
    parser.add_argument('--config', type=pathlib.Path)
    parser.add_argument('--arch', choices=['amd64', 'arm64'])
    parser.add_argument('--jar', type=pathlib.Path)
    parser.add_argument('--notices', type=pathlib.Path)
    parser.add_argument('--latest')
    parser.add_argument('--make-latest', choices=['true', 'false'])
    args = parser.parse_args()
    if args.command == 'promotion-tags':
        print('\n'.join(promotion_tags(args.version, args.directory.read_text().splitlines(), args.latest, args.make_latest == 'true')))
        return
    lock = load_lock()
    if args.command == 'collect':
        collect(lock, args.directory, args.version)
    elif args.command == 'check-sources':
        check_sources(lock, args.directory, args.version)
    elif args.command == 'prepare':
        prepare(lock, args.jar, args.directory, args.version, args.notices)
    else:
        with tarfile.open(args.directory, 'r:*') as tar:
            files, names = selected_files(tar)
        config = json.loads(args.config.read_text())[0]
        check_inventory(files, names, config, args.arch, lock, args.version)
    print('Server image/source checks passed')


if __name__ == '__main__':
    main()
