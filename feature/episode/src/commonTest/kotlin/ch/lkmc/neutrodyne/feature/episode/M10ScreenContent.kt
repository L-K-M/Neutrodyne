// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail

@Composable
internal fun M10ScreenContent(variant: Int) {
    EpisodeScreen(EpisodeUiState(loaded = true, episode = testEpisodeDetail(1)), {}, {}, {}, {})
}
