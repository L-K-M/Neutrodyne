// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.model.RowLive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach

/**
 * In-memory [EpisodeLiveStateSource] (09 `:core:testing` inventory): [live] is the source of truth,
 * filtered to the visible ids each `observe` call reports (absent id = paged row, like the real
 * contract). [lastVisibleIds] records the latest reported set for assertions.
 */
class FakeEpisodeLiveStateSource : EpisodeLiveStateSource {
    val live = MutableStateFlow<Map<Long, RowLive>>(emptyMap())
    val lastVisibleIds = MutableStateFlow<Set<Long>>(emptySet())

    override fun observe(visibleIds: Flow<Set<Long>>): Flow<Map<Long, RowLive>> =
        combine(
            visibleIds.onEach { lastVisibleIds.value = it },
            live,
        ) { ids, map ->
            map.filterKeys { it in ids }
        }
}
