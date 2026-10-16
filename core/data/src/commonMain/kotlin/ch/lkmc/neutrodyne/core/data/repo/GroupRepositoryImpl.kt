// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.GroupRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.OrderedRow
import ch.lkmc.neutrodyne.core.domain.planOrderKeyWrites
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class GroupRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val orderKeys: OrderKeys,
) : GroupRepository {
    private val dao get() = db.groupDao()

    override fun observeGroups(): Flow<List<Group>> = dao.observeGroups().map { rows -> rows.map { it.toModel() } }

    override fun observeGroup(groupId: Long): Flow<Group?> = dao.observeGroup(groupId).map { it?.toModel() }

    override fun observeGroupsOf(podcastId: Long): Flow<List<Group>> =
        dao.observeGroupsOf(podcastId).map { rows -> rows.map { it.toModel() } }

    override fun observeMemberIds(groupId: Long): Flow<Set<Long>> = dao.observeMemberIds(groupId).map { it.toSet() }

    override fun observeMemberships(): Flow<Map<Long, Set<Long>>> =
        dao.observeMembershipPairs().map { rows ->
            rows
                .groupBy({ it.podcastId }, { it.groupId })
                .mapValues { (_, groupIds) -> groupIds.toSet() }
        }

    override suspend fun checkName(
        name: String,
        excludingGroupId: Long?,
    ): Outcome<String, GroupError> {
        val valid =
            when (val result = validatedName(name)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
        val existing = dao.groupByNameKey(valid.nameKey)
        if (existing != null &&
            existing.id != excludingGroupId
        ) {
            return Outcome.Failure(GroupError.NameTaken(existing.id))
        }

        return Outcome.Success(valid.name)
    }

    override suspend fun create(
        draft: GroupDraft,
        memberIds: Set<Long>,
    ): Outcome<Long, GroupError> {
        val name =
            when (val result = validatedName(draft.name)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
        if (!GroupIcons.isValid(draft.iconKey)) return Outcome.Failure(GroupError.InvalidIconKey)

        return db.withWriteTransaction {
            if (!podcastsExist(memberIds)) return@withWriteTransaction Outcome.Failure(GroupError.NotFound)
            val now = clock.now()
            withNameConflict(name.nameKey) {
                val groupId =
                    dao.insert(
                        PodcastGroupEntity(
                            uuid = Uuid.random().toString(),
                            name = name.name,
                            nameKey = name.nameKey,
                            orderKey = orderKeys.after(dao.lastGroupOrderKey()),
                            colorArgb =
                                draft.colorArgb?.let(GroupPalette::opaque)
                                    ?: GroupPalette.nextColor(dao.groupColors()),
                            iconKey = draft.iconKey,
                            playOrder = draft.playOrder,
                            showAsTab = draft.showAsTab,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                var lastKey: String? = null
                for (podcastId in memberIds) {
                    lastKey = orderKeys.after(lastKey)
                    dao.insertMember(memberRow(groupId, podcastId, lastKey, now))
                }
                groupId
            }
        }
    }

    override suspend fun update(
        groupId: Long,
        edit: GroupEdit,
    ): Outcome<Unit, GroupError> =
        db.withWriteTransaction {
            if (dao.groupById(groupId) == null) return@withWriteTransaction Outcome.Failure(GroupError.NotFound)

            val now = clock.now()
            when (edit) {
                is GroupEdit.Rename -> {
                    val name =
                        when (val result = validatedName(edit.name)) {
                            is Outcome.Failure -> return@withWriteTransaction result
                            is Outcome.Success -> result.value
                        }
                    return@withWriteTransaction withNameConflict(name.nameKey) {
                        dao.rename(groupId, name.name, name.nameKey, now)
                    }
                }

                is GroupEdit.Look -> {
                    if (!GroupIcons.isValid(
                            edit.iconKey,
                        )
                    ) {
                        return@withWriteTransaction Outcome.Failure(GroupError.InvalidIconKey)
                    }
                    dao.setLook(groupId, edit.colorArgb?.let(GroupPalette::opaque), edit.iconKey, now)
                }

                is GroupEdit.Orders -> {
                    dao.setOrders(groupId, edit.feedOrder, edit.playOrder, now)
                }

                is GroupEdit.View -> {
                    if (!edit.hasSupportedAgeLimit()) {
                        return@withWriteTransaction Outcome.Failure(GroupError.InvalidHideOlderThanDays)
                    }
                    dao.setView(groupId, edit.filterFlags, edit.mediaFilter, edit.hideOlderThanDays, now)
                }

                is GroupEdit.ShowAsTab -> {
                    dao.setShowAsTab(groupId, edit.show, now)
                }
            }
            Outcome.Success(Unit)
        }

    override suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError> =
        db.withWriteTransaction {
            val current = dao.groupsInOrder()
            val writes =
                planOrderKeyWrites(
                    current.map { OrderedRow(it.id, it.orderKey) },
                    groupIdsInOrder,
                    orderKeys,
                )
                    ?: return@withWriteTransaction Outcome.Failure(GroupError.NotFound)
            val now = clock.now()
            for ((groupId, orderKey) in writes) dao.setGroupOrderKey(groupId, orderKey, now)
            Outcome.Success(Unit)
        }

    override suspend fun setMembers(
        groupId: Long,
        podcastIds: Set<Long>,
    ): Outcome<Unit, GroupError> =
        db.withWriteTransaction {
            if (dao.groupById(groupId) == null || !podcastsExist(podcastIds)) {
                return@withWriteTransaction Outcome.Failure(GroupError.NotFound)
            }

            val current = dao.membersOf(groupId).mapTo(HashSet()) { it.podcastId }
            if (current == podcastIds) return@withWriteTransaction Outcome.Success(Unit)

            val now = clock.now()
            val removed = current - podcastIds
            if (removed.isNotEmpty()) dao.deleteMembers(groupId, removed)
            var lastKey = dao.lastMemberOrderKey(groupId)
            for (podcastId in podcastIds - current) {
                lastKey = orderKeys.after(lastKey)
                dao.insertMember(memberRow(groupId, podcastId, lastKey, now))
            }
            dao.touchUpdatedAt(setOf(groupId), now)
            Outcome.Success(Unit)
        }

    override suspend fun applyMembership(
        podcastIds: Set<Long>,
        add: Set<Long>,
        remove: Set<Long>,
    ): Outcome<Unit, GroupError> =
        db.withWriteTransaction {
            val groups = add + remove
            if (!podcastsExist(podcastIds) ||
                dao.existingIds(groups.toList()).size != groups.size
            ) {
                return@withWriteTransaction Outcome.Failure(GroupError.NotFound)
            }

            val now = clock.now()
            val changed = mutableSetOf<Long>()
            for (groupId in remove) {
                if (dao.deleteMembers(groupId, podcastIds) > 0) changed += groupId
            }
            for (groupId in add) {
                var lastKey = dao.lastMemberOrderKey(groupId)
                for (podcastId in podcastIds) {
                    val orderKey = orderKeys.after(lastKey)
                    if (dao.insertMember(memberRow(groupId, podcastId, orderKey, now)) == -1L) continue
                    lastKey = orderKey
                    changed += groupId
                }
            }
            if (changed.isNotEmpty()) dao.touchUpdatedAt(changed, now)
            Outcome.Success(Unit)
        }

    private suspend fun podcastsExist(podcastIds: Set<Long>): Boolean =
        db.podcastDao().existingIds(podcastIds.toList()).size == podcastIds.size

    private fun memberRow(
        groupId: Long,
        podcastId: Long,
        orderKey: String,
        now: Long,
    ): PodcastGroupMemberEntity =
        PodcastGroupMemberEntity(
            groupId = groupId,
            podcastId = podcastId,
            orderKey = orderKey,
            addedAt = now,
            source = MemberSource.MANUAL,
        )

    private fun validatedName(name: String): Outcome<GroupNames.Validation.Valid, GroupError> =
        when (val result = GroupNames.validate(name)) {
            GroupNames.Validation.Empty -> Outcome.Failure(GroupError.NameEmpty)
            is GroupNames.Validation.TooLong -> Outcome.Failure(GroupError.NameTooLong(result.max))
            is GroupNames.Validation.Valid -> Outcome.Success(result)
        }

    /** SQLite ABORT leaves the transaction usable; resolve the winning row under the writer lock. */
    private suspend fun <T> withNameConflict(
        nameKey: String,
        write: suspend () -> T,
    ): Outcome<T, GroupError> =
        try {
            Outcome.Success(write())
        } catch (error: SQLiteException) {
            // UUID and other storage faults must propagate, even when the requested name exists.
            if (error.message?.contains("UNIQUE constraint failed: podcast_group.nameKey") != true) throw error
            val existing = dao.groupByNameKey(nameKey) ?: throw error
            Outcome.Failure(GroupError.NameTaken(existing.id))
        }

    private fun GroupRow.toModel(): Group =
        with(group) {
            Group(
                id = id,
                uuid = uuid,
                name = name,
                orderKey = orderKey,
                colorArgb = colorArgb,
                iconKey = iconKey,
                feedOrder = feedOrder,
                playOrder = playOrder,
                filterFlags = filterFlags,
                mediaFilter = mediaFilter,
                hideOlderThanDays = hideOlderThanDays,
                showAsTab = showAsTab,
                lastViewedAt = lastViewedAt,
                createdAt = createdAt,
                memberCount = memberCount,
            )
        }
}
