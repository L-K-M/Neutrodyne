// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import ch.lkmc.neutrodyne.core.database.GroupDao
import ch.lkmc.neutrodyne.core.domain.OrderKeys

/** Caller owns the writer transaction, including inserting the returned tail key. */
internal suspend fun GroupDao.appendGroupKey(
    last: String?,
    keys: OrderKeys,
    now: Long,
): String {
    val key = keys.after(last)
    if (key.length <= OrderKeys.MAX_LENGTH) return key

    val rows = groupsInOrder()
    val fresh = keys.rewrite(rows.size + 1)
    for ((row, replacement) in rows.zip(fresh)) setGroupOrderKey(row.id, replacement, now)
    return fresh.last()
}

/** An ignored membership insert must not cause a rewrite or consume a new tail key. */
internal suspend fun GroupDao.appendMemberKey(
    groupId: Long,
    podcastId: Long,
    last: String?,
    keys: OrderKeys,
): String {
    val key = keys.after(last)
    if (key.length <= OrderKeys.MAX_LENGTH) return key

    val rows = membersOf(groupId)
    rows.firstOrNull { it.podcastId == podcastId }?.let { return it.orderKey }
    val fresh = keys.rewrite(rows.size + 1)
    for ((row, replacement) in rows.zip(fresh)) setMemberOrderKey(groupId, row.podcastId, replacement)
    return fresh.last()
}
