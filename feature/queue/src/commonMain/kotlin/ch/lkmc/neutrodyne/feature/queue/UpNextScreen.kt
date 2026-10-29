// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.OfflineBanner
import ch.lkmc.neutrodyne.core.ui.UpNextList
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.queue_clear
import ch.lkmc.neutrodyne.core.ui.resources.queue_play_group
import ch.lkmc.neutrodyne.core.ui.resources.up_next_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.up_next_empty_title
import ch.lkmc.neutrodyne.core.ui.root.LocalMiniPlayerInset
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun UpNextScreen(
    state: UpNextUiState,
    onAction: (EpisodeAction) -> Unit,
    onMove: (Long, Int) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onStopAfter: () -> Unit,
    onPlayGroup: (Long) -> Unit,
    onDiscover: () -> Unit,
    onRetry: () -> Unit,
    onVisibleIds: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_up_next),
            actions = {
                if (state.upNext.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text(stringResource(Res.string.queue_clear)) }
                }
                topBarActions()
            },
        )
        OfflineBanner(state.caps.offline)
        when {
            state.loadFailed -> {
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            }

            !state.loaded -> {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            }

            else -> {
                UpNextList(
                    current = state.current,
                    upNext = state.upNext,
                    context = state.context,
                    live = state.live,
                    caps = state.caps,
                    onAction = onAction,
                    onMove = onMove,
                    onRemove = onRemove,
                    onStopAfter = onStopAfter,
                    modifier = Modifier.weight(1f).navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current + 16.dp),
                    onVisibleIds = onVisibleIds,
                    emptyContent = {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            EmptyState(
                                icon = NdIcons.QueueMusic,
                                title = stringResource(Res.string.up_next_empty_title),
                                body = stringResource(Res.string.up_next_empty_body),
                                actionLabel =
                                    if (state.current == null &&
                                        state.groups.isEmpty()
                                    ) {
                                        stringResource(Res.string.nav_discover)
                                    } else {
                                        null
                                    },
                                onAction = onDiscover,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                state.groups.forEach { group ->
                                    AssistChip(
                                        onClick = { onPlayGroup(group.id) },
                                        label = { Text(stringResource(Res.string.queue_play_group, group.name)) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
