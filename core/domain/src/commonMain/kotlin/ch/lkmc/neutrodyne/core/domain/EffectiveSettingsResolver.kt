// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import kotlinx.coroutines.flow.Flow

/** 05 resolution: M2 merges member groups; playback uses only a member context group. */
interface EffectiveSettingsResolver {
    /** Missing podcasts fall back to globals. A context group applies only to its members. */
    suspend fun playback(
        podcastId: Long,
        contextGroupId: Long?,
    ): EffectivePlayback

    /** Re-emits on global, override, membership, group identity/name and deletion changes. */
    fun observePlayback(
        podcastId: Long,
        contextGroupId: Long?,
    ): Flow<EffectivePlayback>

    /** Only existing requested podcasts are returned. */
    suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>>

    /** Every podcast; null means Manual only. */
    suspend fun refreshIntervals(): Map<Long, Effective<Int?>>

    fun observeNotifications(podcastIds: Collection<Long>): Flow<Map<Long, Effective<Boolean>>>

    fun observeRefreshIntervals(): Flow<Map<Long, Effective<Int?>>>
}
