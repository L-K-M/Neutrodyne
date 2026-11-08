// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Immutable
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.UserMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
    val colors: ArtworkColors? = null,
    val feedUrl: String? = null,
    val loaded: Boolean = false,
    val filters: FeedFilters = FeedFilters(),
    val rowCaps: RowCaps =
        RowCaps(
            false,
            false,
            false,
            null,
            false,
        ),
    val offline: Boolean = false,
    val refreshing: Boolean = false,
    /** The podcast's groups in repository order — the header's membership chips (M2, D6). */
    val groups: ImmutableList<Group> = persistentListOf(),
    val messages: ImmutableList<UserMessage> = persistentListOf(),
) {
    public val gone: Boolean
        get() = loaded && detail == null

    public val effectiveOrder: FeedOrder
        get() = detail.effectiveOrder()
}

/**
 * 05's order default, shared by the two screen states and the view model's feed request:
 * the persisted choice wins, a serial show reads oldest-first, and a missing detail falls
 * back to newest-first.
 */
internal fun PodcastDetail?.effectiveOrder(): FeedOrder =
    this?.episodeOrder
        ?: if (this?.showType == ShowType.SERIAL) {
            FeedOrder.OLDEST_FIRST
        } else {
            FeedOrder.NEWEST_FIRST
        }
