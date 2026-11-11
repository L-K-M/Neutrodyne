#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""E12: Android + desktop JVM + a real local server, with an owned runtime."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import sys
import tempfile
import uuid

from cross_device_clients import AndroidDriver, DesktopDriver, OwnedAndroidEmulator, checked, logged_command, select_app_apk
from cross_device_fixtures import FixtureGateway, HarnessError, OwnedSyncServer, new_workspace, require_workspace


def run_journey(android, desktop, gateway, config, seed, state, progress=print):
    """Only a successful exact-held assertion may authorize the approval phase."""
    run_id = config['runId']

    def phase(client, name, edits=None):
        value = dict(config, edits=[] if edits is None else edits)
        return client.phase(name, value)

    def converge():
        # A round's pull can precede the other client's latest push. Bound the
        # catch-up explicitly and require canonical AND materialized equality.
        for _ in range(5):
            phase(android, 'sync')
            phase(desktop, 'sync')
            phase(android, 'sync')
            left, right = phase(android, 'dump'), phase(desktop, 'dump')
            try:
                counts = state.require_converged(left, right, run_id)
                return left, right, counts
            except state.HarnessStateError:
                pass
        raise HarnessError('convergence: clients did not reach matching complete state')

    progress('stage: seed and password link', flush=True)
    phase(android, 'seed')
    phase(desktop, 'seed')
    phase(android, 'link')
    phase(desktop, 'link')
    first, _, baseline = converge()
    if baseline.get('podcasts') != 50:
        raise HarnessError('baseline: expected fifty subscriptions')

    progress('stage: random edits during sync-server outage', flush=True)
    edits = state.random_edits(seed)
    gateway.set_online(False)
    try:
        for client, changes in ((android, edits['android']), (desktop, edits['desktop'])):
            edited = phase(client, 'edit', changes)
            captured = edited.get('counts', {}).get('outbox')
            if type(captured) is not int or captured <= 0:
                raise HarnessError('offline edits: no captured changes were available to sync')
    finally:
        gateway.set_online(True)
    _, _, random_counts = converge()

    progress('stage: desktop position on Android after sync', flush=True)
    expected_position = 123_456
    phase(desktop, 'edit', [{'op': 'position', 'podcast': 49, 'episode': 49, 'value': expected_position}])
    android_dump, _, _ = converge()
    state.require_resume(android_dump, run_id, 49, expected_position)

    progress('stage: eleven removals held before explicit approval', flush=True)
    expected_ids = [row['syncId'] for row in first['library']['podcasts'] if row['index'] < 11]
    gateway.set_online(False)
    try:
        phase(android, 'edit', [{'op': 'unsubscribe', 'podcast': index} for index in range(11)])
    finally:
        gateway.set_online(True)
    phase(android, 'sync')
    phase(desktop, 'sync')
    held_dump = phase(desktop, 'dump')
    state.require_held(held_dump, run_id, expected_ids)
    held_counts = {'podcasts': held_dump['counts']['podcasts'],
                   'held': len(held_dump['held']), 'removals': len(expected_ids)}
    sender = state.require_dump(phase(android, 'dump'), run_id)
    if sender['counts']['podcasts'] != 39:
        raise HarnessError('held: sender did not remove exactly eleven subscriptions')
    # No best-effort approval: every prior validation must pass first.
    phase(desktop, 'apply-held')
    _, _, final_counts = converge()
    if final_counts.get('podcasts') != 39:
        raise HarnessError('approval: expected thirty-nine subscriptions')
    return {'baseline': baseline, 'randomEdits': {name: len(value) for name, value in edits.items()},
            'afterOfflineEdits': random_counts, 'resumePositionMs': expected_position,
            'positionToleranceMs': 1000, 'heldBeforeApproval': held_counts, 'afterApproval': final_counts,
            'outage': 'sync-server gateway returned 503; radios were not disabled',
            'playback': 'persistent position projection; foreground playback timing remains a device check'}


def build(checkout, gradle, root):
    log = root / 'build-private.log'
    # Local callers pass the machine's shared wrapper. CI uses gradlew with
    # its normal no-daemon configuration. No lock is bypassed.
    logged_command(
        [str(gradle), '--max-workers=2', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
         ':desktopApp:testClasses', ':sync:server:fatJar'], cwd=checkout, log=log,
        stage='build', timeout=5400)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--gradle', type=Path, help='local shared wrapper, or checkout/gradlew in CI')
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java', type=Path, default=(Path(os.environ['JAVA_HOME']) / 'bin/java')
                        if os.environ.get('JAVA_HOME') else None)
    parser.add_argument('--system-image', required=True,
                        choices=['system-images;android-36;google_apis;arm64-v8a',
                                 'system-images;android-36;google_apis;x86_64'])
    parser.add_argument('--workspace-parent', type=Path, default=Path(tempfile.gettempdir()))
    parser.add_argument('--report', type=Path, default=Path('build/reports/cross-device-summary.json'))
    parser.add_argument('--seed', type=int, default=20261110)
    return parser.parse_args()


def main():
    args = arguments()

    def interrupted(_signal, _frame):
        raise HarnessError('harness: terminated')

    # A normal CI/host termination must reach the same owned-child cleanup as
    # a test failure. SIGKILL still requires the persisted ownership manifests.
    signal.signal(signal.SIGTERM, interrupted)
    report = {'schema': 1, 'ok': False, 'startedAt': datetime.now(timezone.utc).isoformat(), 'seed': args.seed}
    root = None
    server = None
    device = None
    gateway = None
    clean = True
    try:
        # Import only after argument parsing so --help works before this
        # independently tested module has been integrated with both drivers.
        import cross_device_state as state
        if args.sdk is None or args.java is None:
            raise HarnessError('config: ANDROID_HOME and JAVA_HOME or explicit runtimes are required')
        checkout = args.checkout.resolve()
        gradle = args.gradle.resolve() if args.gradle else checkout / 'gradlew'
        if checked(['git', 'status', '--porcelain'], stage='source', cwd=checkout).strip():
            raise HarnessError('source: commit the intended changes before collecting runtime proof')
        report['commit'] = checked(['git', 'rev-parse', 'HEAD'], stage='source', cwd=checkout).decode().strip()
        run_id = 'e12-' + uuid.uuid4().hex
        root = new_workspace(args.workspace_parent, run_id)
        report['runId'] = run_id
        print('stage: build real app, drivers and server', flush=True)
        build(checkout, gradle, root)
        app_apk = select_app_apk(checkout / 'app/build/outputs/apk/debug',
                                 args.system_image.rsplit(';', 1)[1])
        test_apk = checkout / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        if not test_apk.is_file():
            raise HarnessError('build: Android test APK is missing')
        # Read the producer's executable JAR, never a thin server.jar. Refuse
        # ambiguous stale versions rather than selecting a runtime by accident.
        candidates = list((checkout / 'sync/server/build/libs').glob('neutrodyne-server-*.jar'))
        if len(candidates) != 1:
            raise HarnessError('build: expected one executable server JAR')
        jar = candidates[0]
        gateway = FixtureGateway(run_id).start()
        server = OwnedSyncServer(root, run_id, jar, args.java).start(gateway)
        config = {'runId': run_id, 'serverUrl': gateway.url, 'feedBaseUrl': gateway.url,
                  'username': 'fixtureadmin', 'password': secrets.token_urlsafe(24)}
        server.create_account(config['username'], config['password'])
        print('stage: boot new owned headless API 36 emulator', flush=True)
        device = OwnedAndroidEmulator(root, run_id, args.sdk, args.system_image).start()
        device.command(['reverse', f'tcp:{gateway.port}', f'tcp:{gateway.port}'])
        android = AndroidDriver(device, run_id, app_apk, test_apk)
        report['artifacts'] = {}
        for name, path in (('android', app_apk), ('server', jar),
                           ('androidTest', checkout / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')):
            with path.open('rb') as artifact:
                report['artifacts'][name] = hashlib.file_digest(artifact, 'sha256').hexdigest()
        desktop = DesktopDriver(root, run_id, checkout, gradle)
        report['proof'] = run_journey(android, desktop, gateway, config, args.seed, state)
        report['gateway'] = gateway.snapshot()
        if (checked(['git', 'rev-parse', 'HEAD'], stage='source', cwd=checkout).decode().strip() != report['commit'] or
                checked(['git', 'status', '--porcelain'], stage='source', cwd=checkout).strip()):
            raise HarnessError('source: checkout changed during runtime proof')
        report['ok'] = True
    except (HarnessError, ValueError) as error:
        # Harness errors are fixed strings; do not copy arbitrary exception
        # messages from subprocesses or transport payloads into artifacts.
        report['error'] = str(error) if isinstance(error, HarnessError) else 'state: verification failed'
    except BaseException:
        report['error'] = 'harness: interrupted or unexpected failure'
    finally:
        for child, method in ((device, 'stop'), (server, 'stop'), (gateway, 'close')):
            if child is not None:
                try:
                    getattr(child, method)()
                except BaseException:
                    clean = False
        report['cleanup'] = clean
        if not clean:
            report['ok'] = False
            report['error'] = 'cleanup: owned runtime did not stop cleanly'
        report['completedAt'] = datetime.now(timezone.utc).isoformat()
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        if root is not None and clean and report['ok']:
            # Only a new, identity-verified, fully stopped workspace is removed.
            require_workspace(root, report['runId'])
            shutil.rmtree(root)
        elif root is not None:
            # The path is safe metadata, not a credential. Kept private for
            # diagnosis/restart; never upload the workspace or its private logs.
            print('private failure workspace: ' + str(root), flush=True)
    print('cross-device journey: ' + ('PASS' if report['ok'] else 'FAIL'), flush=True)
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
