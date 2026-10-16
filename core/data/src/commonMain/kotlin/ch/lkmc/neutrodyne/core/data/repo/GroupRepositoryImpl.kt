// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.GroupMosaicRow
import ch.lkmc.neutrodyne.core.database.GroupRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.GroupPalette
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

    override fun observeMosaics(): Flow<Map<Long, List<GroupMosaicMember>>> =
        dao.observeMosaics().map { rows ->
            // The SQL orders by groupId then member recency; groupBy keeps both.
            rows.groupBy(GroupMosaicRow::groupId).mapValues { (_, members) -> members.map { it.toModel() } }
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

    override suspend fun create(draft: GroupDraft): Outcome<Long, GroupError> {
        val name =
            when (val result = validatedName(draft.name)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
        if (!GroupIcons.isValid(draft.iconKey)) return Outcome.Failure(GroupError.InvalidIconKey)

        return db.withWriteTransaction {
            val now = clock.now()
            withNameConflict(name.nameKey) {
                dao.insert(
                    PodcastGroupEntity(
                        uuid = Uuid.random().toString(),
                        name = name.name,
                        nameKey = name.nameKey,
                        orderKey = orderKeys.after(dao.lastGroupOrderKey()),
                        colorArgb =
                            draft.colorArgb?.let(GroupPalette::opaque) ?: GroupPalette.nextColor(dao.groupColors()),
                        iconKey = draft.iconKey,
                        playOrder = draft.playOrder,
                        showAsTab = draft.showAsTab,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
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

    private fun GroupMosaicRow.toModel(): GroupMosaicMember =
        GroupMosaicMember(
            groupId = groupId,
            podcastId = podcastId,
            displayTitle = title,
            artwork = ArtworkRef(key = artworkKey, url = artworkUrl, version = artworkVersion),
            artworkAvgArgb = artworkAvgArgb,
        )

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
