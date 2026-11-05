// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlinx.collections.immutable.ImmutableList

/**
 * Android raster input; members are the same newest-active ordered 0..4 cells as in-app mosaics.
 * Identity, versions, fallback titles and group appearance participate in W4's source descriptor.
 * No file paths or decoded images cross into this model. An empty group still has a snapshot.
 */
data class MosaicSnapshot(
    val groupId: Long,
    val groupUuid: String,
    val groupName: String,
    val groupColorArgb: Int?,
    val groupIconKey: String?,
    val members: ImmutableList<GroupMosaicMember>,
) {
    init {
        require(members.size <= MAX_MEMBERS) { "Mosaic has at most four cells" }
        require(members.all { it.groupId == groupId }) { "Mosaic members belong to its group" }
        require(members.map { it.podcastId }.distinct().size == members.size) { "Mosaic members are unique" }
    }

    companion object {
        const val MAX_MEMBERS = 4
    }
}
