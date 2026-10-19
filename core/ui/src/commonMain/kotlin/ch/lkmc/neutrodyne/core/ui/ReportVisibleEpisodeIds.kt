// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val VISIBLE_EPISODE_MARGIN = 10

/**
 * Reports the visible lazy-list window plus [margin] items on either side, without loading pages.
 * [idAt] receives a lazy-list index: callers account for headers and use `peek` for paged data,
 * returning null for headers, placeholders and footers. Stopping or leaving releases the IDs;
 * resuming restores the current window without requiring a scroll.
 */
@Composable
public fun ReportVisibleEpisodeIds(
    listState: LazyListState,
    itemCount: () -> Int,
    idAt: (Int) -> Long?,
    onIds: (Set<Long>) -> Unit,
    margin: Int = VISIBLE_EPISODE_MARGIN,
) {
    require(margin >= 0)
    val currentCount by rememberUpdatedState(itemCount)
    val currentIdAt by rememberUpdatedState(idAt)
    val currentOnIds by rememberUpdatedState(onIds)

    val scope = rememberCoroutineScope()
    LifecycleStartEffect(listState, margin) {
        val reporting =
            scope.launch {
                snapshotFlow {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.isEmpty()) {
                        emptySet()
                    } else {
                        val first = (visible.first().index - margin).coerceAtLeast(0)
                        val last = (visible.last().index + margin).coerceAtMost(currentCount() - 1)
                        (first..last).mapNotNullTo(HashSet()) { currentIdAt(it) }
                    }
                }.distinctUntilChanged().collect { currentOnIds(it) }
            }
        onStopOrDispose {
            reporting.cancel()
            currentOnIds(emptySet())
        }
    }
}
