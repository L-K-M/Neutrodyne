// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.NoGroupChannels
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.OrderedRow
import ch.lkmc.neutrodyne.core.domain.planOrderKeyWrites
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * In-memory group lifecycle with the production name, icon and palette rules and the 05 delete/
 * undo semantics: `delete` snapshots the row and members, `undoDelete` restores them while the
 * window is open, and an expired token reports `UndoExpired` after calling [channelSync]'s
 * `onDeleted`. Expiry is lazy on [clock] — advance the clock past `UNDO_WINDOW_MS` and the next
 * call settles the token; pass [appScope] to fire `onDeleted` exactly at the window edge instead.
 * [podcastExists] decides which snapshot members are "still subscribed" on undo.
 */
class FakeGroupRepository(
    private val clock: Clock = TestClock(),
    private val appScope: CoroutineScope? = null,
    private val channelSync: GroupChannelSync = NoGroupChannels,
    private val podcastExists: (Long) -> Boolean = { true },
) : GroupRepository {
    val groups = MutableStateFlow<List<Group>>(emptyList())

    /** Fixture membership state; A1 exposes only membership reads. */
    val members = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())

    /** Fixture mosaic rows per group (B4): the fake does not derive them from [members]. */
    val mosaics = MutableStateFlow<Map<Long, List<GroupMosaicMember>>>(emptyMap())
    private val mutex = Mutex()

    private class Pending(
        val group: Group,
        val memberIds: Set<Long>,
        val deletedAt: Long,
    )

    private val pending = mutableMapOf<String, Pending>()

    override fun observeGroups(): Flow<List<Group>> =
        combine(groups, members) { rows, memberships ->
            rows
                .sortedWith(compareBy<Group> { it.orderKey }.thenBy { it.id })
                .map { it.copy(memberCount = memberships[it.id].orEmpty().size) }
        }

    override fun observeGroup(groupId: Long): Flow<Group?> =
        observeGroups().map { rows -> rows.find { it.id == groupId } }

    override fun observeGroupsOf(podcastId: Long): Flow<List<Group>> =
        combine(observeGroups(), members) { rows, memberships ->
            rows.filter { podcastId in memberships[it.id].orEmpty() }
        }

    override fun observeMemberIds(groupId: Long): Flow<Set<Long>> = members.map { it[groupId].orEmpty() }

    override fun observeMemberships(): Flow<Map<Long, Set<Long>>> =
        members.map { byGroup ->
            val byPodcast = mutableMapOf<Long, MutableSet<Long>>()
            for ((groupId, podcastIds) in byGroup) {
                for (podcastId in podcastIds) byPodcast.getOrPut(podcastId) { mutableSetOf() } += groupId
            }
            byPodcast
        }

    override fun observeMosaics(): Flow<Map<Long, List<GroupMosaicMember>>> = mosaics

    override suspend fun checkName(
        name: String,
        excludingGroupId: Long?,
    ): Outcome<String, GroupError> =
        when (val result = GroupNames.validate(name)) {
            GroupNames.Validation.Empty -> {
                Outcome.Failure(GroupError.NameEmpty)
            }

            is GroupNames.Validation.TooLong -> {
                Outcome.Failure(GroupError.NameTooLong(result.max))
            }

            is GroupNames.Validation.Valid -> {
                val existing =
                    groups.value.find {
                        it.id != excludingGroupId &&
                            (GroupNames.validate(it.name) as GroupNames.Validation.Valid).nameKey == result.nameKey
                    }
                if (existing == null) {
                    Outcome.Success(result.name)
                } else {
                    Outcome.Failure(GroupError.NameTaken(existing.id))
                }
            }
        }

    override suspend fun create(
        draft: GroupDraft,
        memberIds: Set<Long>,
    ): Outcome<Long, GroupError> =
        mutex.withLock {
            val name =
                when (val result = checkName(draft.name)) {
                    is Outcome.Failure -> return@withLock result
                    is Outcome.Success -> result.value
                }
            if (!GroupIcons.isValid(draft.iconKey)) return@withLock Outcome.Failure(GroupError.InvalidIconKey)
            val rows = groups.value
            val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
            val color = draft.colorArgb?.let(GroupPalette::opaque) ?: GroupPalette.nextColor(rows.map { it.colorArgb })
            val created =
                Group(
                    id = id,
                    uuid = Uuid.random().toString(),
                    name = name,
                    orderKey = OrderKey.after(rows.maxByOrNull { it.orderKey }?.orderKey),
                    colorArgb = color,
                    iconKey = draft.iconKey,
                    feedOrder = FeedOrder.NEWEST_FIRST,
                    playOrder = draft.playOrder,
                    filterFlags = 0,
                    mediaFilter = MediaFilter.ALL,
                    hideOlderThanDays = null,
                    showAsTab = draft.showAsTab,
                    lastViewedAt = null,
                    createdAt = clock.now(),
                    memberCount = memberIds.size,
                )
            groups.value = rows + created
            if (memberIds.isNotEmpty()) members.value = members.value + (id to memberIds)
            Outcome.Success(id)
        }

    override suspend fun update(
        groupId: Long,
        edit: GroupEdit,
    ): Outcome<Unit, GroupError> =
        mutex.withLock {
            val group = groups.value.find { it.id == groupId }
            if (group == null) return@withLock Outcome.Failure(GroupError.NotFound)
            val updated =
                when (edit) {
                    is GroupEdit.Rename -> {
                        val name =
                            when (val result = checkName(edit.name, groupId)) {
                                is Outcome.Failure -> return@withLock result
                                is Outcome.Success -> result.value
                            }
                        group.copy(name = name)
                    }

                    is GroupEdit.Look -> {
                        if (!GroupIcons.isValid(edit.iconKey)) {
                            return@withLock Outcome.Failure(GroupError.InvalidIconKey)
                        }
                        group.copy(colorArgb = edit.colorArgb?.let(GroupPalette::opaque), iconKey = edit.iconKey)
                    }

                    is GroupEdit.Orders -> {
                        group.copy(feedOrder = edit.feedOrder, playOrder = edit.playOrder)
                    }

                    is GroupEdit.View -> {
                        if (!edit.hasSupportedAgeLimit()) {
                            return@withLock Outcome.Failure(GroupError.InvalidHideOlderThanDays)
                        }
                        group.copy(
                            filterFlags = edit.filterFlags,
                            mediaFilter = edit.mediaFilter,
                            hideOlderThanDays = edit.hideOlderThanDays,
                        )
                    }

                    is GroupEdit.ShowAsTab -> {
                        group.copy(showAsTab = edit.show)
                    }
                }
            groups.value = groups.value.map { if (it.id == groupId) updated else it }
            Outcome.Success(Unit)
        }

    override suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError> =
        mutex.withLock {
            val current = groups.value.sortedWith(compareBy<Group> { it.orderKey }.thenBy { it.id })
            val writes =
                planOrderKeyWrites(
                    current.map { OrderedRow(it.id, it.orderKey) },
                    groupIdsInOrder,
                    ProtocolOrderKeys,
                )
                    ?: return@withLock Outcome.Failure(GroupError.NotFound)
            groups.value =
                groups.value.map { row -> row.copy(orderKey = writes[row.id] ?: row.orderKey) }
            Outcome.Success(Unit)
        }

    override suspend fun setMembers(
        groupId: Long,
        podcastIds: Set<Long>,
    ): Outcome<Unit, GroupError> =
        mutex.withLock {
            if (groups.value.none { it.id == groupId }) {
                return@withLock Outcome.Failure(GroupError.NotFound)
            }
            members.value = members.value + (groupId to podcastIds)
            Outcome.Success(Unit)
        }

    override suspend fun applyMembership(
        podcastIds: Set<Long>,
        add: Set<Long>,
        remove: Set<Long>,
    ): Outcome<Unit, GroupError> =
        mutex.withLock {
            val known = groups.value.mapTo(HashSet()) { it.id }
            if ((add + remove).any { it !in known }) {
                return@withLock Outcome.Failure(GroupError.NotFound)
            }
            val next = members.value.toMutableMap()
            for (groupId in remove) {
                next[groupId]?.let { next[groupId] = it - podcastIds }
            }
            for (groupId in add) {
                next[groupId] = (next[groupId].orEmpty() + podcastIds)
            }
            members.value = next
            Outcome.Success(Unit)
        }

    override suspend fun delete(groupId: Long): Outcome<DeletedGroupToken, GroupError> =
        mutex.withLock {
            expireElapsedLocked()
            val group =
                groups.value.find { it.id == groupId }
                    ?: return@withLock Outcome.Failure(GroupError.NotFound)
            groups.value = groups.value - group
            val memberIds = members.value[groupId].orEmpty()
            members.value = members.value - groupId
            val token = DeletedGroupToken(Uuid.random().toString())
            pending[token.value] = Pending(group, memberIds, clock.now())
            appScope?.launch {
                delay(GroupRepository.UNDO_WINDOW_MS)
                mutex.withLock { expireLocked(token.value) }
            }
            Outcome.Success(token)
        }

    override suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError> =
        mutex.withLock {
            expireElapsedLocked()
            val entry = pending[token.value] ?: return@withLock Outcome.Failure(GroupError.UndoExpired)
            val nameKey =
                (GroupNames.validate(entry.group.name) as? GroupNames.Validation.Valid)?.nameKey
                    ?: return@withLock Outcome.Failure(GroupError.NameEmpty)
            val holder =
                groups.value.find {
                    (GroupNames.validate(it.name) as? GroupNames.Validation.Valid)?.nameKey == nameKey
                }
            if (holder != null) return@withLock Outcome.Failure(GroupError.NameTaken(holder.id))
            groups.value = groups.value + entry.group
            members.value =
                members.value + (entry.group.id to entry.memberIds.filterTo(HashSet(), podcastExists))
            pending.remove(token.value)
            Outcome.Success(entry.group.id)
        }

    override suspend fun pendingUndoUuids(): Set<String> =
        mutex.withLock {
            expireElapsedLocked()
            pending.values.mapTo(HashSet()) { it.group.uuid }
        }

    /** Settles every token whose window has passed; the channel delete fires per expiry. */
    private suspend fun expireElapsedLocked() {
        val expired =
            pending.filterValues { clock.now() - it.deletedAt >= GroupRepository.UNDO_WINDOW_MS }
        for (token in expired.keys) expireLocked(token)
    }

    private suspend fun expireLocked(token: String) {
        val entry = pending.remove(token) ?: return
        channelSync.onDeleted(entry.group.uuid)
    }
}

/** The fake binds the real `OrderKey` — `:core:testing` may see `:sync:protocol`. */
private object ProtocolOrderKeys : OrderKeys {
    override fun after(last: String?): String = OrderKey.after(last)

    override fun between(
        previous: String?,
        next: String?,
    ): String = OrderKey.between(previous, next)

    override fun rewrite(count: Int): List<String> = OrderKey.rewrite(count)
}
