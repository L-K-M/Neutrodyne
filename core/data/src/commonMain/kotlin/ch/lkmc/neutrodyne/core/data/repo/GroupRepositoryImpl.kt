// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.GroupMosaicRow
import ch.lkmc.neutrodyne.core.database.GroupRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.OrderedRow
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.planOrderKeyWrites
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class GroupRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val orderKeys: OrderKeys,
    private val channelSync: GroupChannelSync,
    private val refreshController: RefreshController,
    @ApplicationScope private val appScope: CoroutineScope,
) : GroupRepository {
    private val dao get() = db.groupDao()

    /**
     * Live undo tokens, keyed by `DeletedGroupToken.value` (05 Delete and undo). In-memory only:
     * process death finalizes every pending delete — the channel sweep of `sync()` then sees no
     * live token and drops the orphan channel. [undoMutex] serializes [undoDelete] against the
     * expiry job so a token is consumed exactly once.
     */
    private val pendingDeletes = mutableMapOf<String, PendingDelete>()
    private val undoMutex = Mutex()

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

        val outcome =
            db.withWriteTransaction {
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
        if (outcome is Outcome.Success && memberIds.isNotEmpty()) rescheduleAfterCommit("group create")
        if (outcome is Outcome.Success) syncChannels()
        return outcome
    }

    override suspend fun update(
        groupId: Long,
        edit: GroupEdit,
    ): Outcome<Unit, GroupError> =
        db
            .withWriteTransaction<Outcome<Unit, GroupError>> {
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
            }.also { outcome ->
                if (outcome is Outcome.Success && edit is GroupEdit.Rename) syncChannels()
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
    ): Outcome<Unit, GroupError> {
        var changed = false
        val outcome =
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
                changed = true
                Outcome.Success(Unit)
            }
        if (changed) rescheduleAfterCommit("membership edit")
        return outcome
    }

    override suspend fun applyMembership(
        podcastIds: Set<Long>,
        add: Set<Long>,
        remove: Set<Long>,
    ): Outcome<Unit, GroupError> {
        var changedAny = false
        val outcome =
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
                changedAny = changed.isNotEmpty()
                Outcome.Success(Unit)
            }
        if (changedAny) rescheduleAfterCommit("membership edit")
        return outcome
    }

    /**
     * 05: memberships and the group set decide each podcast's effective refresh interval (the
     * least over its groups), so a committed change re-plans the periodic refresh. It runs
     * NonCancellable like ScopeSettingsRepository's writes: a screen closing after the commit
     * must not skip it. A failed reschedule never fails the committed write.
     */
    private suspend fun rescheduleAfterCommit(write: String) {
        withContext(NonCancellable) {
            suspendRunCatching { refreshController.reschedulePeriodic() }
                .onFailure { Log.w(TAG, it) { "Refresh reschedule failed after $write" } }
        }
    }

    override suspend fun delete(groupId: Long): Outcome<DeletedGroupToken, GroupError> {
        // Snapshot and delete inside the one write transaction (05 steps 1–2): the member and
        // settings rows die by FK cascade, so the reads must precede the DELETE under the writer.
        // Register the token under the same mutex a sweep uses to read it, before exposing the delete.
        val token =
            undoMutex.withLock {
                val snapshot =
                    db.withWriteTransaction<DeletedGroupSnapshot?> {
                        val group = dao.groupById(groupId)
                        if (group == null) {
                            null
                        } else {
                            val session = db.playSessionDao().get()
                            val sessionContext =
                                if (session != null &&
                                    session.contextType == ContextType.GROUP &&
                                    session.contextId == groupId
                                ) {
                                    SessionContextSnapshot(
                                        // The clear below bumps the generation once; undo restores the
                                        // context only while the session still sits at this value.
                                        expectedGeneration = session.generation + 1,
                                        anchorEpisodeId = session.contextAnchorEpisodeId,
                                        anchorSortDate = session.contextAnchorSortDate,
                                    )
                                } else {
                                    null
                                }
                            val snapshot =
                                DeletedGroupSnapshot(
                                    group = group,
                                    settings = db.scopeSettingsDao().forGroup(groupId),
                                    members = dao.membersOf(groupId),
                                    sessionContext = sessionContext,
                                )
                            dao.clearGroupContext(groupId, clock.now())
                            dao.deleteGroup(groupId)
                            snapshot
                        }
                    } ?: return@withLock null

                val token = DeletedGroupToken(Uuid.random().toString())
                pendingDeletes[token.value] =
                    PendingDelete(
                        snapshot = snapshot,
                        expiry = appScope.launch { expireToken(token.value) },
                    )
                token
            } ?: return Outcome.Failure(GroupError.NotFound)

        syncChannels()
        // 05 step 2: the least refresh interval over the remaining groups may rise; a failed
        // reschedule never turns a committed delete into a failure. SnapshotScheduler.requestSoon
        // joins it when M3 brings that port (see the 2026-10-16 note in 05).
        rescheduleAfterCommit("group delete")
        return Outcome.Success(token)
    }

    override suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError> {
        val outcome = undoLocked(token)

        // 05 step 2 in reverse: the restored group may lower the least refresh interval again.
        if (outcome is Outcome.Success) {
            rescheduleAfterCommit("group undo")
            syncChannels()
        }
        return outcome
    }

    private suspend fun undoLocked(token: DeletedGroupToken): Outcome<Long, GroupError> =
        undoMutex.withLock {
            val pending =
                pendingDeletes[token.value]
                    ?: return@withLock Outcome.Failure(GroupError.UndoExpired)
            val snapshot = pending.snapshot
            val outcome =
                db.withWriteTransaction<Outcome<Long, GroupError>> {
                    // A group created meanwhile may hold the nameKey: then the re-insert is not
                    // attempted, nothing is written and the token stays live until expiry (05).
                    dao.groupByNameKey(snapshot.group.nameKey)?.let {
                        return@withWriteTransaction Outcome.Failure(GroupError.NameTaken(it.id))
                    }
                    dao.insert(snapshot.group)
                    snapshot.settings?.let { db.scopeSettingsDao().upsertGroup(it) }
                    val surviving = db.podcastDao().existingIds(snapshot.members.map { it.podcastId }).toSet()
                    for (member in snapshot.members) {
                        if (member.podcastId in surviving) dao.insertMember(member)
                    }
                    snapshot.sessionContext?.let {
                        dao.restoreGroupContext(
                            groupId = snapshot.group.id,
                            anchorEpisodeId = it.anchorEpisodeId,
                            anchorSortDate = it.anchorSortDate,
                            expectedGeneration = it.expectedGeneration,
                            now = clock.now(),
                        )
                    }
                    Outcome.Success(snapshot.group.id)
                }
            if (outcome is Outcome.Success) {
                pendingDeletes.remove(token.value)
                pending.expiry.cancel()
            }
            outcome
        }

    override suspend fun pendingUndoUuids(): Set<String> =
        undoMutex.withLock { pendingDeletes.values.mapTo(HashSet()) { it.snapshot.group.uuid } }

    /**
     * The end of the undo window (05 step 5): the token goes and the channel port deletes
     * `new_episodes_{uuid}`. A failure only delays the sweep — start-up `sync()` reconciles.
     */
    private suspend fun expireToken(tokenValue: String) {
        delay(GroupRepository.UNDO_WINDOW_MS)
        val pending = undoMutex.withLock { pendingDeletes.remove(tokenValue) } ?: return
        suspendRunCatching { channelSync.onDeleted(pending.snapshot.group.uuid) }
            .onFailure { Log.w(TAG, it) { "Group channel delete failed for ${pending.snapshot.group.uuid}" } }
    }

    private suspend fun syncChannels() {
        // Like rescheduling, reconciliation must survive a screen closing after the commit.
        withContext(NonCancellable) {
            suspendRunCatching { channelSync.sync() }
                .onFailure { Log.w(TAG, it) { "Group channel sweep failed after group write" } }
        }
    }

    private class DeletedGroupSnapshot(
        val group: PodcastGroupEntity,
        val settings: PodcastGroupSettingsEntity?,
        /** Full member rows — undo restores each membership's own `orderKey`, `addedAt`, `source`. */
        val members: List<PodcastGroupMemberEntity>,
        /** Null when `play_session` did not carry a GROUP context on the deleted group. */
        val sessionContext: SessionContextSnapshot?,
    )

    private class SessionContextSnapshot(
        /** The generation the delete's clear left behind; undo restores only while it holds. */
        val expectedGeneration: Long,
        val anchorEpisodeId: Long?,
        val anchorSortDate: Long?,
    )

    private class PendingDelete(
        val snapshot: DeletedGroupSnapshot,
        val expiry: Job,
    )

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

    private companion object {
        const val TAG = "Groups"
    }
}
