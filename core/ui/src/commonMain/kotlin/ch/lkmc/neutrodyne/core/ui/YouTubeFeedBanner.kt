// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.youtube_feed_outage
import ch.lkmc.neutrodyne.core.ui.resources.youtube_feed_retry
import org.jetbrains.compose.resources.stringResource

@Composable
public fun YouTubeFeedBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(stringResource(Res.string.youtube_feed_outage)) },
        trailingContent = { TextButton(onClick = onRetry) { Text(stringResource(Res.string.youtube_feed_retry)) } },
    )
}
