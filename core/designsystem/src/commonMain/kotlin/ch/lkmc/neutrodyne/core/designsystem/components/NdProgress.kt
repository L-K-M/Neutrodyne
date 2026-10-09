// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Progress indicators (08 Nd wrappers). */
public object NdProgress {
    /**
     * The 3 dp episode-progress line (08 EpisodeRow "Progress" slot): determinate for
     * [progress] ∈ 0..1, indeterminate when `null`.
     */
    @Composable
    public fun Linear(
        progress: Float?,
        modifier: Modifier = Modifier,
    ) {
        if (progress == null) {
            LinearProgressIndicator(modifier = modifier, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = modifier,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/** The row's progress-line height (08 EpisodeRow). */
public val NdProgressLineHeight: Dp = 3.dp
