// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

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
public data class NdDialogAction(val label: String, val onClick: () -> Unit)

/**
 * `AlertDialog` wrapper (08 Nd wrappers). On desktop Compose `AlertDialog` is window-backed, so it
 * renders above the expanded player, which is why navigation-level dialogs use it too.
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
        text = { if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium) },
        properties = properties,
    )
}
