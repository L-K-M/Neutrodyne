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
import ch.lkmc.neutrodyne.core.ui.root.HeldChangesBanner
import ch.lkmc.neutrodyne.core.ui.root.ReconnectBanner
import ch.lkmc.neutrodyne.core.ui.root.ReconnectProfile
import ch.lkmc.neutrodyne.core.ui.root.RemoteSessionCard
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.sync_marked_played_elsewhere
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The MS3 shell bridge over `SyncController` (08 Continue on this device / Sync notices): the
 * offer mapped onto [RootUiState.remoteSession], its two actions, the one-shot notices
 * mapped into [RootSlots.userMessages], and the oldest held batch projected onto
 * [RootUiState.heldChanges] (MS2's banner, 08 priority 3). UI-only — eligibility, staleness,
 * adoption and the playing boundary stay with `SessionAdopter` and the playback gates;
 * nothing here decides what plays or when.
 */
public class SyncRootPresentation internal constructor(
    public val remoteSession: RemoteSessionCard?,
    public val heldChanges: HeldChangesBanner?,
    public val reconnect: ReconnectBanner?,
    public val continueHere: () -> Unit,
    public val dismissRemoteSession: () -> Unit,
    public val dismissReconnect: (ReconnectBanner) -> Unit,
    public val isReconnectTargetLive: (ReconnectBanner) -> Boolean,
    public val userMessages: Flow<UserMessage>,
)

/**
 * [syncController] and [playbackController] are thunks resolved only when needed: the graph's
 * sync service dereferences the database at construction, so the collectors below park inside
 * `snapshotFlow` until [enabled] (the shells' database-open gate) turns on. `false` renders the
 * inert pre-open frame — no offer, no messages and no service resolution.
 * [reconnectDismissals] is the app-scoped in-memory "Not now" record (08 priority 4); it is
 * cheap and resolved once per composition, never through the database-backed service.
 *
 * [isPlaying] is the only suppression the shell adds (08: the card takes the mini player's
 * slot "while nothing plays here" and the paused adopted item stays visible underneath): a
 * loaded-but-paused player never hides the offer. Playing-state eligibility itself belongs to
 * `SessionAdopter`, which retracts the offer service-side; this flag only keeps the card out of
 * the frame in which a live local session owns the slot.
 *
 * The two card actions are stale-safe by construction: each recomposition returns lambdas bound
 * to the offer *this frame rendered*, while the gate and playing flag are read at invoke time
 * through `rememberUpdatedState`. A tap queued from an older frame compares its captured offer
 * with the service's live `remoteSession`: a cleared or replaced offer makes the stale callback
 * inert, so Continue cannot resume an unrelated session and Dismiss cannot retract an offer it
 * was never rendered for. The gate is checked before the thunk, so stale actions never resolve
 * the service after close.
 *
 * The reconnect banner follows the same staleness rule by profile identity: `Reconnect` checks
 * the live status before the root pushes `SyncSetupKey`, and "Not now" dismisses only the exact
 * (server URL, username) pair this frame rendered. A previous profile's dismissal never hides
 * another server's banner.
 */
@Composable
public fun rememberSyncRootPresentation(
    syncController: () -> SyncController,
    playbackController: () -> PlaybackController,
    enabled: Boolean,
    isPlaying: Boolean,
    reconnectDismissals: () -> ReconnectDismissals = { ReconnectDismissals() },
): SyncRootPresentation {
    val controller by rememberUpdatedState(syncController)
    val playback by rememberUpdatedState(playbackController)
    val gate by rememberUpdatedState(enabled)
    val playing by rememberUpdatedState(isPlaying)
    val dismissals = remember { reconnectDismissals() }
    // Keyless: the producer parks until the gate opens, then collects forever — the offer flow
    // stays live across recomposition and dies with the composition, never with a frame.
    val offer by produceState<RemoteSessionOffer?>(null) {
        snapshotFlow { gate }.first { it }
        controller().remoteSession.collect { value = it }
    }
    // The held-changes banner (MS2, 08 priority 3): the oldest published prompt — the DAO
    // already orders `ORDER BY heldAt, id` — projected only while the profile is Linked
    // (R7.1 keeps a NotConfigured install sync-free). Unlike the offer this collector
    // re-parks when the gate drops: unlink, relink or a closing database clears the banner
    // in the same frame, so a previous profile's prompt — and the Review target bound to it
    // — can never outlive the state that published it.
    val held by produceState<MassChangePrompt?>(null) {
        snapshotFlow { gate }.collectLatest { open ->
            value = null
            if (open) {
                val sync = controller()
                combine(sync.status, sync.heldChanges) { status, prompts ->
                    if (status is SyncStatus.Linked) prompts.firstOrNull() else null
                }.collect { value = it }
            }
        }
    }
    // The reconnect banner (MS2, 08 priority 4): only `SyncStatus.Reconnect`, and only while its
    // exact (server URL, username) profile has not been dismissed this process run. Like the held
    // banner it re-parks when the gate drops, so a closing database or an unlink clears it in the
    // same frame and a previous profile's banner can never outlive the state that published it.
    val reconnect by produceState<ReconnectBanner?>(null) {
        snapshotFlow { gate }.collectLatest { open ->
            value = null
            if (open) {
                val sync = controller()
                combine(sync.status, dismissals.dismissed) { status, dismissed ->
                    val reconnect = status as? SyncStatus.Reconnect
                    if (reconnect == null ||
                        ReconnectProfile(reconnect.serverUrl, reconnect.username) in dismissed
                    ) {
                        null
                    } else {
                        reconnect.toBanner()
                    }
                }.collect { value = it }
            }
        }
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
    // "Not now" hides only the profile this frame rendered, and only while it is still the live
    // status: a queued tap from a replaced banner is inert and can never dismiss another server.
    val dismissReconnect: (ReconnectBanner) -> Unit =
        { banner ->
            scope.launch {
                suspendRunCatching {
                    if (!gate) return@launch
                    val live = controller().status.value as? SyncStatus.Reconnect ?: return@launch
                    if (live.serverUrl != banner.serverUrl || live.username != banner.username) {
                        return@launch
                    }
                    dismissals.dismiss(banner.profile)
                }
            }
        }
    // The root's "Reconnect" checks this before pushing `SyncSetupKey`, so a stale frame cannot
    // open another profile's setup. The gate is read before the thunk: a closed gate resolves
    // nothing and reports false.
    val isReconnectTargetLive: (ReconnectBanner) -> Boolean =
        { banner ->
            if (!gate) {
                false
            } else {
                val live = controller().status.value as? SyncStatus.Reconnect
                live != null &&
                    live.serverUrl == banner.serverUrl &&
                    live.username == banner.username &&
                    !dismissals.isDismissed(banner.profile)
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
        heldChanges = held?.toBanner(),
        reconnect = if (enabled) reconnect else null,
        continueHere = continueHere,
        dismissRemoteSession = dismissRemoteSession,
        dismissReconnect = dismissReconnect,
        isReconnectTargetLive = isReconnectTargetLive,
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

internal fun MassChangePrompt.toBanner(): HeldChangesBanner =
    HeldChangesBanner(
        id = id,
        deviceName = deviceName,
        podcastCount = podcastTitles.size,
        groupCount = groupNames.size,
    )

internal fun RemoteSessionOffer.toCard(): RemoteSessionCard =
    RemoteSessionCard(
        title = title,
        podcastTitle = podcastTitle,
        positionText = playerTime(positionMs),
        deviceName = deviceName,
    )

internal fun SyncStatus.Reconnect.toBanner(): ReconnectBanner =
    ReconnectBanner(serverUrl = serverUrl, username = username, host = hostOf(serverUrl))

/**
 * The display host for the reconnect banner: scheme, userinfo, path, query and fragment removed.
 * Credentials and userinfo never reach the banner; the port stays, because it is part of the
 * server identity the user recognizes.
 */
private fun hostOf(url: String): String {
    val authority =
        url
            .substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    return authority.substringAfterLast('@').ifEmpty { "?" }
}
