// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.EnterPasswordDialog
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRouteResult
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

/** Queue row starts use playEpisode, retaining the existing context when the row is queued. */
@Composable
internal fun UpNextRoute(viewModel: UpNextViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls
    val clipboard = LocalClipboardManager.current
    val consent by viewModel.playbackActions.meteredConsent.collectAsStateWithLifecycle()
    val passwordEpisode by viewModel.passwordEpisode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    UpNextScreen(
        state = state,
        onAction = { action ->
            when (dispatchEpisodeRoute(action, navigator, urls)) {
                EpisodeRouteResult.UNHANDLED -> {
                    viewModel.onAction(action)
                }

                EpisodeRouteResult.NO_HANDLER -> {
                    check(action is EpisodeAction.WatchOnYouTube)
                    viewModel.onNoHandler(YouTubeLinks.watch(action.videoId))
                }

                EpisodeRouteResult.HANDLED -> {
                    Unit
                }
            }
        },
        onMove = viewModel::move,
        onRemove = viewModel::remove,
        onClear = viewModel::clear,
        onStopAfter = viewModel::stopAfter,
        onPlayGroup = viewModel::playGroup,
        onDiscover = { navigator.selectTab(DiscoverKey) },
        onRetry = viewModel::retry,
        onVisibleIds = viewModel::onVisibleIds,
        topBarActions = { SettingsGearButton() },
    )
    ShowUserMessages(state.messages, viewModel::onMessageShown) { id ->
        when (val action = viewModel.onPlaybackMessageAction(id)) {
            PlaybackMessageAction.OpenPlaybackSettings -> {
                navigator.pushDetail(SettingsKey(SettingsPage.PLAYBACK))
            }

            is PlaybackMessageAction.WatchOnYouTube -> {
                scope.launch {
                    viewModel.watchLink(action.episodeId)?.let { url ->
                        if (urls.open(url) == OpenResult.NO_HANDLER) viewModel.onNoHandler(url)
                    }
                }
            }

            else -> {
                viewModel.consumeCopyLink(id)?.let { clipboard.setText(AnnotatedString(it)) } ?: viewModel.undo(id)
            }
        }
    }
    if (consent != null) {
        MeteredConsentDialog(
            viewModel.playbackActions::consentOnce,
            viewModel.playbackActions::consentAlways,
            viewModel.playbackActions::consentDismiss,
        )
    }
    if (passwordEpisode != null) EnterPasswordDialog(viewModel::submitPassword, viewModel::dismissPassword)
}
