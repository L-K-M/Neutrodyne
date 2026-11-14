// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.EnterPasswordDialog
import ch.lkmc.neutrodyne.core.ui.LocalDrawnReporter
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.radio.RadioActions
import ch.lkmc.neutrodyne.core.ui.radio.RadioDestinationMenu
import ch.lkmc.neutrodyne.core.ui.radio.RadioEpisode
import ch.lkmc.neutrodyne.core.ui.radio.RadioScreen
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

@Composable
internal fun RadioRoute(model: RadioViewModel = metroViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val messages by model.userMessages.collectAsStateWithLifecycle()
    val consent by model.playback.meteredConsent.collectAsStateWithLifecycle()
    val credentials by model.pendingCredentials.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val urls = LocalPlatformActions.current.urls
    val clipboard = LocalClipboardManager.current
    val group = state.playlistId
    val items = key(group) { remember(group) { model.pagingFor(group) }.collectAsLazyPagingItems() }
    val snapshot = items.itemSnapshotList
    val loadedIds = remember(snapshot) { snapshot.items.map { it.id } }
    val loading = group != null && items.loadState.refresh is LoadState.Loading && snapshot.items.isEmpty()
    val loadError =
        listOf(items.loadState.refresh, items.loadState.append, items.loadState.prepend)
            .any { it is LoadState.Error }
    LocalDrawnReporter.current?.ReportWhen { items.loadState.refresh !is LoadState.Loading }
    var menu by remember { mutableStateOf(false) }
    val navigator = LocalAppNavigator.current
    LaunchedEffect(group, snapshot, items.loadState.refresh, items.loadState.append) {
        if (group != null && items.loadState.refresh is LoadState.NotLoading) {
            model.onRows(
                group,
                snapshot.items.map { RadioEpisode(it.id, it.title, it.podcastTitle, it.durationMs, it.sortDate) },
                complete =
                    snapshot.placeholdersBefore == 0 && snapshot.placeholdersAfter == 0 &&
                        items.loadState.append.endOfPaginationReached,
            )
        }
    }
    RadioScreen(
        state.copy(loading = loading, loadError = loadError),
        RadioActions(
            selectEpisode = model::selectEpisode,
            discard = model::discard,
            undo = model::undoDiscard,
            togglePlay = model::togglePlay,
            seek = model::seek,
            selectPlaylist = model::selectPlaylist,
            createPlaylist = model::createPlaylist,
            menu = { menu = true },
            loadEpisode = { index ->
                state.episodes.getOrNull(index)?.id?.let { id ->
                    radioPageIndex(id, snapshot.placeholdersBefore, loadedIds)?.let { items[it] }
                }
            },
            visibleEpisodes = model::onVisibleIds,
            retry = items::retry,
        ),
    )
    if (menu) RadioDestinationMenu(onDismiss = { menu = false })
    if (consent !=
        null
    ) {
        MeteredConsentDialog(
            model.playback::consentOnce,
            model.playback::consentAlways,
            model.playback::consentDismiss,
        )
    }
    if (credentials != null) EnterPasswordDialog(model::onCredentials, model::onCredentialsDismissed)
    ShowUserMessages(messages, model::acknowledgeMessage) { id ->
        model.consumeCopyLink(id)?.let { clipboard.setText(AnnotatedString(it)) }
        when (val action = model.playback.onMessageAction(id)) {
            PlaybackMessageAction.OpenPlaybackSettings -> {
                navigator.push(SettingsKey(SettingsPage.PLAYBACK))
            }

            is PlaybackMessageAction.EnterPassword -> {
                model.onEnterPassword(action.episodeId)
            }

            is PlaybackMessageAction.WatchOnYouTube -> {
                scope.launch {
                    model.youtubeWatchUrl(action.episodeId)?.let { url ->
                        if (urls.open(url) == OpenResult.NO_HANDLER) model.onNoHandler(url)
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }
}

/** Match by identity because the displayed window may lag one paging emission. */
internal fun radioPageIndex(
    episodeId: Long,
    leadingPlaceholders: Int,
    loadedIds: List<Long>,
): Int? {
    val index = loadedIds.indexOf(episodeId)
    return index.takeIf { it >= 0 }?.plus(leadingPlaceholders)
}
