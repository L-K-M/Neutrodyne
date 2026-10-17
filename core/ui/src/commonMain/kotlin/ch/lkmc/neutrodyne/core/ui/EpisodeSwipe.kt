// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_unplayed
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val SWIPE_COMMIT_FRACTION = 0.4f

/** M2 has no queue or download service. Stored defaults remain valid but those sides are inert. */
internal fun swipeEpisodeAction(
    action: SwipeAction,
    row: EpisodeRow,
): EpisodeAction.SetPlayed? =
    if (action ==
        SwipeAction.MARK_PLAYED
    ) {
        EpisodeAction.SetPlayed(row.id, row.playedAt == null, PlayedActionOrigin.SWIPE)
    } else {
        null
    }

internal fun swipeCommits(
    offset: Float,
    width: Int,
): Boolean = width > 0 && abs(offset) > width * SWIPE_COMMIT_FRACTION

/** A release threshold, rather than fling velocity, decides whether a row action commits. */
@Composable
internal fun EpisodeSwipe(
    row: EpisodeRow,
    config: SwipeConfig?,
    onAction: (EpisodeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled =
        LocalPlatformKind.current == PlatformKind.ANDROID && config != null &&
            (swipeEpisodeAction(config.startToEnd, row) != null || swipeEpisodeAction(config.endToStart, row) != null)
    var offset by remember(row.id, config, enabled) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun actionAt(displacement: Float): EpisodeAction.SetPlayed? {
        val startToEnd = (displacement > 0) != rtl
        val swipe = (if (startToEnd) config?.startToEnd else config?.endToStart) ?: SwipeAction.NONE
        return swipeEpisodeAction(swipe, row)
    }
    val action = actionAt(offset)
    Box(
        modifier.onSizeChanged { width = it.width }.then(
            if (enabled) {
                Modifier.pointerInput(row, config, rtl) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val committed = actionAt(offset).takeIf { swipeCommits(offset, width) }
                            offset = 0f
                            committed?.let(onAction)
                        },
                        onDragCancel = { offset = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val next = (offset + amount).coerceIn(-width.toFloat(), width.toFloat())
                            offset = if (actionAt(next) != null) next else 0f
                        },
                    )
                }
            } else {
                Modifier
            },
        ),
    ) {
        if (enabled) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ).clearAndSetSemantics {
                    }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if ((offset > 0) != rtl) Arrangement.Start else Arrangement.End,
            ) {
                Icon(NdIcons.DoneAll, contentDescription = null)
                Text(
                    stringResource(
                        if (action?.played !=
                            false
                        ) {
                            Res.string.action_mark_played
                        } else {
                            Res.string.action_mark_unplayed
                        },
                    ),
                )
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}
