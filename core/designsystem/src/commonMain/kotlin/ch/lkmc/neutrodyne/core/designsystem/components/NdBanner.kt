// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Indeterminate progress indicator (08 Banners and the startup gate). */
@Composable
public fun NdLoading(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier)
}

/**
 * A full-width informational banner (08 Banners and the startup gate): `secondaryContainer`
 * surface with [message], optional [icon] and up to two actions ([primary]/[secondary]).
 * All strings arrive resolved.
 */
@Composable
public fun NdBanner(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: NdDialogAction? = null,
    secondary: NdDialogAction? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BANNER_PADDING, vertical = BANNER_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BANNER_PADDING),
        ) {
            if (icon != null) {
                androidx.compose.material3.Icon(icon, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            secondary?.let { NdTextButton(label = it.label, onClick = it.onClick) }
            primary?.let { NdTextButton(label = it.label, onClick = it.onClick) }
        }
    }
}

private val BANNER_PADDING = 16.dp
