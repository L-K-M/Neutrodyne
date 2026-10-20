// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import kotlinx.coroutines.flow.Flow

/** Main-safe media reads; missing/deleted IDs are absent. Failures/cancellation propagate. */
interface PlaybackMediaLookup {
    suspend fun get(episodeId: Long): PlaybackMediaInfo?

    /** Low-churn metadata/state only, never position ticks. Implementations batch large ID sets. */
    fun observe(episodeIds: Set<Long>): Flow<Map<Long, PlaybackMediaInfo>>
}
