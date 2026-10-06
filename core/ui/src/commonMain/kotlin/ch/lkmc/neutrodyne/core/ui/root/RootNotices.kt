// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dismiss
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_message
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_unknown_device
import ch.lkmc.neutrodyne.core.ui.resources.group_count
import ch.lkmc.neutrodyne.core.ui.resources.notice_first_run_body
import ch.lkmc.neutrodyne.core.ui.resources.notice_first_run_off
import ch.lkmc.neutrodyne.core.ui.resources.notice_first_run_ok
import ch.lkmc.neutrodyne.core.ui.resources.play
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.core.ui.resources.remote_session_detail
import ch.lkmc.neutrodyne.core.ui.resources.remote_session_title
import ch.lkmc.neutrodyne.core.ui.resources.review
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The banners the root renders above the content (08 Banners and the startup gate). Held sync
 * changes show on Feeds and Library only; the first-run card is an `NdBanner`, not a key.
 * `VERIFICATION_ENFORCEMENT` is pushed as `VerificationNoticeKey` by the root itself, not drawn here.
 */
@Composable
internal fun RootBanners(
    state: RootUiState,
    actions: RootActions,
    navigator: AppNavigator,
    onFeedsOrLibrary: Boolean,
) {
    val held = state.heldChanges
    if (held != null && onFeedsOrLibrary) {
        val device = held.deviceName ?: stringResource(Res.string.held_changes_unknown_device)
        // The counts are real plurals so e.g. "1 podcast" reads correctly in every locale.
        val podcasts = pluralStringResource(Res.plurals.podcast_count, held.podcastCount, held.podcastCount)
        val groups = pluralStringResource(Res.plurals.group_count, held.groupCount, held.groupCount)
        NdBanner(
            message = stringResource(Res.string.held_changes_message, device, podcasts, groups),
            primary = NdDialogAction(
                label = stringResource(Res.string.review),
                onClick = { navigator.push(SyncHeldChangesKey(held.id)) },
            ),
        )
    }

    if (state.notice == RootNotice.FIRST_RUN_CHOICE) {
        NdBanner(
            message = stringResource(Res.string.notice_first_run_body),
            primary = NdDialogAction(
                label = stringResource(Res.string.notice_first_run_ok),
                onClick = { actions.dismissNotice(RootNotice.FIRST_RUN_CHOICE) },
            ),
            secondary = NdDialogAction(
                label = stringResource(Res.string.notice_first_run_off),
                onClick = actions.disableUpdateChecks,
            ),
        )
    }
}

/**
 * The MS3 "Continue on this device" card ([remoteSession]) — sits where the mini player would,
 * so it never overlaps it (the shell keeps it `null` while something plays locally).
 */
@Composable
internal fun ContinueOnThisDeviceCard(
    remoteSession: RemoteSessionCard,
    actions: RootActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(CARD_MARGIN)) {
        Column(Modifier.padding(CARD_PADDING)) {
            Text(
                stringResource(Res.string.remote_session_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    Res.string.remote_session_detail,
                    remoteSession.title,
                    remoteSession.positionText,
                    remoteSession.deviceName,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CARD_BUTTON_GAP)) {
                NdOutlinedButton(
                    label = stringResource(Res.string.dismiss),
                    onClick = actions.dismissRemoteSession,
                )
                NdButton(
                    label = stringResource(Res.string.play),
                    onClick = actions.continueHere,
                )
            }
        }
    }
}

private val CARD_MARGIN = 8.dp
private val CARD_PADDING = 16.dp
private val CARD_BUTTON_GAP = 12.dp
