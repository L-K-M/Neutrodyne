// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AllGroupsKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.BindEpisodeSelection
import ch.lkmc.neutrodyne.core.ui.DownloadAllDialogs
import ch.lkmc.neutrodyne.core.ui.EnterPasswordDialog
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRouteResult
import ch.lkmc.neutrodyne.core.ui.MarkFeedPlayedDialog
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

/**
 * The Feeds destination (08 Feeds): Metro resolves the [FeedsViewModel] per nav entry;
 * [dispatchEpisodeRoute] handles the navigation and external-URL actions — a `NO_HANDLER` open
 * posts the screen's copy-link message — the rest go to the ViewModel's repository calls.
 */
@Composable
internal fun FeedsRoute(viewModel: FeedsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DownloadAllDialogs(viewModel.downloadAll)
    val markPlayed by viewModel.markPlayed.state.collectAsStateWithLifecycle()
    val consent by viewModel.playback.meteredConsent.collectAsStateWithLifecycle()
    val credentials by viewModel.pendingCredentials.collectAsStateWithLifecycle()
    val pageState = rememberSaveableStateHolder()
    val live = viewModel.live.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    BindEpisodeSelection(viewModel.episodeSelection)

    FeedsScreen(
        selection = viewModel.episodeSelection,
        onPlayEpisode = viewModel::playEpisode,
        onPlaySelectionNext = viewModel::playSelectionNext,
        onPlaySelectionLast = viewModel::playSelectionLast,
        state = state,
        pagingFor = viewModel::pagingFor,
        prefsFor = viewModel::prefsFor,
        pageState = pageState,
        onPageSettled = viewModel::onPageSettled,
        onVisitStart = viewModel::onVisitStart,
        onVisitEnd = viewModel::onVisitEnd,
        onAllGroups = { navigator.push(AllGroupsKey) },
        live = live,
        onVisibleIds = { ref, ids -> viewModel.onVisibleIds(ref, ids) },
        onRefresh = viewModel::onRefresh,
        onPlay = viewModel::playFeed,
        onFiltersChange = viewModel::onFilters,
        onAction = { action ->
            if (action is EpisodeAction.Open || action is EpisodeAction.OpenPodcast) viewModel.episodeSelection.clear()
            when (dispatchEpisodeRoute(action, navigator, urls)) {
                EpisodeRouteResult.HANDLED -> {}

                // NO_HANDLER only comes back from WatchOnYouTube; the message carries the
                // watch URL it failed to open for the "Copy link" action (08).
                EpisodeRouteResult.NO_HANDLER -> {
                    if (action is EpisodeAction.WatchOnYouTube) {
                        viewModel.onNoHandler(YouTubeLinks.watch(action.videoId))
                    }
                }

                EpisodeRouteResult.UNHANDLED -> {
                    viewModel.onRowAction(action)
                }
            }
        },
        onDownloadAll = viewModel.downloadAll::open,
        onMarkAllPlayedClick = viewModel::markAllPlayed,
        onAddPodcast = { navigator.push(AddPodcastKey(null)) },
        onSearch = { navigator.selectTab(DiscoverKey) },
        onImport = { navigator.push(ImportFileKey) },
        onCreateGroup = { navigator.push(GroupEditKey(null)) },
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

    if (credentials != null) {
        EnterPasswordDialog(
            onSubmit = viewModel::onCredentials,
            onDismiss = viewModel::onCredentialsDismissed,
        )
    }

    ShowUserMessages(
        messages = state.messages,
        onAcknowledgeMessage = viewModel::onMessageShown,
        // The "Copy link" tap reports back by message id (01's onMessageAction contract); the
        // clipboard is a composition concern the platform-free ViewModel never sees. Playback
        // actions (Retry/Settings/Enter/Watch on YouTube) resolve through the helper.
        onMessageAction = { id ->
            viewModel.undoSwipe(id)
            viewModel.consumeCopyLink(id)?.let { clipboard.setText(AnnotatedString(it)) }
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
                        if (url == null || urls.open(url) == OpenResult.NO_HANDLER) {
                            if (url != null) viewModel.onNoHandler(url)
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
