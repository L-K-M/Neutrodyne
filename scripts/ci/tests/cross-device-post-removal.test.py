#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""A dump retains fifty descriptors after eleven real subscriptions disappear."""
import copy
from pathlib import Path
import sys
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cross_device_state as state


def removed_dump():
    ids = [str(uuid.UUID(int=index + 1)) for index in range(50)]
    podcasts = [{'index': index, 'syncId': ids[index], 'title': f'Fixture {index}',
                 'subscribed': index >= 11} for index in range(50)]
    episodes = [{'podcastIndex': index, 'identityKey': f'fixture-episode-{index}',
                 'title': f'Episode {index}', 'present': index >= 11, 'playedAt': None,
                 'positionMs': 0, 'favorite': False, 'inUpNext': False} for index in range(50)]
    groups = [{'uuid': str(uuid.UUID(int=100 + index)), 'name': f'Group {index}',
               'orderKey': f'key-{index}', 'members': list(members)}
              for index, members in enumerate((range(11, 25), range(25, 50)))]
    clock = f'{1_730_000_000_000 << 16:016x}-0000000000000001'
    wire = {f'podcast/{ids[index]}': {'coll': 'podcast', 'fields': {
        'subscribed': {'v': index >= 11, 'c': clock}}} for index in range(50)}
    wire['redirects'] = {}
    return {'runId': 'post-removal', 'phase': 'dump', 'ok': True, 'error': None,
            'wire': wire, 'library': {'podcasts': podcasts, 'episodes': episodes,
                                     'groups': groups, 'upNext': []},
            'held': [], 'skippedClockRows': 0,
            'counts': {'podcasts': 39, 'episodes': 39, 'groups': 2, 'members': 39,
                       'upNext': 0, 'outbox': 0, 'clock': 50, 'held': 0, 'parked': 0}}


class PostRemovalTests(unittest.TestCase):
    def test_thirty_nine_remaining_rows_with_fifty_descriptors_converge(self):
        dump = removed_dump()
        state.require_dump(dump, 'post-removal')
        result = state.require_converged(dump, copy.deepcopy(dump), 'post-removal')
        self.assertEqual(39, result['podcasts'])
        self.assertEqual(39, result['episodes'])

    def test_fifty_reported_rows_with_eleven_absent_descriptors_are_inconsistent(self):
        dump = removed_dump()
        dump['counts'].update(podcasts=50, episodes=50)
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'post-removal')

    def test_a_fabricated_extra_present_episode_cannot_pass(self):
        dump = removed_dump()
        dump['library']['episodes'][0]['present'] = True
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'post-removal')


if __name__ == '__main__':
    unittest.main()
