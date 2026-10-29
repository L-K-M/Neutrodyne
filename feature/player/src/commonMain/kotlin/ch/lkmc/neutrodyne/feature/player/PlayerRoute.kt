// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.EnterPasswordDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_read_failed
import ch.lkmc.neutrodyne.feature.player.resources.player_write_failed
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/** The shells supply queue content; player and queue features remain independent. */
@Composable
public fun PlayerRoute(
    slot: PlayerSlotState,
    modifier: Modifier = Modifier,
    upNext: @Composable () -> Unit = {},
) {
    // Keep DI parameters private; the slot API must not expose internal ViewModels.
    PlayerRouteContent(slot = slot, modifier = modifier, upNext = upNext)
}

@Composable
private fun PlayerRouteContent(
    slot: PlayerSlotState,
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = metroViewModel(),
    episodeVm: PlayerEpisodeViewModel = metroViewModel(),
    upNext: @Composable () -> Unit,
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val failed by episodeVm.writeFailed.collectAsStateWithLifecycle()
    val readFailed by episodeVm.readFailure.collectAsStateWithLifecycle()
    val password by episodeVm.passwordEpisode.collectAsStateWithLifecycle()
    val detail by episodeVm.detail.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val rootScope = LocalRootScope.current
    val scope = rememberCoroutineScope()
    val platform = LocalPlatformActions.current
    val clipboard = LocalClipboardManager.current
    val writeFailureText = stringResource(Res.string.player_write_failed)
    val readFailureText = stringResource(Res.string.player_read_failed)
    val noHandler = stringResource(UiRes.string.no_handler)
    val copyLink = stringResource(UiRes.string.action_copy_link)
    LaunchedEffect(ui.now?.episodeId) { episodeVm.displayEpisode(ui.now?.episodeId) }
    LaunchedEffect(failed) {
        if (failed) {
            rootScope.launch { snackbar.showSnackbar(writeFailureText) }
            episodeVm.acknowledgeFailure()
        }
    }
    LaunchedEffect(readFailed) {
        if (readFailed) {
            rootScope.launch { snackbar.showSnackbar(readFailureText) }
            episodeVm.acknowledgeReadFailure()
        }
    }

    fun openLink(url: String) {
        if (platform.urls.open(url) != OpenResult.NO_HANDLER) return
        rootScope.launch {
            if (snackbar.showSnackbar(
                    noHandler,
                    copyLink,
                    withDismissAction = true,
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                ) == androidx.compose.material3.SnackbarResult.ActionPerformed
            ) {
                clipboard.setText(AnnotatedString(url))
            }
        }
    }

    ShowUserMessages(ui.messages, vm::onMessageShown) { id ->
        when (val action = vm.onMessageAction(id)) {
            PlaybackMessageAction.OpenPlaybackSettings -> {
                slot.navigate(SettingsKey(SettingsPage.PLAYBACK))
            }

            is PlaybackMessageAction.EnterPassword -> {
                episodeVm.requestPassword(action.episodeId)
            }

            is PlaybackMessageAction.WatchOnYouTube -> {
                scope.launch {
                    suspendRunCatching { episodeVm.watchLink(action.episodeId) }
                        .onSuccess { url ->
                            if (url == null) snackbar.showSnackbar(readFailureText) else openLink(url)
                        }.onFailure { snackbar.showSnackbar(readFailureText) }
                }
            }

            else -> {
                Unit
            }
        }
    }
    val currentDetail = detail?.takeIf { it.id == ui.now?.episodeId }
    val link = currentDetail?.let { it.link ?: it.externalMediaId?.let(YouTubeLinks::watch) }
    val seekEpisodeId = ui.now?.episodeId
    PlayerSheet(
        ui = ui,
        position = position,
        slot = slot,
        positionAt = vm::positionAt,
        toggle = vm::togglePlayPause,
        retry = vm::retryPlayback,
        back = vm::skipBack,
        forward = vm::skipForward,
        previous = vm::previous,
        next = vm::next,
        seek = { value -> vm.seekTo(value, seekEpisodeId) },
        dismiss = vm::dismissPaused,
        stop = vm::stopAndClose,
        markPlayed = { ui.now?.let { episodeVm.markPlayed(it.episodeId) } },
        consent = vm::continueOnMobileData,
        declineConsent = vm::declineMobileData,
        consentAlways = vm::alwaysOnMobileData,
        toggleTime = vm::toggleTimeDisplay,
        share =
            link?.let { url ->
                {
                    val sharing = platform.share
                    if (sharing != null) {
                        sharing.shareText(url, ui.now?.title.orEmpty())
                    } else {
                        clipboard.setText(AnnotatedString(url))
                    }
                }
            },
        modifier = modifier,
        upNext = upNext,
        notes = { ui.now?.let { PlayerNotes(it.episodeId, it.position.durationMs, onLink = ::openLink) } },
    )
    if (password != null) EnterPasswordDialog(episodeVm::submitPassword, episodeVm::dismissPassword)
}
