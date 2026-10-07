// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Immutable
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowType

/**
 * The podcast detail screen's state (08 Podcast detail): [loaded] distinguishes the header
 * skeleton from a settled emission; [gone] (emitted `null`) means the podcast was removed
 * elsewhere and the route pops. [effectiveOrder] applies 05's default — serial shows read oldest
 * first. [feedUrl] is the raw feed address behind the overflow's "Copy feed address" (03 feeds it
 * through `FeedInfo`, which `PodcastDetail` deliberately lacks).
 */
@Immutable
public data class PodcastUiState(
    val detail: PodcastDetail? = null,
    val feedUrl: String? = null,
    val loaded: Boolean = false,
    val filters: FeedFilters = FeedFilters(),
    val offline: Boolean = false,
    val refreshing: Boolean = false,
) {
    public val gone: Boolean
        get() = loaded && detail == null

    public val effectiveOrder: FeedOrder
        get() =
            detail?.episodeOrder
                ?: if (detail?.showType == ShowType.SERIAL) {
                    FeedOrder.OLDEST_FIRST
                } else {
                    FeedOrder.NEWEST_FIRST
                }
}
