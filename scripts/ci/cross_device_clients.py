#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Owned, headless Android runtime and real phase-driver transports for E12."""
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import time

from cross_device_fixtures import HarnessError, MAX_RESPONSE_BYTES, private_json, require_workspace


APP_ID = 'ch.lkmc.neutrodyne.debug'
ANDROID_CLASS = 'ch.lkmc.neutrodyne.CrossDeviceSyncAndroidTest'
DESKTOP_CLASS = 'ch.lkmc.neutrodyne.desktop.sync.CrossDeviceSyncDesktopTest'
PHASES = {'seed', 'link', 'edit', 'sync', 'dump', 'apply-held'}


def checked(command, *, stage, cwd=None, environment=None, data=None, timeout=120):
    """Never copy a child error, output or command into a public failure message."""
    try:
        result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                cwd=cwd, env=environment, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise HarnessError(f'{stage}: command unavailable or timed out') from None
    if result.returncode != 0:
        raise HarnessError(f'{stage}: command failed')
    if len(result.stdout) > MAX_RESPONSE_BYTES or len(result.stderr) > MAX_RESPONSE_BYTES:
        raise HarnessError(f'{stage}: output exceeds bound')
    return result.stdout


def read_result(data, run_id, phase):
    try:
        value = json.loads(data)
        if (not isinstance(value, dict) or value.get('runId') != run_id or
                value.get('phase') != phase or value.get('ok') is not True or value.get('error') is not None):
            raise ValueError()
        return value
    except (ValueError, TypeError):
        raise HarnessError(f'{phase}: missing, stale or failed driver result') from None


def logged_command(command, *, cwd, log, stage, timeout):
    """Drain trusted build output to a private file; reap this invocation on failure."""
    with Path(log).open('xb') as output:
        os.chmod(log, 0o600)
        try:
            process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
                                       stdout=output, stderr=subprocess.STDOUT,
                                       start_new_session=(os.name == 'posix'))
        except OSError:
            raise HarnessError(f'{stage}: command unavailable') from None
        try:
            status = process.wait(timeout=timeout)
        except BaseException:
            # The new session contains only our invocation and its children.
            # The shared local wrapper still serializes all local Gradle work.
            if os.name == 'posix':
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                if os.name == 'posix':
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                process.wait(timeout=10)
            raise HarnessError(f'{stage}: command interrupted or timed out') from None
        if status != 0:
            raise HarnessError(f'{stage}: command failed')


class OwnedAndroidEmulator:
    """A new AVD, user data and even console port, all belonging to this run.

    No existing AVD is reused and no adb-server command is issued. An adb serial
    is admitted only after its emulator-reported AVD name matches our child.
    """

    def __init__(self, root, run_id, sdk, system_image):
        self.root = require_workspace(root, run_id)
        self.run_id = run_id
        self.sdk = Path(sdk).resolve()
        if not re.fullmatch(r'system-images;android-36;google_apis;(arm64-v8a|x86_64)', system_image):
            raise HarnessError('emulator: expected an API 36 Google APIs system image')
        self.system_image = system_image
        self.name = 'nd_e12_' + run_id.replace('.', '_').replace('-', '_')
        self.adb = str(self.sdk / 'platform-tools/adb')
        self._process = None
        self._log = None
        self.serial = None
        self.environment = os.environ.copy()
        for variable, directory in (('ANDROID_AVD_HOME', 'avd-home'),
                                    ('ANDROID_USER_HOME', 'android-home'),
                                    ('ANDROID_EMULATOR_HOME', 'emulator-home')):
            path = self.root / directory
            path.mkdir(mode=0o700)
            self.environment[variable] = str(path)
        self.environment['ANDROID_HOME'] = str(self.sdk)
        self.environment['ANDROID_SDK_ROOT'] = str(self.sdk)

    @staticmethod
    def available_port():
        # Never use the serial/ports of another emulator, including the shared
        # Mac's pre-existing emulator-5570. Reserve both emulator ports together.
        for port in range(5580, 5680, 2):
            with socket.socket() as first, socket.socket() as second:
                try:
                    first.bind(('127.0.0.1', port))
                    second.bind(('127.0.0.1', port + 1))
                    return port
                except OSError:
                    pass
        raise HarnessError('emulator: no unused console port')

    def start(self, timeout=240):
        manager = self.sdk / 'cmdline-tools/latest/bin/avdmanager'
        path = self.root / 'owned.avd'
        checked([str(manager), 'create', 'avd', '--name', self.name, '--path', str(path),
                 '--package', self.system_image, '--device', 'pixel_2'],
                stage='avd-create', environment=self.environment, data=b'no\n')
        # Fixed, bounded data partition; no snapshots, host windows or audio.
        with (path / 'config.ini').open('a') as output:
            output.write('\ndisk.dataPartition.size=3G\nhw.keyboard=yes\n')
        port = self.available_port()
        self.serial = f'emulator-{port}'
        self._log = (self.root / 'emulator-private.log').open('xb')
        os.chmod(self.root / 'emulator-private.log', 0o600)
        try:
            self._process = subprocess.Popen(
                [str(self.sdk / 'emulator/emulator'), '-avd', self.name, '-port', str(port),
                 '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-no-metrics',
                 '-gpu', 'swiftshader', '-accel', 'on', '-cores', '2', '-memory', '2048'],
                stdin=subprocess.DEVNULL, stdout=self._log, stderr=subprocess.STDOUT,
                env=self.environment, start_new_session=(os.name == 'posix'))
            private_json(self.root / 'emulator-child.json',
                         {'pid': self._process.pid, 'runId': self.run_id,
                          'avd': self.name, 'serial': self.serial})
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if self._process.poll() is not None:
                    raise HarnessError('emulator: owned child exited before boot')
                try:
                    self.require_owned()
                    booted = self.command(['shell', 'getprop', 'sys.boot_completed'], timeout=5)
                    if booted.strip() == b'1':
                        self.command(['shell', 'settings', 'put', 'global', 'window_animation_scale', '0'])
                        self.command(['shell', 'settings', 'put', 'global', 'transition_animation_scale', '0'])
                        self.command(['shell', 'settings', 'put', 'global', 'animator_duration_scale', '0'])
                        return self
                except HarnessError:
                    pass
                time.sleep(1)
            raise HarnessError('emulator: boot timed out')
        except BaseException:
            self.stop()
            raise

    def require_owned(self):
        if self._process is None or self._process.poll() is not None:
            raise HarnessError('emulator: owned child is not running')
        data = checked([self.adb, '-s', self.serial, 'emu', 'avd', 'name'],
                       stage='emulator-identity', environment=self.environment, timeout=5)
        if data.decode('utf-8', 'replace').splitlines() != [self.name, 'OK']:
            raise HarnessError('emulator: serial belongs to another AVD')

    def command(self, arguments, data=None, timeout=120):
        self.require_owned()
        return checked([self.adb, '-s', self.serial, *arguments], stage='adb',
                       environment=self.environment, data=data, timeout=timeout)

    def stop(self):
        # Terminate only the Popen object we spawned; never kill by serial or
        # process-name discovery. A startup failure need not have an adb serial.
        if self._process is not None and self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=10)
        if self._log is not None:
            self._log.close()
        marker = self.root / 'emulator-child.json'
        if self._process is not None and marker.is_file() and not marker.is_symlink():
            saved = json.loads(marker.read_text())
            if saved.get('pid') == self._process.pid and saved.get('runId') == self.run_id:
                marker.unlink()


class AndroidDriver:
    def __init__(self, device, run_id, app_apk, test_apk):
        self.device = device
        self.run_id = run_id
        self.root = require_workspace(device.root, run_id)
        for path in (app_apk, test_apk):
            if not Path(path).is_file():
                raise HarnessError('android-install: built APK is missing')
        device.command(['install', '-r', '-g', str(Path(app_apk).resolve())])
        device.command(['install', '-r', '-g', str(Path(test_apk).resolve())])
        components = []
        for line in device.command(['shell', 'pm', 'list', 'instrumentation']).decode().splitlines():
            match = re.fullmatch(r'instrumentation:([^ ]+) \(target=([^ )]+)\)', line.strip())
            if match and match[2] == APP_ID and match[1].endswith('/ch.lkmc.neutrodyne.NeutrodyneTestRunner'):
                components.append(match[1])
        if len(components) != 1:
            raise HarnessError('android-install: expected one application test runner')
        self.component = components[0]

    def phase(self, phase, config):
        if phase not in PHASES - {'apply-held'}:
            raise HarnessError('android: unsupported phase')
        if config.get('runId') != self.run_id:
            raise HarnessError('android: config identity does not match')
        self.device.command(['shell', 'am', 'force-stop', APP_ID])
        self.device.command(['shell', 'run-as', APP_ID, 'mkdir', '-p', 'files/cross-device'])
        self.device.command(['shell', f"run-as {APP_ID} sh -c 'umask 077; cat > files/cross-device/config.json'"],
                            data=json.dumps(config, separators=(',', ':')).encode())
        self.device.command(['shell', 'run-as', APP_ID, 'rm', '-f', 'files/cross-device/result.json'])
        try:
            output = self.device.command(
                ['shell', 'am', 'instrument', '-w', '-r', '-e', 'crossDevice', 'true',
                 '-e', 'crossDevicePhase', phase, '-e', 'class', ANDROID_CLASS, self.component], timeout=240)
            log = self.root / f'android-{phase}-{time.monotonic_ns()}.log'
            with os.fdopen(os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as private_log:
                private_log.write(output)
            # am instrument can exit zero for a failed or empty selection.
            if not re.search(rb'(?m)^OK \(1 test\)\s*$', output):
                raise HarnessError(f'{phase}: Android did not execute one successful test')
            value = self.device.command(['exec-out', 'run-as', APP_ID, 'cat', 'files/cross-device/result.json'])
            return read_result(value, self.run_id, phase)
        finally:
            self.device.command(['shell', 'am', 'force-stop', APP_ID])


class DesktopDriver:
    def __init__(self, workspace, run_id, checkout, gradle):
        self.workspace = require_workspace(workspace, run_id)
        self.run_id = run_id
        self.checkout = Path(checkout).resolve()
        self.gradle = str(Path(gradle).resolve())
        self.root = self.workspace / 'desktop'
        (self.root / 'cross-device').mkdir(parents=True, mode=0o700)

    def phase(self, phase, config):
        if phase not in PHASES or config.get('runId') != self.run_id:
            raise HarnessError('desktop: invalid phase or config identity')
        path = self.root / 'cross-device/config.json'
        if path.is_symlink():
            raise HarnessError('desktop: config cannot be a link')
        if path.exists():
            path.unlink()
        private_json(path, config)
        result = self.root / 'cross-device/result.json'
        if result.is_symlink():
            raise HarnessError('desktop: result cannot be a link')
        result.unlink(missing_ok=True)
        log = self.workspace / f'desktop-{phase}-{time.monotonic_ns()}.log'
        # All driver config/credentials are in the private file, never Gradle
        # properties, process arguments or stdout. Do not publish this log.
        logged_command(
            [self.gradle, '--max-workers=2', ':desktopApp:cleanTest', ':desktopApp:test',
             '--tests', DESKTOP_CLASS, '-PcrossDevice', f'-PcrossDeviceRoot={self.root}',
             f'-PcrossDevicePhase={phase}'], cwd=self.checkout, log=log,
            stage=f'{phase}: desktop test', timeout=1800)
        try:
            return read_result(result.read_bytes(), self.run_id, phase)
        except OSError:
            raise HarnessError(f'{phase}: desktop result is missing') from None
