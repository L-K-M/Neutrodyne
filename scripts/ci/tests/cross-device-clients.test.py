#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Transport admission, stale-result and sanitization controls for E12."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cross_device_clients as clients
from cross_device_fixtures import HarnessError, new_workspace


class ResultTests(unittest.TestCase):
    def test_timed_out_logged_child_is_reaped_and_log_is_private(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'private.log'
            with mock.patch.object(clients.subprocess, 'Popen') as spawn, \
                    mock.patch.object(clients.os, 'killpg') as kill:
                child = spawn.return_value
                child.pid = 1234567
                child.wait.side_effect = [subprocess.TimeoutExpired('synthetic', 1), 0]
                with self.assertRaises(HarnessError):
                    clients.logged_command(['synthetic'], cwd=directory, log=log,
                                           stage='probe', timeout=1)
                kill.assert_called_once_with(child.pid, clients.signal.SIGTERM)
                self.assertEqual(2, child.wait.call_count)
            self.assertEqual(0o600, log.stat().st_mode & 0o777)

    def test_stale_failed_missing_and_malformed_results_cannot_pass(self):
        good = {'runId': 'own-run', 'phase': 'sync', 'ok': True, 'error': None}
        self.assertEqual(good, clients.read_result(json.dumps(good), 'own-run', 'sync'))
        for value in ({}, [], {**good, 'runId': 'other'}, {**good, 'phase': 'dump'},
                      {**good, 'ok': 1}, {**good, 'error': 'private-sentinel'},
                      {**good, 'ok': False}):
            with self.subTest(value=value), self.assertRaises(HarnessError) as caught:
                clients.read_result(json.dumps(value), 'own-run', 'sync')
            self.assertNotIn('private-sentinel', str(caught.exception))
        with self.assertRaises(HarnessError):
            clients.read_result(b'not JSON', 'own-run', 'sync')

    def test_command_errors_and_timeout_do_not_publish_child_detail(self):
        with self.assertRaises(HarnessError) as caught:
            clients.checked([sys.executable, '-c',
                             'import sys; print("secret-output"); sys.exit("secret-error")'], stage='probe')
        self.assertEqual('probe: command failed', str(caught.exception))
        with mock.patch.object(clients.subprocess, 'run',
                               side_effect=subprocess.TimeoutExpired(['private-command'], 1)):
            with self.assertRaises(HarnessError) as caught:
                clients.checked(['private-command'], stage='probe')
        self.assertEqual('probe: command unavailable or timed out', str(caught.exception))


class OwnershipTests(unittest.TestCase):
    def test_wrong_avd_name_cannot_admit_a_serial_or_issue_an_app_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = new_workspace(directory, 'own-run')
            device = clients.OwnedAndroidEmulator(root, 'own-run', directory,
                                                 'system-images;android-36;google_apis;arm64-v8a')
            device._process = mock.Mock()
            device._process.poll.return_value = None
            device.serial = 'emulator-5580'
            with mock.patch.object(clients, 'checked', return_value=b'someone_else\nOK\n') as command:
                with self.assertRaises(HarnessError):
                    device.command(['shell', 'am', 'force-stop', clients.APP_ID])
                self.assertEqual(1, command.call_count)
                self.assertEqual(['emu', 'avd', 'name'], command.call_args.args[0][-3:])

    def test_only_the_expected_live_child_admits_a_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = new_workspace(directory, 'own-run')
            device = clients.OwnedAndroidEmulator(root, 'own-run', directory,
                                                 'system-images;android-36;google_apis;x86_64')
            device._process = mock.Mock()
            device._process.poll.return_value = None
            device.serial = 'emulator-5580'
            with mock.patch.object(clients, 'checked', side_effect=[
                    (device.name + '\nOK\n').encode(), b'1\n']) as command:
                self.assertEqual(b'1\n', device.command(['shell', 'getprop', 'sys.boot_completed']))
                self.assertEqual(2, command.call_count)
                self.assertEqual(['-s', 'emulator-5580'], command.call_args.args[0][1:3])
            device._process.poll.return_value = 1
            with mock.patch.object(clients, 'checked') as command:
                with self.assertRaises(HarnessError):
                    device.command(['shell', 'id'])
                command.assert_not_called()

    def test_non_api36_or_arbitrary_image_is_refused_before_creating_avd_homes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = new_workspace(directory, 'own-run')
            with self.assertRaises(HarnessError):
                clients.OwnedAndroidEmulator(root, 'own-run', directory, 'anything')
            self.assertFalse((root / 'avd-home').exists())


class AndroidPhaseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = new_workspace(temporary.name, 'own-run')

    def test_zero_exit_instrumentation_without_one_case_is_failure_and_stops_own_app(self):
        driver = clients.AndroidDriver.__new__(clients.AndroidDriver)
        driver.run_id = 'own-run'
        driver.root = self.root
        driver.component = 'fixture/runner'
        driver.device = mock.Mock()
        driver.device.command.return_value = b'OK (0 tests)\n'
        with self.assertRaises(HarnessError):
            driver.phase('sync', {'runId': 'own-run', 'password': 'test-only-password'})
        calls = driver.device.command.call_args_list
        self.assertEqual(['shell', 'am', 'force-stop', clients.APP_ID], calls[-1].args[0])
        all_arguments = repr([call.args for call in calls])
        self.assertNotIn('test-only-password', all_arguments)
        writes = [call for call in calls if 'data' in call.kwargs]
        self.assertEqual(1, len(writes))
        self.assertEqual('test-only-password', json.loads(writes[0].kwargs['data'])['password'])
        logs = list(self.root.glob('android-sync-*.log'))
        self.assertEqual(1, len(logs))
        self.assertEqual(0o600, logs[0].stat().st_mode & 0o777)

    def test_stale_success_file_is_rejected_even_after_a_successful_case(self):
        driver = clients.AndroidDriver.__new__(clients.AndroidDriver)
        driver.run_id = 'own-run'
        driver.root = self.root
        driver.component = 'fixture/runner'
        driver.device = mock.Mock()

        def command(arguments, **_):
            if 'instrument' in arguments:
                return b'\nOK (1 test)\n'
            return json.dumps({'runId': 'other', 'phase': 'sync', 'ok': True, 'error': None}).encode()

        driver.device.command.side_effect = command
        with self.assertRaises(HarnessError):
            driver.phase('sync', {'runId': 'own-run'})
        self.assertEqual(['shell', 'am', 'force-stop', clients.APP_ID],
                         driver.device.command.call_args.args[0])


class DesktopPhaseTests(unittest.TestCase):
    def test_zero_exit_cannot_reuse_a_previous_success_result_or_expose_password_in_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = new_workspace(directory, 'own-run')
            driver = clients.DesktopDriver(root, 'own-run', directory, sys.executable)
            result = driver.root / 'cross-device/result.json'
            result.write_text(json.dumps({'runId': 'own-run', 'phase': 'sync', 'ok': True, 'error': None}))
            with mock.patch.object(clients, 'logged_command') as command:
                with self.assertRaises(HarnessError):
                    driver.phase('sync', {'runId': 'own-run', 'password': 'test-only-password'})
                self.assertNotIn('test-only-password', repr(command.call_args))
            config = driver.root / 'cross-device/config.json'
            self.assertEqual(0o600, config.stat().st_mode & 0o777)
            self.assertEqual('test-only-password', json.loads(config.read_text())['password'])
            self.assertFalse(result.exists())


if __name__ == '__main__':
    unittest.main()
