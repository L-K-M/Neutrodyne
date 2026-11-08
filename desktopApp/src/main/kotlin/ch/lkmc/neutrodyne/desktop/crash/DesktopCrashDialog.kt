// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_dont_send
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_message
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_send_email
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_show_report
import ch.lkmc.neutrodyne.desktop.resources.crash_dialog_title
import org.jetbrains.compose.resources.stringResource

/**
 * The per-crash email offer (11 Crash files and the email dialog, 08 Dialogs): "Send by email"
 * is the confirm action, "Don't send" the dismiss one, and "Show report" — which reveals the
 * redacted crash file for attaching without handling it — sits in the body like the metered
 * download dialog's extra choices.
 */
@Composable
internal fun DesktopCrashDialog(
    onShowReport: () -> Unit,
    onSendEmail: () -> Unit,
    onDismiss: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.crash_dialog_title),
        confirm = NdDialogAction(label = stringResource(Res.string.crash_dialog_send_email), onClick = onSendEmail),
        dismiss = NdDialogAction(label = stringResource(Res.string.crash_dialog_dont_send), onClick = onDismiss),
    ) {
        Column {
            Text(
                stringResource(Res.string.crash_dialog_message),
                style = MaterialTheme.typography.bodyMedium,
            )
            NdTextButton(label = stringResource(Res.string.crash_dialog_show_report), onClick = onShowReport)
        }
    }
}
