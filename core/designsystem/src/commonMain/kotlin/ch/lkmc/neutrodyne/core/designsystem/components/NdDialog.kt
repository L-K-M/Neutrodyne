// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties

/**
 * One dialog action button: resolved [label] plus the callback.
 */
public data class NdDialogAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * `AlertDialog` wrapper (08 Nd wrappers). On desktop Compose `AlertDialog` is window-backed, so it
 * renders above the expanded player, which is why navigation-level dialogs use it too. The text
 * body is bounded by the dialog's layout and scrolls, so long content (the Licences detail's full
 * terms) stays reachable at any font size.
 */
@Composable
public fun NdDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    text: String? = null,
    confirm: NdDialogAction? = null,
    dismiss: NdDialogAction? = null,
    properties: DialogProperties = DialogProperties(),
) {
    NdDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        icon = icon,
        title = title,
        confirm = confirm,
        dismiss = dismiss,
        properties = properties,
    ) {
        if (text != null) {
            Box(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * [NdDialog] with a custom body (choice lists, forms). The body slot is bounded by the dialog's
 * layout; a scrollable [content] (for example `Column` with `verticalScroll`) keeps long content
 * reachable.
 */
@Composable
public fun NdDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    confirm: NdDialogAction? = null,
    dismiss: NdDialogAction? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        confirmButton = {
            if (confirm != null) NdTextButton(label = confirm.label, onClick = confirm.onClick)
        },
        dismissButton = {
            if (dismiss != null) NdTextButton(label = dismiss.label, onClick = dismiss.onClick)
        },
        icon = { if (icon != null) Icon(icon, contentDescription = null) },
        title = {
            if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall)
        },
        text = content,
        properties = properties,
    )
}
