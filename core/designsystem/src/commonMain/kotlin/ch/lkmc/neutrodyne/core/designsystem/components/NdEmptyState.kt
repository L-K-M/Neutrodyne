// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Centre empty state (08 Empty states): a subdued 48 dp symbol, a [title], supporting [body] and
 * one optional primary action. All strings arrive resolved; the caller passes Compose resources.
 */
@Composable
public fun NdEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(EMPTY_ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = EMPTY_ICON_ALPHA),
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
    }
}

private val EMPTY_ICON_SIZE = 48.dp
private const val EMPTY_ICON_ALPHA = 0.6f
private val ICON_TITLE_GAP = 16.dp
private val TITLE_BODY_GAP = 8.dp
private val BODY_ACTION_GAP = 24.dp
