// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.DownloadDialogs
import ch.lkmc.neutrodyne.core.ui.EnterPasswordDialog
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRouteResult
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch

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
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible()
        onStopOrDispose { viewModel.onHidden() }
    }
    DownloadDialogs(viewModel.downloads)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val consent by viewModel.playback.meteredConsent.collectAsStateWithLifecycle()
    val credentials by viewModel.pendingCredentials.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

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
                    if (action is EpisodeAction.WatchOnYouTube) {
                        viewModel.externalOpen.opened(
                            action.episodeId,
                            action.mode,
                        )
                    }
                }
            }
        },
        onTimestamp = viewModel::onTimestamp,
        onFavorite = viewModel::setFavorite,
        onOpenLink = { url ->
            if (urls.open(url) == OpenResult.NO_HANDLER) viewModel.onLinkOpenFailed(url)
        },
    )

    if (consent != null) {
        MeteredConsentDialog(
            onOnce = viewModel.playback::consentOnce,
            onAlways = viewModel.playback::consentAlways,
            onDismiss = viewModel.playback::consentDismiss,
        )
    }

    if (credentials != null) {
        EnterPasswordDialog(
            onSubmit = viewModel::onCredentials,
            onDismiss = viewModel::onCredentialsDismissed,
        )
    }

    ShowUserMessages(
        state.messages,
        viewModel::onMessageShown,
        // Copy-link messages return their URL; playback actions resolve through the helper.
        onMessageAction = { id ->
            viewModel.externalOpen.onAction(id)
            viewModel.onMessageAction(id)?.let { clipboard.setText(AnnotatedString(it)) }
            when (val action = viewModel.playback.onMessageAction(id)) {
                is PlaybackMessageAction.RetryStart -> {
                    Unit
                }

                PlaybackMessageAction.OpenPlaybackSettings -> {
                    navigator.pushDetail(SettingsKey(SettingsPage.PLAYBACK))
                }

                is PlaybackMessageAction.EnterPassword -> {
                    viewModel.onEnterPassword(action.episodeId)
                }

                is PlaybackMessageAction.WatchOnYouTube -> {
                    scope.launch {
                        val url = viewModel.youtubeWatchUrl(action.episodeId)
                        if (url != null) {
                            if (urls.open(url) == OpenResult.NO_HANDLER) {
                                viewModel.onLinkOpenFailed(url)
                            } else {
                                viewModel.externalOpen.opened(action.episodeId)
                            }
                        }
                    }
                }

                null -> {
                    Unit
                }
            }
        },
    )
}
