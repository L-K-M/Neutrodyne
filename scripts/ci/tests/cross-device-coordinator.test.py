#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Safety gates around the multi-client journey and its public evidence."""
import copy
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cross_device_fixtures import HarnessError


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


coordinator = module('e12_coordinator', 'cross-device-sync.py')
assertion = module('e12_report_assertion', 'assert-cross-device-report.py')


def counts(podcasts=50):
    return {'podcasts': podcasts, 'episodes': 50, 'groups': 2, 'members': 50,
            'upNext': 8, 'outbox': 0, 'clock': 100, 'held': 0, 'parked': 0}


class HarnessStateError(ValueError):
    pass


class JourneyTests(unittest.TestCase):
    def make(self):
        gateway = SimpleNamespace(online=True)
        gateway.set_online = lambda online: setattr(gateway, 'online', online)
        seen = []

        class Client:
            def __init__(self, name):
                self.name = name
                self.podcasts = 50

            def phase(self, name, config):
                seen.append((self.name, name, gateway.online, config.get('edits')))
                if name == 'edit' and config['edits'][0]['op'] == 'unsubscribe':
                    self.podcasts = 39
                if name == 'apply-held':
                    self.podcasts = 39
                if name == 'dump':
                    return {'counts': counts(self.podcasts), 'held': [{}],
                            'library': {'podcasts': [{'index': i, 'syncId': f'podcast-{i}'} for i in range(50)]}}
                return {}

        android, desktop = Client('android'), Client('desktop')
        state = SimpleNamespace(
            HarnessStateError=HarnessStateError,
            require_converged=lambda left, right, run: counts(min(left['counts']['podcasts'],
                                                                   right['counts']['podcasts'])),
            require_resume=lambda *args: None,
            require_dump=lambda dump, run: dump,
            require_held=lambda *args: None,
            random_edits=lambda seed: {name: [{'op': 'fav'}] * 40 for name in ('android', 'desktop')},
        )
        return android, desktop, gateway, state, seen

    def run_case(self, objects):
        android, desktop, gateway, state, _ = objects
        return coordinator.run_journey(android, desktop, gateway, {'runId': 'own-run'}, 1,
                                       state, progress=lambda *args, **kwargs: None)

    def test_failed_exact_held_validation_never_approves(self):
        objects = self.make()

        def refuse(*_):
            raise HarnessStateError('held identities differ')

        objects[3].require_held = refuse
        with self.assertRaises(HarnessStateError):
            self.run_case(objects)
        self.assertTrue(any(name == 'dump' for _, name, _, _ in objects[4]))
        self.assertFalse(any(name == 'apply-held' for _, name, _, _ in objects[4]))
        self.assertTrue(objects[2].online)

    def test_edit_failure_restores_gateway_and_never_approves(self):
        objects = self.make()
        android = objects[0]
        original = android.phase

        def failing(name, config):
            if name == 'edit':
                self.assertFalse(objects[2].online)
                raise HarnessError('edit: synthetic refusal')
            return original(name, config)

        android.phase = failing
        with self.assertRaises(HarnessError):
            self.run_case(objects)
        self.assertTrue(objects[2].online)
        self.assertFalse(any(name == 'apply-held' for _, name, _, _ in objects[4]))

    def test_persistent_mismatch_stops_before_random_edits_or_approval(self):
        objects = self.make()

        def mismatch(*_):
            raise HarnessStateError('wire differs')

        objects[3].require_converged = mismatch
        with self.assertRaises(HarnessError):
            self.run_case(objects)
        self.assertEqual(5, sum(client == 'desktop' and name == 'dump'
                                for client, name, _, _ in objects[4]))
        self.assertFalse(any(name in ('edit', 'apply-held') for _, name, _, _ in objects[4]))


class ReportTests(unittest.TestCase):
    def good(self):
        return {'schema': 1, 'ok': True, 'cleanup': True, 'runId': 'e12-' + 'a' * 32,
                'commit': 'b' * 40, 'seed': 1,
                'startedAt': '2026-11-10T19:00:00+00:00', 'completedAt': '2026-11-10T19:05:00+00:00',
                'artifacts': {name: 'c' * 64 for name in ('android', 'androidTest', 'server')},
                'proof': {'baseline': counts(), 'afterOfflineEdits': counts(), 'afterApproval': counts(39),
                          'heldBeforeApproval': {'podcasts': 50, 'held': 1, 'removals': 11},
                          'randomEdits': {'android': 40, 'desktop': 40},
                          'resumePositionMs': 123_456, 'positionToleranceMs': 1000,
                          'outage': 'sync-server gateway returned 503; radios were not disabled',
                          'playback': 'persistent position projection; foreground playback timing remains a device check'},
                'gateway': {'requests': 100, 'refused': 0, 'errors': 0}}

    def test_complete_aggregate_is_accepted(self):
        assertion.require_report(self.good())

    def test_missing_phase_pending_state_and_false_success_are_refused(self):
        mutations = [lambda x: x.update(ok=False), lambda x: x.update(cleanup=False),
                     lambda x: x.update(schema=True), lambda x: x.update(ok=1),
                     lambda x: x.update(seed=True), lambda x: x.update(cleanup=1),
                     lambda x: x.update(error='synthetic'), lambda x: x.update(commit=''),
                     lambda x: x.update(artifacts={}), lambda x: x.update(proof={}),
                     lambda x: x['proof']['afterApproval'].update(podcasts=50),
                     lambda x: x['proof']['baseline'].update(outbox=1),
                     lambda x: x['proof']['afterOfflineEdits'].update(parked=1),
                     lambda x: x['proof']['heldBeforeApproval'].update(removals=10),
                     lambda x: x['proof']['heldBeforeApproval'].update(held=True),
                     lambda x: x['proof'].update(resumePositionMs=0),
                     lambda x: x['proof'].update(positionToleranceMs=1001),
                     lambda x: x['proof']['randomEdits'].update(desktop=0),
                     lambda x: x.update(completedAt='2026-11-10T18:00:00+00:00'),
                     lambda x: x.update(completedAt='2026-11-10T20:00:00'),
                     lambda x: x['gateway'].update(requests=0)]
        for mutate in mutations:
            value = copy.deepcopy(self.good())
            mutate(value)
            with self.subTest(value=value), self.assertRaises((ValueError, KeyError, TypeError)):
                assertion.require_report(value)


if __name__ == '__main__':
    unittest.main()
