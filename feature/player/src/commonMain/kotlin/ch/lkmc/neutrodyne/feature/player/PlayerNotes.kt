// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.showNotes
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_notes_empty
import ch.lkmc.neutrodyne.feature.player.resources.player_read_failed
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/** Disposing the open Notes tab cancels its repository subscription immediately. */
@Composable
internal fun PlayerNotes(
    episodeId: Long,
    durationMs: Long?,
    vm: PlayerEpisodeViewModel = metroViewModel(),
    onLink: (String) -> Unit,
) {
    LaunchedEffect(episodeId) { vm.showNotes(episodeId) }
    DisposableEffect(vm, episodeId) { onDispose { vm.showNotes(null) } }
    val observed by vm.notes.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.episodeId == episodeId } ?: PlayerNotesState(episodeId = episodeId)
    var imagesRevealed by remember(episodeId) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().testTag("player_notes"), contentAlignment = Alignment.Center) {
        when {
            state.loadFailed -> {
                TextButton(onClick = vm::retryNotes) {
                    Text(
                        stringResource(Res.string.player_read_failed) + ": " +
                            stringResource(UiRes.string.action_retry),
                    )
                }
            }

            !state.loaded -> {
                CircularProgressIndicator()
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    val notes = state.notes
                    if (notes == null || notes.blocks.isEmpty()) {
                        item { Text(stringResource(Res.string.player_notes_empty)) }
                    } else {
                        showNotes(
                            notes = notes,
                            imageMode =
                                if (imagesRevealed && state.imageMode == ShowNotesImageMode.TAP_TO_LOAD) {
                                    ShowNotesImageMode.SHOWN
                                } else {
                                    state.imageMode
                                },
                            durationMs = durationMs,
                            onLink = onLink,
                            // Timestamp seeking arrives with M5, as on Episode detail.
                            onTimestamp = {},
                            onLoadImages = { imagesRevealed = true },
                        )
                    }
                }
            }
        }
    }
}
