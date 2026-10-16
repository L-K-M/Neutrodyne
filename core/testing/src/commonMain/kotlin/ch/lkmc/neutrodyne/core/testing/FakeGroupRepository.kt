// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** In-memory group lifecycle with the production name, icon and palette rules. */
class FakeGroupRepository(
    private val clock: Clock = TestClock(),
) : GroupRepository {
    val groups = MutableStateFlow<List<Group>>(emptyList())

    /** Fixture membership state; A1 exposes only membership reads. */
    val members = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    private val mutex = Mutex()

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

    override suspend fun create(draft: GroupDraft): Outcome<Long, GroupError> =
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
                    memberCount = 0,
                )
            groups.value = rows + created
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
}
