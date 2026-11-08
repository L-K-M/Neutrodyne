// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import coil3.Image
import coil3.test.FakeImage
import kotlinx.coroutines.flow.flowOf

internal enum class M10PodcastVisual { RSS, YOUTUBE, YOUTUBE_WITH_BANNER }

private const val BANNER_URL = "https://example.invalid/m10-banner.jpg"

internal fun m10PodcastImages(): Map<Any, Image> =
    mapOf(
        BANNER_URL to FakeImage(1200, 200, color = 0xFF334455.toInt()),
    )

@Composable
internal fun M10ScreenContent(variant: Int) {
    val visual = M10PodcastVisual.entries[variant]
    val source = if (visual == M10PodcastVisual.RSS) SourceType.RSS else SourceType.YOUTUBE_CHANNEL
    val detail =
        testPodcastDetail(1).copy(
            sourceType = source,
            bannerUrl = BANNER_URL.takeIf { visual == M10PodcastVisual.YOUTUBE_WITH_BANNER },
        )
    val items =
        remember(source) {
            flowOf(
                PagingData.from(
                    (1L..5).map {
                        testEpisodeRow(it).copy(
                            sourceType = source,
                            isVideo = source != SourceType.RSS,
                            externalMediaId = "abcdefghijk".takeIf { source != SourceType.RSS },
                        )
                    },
                ),
            )
        }.collectAsLazyPagingItems()
    PodcastScreen(
        state = PodcastUiState(loaded = true, detail = detail),
        items = items,
        pendingUnsubscribe = null,
        onRefresh = {},
        onOpenSettings = {},
        onFiltersChange = {},
        onOrderChange = {},
        onAction = {},
        onLoadOlder = {},
        onRetryFeed = {},
        onEnterCredentials = {},
        onMarkAllPlayedClick = {},
        onUnsubscribeRequest = {},
        onConfirmUnsubscribe = {},
        onDismissUnsubscribe = {},
        onRemoveGroup = {},
    )
}
