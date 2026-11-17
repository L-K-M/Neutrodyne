// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.KeyBank
import ch.lkmc.neutrodyne.core.designsystem.radio.MomentaryKey

/**
 * One dialog action button: resolved [label] plus the callback. [role] marks destructive
 * confirms (08 Dialogs: they draw in the error colour); every other action stays NORMAL.
 */
public data class NdDialogAction(
    val label: String,
    val role: NdActionRole = NdActionRole.NORMAL,
    val onClick: () -> Unit,
)

/**
 * The app's dialog (08 Nd wrappers, PO-55): a paper card in a brass rim with the title in
 * Courier and its actions as momentary piano keys. It is a `BasicAlertDialog`, so on the desktop it
 * is window-backed and renders above the expanded player, which is why navigation-level dialogs
 * use it too. The text body is bounded by the dialog's layout and scrolls, so long content (the
 * Licences detail's full terms) stays reachable at any font size.
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
@OptIn(ExperimentalMaterial3Api::class)
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
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        Column(
            Modifier
                .widthIn(min = DIALOG_MIN_WIDTH, max = DIALOG_MAX_WIDTH)
                .shadow(6.dp, DialogShape)
                .clip(DialogShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.5.dp, Brass, DialogShape)
                .padding(DIALOG_PADDING),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(24.dp),
                )
            }
            if (title != null) {
                Text(
                    title,
                    Modifier
                        .align(if (icon != null) Alignment.CenterHorizontally else Alignment.Start)
                        .padding(bottom = 12.dp)
                        .semantics { heading() },
                    color = Ink,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            // The body takes what the title and keys leave; long bodies scroll inside it.
            Box(Modifier.weight(1f, fill = false)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
                }
            }
            if (confirm != null || dismiss != null) {
                KeyBank(Modifier.align(Alignment.End).padding(top = 18.dp)) {
                    for (action in listOfNotNull(dismiss, confirm)) {
                        MomentaryKey(
                            action.label,
                            action.onClick,
                            destructive = action.role == NdActionRole.DESTRUCTIVE,
                        )
                    }
                }
            }
        }
    }
}

private val DialogShape = RoundedCornerShape(6.dp)
private val DIALOG_MIN_WIDTH = 280.dp
private val DIALOG_MAX_WIDTH = 560.dp
private val DIALOG_PADDING = 22.dp
