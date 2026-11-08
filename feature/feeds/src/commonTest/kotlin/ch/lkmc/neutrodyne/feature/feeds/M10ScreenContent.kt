// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun M10ScreenContent(variant: Int) {
    val rows = remember { flowOf(PagingData.from<FeedItem>((1L..8).map { FeedItem.Episode(testEpisodeRow(it)) })) }
    val state =
        FeedsUiState(
            tabs =
                persistentListOf(FeedTabUi(FeedTab(FeedSource.All, "All", null, null, null), FeedSourceRef.All)),
        )
    FeedsScreen(state, { rows }, {}, {}, { _, _ -> }, {}, {}, {}, {})
}
