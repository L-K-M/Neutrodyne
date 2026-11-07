// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.ui.resources.group_count
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_unknown_device
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_and_more
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_apply
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_apply_here
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_decide_later
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_failed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_keep_mine
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_keep_mine_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_removals_both
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_title
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.HeldResolveResult
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncController
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * The held mass-change review behind `SyncHeldChangesKey` (08 Held-removal review, 10 Mass-change
 * guard step 3). The prompt's disappearance from [SyncController.heldChanges] means another
 * surface already decided the batch, so the route closes itself; nothing is decided on its own.
 */
@Composable
internal fun SyncHeldChangesRoute(
    controller: SyncController,
    id: Long,
) {
    val prompts by controller.heldChanges.collectAsStateWithLifecycle()
    val prompt = prompts.firstOrNull { it.id == id }
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val key = remember(id) { SyncHeldChangesKey(id) }

    // A decision from anywhere removes the row; nothing left to review closes the route.
    LaunchedEffect(prompt) {
        if (prompt == null) navigator.pop(key)
    }
    if (prompt == null) return
    HeldChangesDialogContent(
        prompt = prompt,
        busy = busy,
        failed = failed,
        onResolve = { resolution ->
            scope.launch {
                busy = true
                failed = false
                when (controller.resolveHeld(id, resolution)) {
                    HeldResolveResult.Resolved, HeldResolveResult.Gone -> {
                        navigator.pop(key)
                    }

                    HeldResolveResult.Failed -> {
                        // The batch stays held: keep the prompt actionable instead of
                        // closing as though the decision went through.
                        busy = false
                        failed = true
                    }
                }
            }
        },
    )
}

/**
 * The dialog body [SyncHeldChangesRoute] renders inside the nav host's `Dialog` window (08
 * Held-removal review): the batch's summary, then the three choices stacked — Apply in the error
 * colour, Keep mine with its helper, Decide later as the focus default so Enter never removes.
 */
@Composable
internal fun HeldChangesDialogContent(
    prompt: MassChangePrompt,
    busy: Boolean,
    failed: Boolean,
    onResolve: (HeldResolution) -> Unit,
) {
    val names = prompt.podcastTitles + prompt.groupNames
    val decideLaterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { decideLaterFocus.requestFocus() }
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        BoxWithConstraints {
            val maxListHeight = maxHeight / 2
            // The whole body must scroll when large text leaves no room for the actions.
            Column(Modifier.padding(DIALOG_PADDING).verticalScroll(rememberScrollState())) {
                Text(
                    heldTitle(prompt),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                )
                Spacer(Modifier.height(BODY_GAP))
                Text(stringResource(Res.string.sync_held_apply_here), style = MaterialTheme.typography.bodyLarge)
                if (names.isNotEmpty()) {
                    Spacer(Modifier.height(LIST_GAP))
                    // The list caps at half the window height and scrolls beyond it (08).
                    Column(
                        Modifier.heightIn(max = maxListHeight).verticalScroll(rememberScrollState()),
                    ) {
                        for (name in names.take(MAX_LISTED)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                        val remaining = names.size - MAX_LISTED
                        if (remaining > 0) {
                            Text(
                                stringResource(Res.string.sync_held_and_more, remaining),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (failed) {
                    Spacer(Modifier.height(LIST_GAP))
                    Text(
                        stringResource(Res.string.sync_held_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = ACTIONS_GAP),
                    horizontalAlignment = Alignment.End,
                ) {
                    NdTextButton(
                        label = stringResource(Res.string.sync_held_apply),
                        onClick = { onResolve(HeldResolution.APPLY) },
                        role = NdActionRole.DESTRUCTIVE,
                        enabled = !busy,
                    )
                    NdTextButton(
                        label = stringResource(Res.string.sync_held_keep_mine),
                        onClick = { onResolve(HeldResolution.KEEP_MINE) },
                        enabled = !busy,
                    )
                    Text(
                        stringResource(Res.string.sync_held_keep_mine_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = BUTTON_LABEL_INSET),
                    )
                    NdTextButton(
                        label = stringResource(Res.string.sync_held_decide_later),
                        onClick = { onResolve(HeldResolution.DECIDE_LATER) },
                        enabled = !busy,
                        modifier = Modifier.focusRequester(decideLaterFocus),
                    )
                }
            }
        }
    }
}

/** "{device} removed {n} podcasts [and {g} groups]"; the groups part only when `g > 0` (08). */
@Composable
private fun heldTitle(prompt: MassChangePrompt): String {
    val device = prompt.deviceName ?: stringResource(UiRes.string.held_changes_unknown_device)
    val podcasts =
        pluralStringResource(UiRes.plurals.podcast_count, prompt.podcastTitles.size, prompt.podcastTitles.size)
    val groups = pluralStringResource(UiRes.plurals.group_count, prompt.groupNames.size, prompt.groupNames.size)
    val removals =
        when {
            prompt.podcastTitles.isNotEmpty() && prompt.groupNames.isNotEmpty() -> {
                stringResource(Res.string.sync_held_removals_both, podcasts, groups)
            }

            prompt.groupNames.isNotEmpty() -> {
                groups
            }

            else -> {
                podcasts
            }
        }
    return stringResource(Res.string.sync_held_title, device, removals)
}

private val DIALOG_PADDING = 24.dp
private val BODY_GAP = 16.dp
private val LIST_GAP = 8.dp
private val ACTIONS_GAP = 16.dp
private val BUTTON_LABEL_INSET = 12.dp
private const val MAX_LISTED = 10
