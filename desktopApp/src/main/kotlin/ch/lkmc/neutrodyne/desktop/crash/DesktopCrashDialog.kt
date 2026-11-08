// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_dont_send
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_message
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_send_email
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_show_report
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun DesktopCrashDialog(
    onShowReport: () -> Unit,
    onSendEmail: () -> Unit,
    onDismiss: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.crash_dialog_title),
        text = stringResource(Res.string.crash_dialog_message),
        dismiss = NdDialogAction(label = stringResource(Res.string.crash_dialog_dont_send), onClick = onDismiss),
        confirm = NdDialogAction(label = stringResource(Res.string.crash_dialog_send_email), onClick = onSendEmail),
    )
}
