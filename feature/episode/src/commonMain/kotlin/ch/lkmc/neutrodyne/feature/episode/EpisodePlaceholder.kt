// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.feature.episode.resources.episode_arrives_later
import ch.lkmc.neutrodyne.feature.episode.resources.episode_placeholder_title
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.episode.resources.Res as EpisodeRes

/**
 * M1a placeholder for the episode-detail slice, which replaces this composable with the real
 * route: the feed's row taps push `EpisodeKey` today, and nav3's provider crashes on a key no
 * module contributes — so the placeholder only needs to keep navigation alive (94-1's rule).
 */
@Composable
internal fun EpisodePlaceholder() {
    val title = stringResource(EpisodeRes.string.episode_placeholder_title)
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = title)
        NdEmptyState(
            icon = NdIcons.PlayArrow,
            title = title,
            body = stringResource(EpisodeRes.string.episode_arrives_later),
        )
    }
}
