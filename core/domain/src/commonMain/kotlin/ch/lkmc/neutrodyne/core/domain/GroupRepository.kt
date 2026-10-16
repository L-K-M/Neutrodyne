// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import kotlinx.coroutines.flow.Flow

/** Group lifecycle, membership and ordering (05); mosaics and delete/undo arrive separately. */
interface GroupRepository {
    /** Every group list is ordered by (orderKey, id). */
    fun observeGroups(): Flow<List<Group>>

    fun observeGroup(groupId: Long): Flow<Group?>

    fun observeGroupsOf(podcastId: Long): Flow<List<Group>>

    fun observeMemberIds(groupId: Long): Flow<Set<Long>>

    /** podcastId → groupIds; podcasts in no group are absent (05 Membership). */
    fun observeMemberships(): Flow<Map<Long, Set<Long>>>

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
}

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
