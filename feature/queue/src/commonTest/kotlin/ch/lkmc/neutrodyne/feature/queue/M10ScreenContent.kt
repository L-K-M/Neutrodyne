// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow

@Composable
internal fun M10ScreenContent(variant: Int) {
    val state = UpNextUiState(loaded = true, upNext = (1L..4).map { UpNextItem(it, testEpisodeRow(it)) })
    UpNextScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
}
