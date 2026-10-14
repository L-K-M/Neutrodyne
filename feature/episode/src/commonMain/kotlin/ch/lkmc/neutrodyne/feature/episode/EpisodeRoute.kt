// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRouteResult
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

/**
 * The `EpisodeKey` route (08 Episode detail): navigation and external URLs go through
 * [dispatchEpisodeRoute], repository writes land in the ViewModel.
 */
@Composable
internal fun EpisodeRoute(
    key: EpisodeKey,
    viewModel: EpisodeViewModel =
        assistedMetroViewModel<EpisodeViewModel, EpisodeViewModel.Factory> {
            create(key.episodeId)
        },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls
    val clipboard = LocalClipboardManager.current

    EpisodeScreen(
        state = state,
        onAction = { action ->
            when (dispatchEpisodeRoute(action, navigator, urls)) {
                EpisodeRouteResult.UNHANDLED -> {
                    viewModel.onAction(action)
                }

                EpisodeRouteResult.NO_HANDLER -> {
                    // WatchOnYouTube is dispatchEpisodeRoute's only external action.
                    check(action is EpisodeAction.WatchOnYouTube)
                    viewModel.onLinkOpenFailed(YouTubeLinks.watch(action.videoId))
                }

                EpisodeRouteResult.HANDLED -> {
                    Unit
                }
            }
        },
        onFavorite = viewModel::setFavorite,
        onOpenLink = { url ->
            if (urls.open(url) == OpenResult.NO_HANDLER) viewModel.onLinkOpenFailed(url)
        },
    )

    ShowUserMessages(
        state.messages,
        viewModel::onMessageShown,
        onMessageAction = { id ->
            viewModel.onMessageAction(id)?.let { clipboard.setText(AnnotatedString(it)) }
        },
    )
}
