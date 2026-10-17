// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.platform.rememberIsActivityRecreating
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.selection_close
import ch.lkmc.neutrodyne.core.ui.resources.selection_count
import ch.lkmc.neutrodyne.core.ui.resources.selection_select_all
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Save IDs across recreation, release the ViewModel's selection when this screen leaves. */
@Composable
public fun BindEpisodeSelection(selection: EpisodeSelectionController) {
    var saved by rememberSaveable { mutableStateOf(selection.snapshot()) }
    var generation by rememberSaveable { mutableIntStateOf(selection.generation) }
    val isRecreating = rememberIsActivityRecreating()
    LaunchedEffect(selection) {
        // A saved tab entry belongs to its old visit; rotation retains the same generation.
        selection.bind(saved, generation)
        generation = selection.generation
        selection.ids.collect { saved = selection.snapshot() }
    }
    DisposableEffect(selection) {
        onDispose { if (!isRecreating()) selection.leave() }
    }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        val ids by selection.ids.collectAsStateWithLifecycle()
        NavigationBackHandler(
            rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = ids.isNotEmpty(),
            onBackCompleted = selection::clear,
        )
    }
}

/** Only M2 actions are exposed; queue and download actions arrive with their services. */
@Composable
public fun SelectionTopBar(
    selection: EpisodeSelectionController,
    count: Int,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy by selection.busy.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    NdTopAppBar(
        title = pluralStringResource(Res.plurals.selection_count, count, count),
        modifier = modifier,
        navigation = {
            NdIconButton(selection::clear, NdIcons.Close, stringResource(Res.string.selection_close))
        },
        actions = {
            NdIconButton(
                { selection.perform(EpisodeSelectionAction.MARK_PLAYED) },
                NdIcons.DoneAll,
                stringResource(Res.string.action_mark_played),
                enabled = !busy,
            )
            Box {
                NdIconButton({ menu = true }, NdIcons.MoreVert, stringResource(Res.string.action_more))
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_mark_unplayed)) },
                        enabled = !busy,
                        onClick = {
                            menu = false
                            selection.perform(EpisodeSelectionAction.MARK_UNPLAYED)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.selection_select_all)) },
                        onClick = {
                            menu = false
                            onSelectAll()
                        },
                    )
                }
            }
        },
    )
}

/** Attach above the focused row so selection shortcuts also work with a hardware keyboard. */
public fun Modifier.episodeSelectionKeys(
    selection: EpisodeSelectionController?,
    onSelectAll: () -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || selection?.ids?.value.isNullOrEmpty()) return@onPreviewKeyEvent false
        when {
            event.key == Key.Escape -> {
                selection?.clear()
                true
            }

            event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed) -> {
                onSelectAll()
                true
            }

            else -> {
                false
            }
        }
    }
