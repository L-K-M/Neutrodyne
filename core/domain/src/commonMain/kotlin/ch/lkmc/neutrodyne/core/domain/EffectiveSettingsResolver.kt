// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.Effective
import kotlinx.coroutines.flow.Flow

/** M2 resolution from 05: podcast, deciding member groups, then app defaults. */
interface EffectiveSettingsResolver {
    /** Only existing requested podcasts are returned. */
    suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>>

    /** Every podcast; null means Manual only. */
    suspend fun refreshIntervals(): Map<Long, Effective<Int?>>

    fun observeNotifications(podcastIds: Collection<Long>): Flow<Map<Long, Effective<Boolean>>>

    fun observeRefreshIntervals(): Flow<Map<Long, Effective<Int?>>>
}
