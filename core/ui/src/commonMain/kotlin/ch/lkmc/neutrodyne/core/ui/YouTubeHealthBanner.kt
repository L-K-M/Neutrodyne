// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.youtube_breaker_banner
import ch.lkmc.neutrodyne.core.ui.resources.youtube_try_now
import org.jetbrains.compose.resources.stringResource

/** M9a has no runtime updater. The health owner handles the single half-open trial. */
@Composable
fun YouTubeHealthBanner(
    isOpen: Boolean,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isOpen) return
    Column(modifier) {
        Text(stringResource(Res.string.youtube_breaker_banner))
        TextButton(onClick = retry) { Text(stringResource(Res.string.youtube_try_now)) }
    }
}
