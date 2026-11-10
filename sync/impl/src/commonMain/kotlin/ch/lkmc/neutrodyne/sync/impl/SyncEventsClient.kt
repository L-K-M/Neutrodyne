// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.sync.api.SyncStreamState
import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangedEvent
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.serverSentEventsSession
import io.ktor.client.request.header
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** 10 Live updates: Android asks for 90-s heartbeats (risk SR7). */
internal const val SSE_HEARTBEAT_ANDROID_SECONDS = 90

/** 10 Live updates: the desktop asks for 60-s heartbeats. */
internal const val SSE_HEARTBEAT_DESKTOP_SECONDS = 60

/**
 * One parsed line of the events stream (10 Events). A `changed` event carries only `by` — its
 * cursor is dropped here, because SSE only signals and data always comes through a round.
 */
sealed interface SyncStreamEvent {
    /** A `:` comment line — the heartbeat that proves the stream is alive. */
    data object Comment : SyncStreamEvent

    /** A bare `retry:` line — the server asks for this delay before the next reconnect. */
    data class Retry(
        val delayMs: Long,
    ) : SyncStreamEvent

    /** `event: changed`; [by] is the pushing device's ID (null when the payload lacks it). */
    data class Changed(
        val by: String?,
    ) : SyncStreamEvent
}

/**
 * An open events stream (10 Live updates). [events] emits until the server ends the stream;
 * transport-level failures surface as a [SyncTransportException] error — `401` and `429`
 * keep their status and `Retry-After`.
 */
interface SyncEventSession {
    val events: Flow<SyncStreamEvent>

    /** Cancels the underlying call — unlink, revoke and the platform gate land here. */
    suspend fun close()
}

/** The transport under [SyncEventsClient]; tests substitute a scripted implementation. */
interface SyncEventTransport {
    /**
     * Opens `GET <base>/api/v1/events?hb=…` (10 Events). A non-2xx response throws
     * [SyncTransportException] with the status.
     */
    suspend fun open(
        baseUrl: String,
        token: String,
        heartbeatSeconds: Int,
    ): SyncEventSession
}

/**
 * [SyncEventTransport] over the `SYNC_STREAM` Ktor client (10 Live updates, 01 Networking
 * baseline): the sync client derived with no read and no call timeout, so a 60–90 s heartbeat
 * interval outlives the family's 30 s read timeout; the client's watchdog replaces it. The
 * bearer token is added per request, never through an interceptor shared with other clients —
 * a redirect hop to another authority drops it at the same layer the rest of the family's
 * authenticated calls rely on. Ktor's own session reconnection stays off — the supervisor
 * owns the documented cadence.
 */
class KtorSyncEventTransport(
    clients: NeutrodyneHttpClients,
) : SyncEventTransport {
    private val client: HttpClient = clients.client(HttpClientKind.SYNC_STREAM)

    override suspend fun open(
        baseUrl: String,
        token: String,
        heartbeatSeconds: Int,
    ): SyncEventSession {
        val url = baseUrl.trimEnd('/') + "/api/v1/events?hb=" + heartbeatSeconds
        try {
            return KtorSyncEventSession(
                client.serverSentEventsSession(
                    urlString = url,
                    showCommentEvents = true,
                    showRetryEvents = true,
                ) {
                    header("Neutrodyne-Sync-Protocol", PROTOCOL_VERSION.toString())
                    header("Authorization", "Bearer $token")
                },
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw failure.asTransport()
        }
    }
}

private class KtorSyncEventSession(
    private val session: ClientSSESession,
) : SyncEventSession {
    override val events: Flow<SyncStreamEvent> =
        session.incoming
            .mapNotNull { it.toStreamEvent() }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                throw failure.asTransport()
            }

    override suspend fun close() {
        session.coroutineContext[Job]?.cancel()
    }
}

private fun ServerSentEvent.toStreamEvent(): SyncStreamEvent? {
    val retryMs = retry
    return when {
        // A bare `retry:` line carries no event name, data or comment (10 Events).
        retryMs != null && data == null && event == null && comments == null -> {
            SyncStreamEvent.Retry(retryMs)
        }

        comments != null -> {
            SyncStreamEvent.Comment
        }

        event == EVENT_CHANGED -> {
            data
                ?.let { runCatching { SyncJson.decodeFromString(ChangedEvent.serializer(), it) }.getOrNull() }
                ?.let { SyncStreamEvent.Changed(it.by) }
        }

        else -> {
            null
        }
    }
}

private const val EVENT_CHANGED = "changed"
private const val FEATURE_SSE = "sse"

private fun Throwable.asTransport(): SyncTransportException =
    when (this) {
        is SyncTransportException -> {
            this
        }

        is SSEClientException -> {
            SyncTransportException(
                status = response?.status?.value ?: 0,
                code = null,
                message = "events stream failed: $message",
                retryAfterSeconds = response?.headers?.get("Retry-After")?.toLongOrNull(),
            )
        }

        else -> {
            SyncTransportException(0, null, "events stream failed: $message")
        }
    }

/**
 * The SSE half of 10 Live updates: one long-lived `GET /api/v1/events` connection while the
 * platform gate (Android: UI visible or playback; desktop: awake), the link and the network
 * all hold — and only while the server's discovery document advertises `sse`. The stream only
 * signals: a remote `changed` event and a (re)connect itself call [onRemoteChanged], which
 * pokes the scheduler's coalesced round — event data is never applied.
 *
 * Reconnection follows the documented cadence: the server's `retry` hint (10 s) after a clean
 * end, `Retry-After` after a `429`, and exponential backoff to 5 min with ±20 % jitter after
 * repeated failures. The watchdog reconnects after `2 × hb + 15 s` of silence. A `401` writes
 * `Revoked` to `sync_state` and calls [onRevoked] — the same path a revoked round takes — and
 * the supervisor stops: the link gate it combines on has already flipped.
 */
class SyncEventsClient(
    private val db: NeutrodyneDatabase,
    private val transport: SyncEventTransport,
    private val discover: suspend (String) -> DiscoveryDocument,
    private val address: SyncServerAddress,
    private val tokens: SyncTokenStore,
    private val network: NetworkMonitor,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val onRemoteChanged: suspend () -> Unit,
    private val onRevoked: suspend () -> Unit,
    private val random: Random = Random,
    private val diagnosticsRuntime: SyncRuntimeDiagnostics = SyncRuntimeDiagnostics(),
    private val identityMutex: SyncIdentityLock = SyncIdentityLock(),
) {
    /** `SyncActivity.LIVE` while a stream is open (10 Live updates). */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** The platform's gate — Android's 60-s backgrounding grace and the desktop's sleep. */
    private val allowed = MutableStateFlow(false)

    /**
     * The stable live-link identity. A true-to-true account replacement must also close the
     * old stream; cursor and clock progress do not restart it. Scheduler link changes and
     * pending authorization writes update it through [reevaluate].
     */
    private val linkLive = MutableStateFlow<SyncLinkIdentity?>(null)

    @Volatile private var started = false
    private var heartbeatSeconds = SSE_HEARTBEAT_DESKTOP_SECONDS
    private var diagnosticsTicket: SyncDiagnosticTicket? = null

    /**
     * Starts the supervisor once (the platform's start-up initializer calls it). [allowedFlow]
     * is the platform's gate and [heartbeatSeconds] its `hb` request — 90 on Android, 60 on
     * the desktop (10 Live updates).
     */
    fun start(
        allowedFlow: Flow<Boolean>,
        heartbeatSeconds: Int,
    ) {
        if (started) return
        started = true
        this.heartbeatSeconds = heartbeatSeconds
        scope.launch { allowedFlow.collect { allowed.value = it } }
        scope.launch { reevaluate() }
        scope.launch { supervise() }
    }

    /** `SyncScheduler`'s funnel for link, unlink and revoke — re-reads the gate at once. */
    internal suspend fun reevaluate() {
        identityMutex.withLock {
            linkLive.value =
                db
                    .syncStateDao()
                    .get()
                    ?.takeIf { it.isLive() }
                    ?.linkIdentity()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun supervise() {
        combine(allowed, linkLive, network.status) { a, link, n -> link.takeIf { a && n.isConnected } }
            .distinctUntilChanged()
            .collectLatest { owner ->
                _connected.value = false
                try {
                    if (owner != null) runLoop(owner)
                } finally {
                    _connected.value = false
                    diagnosticsTicket?.let { diagnosticsRuntime.streamState(it, SyncStreamState.STOPPED) }
                }
            }
    }

    /**
     * The reconnect loop for one gate-open period: connect → read → wait → connect, until the
     * gate closes (collectLatest cancels it) or the link reads dead between attempts.
     */
    private suspend fun runLoop(owner: SyncLinkIdentity) {
        var failures = 0
        var retryMs = SERVER_RETRY_MS
        while (coroutineContext.isActive) {
            if (!owns(owner)) {
                reevaluate()
                return
            }
            val outcome = runOnce(owner) { retryMs = it }
            diagnosticsTicket?.let {
                diagnosticsRuntime.streamState(
                    it,
                    when (outcome) {
                        Outcome.NoSse -> SyncStreamState.UNSUPPORTED
                        Outcome.Idle, Outcome.Superseded, is Outcome.Revoked -> SyncStreamState.STOPPED
                        else -> SyncStreamState.WAITING
                    },
                )
            }
            when (outcome) {
                // A clean end (24-h cap, shutdown, a proxy hop) reconnects at the server's
                // own cadence; a stream that opened proves the route works, so no backoff.
                Outcome.Ended -> {
                    failures = 0
                    delay(retryMs)
                }

                Outcome.NoSse -> {
                    delay(FEATURE_RECHECK_MS)
                }

                // Linked without a usable URL/token/deviceId is "Reconnect": idle until the
                // link changes again (the scheduler's funnel is the real wake-up).
                Outcome.Idle -> {
                    delay(FEATURE_RECHECK_MS)
                }

                is Outcome.Revoked -> {
                    markRevoked(outcome.profile)
                    reevaluate()
                    return
                }

                Outcome.Superseded -> {
                    reevaluate()
                    return
                }

                is Outcome.Wait -> {
                    delay(outcome.delayMs)
                }

                Outcome.Failed -> {
                    failures++
                    delay(backoffMs(failures, retryMs))
                }
            }
        }
        // A link change that raced past the scheduler funnel still flips the gate here.
        reevaluate()
    }

    private suspend fun runOnce(
        owner: SyncLinkIdentity,
        onServerRetry: (Long) -> Unit,
    ): Outcome {
        val context =
            identityMutex.withLock {
                val state = db.syncStateDao().get() ?: return@withLock null
                if (!state.isLive() || state.linkIdentity() != owner) return@withLock null
                val url = address.serverUrl() ?: return@withLock null
                if (url != state.serverUrl || state.deviceId == null) return@withLock null
                val token = tokens.load(hostOf(url)) ?: return@withLock null
                StreamContext(state, url, token)
            } ?: return Outcome.Idle
        val state = context.profile
        val url = context.url
        val token = context.token
        val deviceId = checkNotNull(state.deviceId)
        val ticket = diagnosticsRuntime.activate(state.diagnosticProfile(url))
        diagnosticsTicket = ticket
        diagnosticsRuntime.streamState(ticket, SyncStreamState.CONNECTING)

        val features =
            try {
                val document = discover(url)
                diagnosticsRuntime.rememberVersion(url, document.serverVersion)
                document.features
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                return failure.asOutcome(state)
            }
        // 10 Live updates: connect only while the server advertises the events endpoint.
        if (!owns(owner)) return Outcome.Superseded
        if (FEATURE_SSE !in features) return Outcome.NoSse

        diagnosticsRuntime.streamOpening(ticket)
        val session =
            try {
                transport.open(url, token, heartbeatSeconds)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                return failure.asOutcome(state)
            }
        // The bus does not replay (10 EventBus): pushes missed while this device was
        // disconnected surface only through a round, so every (re)connect pokes one.
        return try {
            // The session is already open: cancellation/failure in the initial poke must
            // release it and clear the connected state just like a read failure.
            if (!signalRemote(owner)) return Outcome.Superseded
            _connected.value = true
            diagnosticsRuntime.streamState(ticket, SyncStreamState.CONNECTED)
            readStream(session, state, deviceId, onServerRetry)
        } finally {
            _connected.value = false
            diagnosticsRuntime.streamState(ticket, SyncStreamState.STOPPED)
            runCatching { session.close() }
        }
    }

    /**
     * Reads the stream until it ends, fails or stalls (no byte for `2 × hb + 15 s` — 10 Live
     * updates' watchdog; `hb` comment lines and every event count as a byte).
     */
    private suspend fun readStream(
        session: SyncEventSession,
        profile: SyncStateEntity,
        deviceId: String,
        onServerRetry: (Long) -> Unit,
    ): Outcome =
        supervisorScope {
            // A failed producer must reach receiveCatching for status classification;
            // ordinary coroutineScope cancels this reader before it can handle the 401.
            val watchdogMs = heartbeatSeconds * 2_000L + WATCHDOG_SLACK_MS
            val channel = session.events.produceIn(this)
            try {
                while (coroutineContext.isActive) {
                    val result =
                        try {
                            withTimeout(watchdogMs) { channel.receiveCatching() }
                        } catch (timeout: TimeoutCancellationException) {
                            return@supervisorScope Outcome.Failed
                        }
                    result.exceptionOrNull()?.let { return@supervisorScope it.asOutcome(profile) }
                    when (val event = result.getOrNull()) {
                        null -> {
                            return@supervisorScope Outcome.Ended
                        }

                        is SyncStreamEvent.Comment -> {}

                        is SyncStreamEvent.Retry -> {
                            onServerRetry(event.delayMs)
                        }

                        is SyncStreamEvent.Changed -> {
                            // Our own push's echo is ignored; a missing `by` counts as remote.
                            if (event.by != deviceId && !signalRemote(profile.linkIdentity())) {
                                return@supervisorScope Outcome.Superseded
                            }
                        }
                    }
                }
            } finally {
                channel.cancel()
            }
            // Reached only when the coroutine was cancelled between iterations — the caller's
            // next suspension raises the CancellationException as usual.
            Outcome.Ended
        }

    private fun Throwable.asOutcome(profile: SyncStateEntity): Outcome =
        (this as? SyncTransportException)?.let { e ->
            when {
                e.status == HTTP_UNAUTHORIZED -> {
                    Outcome.Revoked(profile)
                }

                e.status == HTTP_TOO_MANY_REQUESTS -> {
                    Outcome.Wait((e.retryAfterSeconds ?: SERVER_RETRY_SECONDS) * 1_000)
                }

                else -> {
                    Outcome.Failed
                }
            }
        } ?: Outcome.Failed

    /**
     * 10 Errors: the same write `SyncEngine.markRevoked` performs, then the same cancel path.
     * Only the captured account may be revoked. Clearing its live identity also closes the
     * stream and lets a later re-link reopen it.
     */
    private suspend fun markRevoked(expected: SyncStateEntity) {
        identityMutex.withLock {
            if (address.serverUrl() != expected.serverUrl) return@withLock
            val owned =
                db.withWriteTransaction {
                    if (!sameSyncIdentity(expected, db.syncStateDao().get())) return@withWriteTransaction false
                    db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED)
                    true
                }
            if (!owned) return@withLock
            // Its own gate update cancels this stream job. Finish the owned local cleanup
            // before releasing identity ownership, even when collectLatest cancels us.
            withContext(NonCancellable) {
                linkLive.value = null
                onRevoked()
            }
        }
    }

    private suspend fun owns(owner: SyncLinkIdentity): Boolean = identityMutex.withLock { ownsLocked(owner) }

    private suspend fun ownsLocked(owner: SyncLinkIdentity): Boolean {
        val state = db.syncStateDao().get() ?: return false
        return state.isLive() && state.linkIdentity() == owner && address.serverUrl() == owner.serverUrl
    }

    private suspend fun signalRemote(owner: SyncLinkIdentity): Boolean =
        identityMutex.withLock {
            if (!ownsLocked(owner)) return@withLock false
            onRemoteChanged()
            true
        }

    private fun SyncStateEntity.isLive(): Boolean = enabled && linkedAt != null && lastError != SYNC_ERROR_REVOKED

    private class StreamContext(
        val profile: SyncStateEntity,
        val url: String,
        val token: String,
    )

    /** Server `retry` × 2^(n-1) capped at 5 min, ±20 % jitter (10 Live updates). */
    private fun backoffMs(
        failures: Int,
        retryMs: Long,
    ): Long {
        val base = minOf(retryMs * (1L shl minOf(failures - 1, 5)), MAX_BACKOFF_MS)
        return (base * random.nextDouble(JITTER_LOW, JITTER_HIGH)).toLong().coerceAtLeast(1L)
    }

    private sealed interface Outcome {
        /** The stream opened and ended cleanly — reconnect after the server `retry` hint. */
        data object Ended : Outcome

        /** Discovery lacks `sse` — the server cannot stream; polling covers it (10 Live updates). */
        data object NoSse : Outcome

        /** No URL/token/device to connect with — sleep until the next link re-check. */
        data object Idle : Outcome

        /** `429` — wait `Retry-After` (or the server retry default) before the next attempt. */
        data class Wait(
            val delayMs: Long,
        ) : Outcome

        /** A connect/read/watchdog failure — exponential backoff applies. */
        data object Failed : Outcome

        /** `401` — the link is revoked; record it and stop. */
        data class Revoked(
            val profile: SyncStateEntity,
        ) : Outcome

        /** The request belongs to a replaced link; restart only for the current identity. */
        data object Superseded : Outcome
    }

    private companion object {
        /** The server's `retry: 10000` default (10 Events) and the reconnect floor. */
        const val SERVER_RETRY_MS = 10_000L
        const val SERVER_RETRY_SECONDS = 10L

        /** 10 Live updates: backoff tops out at 5 min of ±20 % jittered doubling. */
        const val MAX_BACKOFF_MS = 300_000L
        const val JITTER_LOW = 0.8
        const val JITTER_HIGH = 1.2

        /** 10 Live updates: the watchdog's slack on top of `2 × hb`. */
        const val WATCHDOG_SLACK_MS = 15_000L

        /** A server without `sse` is re-discovered on the backoff cap's cadence. */
        const val FEATURE_RECHECK_MS = 300_000L

        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
