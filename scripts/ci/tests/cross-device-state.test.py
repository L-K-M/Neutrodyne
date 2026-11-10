#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Bounded pure tests for the E12 cross-device state comparison module.

These tests exercise only the pure validation/random-edit boundary.  They do
not start a server, emulator, desktop runtime or Gradle, and they do not touch
the network or the filesystem outside the module import.
"""
import copy
import importlib.util
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    'cross_device_state', Path(__file__).parents[1] / 'cross_device_state.py')
state = importlib.util.module_from_spec(spec)
spec.loader.exec_module(state)


def base_dump(run_id='run-1'):
    podcasts = [
        {'index': index, 'syncId': f'podcast-{index}', 'title': f'Feed {index}', 'subscribed': True}
        for index in range(50)
    ]
    episodes = [
        {
            'podcastIndex': index,
            'identityKey': f'episode-{index}',
            'title': f'Episode {index}',
            'present': True,
            'playedAt': None,
            'positionMs': 0,
            'favorite': False,
            'inUpNext': False,
        }
        for index in range(50)
    ]
    groups = [
        {'uuid': 'group-0', 'name': 'Group A', 'orderKey': 'a', 'members': list(range(25))},
        {'uuid': 'group-1', 'name': 'Group B', 'orderKey': 'b', 'members': list(range(25, 50))},
    ]
    return {
        'runId': run_id,
        'phase': 'dump',
        'ok': True,
        'error': None,
        'wire': {
            'redirects': {},
            'podcast/podcast-0': {'coll': 'PODCAST', 'fields': {}},
            'episode/episode-0': {'coll': 'EPISODE', 'fields': {}},
        },
        'library': {'podcasts': podcasts, 'episodes': episodes, 'groups': groups, 'upNext': []},
        'held': [],
        'skippedClockRows': 0,
        'counts': {
            'podcasts': 50,
            'episodes': 50,
            'groups': 2,
            'members': 50,
            'upNext': 0,
            'outbox': 0,
            'clock': 0,
            'held': 0,
            'parked': 0,
        },
    }


def add_queue(dump, index, order_key='q'):
    dump['library']['upNext'].append({
        'podcastIndex': index,
        'identityKey': f'episode-{index}',
        'title': f'Episode {index}',
        'orderKey': order_key,
    })
    dump['library']['episodes'][index]['inUpNext'] = True
    dump['counts']['upNext'] = len(dump['library']['upNext'])


def set_position(dump, index, value):
    dump['library']['episodes'][index]['positionMs'] = value


def make_held(dump, removal_ids, group_removals=(), held_id=1, held_at=123):
    dump['held'] = [{
        'id': held_id,
        'heldAt': held_at,
        'state': 'HELD',
        'podcasts': [{'id': removal_id, 'clock': 'clock'} for removal_id in removal_ids],
        'groups': [{'id': removal_id, 'clock': 'clock'} for removal_id in group_removals],
    }]
    dump['counts']['held'] = 1


# The representative played indexes of the seeded baseline: the union of the
# current driver's index%6==0 set and the corrected contract's index%5==0 set.
PLAYED_POOL = {0, 5, 6, 10, 12, 15, 18, 20, 24, 25, 30, 35, 36, 40, 42, 45, 48}
SEEDED_QUEUE = {3, 9, 15, 21, 27, 33, 39, 45} | set(range(8))


def replay(edits, initial_queue, initial_played):
    """Independent driver-semantics model of the bounded edit sequence."""
    membership = [set(range(25)), set(range(25, 50))]
    queue = set(initial_queue)
    played = set(initial_played)
    favorite = set()
    group_order = [0, 1]
    seen = set()
    for edit in edits:
        if type(edit) is not dict:
            raise AssertionError('edit is not an object')
        op = edit['op']
        seen.add(op)
        if op == 'played':
            index = edit['episode']
            if edit['podcast'] != index:
                raise AssertionError('played podcast mismatch')
            if type(edit['value']) is not int or edit['value'] <= 0:
                raise AssertionError('played value is not a positive epoch')
            if index in PLAYED_POOL:
                raise AssertionError('played edit targets a representative played index')
            played.add(index)
        elif op == 'unplayed':
            index = edit['episode']
            if edit['podcast'] != index:
                raise AssertionError('unplayed podcast mismatch')
            if index not in PLAYED_POOL:
                raise AssertionError('unplayed edit targets a never played index')
            played.discard(index)
        elif op == 'position':
            index = edit['episode']
            if edit['podcast'] != index:
                raise AssertionError('position podcast mismatch')
            if type(edit['value']) is not int or not 1000 <= edit['value'] <= 2_000_000:
                raise AssertionError('position value is out of bounds')
        elif op == 'fav':
            index = edit['episode']
            if edit['podcast'] != index:
                raise AssertionError('fav podcast mismatch')
            if type(edit['fav']) is not bool:
                raise AssertionError('fav is not a boolean')
            if edit['fav']:
                favorite.add(index)
            else:
                favorite.discard(index)
        elif op == 'membership_add':
            group = edit['group']
            index = edit['podcast']
            if group not in (0, 1) or not 0 <= index < 50:
                raise AssertionError('membership_add is out of range')
            if index in membership[group]:
                raise AssertionError('duplicate membership add')
            membership[group].add(index)
        elif op == 'membership_remove':
            group = edit['group']
            index = edit['podcast']
            if group not in (0, 1) or index not in membership[group]:
                raise AssertionError('membership_remove targets a missing entry')
            membership[group].remove(index)
        elif op == 'group_order':
            group = edit['group']
            before = edit['before']
            if group not in (0, 1) or not 0 <= before <= 1:
                raise AssertionError('group_order is out of range')
            order = group_order[:]
            order.remove(group)
            order.insert(before, group)
            group_order = order
        elif op == 'queue_add':
            index = edit['episode']
            if edit['podcast'] != index:
                raise AssertionError('queue_add podcast mismatch')
            if index in queue:
                raise AssertionError('duplicate queue add')
            if 'before' in edit:
                if type(edit['before']) is not int or not 0 <= edit['before'] < len(queue):
                    raise AssertionError('queue_add before index is out of range')
            queue.add(index)
        elif op == 'queue_remove':
            index = edit['episode']
            if edit['podcast'] != index:
                raise AssertionError('queue_remove podcast mismatch')
            if index not in queue:
                raise AssertionError('queue_remove targets a missing entry')
            queue.remove(index)
        else:
            raise AssertionError('unknown operation')
    return membership, queue, played, favorite, group_order, seen


class RequireDumpTests(unittest.TestCase):
    def test_valid_dump_passes(self):
        dump = base_dump()
        self.assertEqual(dump, state.require_dump(dump, 'run-1'))

    def test_rejects_non_object_and_empty(self):
        for value in (None, [], 'x', 1, {}):
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(value, 'run-1')

    def test_rejects_stale_run(self):
        dump = base_dump()
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-2')

    def test_rejects_wrong_phase(self):
        dump = base_dump()
        dump['phase'] = 'sync'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_not_ok(self):
        for ok in (False, 1, 'true', None):
            dump = base_dump()
            dump['ok'] = ok
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')

    def test_rejects_error(self):
        for error in ('failure', '', 0, False):
            dump = base_dump()
            dump['error'] = error
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')

    def test_rejects_skipped_rows(self):
        for skipped in (1, True, -1, '0'):
            dump = base_dump()
            dump['skippedClockRows'] = skipped
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')

    def test_rejects_bool_and_negative_counts(self):
        for key in state.COUNT_KEYS:
            for value in (True, False, -1, '1', 1.0):
                dump = base_dump()
                dump['counts'][key] = value
                with self.assertRaises(state.HarnessStateError):
                    state.require_dump(dump, 'run-1')

    def test_rejects_duplicate_and_missing_podcast_identities(self):
        dump = base_dump()
        dump['library']['podcasts'][1]['index'] = 0
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['library']['podcasts'][1]['syncId'] = 'podcast-0'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['library']['podcasts'] = dump['library']['podcasts'][:49]
        dump['counts']['podcasts'] = 49
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_duplicate_episode_identities(self):
        dump = base_dump()
        dump['library']['episodes'][1]['podcastIndex'] = 0
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['library']['episodes'][1]['identityKey'] = 'episode-0'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_bool_as_int_in_episodes(self):
        for key in ('podcastIndex', 'positionMs'):
            dump = base_dump()
            dump['library']['episodes'][0][key] = True
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')
        for key in ('present', 'favorite', 'inUpNext'):
            dump = base_dump()
            dump['library']['episodes'][0][key] = 1
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')

    def test_rejects_unknown_group_member(self):
        for member in (-1, 50, 1.0, True):
            dump = base_dump()
            dump['library']['groups'][0]['members'][0] = member
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['library']['groups'][0]['members'][1] = 0
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_unknown_queue_entry(self):
        for podcast_index in (-1, 50, True):
            dump = base_dump()
            add_queue(dump, 0)
            dump['library']['upNext'][0]['podcastIndex'] = podcast_index
            with self.assertRaises(state.HarnessStateError):
                state.require_dump(dump, 'run-1')
        dump = base_dump()
        add_queue(dump, 0)
        dump['library']['upNext'][0]['identityKey'] = 'episode-1'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        add_queue(dump, 0)
        add_queue(dump, 1)
        dump['library']['upNext'][1]['identityKey'] = 'episode-0'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_inconsistent_queue_flags(self):
        dump = base_dump()
        add_queue(dump, 0)
        dump['library']['episodes'][0]['inUpNext'] = False
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_malformed_held(self):
        dump = base_dump()
        make_held(dump, [f'podcast-{index}' for index in range(11)])
        dump['held'][0]['podcasts'][0] = 'podcast-0'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        make_held(dump, [f'podcast-{index}' for index in range(11)])
        del dump['held'][0]['podcasts'][0]['clock']
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        make_held(dump, [f'podcast-{index}' for index in range(11)])
        dump['held'][0]['podcasts'][1]['id'] = 'podcast-0'
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_rejects_malformed_wire(self):
        dump = base_dump()
        del dump['wire']['redirects']
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['wire']['podcast/podcast-0'] = []
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['wire'] = {'redirects': {}}
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')

    def test_partially_subscribed_library_passes(self):
        dump = base_dump()
        for index in range(11):
            dump['library']['podcasts'][index]['subscribed'] = False
            dump['library']['episodes'][index]['present'] = False
        dump['counts']['podcasts'] = 39
        dump['counts']['episodes'] = 39
        state.require_dump(dump, 'run-1')

    def test_rejects_inconsistent_counts(self):
        dump = base_dump()
        dump['counts']['members'] = 49
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['library']['podcasts'][0]['subscribed'] = False
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')
        dump = base_dump()
        dump['library']['episodes'][0]['present'] = False
        with self.assertRaises(state.HarnessStateError):
            state.require_dump(dump, 'run-1')


class RequireConvergedTests(unittest.TestCase):
    def test_equal_valid_dumps_pass(self):
        counts = state.require_converged(base_dump(), base_dump(), 'run-1')
        self.assertEqual(50, counts['podcasts'])
        self.assertEqual(50, counts['episodes'])
        self.assertEqual(2, counts['groups'])
        self.assertEqual(50, counts['members'])
        self.assertEqual(0, counts['upNext'])
        self.assertEqual(0, counts['outbox'])
        self.assertEqual(0, counts['held'])
        self.assertEqual(0, counts['parked'])

    def test_equal_queue_entries_pass(self):
        left = base_dump()
        right = base_dump()
        add_queue(left, 0, 'a')
        add_queue(left, 1, 'b')
        add_queue(right, 0, 'a')
        add_queue(right, 1, 'b')
        counts = state.require_converged(left, right, 'run-1')
        self.assertEqual(2, counts['upNext'])

    def test_equal_episode_state_passes(self):
        left = base_dump()
        right = base_dump()
        for dump in (left, right):
            dump['library']['episodes'][0]['playedAt'] = 123
            dump['library']['episodes'][0]['favorite'] = True
            set_position(dump, 0, 5_000)
        state.require_converged(left, right, 'run-1')

    def test_partially_subscribed_convergence_passes(self):
        left = base_dump()
        right = base_dump()
        for dump in (left, right):
            for index in range(11):
                dump['library']['podcasts'][index]['subscribed'] = False
                dump['library']['episodes'][index]['present'] = False
            dump['counts']['podcasts'] = 39
            dump['counts']['episodes'] = 39
            dump['counts']['members'] = 39
            dump['library']['groups'][0]['members'] = list(range(11, 25))
        counts = state.require_converged(left, right, 'run-1')
        self.assertEqual(39, counts['podcasts'])
        self.assertEqual(39, counts['episodes'])

    def test_subscribed_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['library']['podcasts'][0]['subscribed'] = False
        right['counts']['podcasts'] = 49
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_empty_and_malformed_equal_states_fail(self):
        with self.assertRaises(state.HarnessStateError):
            state.require_converged({}, {}, 'run-1')
        with self.assertRaises(state.HarnessStateError):
            state.require_converged([], [], 'run-1')
        left = base_dump()
        right = base_dump()
        del left['counts']
        del right['counts']
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_wire_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['wire']['podcast/podcast-0']['fields'] = {'subscribed': {'v': False}}
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_wire_nested_array_reorder_fails(self):
        left = base_dump()
        right = base_dump()
        left['wire']['episode/episode-0']['fields'] = {'grow': {'v': ['a', 'b', 'c']}}
        right['wire']['episode/episode-0']['fields'] = {'grow': {'v': ['a', 'c', 'b']}}
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_wire_object_key_order_is_normalized(self):
        left = base_dump()
        right = base_dump()
        right['wire'] = dict(reversed(list(right['wire'].items())))
        right['wire']['episode/episode-0'] = {'fields': {}, 'coll': 'EPISODE'}
        state.require_converged(left, right, 'run-1')

    def test_library_title_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['library']['episodes'][0]['title'] = 'Other'
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_library_favorite_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['library']['episodes'][0]['favorite'] = True
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_library_played_at_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['library']['episodes'][0]['playedAt'] = 123
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_position_exactly_one_second_passes(self):
        left = base_dump()
        right = base_dump()
        set_position(left, 0, 10_000)
        set_position(right, 0, 11_000)
        state.require_converged(left, right, 'run-1')

    def test_position_one_second_and_one_millisecond_fails(self):
        left = base_dump()
        right = base_dump()
        set_position(left, 0, 10_000)
        set_position(right, 0, 11_001)
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_queue_order_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        add_queue(left, 0, 'a')
        add_queue(left, 1, 'b')
        add_queue(right, 0, 'b')
        add_queue(right, 1, 'a')
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_group_order_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['library']['groups'].reverse()
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_member_order_mismatch_fails(self):
        left = base_dump()
        right = base_dump()
        right['library']['groups'][0]['members'].reverse()
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_held_residue_fails(self):
        left = base_dump()
        right = base_dump()
        make_held(right, [f'podcast-{index}' for index in range(11)])
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_outbox_residue_fails(self):
        left = base_dump()
        right = base_dump()
        right['counts']['outbox'] = 1
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')

    def test_parked_residue_fails(self):
        left = base_dump()
        right = base_dump()
        right['counts']['parked'] = 1
        with self.assertRaises(state.HarnessStateError):
            state.require_converged(left, right, 'run-1')


class RequireResumeTests(unittest.TestCase):
    def test_explicit_value_within_tolerance_passes(self):
        dump = base_dump()
        set_position(dump, 7, 2_000)
        result = state.require_resume(dump, 'run-1', 7, 1_000)
        self.assertEqual(2_000, result['positionMs'])

    def test_explicit_value_at_exact_edge_passes(self):
        dump = base_dump()
        set_position(dump, 7, 2_000)
        state.require_resume(dump, 'run-1', 7, 1_000)

    def test_explicit_value_beyond_tolerance_fails(self):
        dump = base_dump()
        set_position(dump, 7, 2_001)
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 7, 1_000)

    def test_both_zero_is_not_proof(self):
        dump = base_dump()
        set_position(dump, 7, 0)
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 7, 0)

    def test_zero_actual_is_rejected(self):
        dump = base_dump()
        set_position(dump, 7, 0)
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 7, 500)

    def test_zero_expected_is_rejected(self):
        dump = base_dump()
        set_position(dump, 7, 500)
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 7, 0)

    def test_missing_or_absent_episode_fails(self):
        dump = base_dump()
        set_position(dump, 7, 2_000)
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 50, 1_000)
        dump = base_dump()
        set_position(dump, 7, 2_000)
        dump['library']['episodes'][7]['present'] = False
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 7, 1_000)

    def test_invalid_expected_type_fails(self):
        dump = base_dump()
        set_position(dump, 7, 2_000)
        with self.assertRaises(state.HarnessStateError):
            state.require_resume(dump, 'run-1', 7, True)


class RequireHeldTests(unittest.TestCase):
    def setUp(self):
        self.expected = [f'podcast-{index}' for index in range(11)]

    def test_valid_held_passes(self):
        dump = base_dump()
        make_held(dump, self.expected)
        counts = state.require_held(dump, 'run-1', self.expected)
        self.assertEqual(50, counts['podcasts'])
        self.assertEqual(1, counts['held'])
        self.assertEqual(11, counts['podcastRemovals'])
        self.assertEqual(0, counts['groupRemovals'])

    def test_only_an_actual_held_state_may_authorize_approval(self):
        for marker in (None, 'STAGED', 'DEFERRED', 'unknown'):
            dump = base_dump()
            make_held(dump, self.expected)
            if marker is None:
                del dump['held'][0]['state']
            else:
                dump['held'][0]['state'] = marker
            with self.subTest(marker=marker), self.assertRaises(state.HarnessStateError):
                state.require_held(dump, 'run-1', self.expected)

    def test_wrong_identities_fail(self):
        dump = base_dump()
        wrong = [f'podcast-{index}' for index in range(11)]
        wrong[0] = 'podcast-11'
        make_held(dump, wrong)
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)

    def test_wrong_removal_count_fails(self):
        for count in (10, 12):
            dump = base_dump()
            make_held(dump, [f'podcast-{index}' for index in range(count)])
            with self.assertRaises(state.HarnessStateError):
                state.require_held(dump, 'run-1', self.expected)

    def test_malformed_removals_fail(self):
        dump = base_dump()
        make_held(dump, self.expected)
        dump['held'][0]['podcasts'][0] = 'podcast-0'
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)
        dump = base_dump()
        make_held(dump, self.expected)
        del dump['held'][0]['podcasts'][0]['clock']
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)

    def test_group_removal_is_refused(self):
        dump = base_dump()
        make_held(dump, self.expected, group_removals=['group-0'])
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)

    def test_unsubscribed_receiver_fails(self):
        dump = base_dump()
        make_held(dump, self.expected)
        dump['library']['podcasts'][12]['subscribed'] = False
        dump['library']['episodes'][12]['present'] = False
        dump['counts']['podcasts'] = 49
        dump['counts']['episodes'] = 49
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)

    def test_multiple_batches_fail(self):
        dump = base_dump()
        make_held(dump, self.expected)
        dump['held'].append(copy.deepcopy(dump['held'][0]))
        dump['counts']['held'] = 2
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)

    def test_missing_batch_fails(self):
        dump = base_dump()
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', self.expected)

    def test_invalid_expected_identity_fails(self):
        dump = base_dump()
        make_held(dump, self.expected)
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', ['podcast-0'] * 11)
        with self.assertRaises(state.HarnessStateError):
            state.require_held(dump, 'run-1', ['podcast-0'] * 10)


class RandomEditsTests(unittest.TestCase):
    def test_deterministic_for_same_seed(self):
        self.assertEqual(state.random_edits(7), state.random_edits(7))

    def test_different_seeds_differ(self):
        self.assertNotEqual(state.random_edits(7), state.random_edits(8))

    def test_coverage_each_family_both_sides(self):
        result = state.random_edits(7)
        for side in ('android', 'desktop'):
            seen = {edit['op'] for edit in result[side]}
            self.assertEqual(set(state.OP_FAMILIES), seen)
            self.assertEqual(40, len(result[side]))

    def test_bounds_are_validated(self):
        for seed in (-1, 1 << 32, True, '7', 1.0):
            with self.assertRaises(state.HarnessStateError):
                state.random_edits(seed)
        for count in (0, 201, True, '40', 1.0):
            with self.assertRaises(state.HarnessStateError):
                state.random_edits(7, count)

    def test_result_is_json_serializable(self):
        json.dumps(state.random_edits(7))

    def test_replay_against_both_seeded_baselines(self):
        result = state.random_edits(7)
        for side in ('android', 'desktop'):
            for initial_queue in ({3, 9, 15, 21, 27, 33, 39, 45}, set(range(8))):
                for initial_played in ({0, 6, 12, 18, 24, 30, 36, 42, 48},
                                       {0, 5, 10, 15, 20, 25, 30, 35, 40, 45}):
                    membership, queue, played, favorite, order, seen = replay(
                        result[side], initial_queue, initial_played)
                    self.assertEqual(set(state.OP_FAMILIES), seen)
                    self.assertEqual([0, 1], sorted(order))
                    self.assertTrue(all(0 <= index < 50 for group in membership for index in group))
                    self.assertTrue(all(0 <= index < 50 for index in queue))
                    self.assertTrue(all(0 <= index < 50 for index in played))

    def test_queue_edits_never_touch_seeded_or_played_indexes(self):
        result = state.random_edits(7)
        for side in ('android', 'desktop'):
            for edit in result[side]:
                if edit['op'] in ('queue_add', 'queue_remove'):
                    self.assertNotIn(edit['episode'], SEEDED_QUEUE)
                    self.assertNotIn(edit['episode'], PLAYED_POOL)
                if edit['op'] == 'played':
                    self.assertNotIn(edit['episode'], PLAYED_POOL)
                if edit['op'] == 'unplayed':
                    self.assertIn(edit['episode'], PLAYED_POOL)


if __name__ == '__main__':
    unittest.main()
