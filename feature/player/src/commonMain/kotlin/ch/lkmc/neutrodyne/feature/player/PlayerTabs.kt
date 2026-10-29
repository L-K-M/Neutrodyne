// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_collapse_panel
import ch.lkmc.neutrodyne.feature.player.resources.player_expand_panel
import ch.lkmc.neutrodyne.feature.player.resources.player_notes
import ch.lkmc.neutrodyne.feature.player.resources.player_queue_count
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal enum class PlayerTab { UP_NEXT, NOTES }

/** Only the tab strip drags the panel; notes and queue keep their own scrolling. */
@Composable
internal fun PlayerTabs(
    state: PlayerSheetState,
    selected: PlayerTab,
    onSelect: (PlayerTab) -> Unit,
    count: Int,
    backAvailability: PlayerBackAvailability,
    upNext: @Composable () -> Unit,
    notes: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reducedMotion = LocalReducedMotion.current
    val motion = if (reducedMotion) SheetMotion.IMMEDIATE else SheetMotion.ANIMATED
    val scope = rememberCoroutineScope()
    PlayerBackHandler(state, backAvailability)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val peek = with(density) { PLAYER_TABS_PEEK.toPx() }
        val collapsed = (with(density) { maxHeight.toPx() } - peek).coerceAtLeast(0f)
        LaunchedEffect(collapsed) { state.setAnchors(collapsed, 0f, SheetDismissal.BLOCKED) }
        Surface(
            Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(0, (state.drag.offset.takeIf { it.isFinite() } ?: collapsed).roundToInt())
                }.testTag("player_tabs_panel"),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(PLAYER_TABS_PEEK).anchoredDraggable(
                        state.drag,
                        Orientation.Vertical,
                        flingBehavior =
                            AnchoredDraggableDefaults.flingBehavior(
                                state.drag,
                                positionalThreshold = { it * PANEL_SETTLE_FRACTION },
                                animationSpec = NeutrodyneMotion.spatial(reducedMotion),
                            ),
                    ),
                ) {
                    NdIconButton(
                        onClick = {
                            scope.launch {
                                if (state.isExpanded) state.collapse(motion) else state.expand(motion)
                            }
                        },
                        icon = NdIcons.ArrowForwardIos,
                        modifier = Modifier.rotate(if (state.isExpanded) PANEL_ICON_ROTATION else -PANEL_ICON_ROTATION),
                        contentDescription =
                            stringResource(
                                if (state.isExpanded) {
                                    Res.string.player_collapse_panel
                                } else {
                                    Res.string.player_expand_panel
                                },
                            ),
                    )
                    PrimaryTabRow(selectedTabIndex = selected.ordinal, modifier = Modifier.weight(1f)) {
                        PlayerTab.entries.forEach { tab ->
                            val label =
                                if (tab == PlayerTab.NOTES) {
                                    stringResource(Res.string.player_notes)
                                } else {
                                    stringResource(Res.string.player_queue_count, count)
                                }
                            Tab(
                                selected = selected == tab,
                                onClick = {
                                    onSelect(tab)
                                    scope.launch { state.expand(motion) }
                                },
                                text = { Text(label) },
                            )
                        }
                    }
                }
                if (state.progress > 0f) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (selected == PlayerTab.UP_NEXT) upNext() else notes()
                    }
                }
            }
        }
    }
}

internal val PLAYER_TABS_PEEK = 56.dp
private const val PANEL_SETTLE_FRACTION = 0.5f
private const val PANEL_ICON_ROTATION = 90f
