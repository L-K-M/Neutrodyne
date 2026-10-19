// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

data class PodcastKeyInfo(
    val syncId: PodcastId,
    val subscribedAt: Long,
) {
    init {
        require(subscribedAt >= 0)
    }
}

data class GroupKeyInfo(
    val uuid: GroupId,
    val createdAt: Long,
) {
    init {
        require(createdAt >= 0)
    }
}

object SurvivorRule {
    fun podcast(
        a: PodcastKeyInfo,
        b: PodcastKeyInfo,
    ): PodcastKeyInfo = if (compareValuesBy(a, b, { it.subscribedAt }, { it.syncId.value }) <= 0) a else b

    fun group(
        a: GroupKeyInfo,
        b: GroupKeyInfo,
    ): GroupKeyInfo = if (compareValuesBy(a, b, { it.createdAt }, { it.uuid.value }) <= 0) a else b
}
