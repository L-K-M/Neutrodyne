// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton

/**
 * The centred list-screen empty state (08 Empty states): a 96 dp symbol in `primary`, [title]
 * `titleMedium`, [body] `bodyMedium`, one primary and up to two secondary actions; max width
 * 400 dp. All strings arrive resolved. (`NdEmptyState` stays the startup gate's variant.)
 */
@Composable
public fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp).widthIn(max = 400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(EMPTY_ICON_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(ICON_TITLE_GAP))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(TITLE_BODY_GAP))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(BODY_ACTION_GAP))
            NdButton(label = actionLabel, onClick = onAction)
        }
        if (secondaryLabel != null && onSecondary != null) {
            NdTextButton(label = secondaryLabel, onClick = onSecondary)
        }
        if (tertiaryLabel != null && onTertiary != null) {
            NdTextButton(label = tertiaryLabel, onClick = onTertiary)
        }
    }
}

private val EMPTY_ICON_SIZE = 96.dp
private val ICON_TITLE_GAP = 16.dp
private val TITLE_BODY_GAP = 8.dp
private val BODY_ACTION_GAP = 24.dp
