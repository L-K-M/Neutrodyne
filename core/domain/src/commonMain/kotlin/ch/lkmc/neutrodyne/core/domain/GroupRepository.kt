// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

/** Group lifecycle, membership, ordering, mosaics and delete/undo (05). */
interface GroupRepository {
    /** Every group list is ordered by (orderKey, id). */
    fun observeGroups(): Flow<List<Group>>

    fun observeGroup(groupId: Long): Flow<Group?>

    fun observeGroupsOf(podcastId: Long): Flow<List<Group>>

    fun observeMemberIds(groupId: Long): Flow<Set<Long>>

    /** podcastId → groupIds; podcasts in no group are absent (05 Membership). */
    fun observeMemberships(): Flow<Map<Long, Set<Long>>>
    /** Each group's four newest-active members for `GroupMosaic`, keyed by `groupId` (02, 05). */
    fun observeMosaics(): Flow<Map<Long, List<GroupMosaicMember>>>

    suspend fun checkName(
        name: String,
        excludingGroupId: Long? = null,
    ): Outcome<String, GroupError>

    /** [memberIds] join the new group in the same write transaction, in iteration order. */
    suspend fun create(
        draft: GroupDraft,
        memberIds: Set<Long> = emptySet(),
    ): Outcome<Long, GroupError>

    suspend fun update(
        groupId: Long,
        edit: GroupEdit,
    ): Outcome<Unit, GroupError>

    /**
     * The drag-reorder / move write (05 Order): a list that is not a permutation of the current
     * ids fails [GroupError.NotFound] and writes nothing; otherwise only the moved rows'
     * `orderKey`s are rewritten.
     */
    suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError>

    /** The group editor's member picker (05): sets [groupId]'s exact member set. */
    suspend fun setMembers(
        groupId: Long,
        podcastIds: Set<Long>,
    ): Outcome<Unit, GroupError>

    /**
     * The tri-state membership write (05 Membership): each podcast in [podcastIds] joins every
     * group in [add] and leaves every group in [remove]; untouched memberships write nothing. A
     * group listed in both ends up added (the removes run first).
     */
    suspend fun applyMembership(
        podcastIds: Set<Long>,
        add: Set<Long>,
        remove: Set<Long>,
    ): Outcome<Unit, GroupError>

    /**
     * 05 Delete and undo: the group row, its memberships and its settings leave in one write
     * transaction (podcasts are never deleted) and a GROUP play context on it is cleared. The
     * returned token keeps the snapshot undoable for [UNDO_WINDOW_MS]; at expiry
     * `GroupChannelSync.onDeleted` deletes the channel.
     */
    suspend fun delete(groupId: Long): Outcome<DeletedGroupToken, GroupError>

    /**
     * Restores the snapshot under [token]: the row with its original `id`, `uuid` and `orderKey`,
     * the settings row, the members whose podcasts still exist and — while `play_session`'s
     * `generation` is still the post-delete value — the cleared GROUP context. A `nameKey` taken
     * meanwhile fails [GroupError.NameTaken] without writing; after the window
     * [GroupError.UndoExpired].
     */
    suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError>

    /**
     * The group uuids whose deletes are still inside the undo window — the channel sweep of
     * `GroupChannelSync.sync` (05 Notification channels) must spare them: their channels stay
     * until `onDeleted` or process death settles the token.
     */
    suspend fun pendingUndoUuids(): Set<String>

    companion object {
        /** 05 Delete and undo: how long a deleted group stays undoable, in milliseconds. */
        const val UNDO_WINDOW_MS = 10_000L
    }
}

/** The handle to a live undo window (05 Delete and undo); opaque, process-local, 10 s. */
@JvmInline
value class DeletedGroupToken(
    val value: String,
)

sealed interface GroupError {
    data object NameEmpty : GroupError

    data class NameTooLong(
        val max: Int,
    ) : GroupError

    data class NameTaken(
        val groupId: Long,
    ) : GroupError

    data object NotFound : GroupError

    data object InvalidIconKey : GroupError

    data object InvalidHideOlderThanDays : GroupError

    data object UndoExpired : GroupError
}
