// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.BindEpisodeSelection
import ch.lkmc.neutrodyne.core.ui.DownloadAllDialogs
import ch.lkmc.neutrodyne.core.ui.DownloadDialogs
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRouteResult
import ch.lkmc.neutrodyne.core.ui.MarkFeedPlayedDialog
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.net_other
import ch.lkmc.neutrodyne.core.ui.resources.podcast_removed
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.core.ui.whenOutcome
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The `PodcastKey` route (08 Podcast detail): the key-scoped ViewModel comes from MetroX's manual
 * assisted factory, navigation and external URLs route through [dispatchEpisodeRoute], and the
 * gone-state pops after the "removed" snackbar (08 Podcast detail states).
 */
@Composable
internal fun PodcastRoute(key: PodcastKey) {
    val viewModel =
        assistedMetroViewModel<PodcastViewModel, PodcastViewModel.Factory> {
            create(key.podcastId)
        }
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible()
        onStopOrDispose { viewModel.onHidden() }
    }
    DownloadDialogs(viewModel.downloads)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val markPlayed by viewModel.markPlayed.state.collectAsStateWithLifecycle()
    val consent by viewModel.playback.meteredConsent.collectAsStateWithLifecycle()
    val items = viewModel.feed.collectAsLazyPagingItems()
    val live = viewModel.live.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val noHandler = rememberNoHandlerFallback()
    var pending by remember { mutableStateOf<PendingUnsubscribe?>(null) }
    var passwordPrompt by remember { mutableStateOf(false) }

    // 08 Podcast detail states: `observePodcast` emitting null means the podcast was removed
    // elsewhere — pop with the snackbar.
    LaunchedEffect(state.gone) {
        if (state.gone) {
            snackbar.showSnackbar(getString(Res.string.podcast_removed))
            navigator.pop()
        }
    }

    DownloadAllDialogs(viewModel.downloadAll)
    BindEpisodeSelection(viewModel.episodeSelection)

    PodcastScreen(
        selection = viewModel.episodeSelection,
        onPlaySelectionNext = viewModel::playSelectionNext,
        onPlaySelectionLast = viewModel::playSelectionLast,
        state = state,
        items = items,
        live = live,
        onVisibleIds = viewModel::onVisibleIds,
        pendingUnsubscribe = pending,
        onRefresh = viewModel::onRefresh,
        onOpenSettings = { navigator.pushDetail(PodcastSettingsKey(key.podcastId)) },
        onFiltersChange = viewModel::onFiltersChange,
        onOrderChange = viewModel::setOrder,
        onAction = { action ->
            if (action is EpisodeAction.Open || action is EpisodeAction.OpenPodcast) viewModel.episodeSelection.clear()
            when (dispatchEpisodeRoute(action, navigator, urls)) {
                EpisodeRouteResult.HANDLED -> {
                    if (action is EpisodeAction.WatchOnYouTube) {
                        viewModel.externalOpen.opened(
                            action.episodeId,
                            action.mode,
                        )
                    }
                }

                // Only `WatchOnYouTube` can fail today: offer its watch URL for copying.
                EpisodeRouteResult.NO_HANDLER -> {
                    (action as? EpisodeAction.WatchOnYouTube)
                        ?.let { YouTubeLinks.watch(it.videoId) }
                        ?.let(noHandler)
                }

                EpisodeRouteResult.UNHANDLED -> {
                    viewModel.onRowAction(action)
                }
            }
        },
        onLoadOlder = viewModel::loadOlder,
        onRetryFeed = viewModel::retryFeed,
        onEnterCredentials = { credentials ->
            scope.launch {
                viewModel.setCredentials(credentials)?.whenOutcome(
                    onSuccess = {},
                    onFailure = { error ->
                        snackbar.showSnackbar(addPodcastErrorText(error).resolve())
                    },
                )
            }
        },
        onDownloadAll = { viewModel.downloadAll.open(FeedSource.Podcast(key.podcastId)) },
        onMarkAllPlayedClick = viewModel::markAllPlayed,
        onUnsubscribeRequest = {
            scope.launch {
                val title = state.detail?.displayTitle ?: return@launch
                // A failed count read must not crash — and the dialog can't show without it.
                // The read isn't a write, so it reports the generic load failure.
                val count =
                    suspendRunCatching { viewModel.downloadedCount() }.getOrNull() ?: run {
                        snackbar.showSnackbar(getString(Res.string.net_other))
                        return@launch
                    }
                pending = PendingUnsubscribe(title, count)
            }
        },
        onConfirmUnsubscribe = {
            pending = null
            viewModel.unsubscribe()
        },
        onDismissUnsubscribe = { pending = null },
        onRemoveGroup = viewModel::removeFromGroup,
    )

    markPlayed?.let { dialog ->
        MarkFeedPlayedDialog(
            dialog,
            viewModel.markPlayed::select,
            viewModel.markPlayed::confirm,
            viewModel.markPlayed::dismiss,
        )
    }

    if (consent != null) {
        MeteredConsentDialog(
            onOnce = viewModel.playback::consentOnce,
            onAlways = viewModel.playback::consentAlways,
            onDismiss = viewModel.playback::consentDismiss,
        )
    }

    // The playback snackbar's Enter action and the banner share this dialog: the screen's
    // podcast owns the credentials either way.
    if (passwordPrompt) {
        CredentialsDialog(
            onSubmit = { credentials ->
                passwordPrompt = false
                scope.launch {
                    viewModel.setCredentials(credentials)?.whenOutcome(
                        onSuccess = {},
                        onFailure = { error ->
                            snackbar.showSnackbar(addPodcastErrorText(error).resolve())
                        },
                    )
                }
            },
            onDismiss = { passwordPrompt = false },
        )
    }

    ShowUserMessages(
        state.messages,
        viewModel::onMessageShown,
        // Membership Undo stays in the ViewModel; playback actions resolve through the helper.
        onMessageAction = { id ->
            viewModel.externalOpen.onAction(id)
            viewModel.onMessageAction(id)
            when (val action = viewModel.playback.onMessageAction(id)) {
                is PlaybackMessageAction.RetryStart -> {
                    Unit
                }

                PlaybackMessageAction.OpenPlaybackSettings -> {
                    navigator.pushDetail(SettingsKey(SettingsPage.PLAYBACK))
                }

                is PlaybackMessageAction.EnterPassword -> {
                    passwordPrompt = true
                }

                is PlaybackMessageAction.WatchOnYouTube -> {
                    scope.launch {
                        val url = viewModel.youtubeWatchUrl(action.episodeId)
                        if (url != null) {
                            if (urls.open(url) == OpenResult.NO_HANDLER) {
                                noHandler(url)
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
