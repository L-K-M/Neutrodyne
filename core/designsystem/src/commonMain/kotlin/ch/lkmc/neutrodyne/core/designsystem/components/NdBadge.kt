// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Count badge (08 CoverTile): [count] capped at "99+", nothing when zero. */
@Composable
public fun NdBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Badge(modifier = modifier) {
        Text(
            if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private const val BADGE_CAP = 99
