#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Pure E12 cross-device dump comparison and deterministic random edits.

This module is a bounded validation boundary: it never touches the network,
the database or the filesystem, and it never applies an edit.  Every failure is
a fixed HarnessStateError reason; caller values and exception text are never
echoed.
"""

import random

PODCAST_COUNT = 50
EPISODE_COUNT = 50
QUEUE_COUNT = 8
T_BASE = 1_730_000_000_000
POSITION_TOLERANCE_MS = 1000
MAX_EDITS = 200
MAX_SEED = (1 << 32) - 1

COUNT_KEYS = (
    'podcasts',
    'episodes',
    'groups',
    'members',
    'upNext',
    'outbox',
    'clock',
    'held',
    'parked',
)
LIBRARY_KEYS = ('podcasts', 'episodes', 'groups', 'upNext')
REMOVAL_ID_KEYS = ('id', 'rid')

# The seeded baseline.  The drivers seed one episode per podcast, two groups of
# 25 members and a representative played set.  The queue is 3, 9, ... 45 in the
# current drivers and 0..7 in the corrected contract, so random queue
# operations use indexes outside both.  Played and favourite indexes are
# likewise kept disjoint from the queue-operation indexes.
SEED_QUEUE = frozenset({3, 9, 15, 21, 27, 33, 39, 45})
SEED_QUEUE_ALT = frozenset(range(QUEUE_COUNT))
SEED_PLAYED = frozenset({0, 6, 12, 18, 24, 30, 36, 42, 48})
SEED_PLAYED_ALT = frozenset({0, 5, 10, 15, 20, 25, 30, 35, 40, 45})
SEED_FAVORITE = frozenset({2, 8, 14, 20, 26, 32, 38, 44})
SEED_FAVORITE_ALT = frozenset({2, 7, 12, 17, 22, 27, 32, 37, 42, 47})

INITIAL_QUEUE = SEED_QUEUE | SEED_QUEUE_ALT
PLAYED_POOL = SEED_PLAYED | SEED_PLAYED_ALT
FAVORITE_POOL = SEED_FAVORITE | SEED_FAVORITE_ALT
QUEUE_SAFE = frozenset(set(range(PODCAST_COUNT)) - INITIAL_QUEUE - PLAYED_POOL)
STATE_SAFE = frozenset(set(range(PODCAST_COUNT)) - QUEUE_SAFE)
# Three disjoint pools cover all 50 fixture indexes: never-played indexes
# receive played edits, representative played indexes receive unplayed edits,
# and never-played, never-queued indexes receive queue edits.
UNPLAYED_POOL = frozenset(STATE_SAFE - PLAYED_POOL)

OP_FAMILIES = (
    'played',
    'unplayed',
    'position',
    'fav',
    'membership_add',
    'membership_remove',
    'group_order',
    'queue_add',
    'queue_remove',
)


class HarnessStateError(ValueError):
    """A fixed, safe validation reason; never carries caller data."""


# --- small typed helpers -----------------------------------------------------------------------


def _is_int(value):
    return type(value) is int


def _is_bool(value):
    return type(value) is bool


def _is_text(value):
    return type(value) is str


def _require_dict(value, reason):
    if type(value) is not dict:
        raise HarnessStateError(reason)
    return value


def _require_list(value, reason):
    if type(value) is not list:
        raise HarnessStateError(reason)
    return value


def _require_int(value, reason):
    if not _is_int(value):
        raise HarnessStateError(reason)
    return value


def _require_text(value, reason):
    if not _is_text(value) or not value:
        raise HarnessStateError(reason)
    return value


def _require_clock(value, reason):
    # MassChangeGuard stages a missing clock as the empty string.
    if not _is_text(value):
        raise HarnessStateError(reason)
    return value


# --- dump validation ---------------------------------------------------------------------------


def _validate_counts(counts):
    for key in COUNT_KEYS:
        if key not in counts:
            raise HarnessStateError('dump: malformed counts')
        value = counts[key]
        if not _is_int(value) or value < 0:
            raise HarnessStateError('dump: malformed counts')


def _validate_podcasts(podcasts):
    if len(podcasts) != PODCAST_COUNT:
        raise HarnessStateError('dump: expected fifty podcasts')
    seen_indices = set()
    seen_sync_ids = set()
    for entry in podcasts:
        if type(entry) is not dict:
            raise HarnessStateError('dump: malformed podcast descriptor')
        index = entry.get('index')
        if not _is_int(index) or not 0 <= index < PODCAST_COUNT or index in seen_indices:
            raise HarnessStateError('dump: duplicate podcast identity')
        sync_id = entry.get('syncId')
        if not _is_text(sync_id) or sync_id in seen_sync_ids:
            raise HarnessStateError('dump: duplicate podcast identity')
        if not _is_text(entry.get('title')):
            raise HarnessStateError('dump: malformed podcast descriptor')
        if not _is_bool(entry.get('subscribed')):
            raise HarnessStateError('dump: malformed podcast descriptor')
        seen_indices.add(index)
        seen_sync_ids.add(sync_id)
    if seen_indices != set(range(PODCAST_COUNT)):
        raise HarnessStateError('dump: expected fifty podcasts')


def _validate_episodes(episodes):
    if len(episodes) != EPISODE_COUNT:
        raise HarnessStateError('dump: expected fifty episodes')
    seen_indexes = set()
    seen_identity_keys = set()
    for entry in episodes:
        if type(entry) is not dict:
            raise HarnessStateError('dump: malformed episode descriptor')
        podcast_index = entry.get('podcastIndex')
        if not _is_int(podcast_index) or not 0 <= podcast_index < PODCAST_COUNT:
            raise HarnessStateError('dump: malformed episode descriptor')
        if podcast_index in seen_indexes:
            raise HarnessStateError('dump: duplicate episode identity')
        identity_key = entry.get('identityKey')
        if not _is_text(identity_key) or identity_key in seen_identity_keys:
            raise HarnessStateError('dump: duplicate episode identity')
        if not _is_text(entry.get('title')):
            raise HarnessStateError('dump: malformed episode descriptor')
        if not _is_bool(entry.get('present')):
            raise HarnessStateError('dump: malformed episode descriptor')
        played_at = entry.get('playedAt')
        if played_at is not None and (not _is_int(played_at) or played_at < 0):
            raise HarnessStateError('dump: malformed episode descriptor')
        position_ms = entry.get('positionMs')
        if not _is_int(position_ms) or position_ms < 0:
            raise HarnessStateError('dump: malformed episode descriptor')
        if not _is_bool(entry.get('favorite')):
            raise HarnessStateError('dump: malformed episode descriptor')
        if not _is_bool(entry.get('inUpNext')):
            raise HarnessStateError('dump: malformed episode descriptor')
        seen_indexes.add(podcast_index)
        seen_identity_keys.add(identity_key)
    if seen_indexes != set(range(PODCAST_COUNT)):
        raise HarnessStateError('dump: expected fifty episodes')


def _validate_groups(groups):
    seen_uuids = set()
    total_members = 0
    for entry in groups:
        if type(entry) is not dict:
            raise HarnessStateError('dump: malformed group descriptor')
        uuid = entry.get('uuid')
        if not _is_text(uuid) or uuid in seen_uuids:
            raise HarnessStateError('dump: duplicate group identity')
        if not _is_text(entry.get('name')) or not _is_text(entry.get('orderKey')):
            raise HarnessStateError('dump: malformed group descriptor')
        members = entry.get('members')
        if type(members) is not list:
            raise HarnessStateError('dump: malformed group descriptor')
        seen_members = set()
        for member in members:
            if not _is_int(member) or not 0 <= member < PODCAST_COUNT or member in seen_members:
                raise HarnessStateError('dump: unknown group member')
            seen_members.add(member)
        total_members += len(members)
        seen_uuids.add(uuid)
    return total_members


def _validate_up_next(up_next, episodes):
    episode_by_index = {entry['podcastIndex']: entry for entry in episodes}
    queued = set()
    for entry in up_next:
        if type(entry) is not dict:
            raise HarnessStateError('dump: malformed queue descriptor')
        podcast_index = entry.get('podcastIndex')
        identity_key = entry.get('identityKey')
        if not _is_int(podcast_index) or not 0 <= podcast_index < PODCAST_COUNT:
            raise HarnessStateError('dump: unknown queue episode')
        if not _is_text(identity_key) or identity_key in queued:
            raise HarnessStateError('dump: duplicate queue identity')
        episode = episode_by_index.get(podcast_index)
        if episode is None or episode['identityKey'] != identity_key:
            raise HarnessStateError('dump: unknown queue episode')
        if not _is_text(entry.get('title')) or not _is_text(entry.get('orderKey')):
            raise HarnessStateError('dump: malformed queue descriptor')
        queued.add(identity_key)
    for entry in episodes:
        if entry['inUpNext'] != (entry['identityKey'] in queued):
            raise HarnessStateError('dump: inconsistent queue flags')
    return len(queued)


def _validate_library(library, counts):
    for key in LIBRARY_KEYS:
        if key not in library or type(library[key]) is not list:
            raise HarnessStateError('dump: malformed library')
    podcasts = library['podcasts']
    episodes = library['episodes']
    groups = library['groups']
    up_next = library['upNext']
    _validate_podcasts(podcasts)
    _validate_episodes(episodes)
    total_members = _validate_groups(groups)
    queued = _validate_up_next(up_next, episodes)
    # The library always lists all fifty fixture descriptors; the counts are
    # the materialized subscribed/present rows, so they can be smaller.
    subscribed = sum(1 for entry in podcasts if entry['subscribed'])
    present = sum(1 for entry in episodes if entry['present'])
    if (
        counts['podcasts'] != subscribed
        or counts['episodes'] != present
        or counts['groups'] != len(groups)
        or counts['members'] != total_members
        or counts['upNext'] != queued
    ):
        raise HarnessStateError('dump: inconsistent counts')


def _removal_id(removal):
    if type(removal) is not dict:
        raise HarnessStateError('dump: malformed held removal')
    identity_keys = [key for key in REMOVAL_ID_KEYS if key in removal]
    if len(identity_keys) != 1:
        raise HarnessStateError('dump: malformed held removal')
    removal_id = removal[identity_keys[0]]
    if not _is_text(removal_id):
        raise HarnessStateError('dump: malformed held removal')
    _require_clock(removal.get('clock'), 'dump: malformed held removal')
    return removal_id


def _validate_held(held, counts):
    for entry in held:
        if type(entry) is not dict:
            raise HarnessStateError('dump: malformed held batch')
        if not _is_int(entry.get('id')) or not _is_int(entry.get('heldAt')) or entry['heldAt'] < 0:
            raise HarnessStateError('dump: malformed held batch')
        if entry.get('state') not in ('STAGED', 'HELD', 'DEFERRED'):
            raise HarnessStateError('dump: malformed held batch state')
        for key in ('podcasts', 'groups'):
            removals = entry.get(key)
            if type(removals) is not list:
                raise HarnessStateError('dump: malformed held batch')
            seen = set()
            for removal in removals:
                removal_id = _removal_id(removal)
                if removal_id in seen:
                    raise HarnessStateError('dump: duplicate held identity')
                seen.add(removal_id)
    if counts['held'] != len(held):
        raise HarnessStateError('dump: inconsistent counts')


def _validate_wire(wire):
    redirects = wire.get('redirects')
    if type(redirects) is not dict:
        raise HarnessStateError('dump: malformed wire state')
    for target in redirects.values():
        if not _is_text(target):
            raise HarnessStateError('dump: malformed wire state')
    records = 0
    for key, record in wire.items():
        if key == 'redirects':
            continue
        if not _is_text(key):
            raise HarnessStateError('dump: malformed wire state')
        if type(record) is not dict:
            raise HarnessStateError('dump: malformed wire state')
        if not _is_text(record.get('coll')):
            raise HarnessStateError('dump: malformed wire state')
        records += 1
    if records == 0:
        raise HarnessStateError('dump: malformed wire state')


def require_dump(value, run_id):
    """Validate one driver dump result and return it unchanged.

    Rejects a malformed, stale, failed or partial dump before any comparison.
    """
    dump = _require_dict(value, 'dump: result is not an object')
    if dump.get('runId') != run_id:
        raise HarnessStateError('dump: stale run identity')
    if dump.get('phase') != 'dump':
        raise HarnessStateError('dump: wrong phase')
    if dump.get('ok') is not True:
        raise HarnessStateError('dump: result is not ok')
    if dump.get('error') is not None:
        raise HarnessStateError('dump: result carries an error')
    skipped = _require_int(dump.get('skippedClockRows'), 'dump: malformed skipped clock row count')
    if skipped != 0:
        raise HarnessStateError('dump: malformed clock rows were skipped')
    counts = _require_dict(dump.get('counts'), 'dump: malformed counts')
    _validate_counts(counts)
    library = _require_dict(dump.get('library'), 'dump: malformed library')
    _validate_library(library, counts)
    held = _require_list(dump.get('held'), 'dump: malformed held batches')
    _validate_held(held, counts)
    wire = _require_dict(dump.get('wire'), 'dump: malformed wire state')
    _validate_wire(wire)
    return dump


# --- convergence ------------------------------------------------------------------------------


def _canonical(value):
    """Normalize object keys only; array order and scalar types are preserved."""
    if type(value) is dict:
        return ('object', tuple(sorted((key, _canonical(item)) for key, item in value.items())))
    if type(value) is list:
        return ('array', tuple(_canonical(item) for item in value))
    if type(value) is bool:
        return ('bool', value)
    if type(value) is int:
        return ('int', value)
    if type(value) is float:
        return ('float', value)
    if type(value) is str:
        return ('str', value)
    if value is None:
        return ('null', None)
    return ('other', type(value).__name__)


def _episodes_equal(left, right):
    if len(left) != len(right):
        return False
    for first, second in zip(left, right):
        for key in ('podcastIndex', 'identityKey', 'title', 'present', 'playedAt', 'favorite', 'inUpNext'):
            if first[key] != second[key]:
                return False
        if abs(first['positionMs'] - second['positionMs']) > POSITION_TOLERANCE_MS:
            return False
    return True


def _libraries_equal(left, right):
    if left['podcasts'] != right['podcasts']:
        return False
    if not _episodes_equal(left['episodes'], right['episodes']):
        return False
    if left['groups'] != right['groups']:
        return False
    if left['upNext'] != right['upNext']:
        return False
    return True


def require_converged(android, desktop, run_id):
    """Require two validated dumps to describe the same accepted state.

    The canonical wire must match exactly (object keys normalized, array order
    preserved) and the materialized library must match field for field, with a
    1000 ms tolerance on positionMs only.  Held, outbox and parked residue is
    refused.  Returns fixed aggregate counts, never raw state.
    """
    left = require_dump(android, run_id)
    right = require_dump(desktop, run_id)
    if _canonical(left['wire']) != _canonical(right['wire']):
        raise HarnessStateError('converged: wire mismatch')
    if not _libraries_equal(left['library'], right['library']):
        raise HarnessStateError('converged: library mismatch')
    for dump in (left, right):
        counts = dump['counts']
        if dump['held'] or counts['held'] != 0 or counts['outbox'] != 0 or counts['parked'] != 0:
            raise HarnessStateError('converged: residue present')
    counts = left['counts']
    return {
        'podcasts': counts['podcasts'],
        'episodes': counts['episodes'],
        'groups': counts['groups'],
        'members': counts['members'],
        'upNext': counts['upNext'],
        'outbox': 0,
        'clock': counts['clock'],
        'held': 0,
        'parked': 0,
    }


# --- resume proof -----------------------------------------------------------------------------


def require_resume(dump, run_id, podcast_index, expected_position_ms):
    """Require a present episode's position within 1000 ms of the explicit value.

    The expected value is supplied by the caller (the desktop-written position);
    a dump that merely reports zero is never accepted as proof.
    """
    validated = require_dump(dump, run_id)
    if not _is_int(podcast_index) or not 0 <= podcast_index < PODCAST_COUNT:
        raise HarnessStateError('resume: unknown episode')
    if not _is_int(expected_position_ms) or expected_position_ms <= 0:
        raise HarnessStateError('resume: position is not explicit')
    episode = None
    for entry in validated['library']['episodes']:
        if entry['podcastIndex'] == podcast_index:
            episode = entry
            break
    if episode is None:
        raise HarnessStateError('resume: unknown episode')
    if not episode['present']:
        raise HarnessStateError('resume: episode is not present')
    position = episode['positionMs']
    if position <= 0:
        raise HarnessStateError('resume: position is not positive')
    if abs(position - expected_position_ms) > POSITION_TOLERANCE_MS:
        raise HarnessStateError('resume: position mismatch')
    return {
        'podcastIndex': podcast_index,
        'positionMs': position,
        'playedAt': episode['playedAt'],
        'favorite': episode['favorite'],
        'inUpNext': episode['inUpNext'],
    }


# --- held batch -------------------------------------------------------------------------------


def require_held(dump, run_id, expected_podcast_ids):
    """Validate a held mass-removal batch without applying it.

    The receiver must still subscribe to all 50 podcasts, carry exactly one
    held batch whose podcast removal identities are exactly the expected eleven,
    and carry no group removals or skipped clock rows.
    """
    validated = require_dump(dump, run_id)
    if type(expected_podcast_ids) not in (list, tuple, set, frozenset):
        raise HarnessStateError('held: invalid expected removals')
    expected = list(expected_podcast_ids)
    if len(expected) != 11 or len(set(expected)) != 11:
        raise HarnessStateError('held: invalid expected removals')
    for removal_id in expected:
        if not _is_text(removal_id):
            raise HarnessStateError('held: invalid expected removals')
    podcasts = validated['library']['podcasts']
    if len(podcasts) != PODCAST_COUNT or not all(entry['subscribed'] for entry in podcasts):
        raise HarnessStateError('held: subscriber count mismatch')
    subscribed_ids = {entry['syncId'] for entry in podcasts}
    held = validated['held']
    if len(held) != 1:
        raise HarnessStateError('held: expected one batch')
    batch = held[0]
    if batch['state'] != 'HELD':
        raise HarnessStateError('held: batch is not awaiting approval')
    removal_ids = [_removal_id(removal) for removal in batch['podcasts']]
    if len(removal_ids) != 11 or set(removal_ids) != set(expected):
        raise HarnessStateError('held: podcast removal mismatch')
    if not set(removal_ids) <= subscribed_ids:
        raise HarnessStateError('held: podcast removal mismatch')
    if batch['groups']:
        raise HarnessStateError('held: unexpected group removal')
    return {
        'podcasts': PODCAST_COUNT,
        'held': 1,
        'podcastRemovals': 11,
        'groupRemovals': 0,
        'skippedClockRows': 0,
    }


# --- deterministic random edits ---------------------------------------------------------------


def _initial_state():
    return {
        'members': [set(range(25)), set(range(25, PODCAST_COUNT))],
        'queue': set(INITIAL_QUEUE),
        'added_queue': set(),
        'favorite': set(FAVORITE_POOL),
        'group_order': [0, 1],
    }


def _choose(rng, candidates):
    return rng.choice(sorted(candidates))


def _make_edit(family, state, rng):
    if family == 'played':
        index = _choose(rng, UNPLAYED_POOL)
        return {
            'op': 'played',
            'podcast': index,
            'episode': index,
            'value': T_BASE + 1_000_000 + rng.randrange(0, 1_000_000),
        }
    if family == 'unplayed':
        index = _choose(rng, PLAYED_POOL)
        return {'op': 'unplayed', 'podcast': index, 'episode': index}
    if family == 'position':
        index = _choose(rng, STATE_SAFE)
        return {
            'op': 'position',
            'podcast': index,
            'episode': index,
            'value': 1000 + rng.randrange(0, 1_999_001),
        }
    if family == 'fav':
        index = _choose(rng, STATE_SAFE)
        favorite = index not in state['favorite']
        if favorite:
            state['favorite'].add(index)
        else:
            state['favorite'].discard(index)
        return {'op': 'fav', 'podcast': index, 'episode': index, 'fav': favorite}
    if family == 'membership_add':
        groups = [group for group in (0, 1) if len(state['members'][group]) < PODCAST_COUNT]
        if not groups:
            return _make_edit('position', state, rng)
        group = rng.choice(groups)
        index = _choose(rng, set(range(PODCAST_COUNT)) - state['members'][group])
        state['members'][group].add(index)
        return {'op': 'membership_add', 'group': group, 'podcast': index}
    if family == 'membership_remove':
        groups = [group for group in (0, 1) if state['members'][group]]
        if not groups:
            return _make_edit('position', state, rng)
        group = rng.choice(groups)
        index = _choose(rng, state['members'][group])
        state['members'][group].remove(index)
        return {'op': 'membership_remove', 'group': group, 'podcast': index}
    if family == 'group_order':
        group = rng.choice((0, 1))
        before = 1 - state['group_order'].index(group)
        state['group_order'].remove(group)
        state['group_order'].insert(before, group)
        return {'op': 'group_order', 'group': group, 'before': before}
    if family == 'queue_add':
        candidates = QUEUE_SAFE - state['queue']
        if not candidates:
            return _make_edit('position', state, rng)
        index = _choose(rng, candidates)
        state['queue'].add(index)
        state['added_queue'].add(index)
        edit = {'op': 'queue_add', 'podcast': index, 'episode': index}
        if state['queue'] and rng.random() < 0.5:
            edit['before'] = 0
        return edit
    if family == 'queue_remove':
        index = _choose(rng, state['added_queue'])
        state['queue'].discard(index)
        state['added_queue'].discard(index)
        return {'op': 'queue_remove', 'podcast': index, 'episode': index}
    raise HarnessStateError('random: unknown operation')


def _client_edits(seed, salt, count):
    rng = random.Random(seed * 2 + salt)
    state = _initial_state()
    edits = []
    seed_plan = list(OP_FAMILIES)
    rng.shuffle(seed_plan)
    # A queue removal is only valid after this client added a queue entry.
    remove_at = seed_plan.index('queue_remove')
    add_at = seed_plan.index('queue_add')
    if remove_at < add_at:
        seed_plan[remove_at], seed_plan[add_at] = seed_plan[add_at], seed_plan[remove_at]
    for family in seed_plan[:count]:
        edits.append(_make_edit(family, state, rng))
    while len(edits) < count:
        family = rng.choice(OP_FAMILIES)
        if family == 'queue_remove' and not state['added_queue']:
            family = 'position'
        edits.append(_make_edit(family, state, rng))
    return edits


def random_edits(seed, count=40):
    """Return deterministic JSON driver edits for both clients.

    Both clients start from the same seeded baseline and edit independently.
    Queue operations stay on indexes that are never played and never in either
    plausible seeded queue, so no duplicate or missing queue edit is emitted.
    """
    if not _is_int(seed) or not 0 <= seed <= MAX_SEED:
        raise HarnessStateError('random: invalid seed')
    if not _is_int(count) or not 1 <= count <= MAX_EDITS:
        raise HarnessStateError('random: invalid count')
    return {
        'android': _client_edits(seed, 0, count),
        'desktop': _client_edits(seed, 1, count),
    }
