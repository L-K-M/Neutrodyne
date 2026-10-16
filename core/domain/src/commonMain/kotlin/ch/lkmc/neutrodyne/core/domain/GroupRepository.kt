// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import kotlinx.coroutines.flow.Flow

/** Basic group lifecycle (05); membership writes, reordering and delete/undo arrive separately. */
interface GroupRepository {
    /** Every group list is ordered by (orderKey, id). */
    fun observeGroups(): Flow<List<Group>>

    fun observeGroup(groupId: Long): Flow<Group?>

    fun observeGroupsOf(podcastId: Long): Flow<List<Group>>

    fun observeMemberIds(groupId: Long): Flow<Set<Long>>

    /** Each group's four newest-active members for `GroupMosaic`, keyed by `groupId` (02, 05). */
    fun observeMosaics(): Flow<Map<Long, List<GroupMosaicMember>>>

    suspend fun checkName(
        name: String,
        excludingGroupId: Long? = null,
    ): Outcome<String, GroupError>

    suspend fun create(draft: GroupDraft): Outcome<Long, GroupError>

    suspend fun update(
        groupId: Long,
        edit: GroupEdit,
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
