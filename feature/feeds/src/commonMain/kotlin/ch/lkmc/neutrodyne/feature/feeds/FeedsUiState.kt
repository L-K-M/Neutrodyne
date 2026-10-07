// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Immutable
import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The All feed's M1a state (08 Group feed pager → Data and state, trimmed to its one page): tab
 * state, selection persistence and banner plumbing arrive with M2's groups. [filters] are the
 * transient chip state; [hasSubscriptions] distinguishes "no subscriptions" onboarding from
 * "all caught up".
 */
@Immutable
public data class FeedsUiState(
    val filters: FeedFilters = FeedFilters(),
    val offline: Boolean = false,
    val refreshing: Boolean = false,
    val hasSubscriptions: Boolean = true,
)

/** One list entry of the feed: a day header or an episode row (08 `FeedItem`). */
public sealed interface FeedItem {
    public data class Day(
        val label: UiText,
        val key: String,
    ) : FeedItem

    public data class Episode(
        val row: EpisodeRow,
    ) : FeedItem
}

/**
 * Inserts a [FeedItem.Day] before the first row of each local day (08 Pages/paging/scroll
 * memory: `insertSeparators` between rows whose `sortDate` days differ; headers are ordinary
 * items keyed `"d:{epochDay}"`, never sticky).
 */
public fun PagingData<EpisodeRow>.withDayHeaders(nowMs: Long): PagingData<FeedItem> =
    map<EpisodeRow, FeedItem> { FeedItem.Episode(it) }.insertSeparators { before, after ->
        val afterRow = (after as? FeedItem.Episode)?.row ?: return@insertSeparators null
        val day = FeedDates.dayKey(afterRow.sortDate)
        val beforeDay = (before as? FeedItem.Episode)?.row?.let { FeedDates.dayKey(it.sortDate) }
        if (beforeDay == day) {
            null
        } else {
            FeedItem.Day(label = FeedDates.dayLabel(afterRow.sortDate, nowMs), key = "d:$day")
        }
    }

/** [withDayHeaders] on the feed's flow. */
public fun Flow<PagingData<EpisodeRow>>.withDayHeaders(nowMs: Long): Flow<PagingData<FeedItem>> =
    map { it.withDayHeaders(nowMs) }
