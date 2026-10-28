// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.up_next_empty_body
import org.jetbrains.compose.resources.stringResource

internal enum class QueueVisualState { EMPTY, CURRENT_ONLY, CONTENT, EXTERNAL }

internal enum class QueueAppearance { LIGHT, DARK, STRESS }

internal enum class QueueVisual(
    val state: QueueVisualState,
    val appearance: QueueAppearance,
) {
    EMPTY(QueueVisualState.EMPTY, QueueAppearance.LIGHT),
    EMPTY_DARK(QueueVisualState.EMPTY, QueueAppearance.DARK),
    CURRENT_ONLY(QueueVisualState.CURRENT_ONLY, QueueAppearance.LIGHT),
    CURRENT_ONLY_DARK(QueueVisualState.CURRENT_ONLY, QueueAppearance.DARK),
    CONTENT(QueueVisualState.CONTENT, QueueAppearance.LIGHT),
    CONTENT_DARK(QueueVisualState.CONTENT, QueueAppearance.DARK),
    EXTERNAL(QueueVisualState.EXTERNAL, QueueAppearance.LIGHT),
    EXTERNAL_DARK(QueueVisualState.EXTERNAL, QueueAppearance.DARK),
    CONTENT_STRESS(QueueVisualState.CONTENT, QueueAppearance.STRESS),
}

internal const val QUEUE_DESKTOP_WIDTH_DP = 900
internal const val QUEUE_CAPTURE_HEIGHT_DP = 640
internal const val QUEUE_STRESS_FONT_SCALE = 2f
internal const val QUEUE_ARTWORK_SIDE_PX = 96
internal val queueArtworkColor = 0xFF3366CC.toInt()

internal val queueVisualRows =
    listOf(
        testEpisodeRow(2, title = "A long episode title that wraps onto a second line"),
        testEpisodeRow(
            3,
            title = "A YouTube episode",
            sourceType = SourceType.YOUTUBE_CHANNEL,
            externalMediaId = "abcdefghijk",
        ),
    )

internal val queueVisualArtwork: List<ArtworkRef> =
    listOf(testEpisodeRow(1).artwork) + queueVisualRows.map { it.artwork }

@Composable
internal fun QueueVisualContent(visual: QueueVisual) {
    val density = LocalDensity.current
    val stress = visual.appearance == QueueAppearance.STRESS
    CompositionLocalProvider(
        LocalUiClock provides TestClock(),
        LocalDensity provides Density(density.density, if (stress) QUEUE_STRESS_FONT_SCALE else density.fontScale),
        LocalLayoutDirection provides if (stress) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        QueueVisualList(visual.state)
    }
}

@Composable
private fun QueueVisualList(state: QueueVisualState) {
    val row = testEpisodeRow(1, title = "Current episode")
    val hasQueue = state == QueueVisualState.CONTENT || state == QueueVisualState.EXTERNAL
    UpNextList(
        current =
            if (state == QueueVisualState.EMPTY) {
                null
            } else {
                UpNextCurrent(row.id, row.title, row.podcastTitle, row.artwork, state != QueueVisualState.CURRENT_ONLY)
            },
        upNext = if (hasQueue) queueVisualRows.map { UpNextItem(it.id, it) } else emptyList(),
        context =
            if (hasQueue) {
                PlayContextInfo(ContextType.GROUP, 1, "tech", FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL)
            } else {
                null
            },
        live = emptyMap(),
        caps = RowCaps.FULL.copy(inAppPlayback = state != QueueVisualState.EXTERNAL, downloads = false),
        onAction = {},
        onMove = { _, _ -> },
        onRemove = {},
        onStopAfter = {},
        modifier = Modifier.fillMaxSize(),
        emptyContent = { Text(stringResource(Res.string.up_next_empty_body)) },
    )
}
