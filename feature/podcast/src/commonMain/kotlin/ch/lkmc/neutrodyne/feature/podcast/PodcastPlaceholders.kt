// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.podcast_settings
import ch.lkmc.neutrodyne.feature.podcast.resources.podcast_arrives_later
import ch.lkmc.neutrodyne.feature.podcast.resources.podcast_placeholder_title
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.podcast.resources.Res as PodcastRes

/**
 * M1a placeholders for the Podcast slice (#96), which replaces these composables with the real
 * routes: the Library's tile tap and Podcast-settings menu push `PodcastKey`/`PodcastSettingsKey`
 * today, and nav3's provider crashes on a key no module contributes — so the placeholders only
 * need to keep navigation alive.
 */
@Composable
internal fun PodcastPlaceholder() =
    PodcastPlaceholderScreen(
        title = stringResource(PodcastRes.string.podcast_placeholder_title),
        icon = NdIcons.RssFeed,
    )

@Composable
internal fun PodcastSettingsPlaceholder() =
    PodcastPlaceholderScreen(
        title = stringResource(Res.string.podcast_settings),
        icon = NdIcons.Settings,
    )

@Composable
private fun PodcastPlaceholderScreen(
    title: String,
    icon: ImageVector,
) {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = title)
        NdEmptyState(
            icon = icon,
            title = title,
            body = stringResource(PodcastRes.string.podcast_arrives_later),
        )
    }
}
