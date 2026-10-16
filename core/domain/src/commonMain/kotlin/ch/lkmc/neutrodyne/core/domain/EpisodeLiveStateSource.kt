// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.RowLive
import kotlinx.coroutines.flow.Flow

/**
 * The live-row overlay contract (08 Live row state). Positions, live download state and now-playing
 * reach rows only through this source, so high-churn tables stay out of every paged query
 * (02 Invalidation hygiene, D16/D17).
 */
interface EpisodeLiveStateSource {
    /**
     * Live overlay for the IDs currently on screen (plus margin). Emits a map containing only IDs
     * with live data; absent = use the paged row. Conflated, main-safe; at most ~4 emissions per
     * second in steady state.
     */
    fun observe(visibleIds: Flow<Set<Long>>): Flow<Map<Long, RowLive>>
}
