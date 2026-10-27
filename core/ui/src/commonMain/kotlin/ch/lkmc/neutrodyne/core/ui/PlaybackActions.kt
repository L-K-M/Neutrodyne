// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.RejectReason
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.action_watch_on_youtube
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_title
import ch.lkmc.neutrodyne.core.ui.resources.offline_banner
import ch.lkmc.neutrodyne.core.ui.resources.playback_action_settings
import ch.lkmc.neutrodyne.core.ui.resources.playback_audio_gone
import ch.lkmc.neutrodyne.core.ui.resources.playback_external_only
import ch.lkmc.neutrodyne.core.ui.resources.playback_metered_always
import ch.lkmc.neutrodyne.core.ui.resources.playback_metered_blocked
import ch.lkmc.neutrodyne.core.ui.resources.playback_metered_once
import ch.lkmc.neutrodyne.core.ui.resources.playback_metered_title
import ch.lkmc.neutrodyne.core.ui.resources.playback_needs_password
import ch.lkmc.neutrodyne.core.ui.resources.playback_no_media
import ch.lkmc.neutrodyne.core.ui.resources.playback_nothing_to_play
import ch.lkmc.neutrodyne.core.ui.resources.playback_nothing_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.playback_queue_already_playing
import ch.lkmc.neutrodyne.core.ui.resources.playback_queue_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.playback_queue_youtube
import ch.lkmc.neutrodyne.core.ui.resources.playback_start_failed
import ch.lkmc.neutrodyne.core.ui.resources.playback_unsupported_format
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_disabled
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_engine_failed
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_failed
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_in_apk
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_in_apk_desktop
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_yet
import ch.lkmc.neutrodyne.core.ui.resources.playback_youtube_not_yet_desktop
import ch.lkmc.neutrodyne.core.ui.resources.podcast_enter_password
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A pending "Stream on mobile data?" decision (08 Dialogs); the dialog binds the episode. */
public data class MeteredConsent(
    val episodeId: Long,
)

/**
 * What a playback snackbar's action label should do — [PlaybackActions.onMessageAction] returns
 * the kind and performs the ViewModel-owned part itself (Retry re-runs the stored start), so the
 * route only handles UI work: navigation, the credentials dialog and external opens.
 */
public sealed interface PlaybackMessageAction {
    /** "Couldn't start playback" + Retry — the stored start was re-run already. */
    public data object RetryStart : PlaybackMessageAction

    /** "Streaming on mobile data is off" + Settings — open `SettingsKey(SettingsPage.PLAYBACK)`. */
    public data object OpenPlaybackSettings : PlaybackMessageAction

    /** "This feed needs a password" + Enter — open the Enter password dialog of [episodeId]'s feed. */
    public data class EnterPassword(
        val episodeId: Long,
    ) : PlaybackMessageAction

    /** A refused YouTube start's "Watch on YouTube" — open the episode's watch link. */
    public data class WatchOnYouTube(
        val episodeId: Long,
    ) : PlaybackMessageAction
}

/**
 * The screens' shared playback entry point (M4/W10): runs controller starts and queue adds on the
 * screen's scope, maps [PlayResult]/[AddResult] to snackbars and the metered-consent dialog per
 * 08's Issues, results and events table, and keeps the pending retry/consent state those results
 * need. [context] names the feed in "Nothing unplayed in '{context}'" — a `UiText.Res` for named
 * sources, `UiText.Raw` for user titles, null for context-free starts (the generic fallback shows).
 *
 * Consent retries the exact attempt that was refused; "Always" first runs [allowMeteredStreaming]
 * (the caller's `playback.stream_on_metered = ALLOW` write) and only then grants and retries, so a
 * failed policy write never masquerades as consent. Thrown failures surface through [onFailure],
 * the screen's write-failure reporter.
 */
public class PlaybackActions(
    private val scope: CoroutineScope,
    private val controller: PlaybackController,
    private val states: PlaybackStateSource,
    private val messages: UserMessages,
    private val platform: PlatformKind,
    private val allowMeteredStreaming: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val consent = MutableStateFlow<MeteredConsent?>(null)
    public val meteredConsent: StateFlow<MeteredConsent?> = consent.asStateFlow()
    private var consentRetry: Retry? = null
    private var startGeneration = 0L
    private var startJob: Job? = null

    init {
        scope.launch {
            states.pendingMeteredConsent.collect { pending ->
                val awaiting = consent.value
                if (awaiting != null && pending?.episodeId != awaiting.episodeId) consentDismiss()
            }
        }
    }

    /** Action-bearing snackbars: message id → the follow-up its action label promised. */
    private val pendingActions = mutableMapOf<Long, PendingAction>()

    /**
     * 08's row PlayToggle: [episodeId] is the row's episode. The current item toggles through the
     * transports — [PlaybackController.pause] while it plays, `play()` to resume through the same
     * gates a start runs — and every other row runs [start]. [PlaybackStateSource.nowPlaying] is
     * the current-item check (W10), not the row's live flag.
     */
    public fun playToggle(
        episodeId: Long,
        context: UiText?,
        start: suspend () -> PlayResult,
    ) {
        val now = states.nowPlaying.value
        if (now?.episodeId != episodeId) {
            play(context, start)
            return
        }
        if (now.playWhenReady || now.isPlaying) {
            ++startGeneration
            startJob?.cancel()
            consentDismiss()
            controller.pause()
        } else {
            play(context) { controller.play() }
        }
    }

    /** A context/header start (08's page Play): [attempt] runs on [scope], its result maps below. */
    public fun play(
        context: UiText?,
        attempt: suspend () -> PlayResult,
    ) {
        val generation = ++startGeneration
        startJob?.cancel()
        consentDismiss()
        startJob =
            scope.launch {
                val result = suspendRunCatching(attempt)
                // A slower refused start must not reopen consent after a replacement start.
                if (generation != startGeneration) return@launch
                result.onFailure(onFailure).onSuccess { onPlayResult(it, context, attempt) }
            }
    }

    /** Up-next adds (row overflow, selection, detail) — queue edits, never audio starts (06). */
    public fun playNext(
        episodeIds: List<Long>,
        onAdded: () -> Unit = {},
    ) {
        addToQueue({ controller.playNext(episodeIds) }, onAdded)
    }

    public fun playLast(
        episodeIds: List<Long>,
        onAdded: () -> Unit = {},
    ) {
        addToQueue({ controller.playLast(episodeIds) }, onAdded)
    }

    private fun addToQueue(
        attempt: suspend () -> AddResult,
        onAdded: () -> Unit,
    ) {
        scope.launch {
            suspendRunCatching(attempt)
                .onFailure(onFailure)
                .onSuccess { result ->
                    if (result is AddResult.Added) {
                        onAdded()
                    } else if (result is AddResult.Rejected) {
                        messages.post(
                            UiText.Res(
                                when (result.reason) {
                                    RejectReason.ALREADY_PLAYING -> Res.string.playback_queue_already_playing
                                    RejectReason.YOUTUBE_EXTERNAL -> Res.string.playback_queue_youtube
                                    RejectReason.UNAVAILABLE -> Res.string.playback_queue_unavailable
                                },
                            ),
                        )
                    }
                }
        }
    }

    /** Once: consent for this start only, then retry the refused attempt (08). */
    public fun consentOnce() {
        val retry = consumeConsent() ?: return
        controller.grantMeteredStreaming()
        play(retry.context, retry.attempt)
    }

    /** Always: persist ALLOW, consent for this start, then retry — a failed write aborts both. */
    public fun consentAlways() {
        val retry = consumeConsent() ?: return
        val generation = startGeneration
        scope.launch {
            suspendRunCatching { allowMeteredStreaming() }
                .onFailure {
                    onFailure(it)
                    return@launch
                }
            if (generation != startGeneration) return@launch
            controller.grantMeteredStreaming()
            play(retry.context, retry.attempt)
        }
    }

    /** Cancel drops the pending retry; the controller retains its consent until the next start. */
    public fun consentDismiss() {
        consumeConsent()
    }

    private fun consumeConsent(): Retry? {
        consent.value = null
        return consentRetry.also { consentRetry = null }
    }

    /**
     * The screen's snackbar-action dispatcher (01's onMessageAction contract): resolves the
     * message's promised follow-up once. `RetryStart` re-runs the stored start here; the other
     * kinds return for the route to perform (navigation, dialog, external open).
     */
    public fun onMessageAction(id: Long): PlaybackMessageAction? =
        when (val pending = pendingActions.remove(id)) {
            is PendingAction.RetryStart -> {
                play(pending.retry.context, pending.retry.attempt)
                PlaybackMessageAction.RetryStart
            }

            PendingAction.OpenPlaybackSettings -> {
                PlaybackMessageAction.OpenPlaybackSettings
            }

            is PendingAction.EnterPassword -> {
                PlaybackMessageAction.EnterPassword(pending.episodeId)
            }

            is PendingAction.WatchOnYouTube -> {
                PlaybackMessageAction.WatchOnYouTube(pending.episodeId)
            }

            null -> {
                null
            }
        }

    /** A dismissed snackbar can never fire its action; drop the stale pending entry. */
    public fun onMessageShown(id: Long) {
        pendingActions.remove(id)
    }

    /** 08's start-result mapping: `Started` is silent, everything else surfaces once. */
    private fun onPlayResult(
        result: PlayResult,
        context: UiText?,
        attempt: suspend () -> PlayResult,
    ) {
        when (result) {
            PlayResult.Started -> {
                Unit
            }

            is PlayResult.NothingToPlay -> {
                val text =
                    when {
                        result.externalOnly && context != null -> {
                            UiText.Res(Res.string.playback_external_only, listOf(context))
                        }

                        context != null -> {
                            UiText.Res(Res.string.playback_nothing_unplayed, listOf(context))
                        }

                        else -> {
                            UiText.Res(Res.string.playback_nothing_to_play)
                        }
                    }
                messages.post(text)
            }

            is PlayResult.NeedsMeteredConsent -> {
                consentRetry = Retry(context, attempt)
                consent.value = MeteredConsent(result.episodeId)
            }

            PlayResult.MeteredBlocked -> {
                val message =
                    messages.post(
                        UiText.Res(Res.string.playback_metered_blocked),
                        action = UiText.Res(Res.string.playback_action_settings),
                    )
                pendingActions[message.id] = PendingAction.OpenPlaybackSettings
            }

            PlayResult.Offline -> {
                messages.post(UiText.Res(Res.string.offline_banner))
            }

            is PlayResult.NotPlayable -> {
                onNotPlayable(result)
            }

            PlayResult.ServiceUnavailable -> {
                val message =
                    messages.post(
                        UiText.Res(Res.string.playback_start_failed),
                        action = UiText.Res(Res.string.action_retry),
                    )
                pendingActions[message.id] = PendingAction.RetryStart(Retry(context, attempt))
            }
        }
    }

    private fun onNotPlayable(result: PlayResult.NotPlayable) {
        when (val reason = result.reason) {
            is UnplayableReason.Http -> {
                // 404/410 are the gone-for-good pins; other statuses get the generic failure.
                if (reason.status == 404 || reason.status == 410) {
                    messages.post(UiText.Res(Res.string.playback_audio_gone))
                } else {
                    messages.post(UiText.Res(Res.string.playback_start_failed))
                }
            }

            UnplayableReason.AuthRequired -> {
                val message =
                    messages.post(
                        UiText.Res(Res.string.playback_needs_password),
                        action = UiText.Res(Res.string.podcast_enter_password),
                    )
                pendingActions[message.id] = PendingAction.EnterPassword(result.episodeId)
            }

            UnplayableReason.UnsupportedFormat -> {
                messages.post(UiText.Res(Res.string.playback_unsupported_format))
            }

            UnplayableReason.NoMedia -> {
                messages.post(UiText.Res(Res.string.playback_no_media))
            }

            is UnplayableReason.YouTube -> {
                messages.post(AvailabilityText.describe(reason.availability))
            }

            UnplayableReason.YouTubeExtraction -> {
                messages.post(UiText.Res(Res.string.playback_youtube_failed))
            }

            is UnplayableReason.YouTubeExternal -> {
                val message =
                    messages.post(
                        externalReasonText(reason.reason),
                        action = UiText.Res(Res.string.action_watch_on_youtube),
                    )
                pendingActions[message.id] = PendingAction.WatchOnYouTube(result.episodeId)
            }
        }
    }

    /** 08's External reason texts, the snackbar's short column (desktop wording where it differs). */
    private fun externalReasonText(reason: ExternalReason): UiText =
        UiText.Res(
            when (reason) {
                ExternalReason.NOT_YET_AVAILABLE -> {
                    if (platform == PlatformKind.DESKTOP) {
                        Res.string.playback_youtube_not_yet_desktop
                    } else {
                        Res.string.playback_youtube_not_yet
                    }
                }

                ExternalReason.NOT_IN_THIS_APK -> {
                    if (platform == PlatformKind.DESKTOP) {
                        Res.string.playback_youtube_not_in_apk_desktop
                    } else {
                        Res.string.playback_youtube_not_in_apk
                    }
                }

                ExternalReason.DISABLED_BY_USER -> {
                    Res.string.playback_youtube_disabled
                }

                ExternalReason.ENGINE_FAILED -> {
                    Res.string.playback_youtube_engine_failed
                }
            },
        )

    /** A start that consent may retry, or that the "Couldn't start playback" Retry re-runs. */
    private data class Retry(
        val context: UiText?,
        val attempt: suspend () -> PlayResult,
    )

    private sealed interface PendingAction {
        data class RetryStart(
            val retry: Retry,
        ) : PendingAction

        data object OpenPlaybackSettings : PendingAction

        data class EnterPassword(
            val episodeId: Long,
        ) : PendingAction

        data class WatchOnYouTube(
            val episodeId: Long,
        ) : PendingAction
    }
}

/**
 * The metered-streaming decision (08 Dialogs: a "small confirmation" the screen owns). `NdDialog`
 * only carries two action slots, so the three buttons use `AlertDialog`'s dismiss slot as a row —
 * Once stays the rightmost confirm, Always and Cancel sit beside it.
 */
@Composable
public fun MeteredConsentDialog(
    onOnce: () -> Unit,
    onAlways: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        confirmButton = {
            NdTextButton(stringResource(Res.string.playback_metered_once), onClick = onOnce)
        },
        dismissButton = {
            Row {
                NdTextButton(stringResource(Res.string.playback_metered_always), onClick = onAlways)
                NdTextButton(stringResource(Res.string.action_cancel), onClick = onDismiss)
            }
        },
        title = {
            Text(
                stringResource(Res.string.playback_metered_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
    )
}

/**
 * The Enter password dialog a refused start's snackbar action opens (08 results table: "opens the
 * Enter password dialog for the episode's podcast"). Same shape as the credentials banner's
 * dialog; [onSubmit] receives the pair for `PodcastRepository.setCredentials`.
 */
@Composable
public fun EnterPasswordDialog(
    onSubmit: (BasicCredentials) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    NdDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = NdIcons.Key,
        title = stringResource(Res.string.add_auth_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) {
                onSubmit(BasicCredentials(username, password))
            },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        CredentialFields(
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
        )
    }
}
