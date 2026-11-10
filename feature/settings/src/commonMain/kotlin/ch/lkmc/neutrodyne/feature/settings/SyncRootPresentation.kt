// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.playerTime
import ch.lkmc.neutrodyne.core.ui.root.RemoteSessionCard
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.sync_marked_played_elsewhere
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The MS3 shell bridge over `SyncController` (08 Continue on this device / Sync notices): the
 * offer mapped onto [RootUiState.remoteSession], its two actions and the one-shot notices
 * mapped into [RootSlots.userMessages]. UI-only — eligibility, staleness, adoption and the
 * playing boundary stay with `SessionAdopter` and the playback gates; nothing here decides
 * what plays or when.
 */
public class SyncRootPresentation internal constructor(
    public val remoteSession: RemoteSessionCard?,
    public val continueHere: () -> Unit,
    public val dismissRemoteSession: () -> Unit,
    public val userMessages: Flow<UserMessage>,
)

/**
 * [syncController] and [playbackController] are thunks resolved only when needed: the graph's
 * sync service dereferences the database at construction, so the collectors below park inside
 * `snapshotFlow` until [enabled] (the shells' database-open gate) turns on. `false` renders the
 * inert pre-open frame — no offer, no messages and no service resolution.
 *
 * [isPlaying] is the only suppression the shell adds (08: the card takes the mini player's
 * slot "while nothing plays here" and the paused adopted item stays visible underneath): a
 * loaded-but-paused player never hides the offer. Playing-state eligibility itself belongs to
 * `SessionAdopter`, which retracts the offer service-side; this flag only keeps the card out of
 * the frame in which a live local session owns the slot.
 *
 * The two actions are stale-safe by construction: each recomposition returns lambdas bound to
 * the offer *this frame rendered*, while the gate and playing flag are read at invoke time
 * through `rememberUpdatedState`. A tap queued from an older frame compares its captured offer
 * with the service's live `remoteSession`: a cleared or replaced offer makes the stale callback
 * inert, so Continue cannot resume an unrelated session and Dismiss cannot retract an offer it
 * was never rendered for. The gate is checked before the thunk, so stale actions never resolve
 * the service after close.
 */
@Composable
public fun rememberSyncRootPresentation(
    syncController: () -> SyncController,
    playbackController: () -> PlaybackController,
    enabled: Boolean,
    isPlaying: Boolean,
): SyncRootPresentation {
    val controller by rememberUpdatedState(syncController)
    val playback by rememberUpdatedState(playbackController)
    val gate by rememberUpdatedState(enabled)
    val playing by rememberUpdatedState(isPlaying)
    // Keyless: the producer parks until the gate opens, then collects forever — the offer flow
    // stays live across recomposition and dies with the composition, never with a frame.
    val offer by produceState<RemoteSessionOffer?>(null) {
        snapshotFlow { gate }.first { it }
        controller().remoteSession.collect { value = it }
    }
    // What this frame rendered: null while playing suppresses the card. Bound into the
    // callbacks below by value, so a stale frame's tap names the offer it was rendered for.
    val rendered = if (isPlaying) null else offer
    val scope = rememberCoroutineScope()

    // Continue is the deliberate resume path only: play() resumes the session the
    // sync/pending writes staged — never an auto-play, never an arbitrary episode.
    // The guards keep a stale frame's tap from resuming an unrelated session.
    val continueHere: () -> Unit =
        {
            scope.launch {
                suspendRunCatching {
                    if (!gate || playing || rendered == null) return@launch
                    if (controller().remoteSession.value != rendered) return@launch
                    playback().play()
                }
            }
        }

    // Dismiss guards the same staleness: only retract the offer this card was rendered for,
    // so a queued tap can never hide a newer replacement.
    val dismissRemoteSession: () -> Unit =
        {
            scope.launch {
                suspendRunCatching {
                    if (!gate || rendered == null) return@launch
                    val sync = controller()
                    if (sync.remoteSession.value != rendered) return@launch
                    sync.dismissRemoteSession()
                }
            }
        }
    val userMessages =
        remember {
            flow {
                snapshotFlow { gate }.first { it }
                var nextId = 0L
                controller().notices.collect { notice ->
                    if (notice is SyncNotice.MarkedPlayedElsewhere) {
                        emit(
                            UserMessage(
                                id = nextId++,
                                text =
                                    UiText.Res(
                                        Res.string.sync_marked_played_elsewhere,
                                        listOf(notice.deviceName),
                                    ),
                            ),
                        )
                    }
                }
            }
        }
    return SyncRootPresentation(
        remoteSession = rendered?.toCard(),
        continueHere = continueHere,
        dismissRemoteSession = dismissRemoteSession,
        userMessages = userMessages,
    )
}

/**
 * Fans several serial [UserMessage] streams into one `RootSlots.userMessages` stream without
 * breaking the producers' emission contract: upstream `emit` suspends until the downstream
 * snackbar dismissed the message (each item is acknowledged only after the forward `emit`
 * returns). `kotlinx.coroutines.flow.merge` cannot be used here — its channel decouples the
 * producers, so an upstream like `DesktopBannerNotifier` would tear down a message's
 * bookkeeping (route lookups for `userMessageAction`) while the snackbar is still on screen.
 */
public fun serialUserMessages(vararg upstreams: Flow<UserMessage>): Flow<UserMessage> =
    flow {
        coroutineScope {
            val inbox =
                Channel<Pair<UserMessage, CompletableDeferred<Unit>>>(Channel.RENDEZVOUS)
            for (upstream in upstreams) {
                launch {
                    upstream.collect { message ->
                        val ack = CompletableDeferred<Unit>()
                        inbox.send(message to ack)
                        ack.await()
                    }
                }
            }
            for ((message, ack) in inbox) {
                emit(message)
                ack.complete(Unit)
            }
        }
    }

internal fun RemoteSessionOffer.toCard(): RemoteSessionCard =
    RemoteSessionCard(
        title = title,
        podcastTitle = podcastTitle,
        positionText = playerTime(positionMs),
        deviceName = deviceName,
    )
