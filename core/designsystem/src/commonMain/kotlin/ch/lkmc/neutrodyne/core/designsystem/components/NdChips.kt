// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A filter chip (08 Nd wrappers): [label] with an optional [leadingIcon] (the sort/filter
 * glyph); selected state follows [selected]. All strings arrive resolved.
 */
@Composable
public fun NdFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon =
            if (leadingIcon != null) {
                { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(ICON_SIZE)) }
            } else {
                null
            },
    )
}

/**
 * An assist chip (08 Nd wrappers): [label] with an optional [leadingIcon]; action chips like
 * Discover's "Add by URL" carry no selected state. All strings arrive resolved.
 */
@Composable
public fun NdAssistChip(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon =
            if (leadingIcon != null) {
                { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(ICON_SIZE)) }
            } else {
                null
            },
    )
}

private val ICON_SIZE = 18.dp
