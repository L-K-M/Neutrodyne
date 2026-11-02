// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.download_all
import ch.lkmc.neutrodyne.core.ui.resources.download_all_capped
import ch.lkmc.neutrodyne.core.ui.resources.download_all_confirm
import ch.lkmc.neutrodyne.core.ui.resources.download_all_empty
import ch.lkmc.neutrodyne.core.ui.resources.download_confirm
import ch.lkmc.neutrodyne.core.ui.resources.download_mobile_confirm
import ch.lkmc.neutrodyne.core.ui.resources.download_mobile_once
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import org.jetbrains.compose.resources.stringResource

@Composable
public fun DownloadAllDialogs(controller: DownloadAllController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val messages by controller.messages.flow.collectAsStateWithLifecycle()
    state?.let {
        DownloadAllDialog(
            it,
            controller::confirm,
            controller::confirmMetered,
            controller::dismiss,
        )
    }
    ShowUserMessages(
        messages,
        controller.messages::shown,
    )
}

@Composable
public fun DownloadAllDialog(
    state: DownloadAllState,
    onConfirm: () -> Unit,
    onMobileData: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val estimate = state.estimate
    NdDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.download_all),
        confirm =
            if (!state.busy &&
                estimate?.episodeIds?.isNotEmpty() == true
            ) {
                NdDialogAction(
                    stringResource(
                        if (state.metered ==
                            null
                        ) {
                            Res.string.download_confirm
                        } else {
                            Res.string.download_mobile_once
                        },
                    ),
                    onClick = if (state.metered == null) onConfirm else onMobileData,
                )
            } else {
                null
            },
        dismiss =
            if (state.busy && estimate != null) {
                null
            } else {
                NdDialogAction(
                    stringResource(Res.string.action_cancel),
                    onClick = onDismiss,
                )
            },
    ) {
        Column {
            state.error?.let { Text(it.asString()) }
            if (estimate != null) {
                val metered = state.metered
                Text(
                    stringResource(
                        if (metered ==
                            null
                        ) {
                            Res.string.download_all_confirm
                        } else {
                            Res.string.download_mobile_confirm
                        },
                        metered?.count ?: estimate.episodeIds.size,
                        downloadSizeText(metered?.knownBytes ?: estimate.knownBytes).asString(),
                        metered?.unknownSizeCount ?: estimate.unknownSizeCount,
                    ),
                )
                if (estimate.capped) {
                    Text(
                        stringResource(
                            Res.string.download_all_capped,
                            estimate.episodeIds.size,
                            estimate.totalCandidates,
                        ),
                    )
                }
                if (estimate.episodeIds.isEmpty()) Text(stringResource(Res.string.download_all_empty))
            } else if (state.busy) {
                ch.lkmc.neutrodyne.core.designsystem.components
                    .NdLoading()
            }
        }
    }
}
