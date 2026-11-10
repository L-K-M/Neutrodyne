// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.ui.resources.group_count
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_unknown_device
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_and_more
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_apply
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_decide_later
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_keep_mine
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_keep_mine_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_question
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_held_title_groups
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncController
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * The `SyncHeldChangesKey` dialog for one `MassChangePrompt` (08 Held changes, R7.7, 10
 * Mass-change guard): pushed by the banner's or the status card's Review, never opened on its
 * own. The nav host draws it in the `Dialog` overlay, whose scrim tap, back and Esc pop the
 * entry unanswered — the same outcome as Decide later, which keeps the batch and its banner.
 *
 * The dialog belongs to the (server, account, device) identity and to its row's id, never its
 * position: a resolution on any path or an unlink removes the row and the open dialog follows
 * it; a content update to the same id repaints in place. `resolveHeld` is a fire-and-forget
 * command, so a click only requests the answer and pops — the row and the banner leave when the
 * backend's atomic resolution commits, which is also why the answer re-checks identity and row
 * presence first: a stale click can never resolve a different account's batch.
 */
@Composable
fun SyncHeldChangesDialog(
    controller: SyncController,
    key: SyncHeldChangesKey,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalAppNavigator.current
    val status by controller.status.collectAsStateWithLifecycle()
    val held by controller.heldChanges.collectAsStateWithLifecycle()
    val identity = status.identity()
    val prompt = held.firstOrNull { it.id == key.id }
    LaunchedEffect(identity, prompt != null) {
        if (identity == null || prompt == null) navigator.pop(key)
    }
    if (identity != null && prompt != null) {
        HeldChangesDialogContent(
            prompt = prompt,
            onResolve = { resolution ->
                if (
                    controller.status.value.identity() == identity &&
                    controller.heldChanges.value.any { it.id == key.id }
                ) {
                    controller.resolveHeld(key.id, resolution)
                    navigator.pop(key)
                }
            },
            modifier = modifier,
        )
    }
}

/** The dialog body (08's wording): title counts, the staged names, three stacked buttons. */
@Composable
private fun HeldChangesDialogContent(
    prompt: MassChangePrompt,
    onResolve: (HeldResolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        modifier = modifier,
    ) {
        val scroll = rememberScrollState()
        val bars = LocalScrollbars.current
        // The whole body scrolls, so every title, name and action stays reachable at any
        // window size or font scale.
        Column(
            Modifier
                .padding(DIALOG_PADDING)
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, ndScrollbarColor(bars)),
        ) {
            Text(
                heldTitle(prompt),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            Text(
                stringResource(Res.string.sync_held_question),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = BODY_GAP),
            )
            val items = prompt.podcastTitles + prompt.groupNames
            if (items.isNotEmpty()) {
                val listScroll = rememberScrollState()
                // The staged names are ordinary text — titles and names only, never a feed URL —
                // in their own scroll bounded to half the window height (08), so a long batch
                // can't push the actions off the screen.
                val windowHeight =
                    with(LocalDensity.current) {
                        LocalWindowInfo.current.containerSize.height
                            .toDp()
                    }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = LIST_GAP)
                        .then(
                            if (windowHeight > 0.dp) {
                                Modifier.heightIn(max = windowHeight / 2)
                            } else {
                                Modifier
                            },
                        ).verticalScroll(listScroll),
                ) {
                    items.take(MAX_LISTED).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val more = items.size - MAX_LISTED
                if (more > 0) {
                    Text(
                        pluralStringResource(Res.plurals.sync_held_and_more, more, more),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = LIST_GAP),
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = ACTIONS_GAP),
                verticalArrangement = Arrangement.spacedBy(ACTION_GAP),
            ) {
                NdButton(
                    label = stringResource(Res.string.sync_held_apply),
                    onClick = { onResolve(HeldResolution.APPLY) },
                    modifier = Modifier.fillMaxWidth(),
                )
                NdOutlinedButton(
                    label = stringResource(Res.string.sync_held_keep_mine),
                    onClick = { onResolve(HeldResolution.KEEP_MINE) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.sync_held_keep_mine_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NdTextButton(
                    label = stringResource(Res.string.sync_held_decide_later),
                    onClick = { onResolve(HeldResolution.DECIDE_LATER) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "{device} removed {n} podcasts[ and {g} groups]" — the groups part only when `g > 0` (08). */
@Composable
private fun heldTitle(prompt: MassChangePrompt): String {
    val device = prompt.deviceName ?: stringResource(UiRes.string.held_changes_unknown_device)
    val podcasts =
        pluralStringResource(UiRes.plurals.podcast_count, prompt.podcastTitles.size, prompt.podcastTitles.size)
    if (prompt.groupNames.isEmpty()) {
        return stringResource(Res.string.sync_held_title, device, podcasts)
    }
    val groups =
        pluralStringResource(UiRes.plurals.group_count, prompt.groupNames.size, prompt.groupNames.size)
    return stringResource(Res.string.sync_held_title_groups, device, podcasts, groups)
}

private const val MAX_LISTED = 10
private val DIALOG_PADDING = 24.dp
private val BODY_GAP = 12.dp
private val LIST_GAP = 8.dp
private val ACTIONS_GAP = 20.dp
private val ACTION_GAP = 8.dp
