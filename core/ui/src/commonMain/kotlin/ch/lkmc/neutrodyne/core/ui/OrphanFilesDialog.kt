// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.download_cleanup
import ch.lkmc.neutrodyne.core.ui.resources.download_cleanup_confirm
import ch.lkmc.neutrodyne.core.ui.resources.download_delete
import org.jetbrains.compose.resources.stringResource

@Composable
public fun OrphanFilesDialog(
    bytes: Long,
    working: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    error: UiText? = null,
) {
    NdDialog(
        modifier = modifier,
        onDismissRequest = { if (!working) onDismiss() },
        title = stringResource(Res.string.download_cleanup),
        confirm =
            if (working) {
                null
            } else {
                NdDialogAction(
                    stringResource(Res.string.download_delete),
                    role = NdActionRole.DESTRUCTIVE,
                    onClick = onDelete,
                )
            },
        dismiss =
            if (working) {
                null
            } else {
                NdDialogAction(
                    stringResource(Res.string.action_cancel),
                    onClick = onDismiss,
                )
            },
    ) {
        Column {
            Text(
                stringResource(
                    Res.string.download_cleanup_confirm,
                    downloadSizeText(bytes).asString(),
                ),
            )
            error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        }
    }
}
