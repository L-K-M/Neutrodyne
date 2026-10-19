// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** App-scoped portable resolver, inert until a caller invokes it. The supplied scope owns shutdown;
 * individual callers own waiter budgets, while the last waiter owns flight cancellation.
 */
class YtDlpStreamResolver(
    private val client: YtDlpClient,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: YouTubeExtractionHealth,
    private val cache: ResolvedUrlCache,
    private val errors: YtDlpErrorMapper,
    private val hints: IpFamilyHints,
    private val context: () -> YtxContext,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : YouTubeStreamResolver {
    private data class Key(
        val videoId: String,
        val pref: AudioPref,
    )

    private class Flight(
        val deadline: YtxDeadline,
        val result: Deferred<ResolveResult>,
        var waiters: Int = 0,
    )

    private val lock = Any()
    private val flights = mutableMapOf<Key, Flight>()
    private var generation = 0L

    override suspend fun resolveAudio(
        videoId: String,
        pref: AudioPref,
        purpose: ResolvePurpose,
    ): ResolveResult {
        if (!YouTubeIds.VIDEO.matches(videoId)) return ResolveResult.Unavailable(Availability.UNAVAILABLE)
        if (!capabilities.capabilities.value.inAppPlayback) return ResolveResult.Unsupported
        val budget = if (client.isReady) purpose.warmTimeoutMs else purpose.coldTimeoutMs
        val waiterDeadline = YtxDeadline(clock.elapsedRealtime() + budget)
        val key = Key(videoId, pref)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!capabilities.capabilities.value.inAppPlayback) return ResolveResult.Unsupported
            val flight =
                synchronized(lock) {
                    cache.get(videoId, pref)?.let {
                        hints.set(it.ipFamily)
                        return ResolveResult.Ok(it)
                    }
                    val existing = flights[key]
                    val active =
                        existing?.takeIf {
                            !it.result.isCompleted &&
                                it.deadline.elapsedRealtimeMs > clock.elapsedRealtime()
                        }
                    val selected = active ?: newFlight(key, purpose, waiterDeadline).also { flights[key] = it }
                    selected.waiters++
                    selected
                }
            val remaining = waiterDeadline.elapsedRealtimeMs - clock.elapsedRealtime()
            val result =
                try {
                    if (remaining <= 0) return YtDlpErrorMapper.transient(TransientKind.TIMEOUT)
                    flight.result.start()
                    withTimeoutOrNull(remaining) { flight.result.await() }
                        ?: return YtDlpErrorMapper.transient(TransientKind.TIMEOUT)
                } finally {
                    val last =
                        synchronized(lock) {
                            flight.waiters--
                            if (flight.waiters == 0) {
                                if (flights[key] === flight) flights.remove(key)
                            }
                            flight.waiters == 0
                        }
                    if (last) flight.result.cancel()
                }
            // A background waiter may outlive the first playback caller's fixed flight deadline.
            if (purpose == ResolvePurpose.BACKGROUND && result is ResolveResult.Transient &&
                result.kind == TransientKind.TIMEOUT &&
                flight.deadline.elapsedRealtimeMs < waiterDeadline.elapsedRealtimeMs &&
                clock.elapsedRealtime() < waiterDeadline.elapsedRealtimeMs
            ) {
                continue
            }
            return result
        }
    }

    private fun newFlight(
        key: Key,
        purpose: ResolvePurpose,
        deadline: YtxDeadline,
    ): Flight {
        val version = generation
        val result = scope.async(start = CoroutineStart.LAZY) { extract(key, purpose, deadline, version) }
        return Flight(deadline, result)
    }

    private suspend fun extract(
        key: Key,
        purpose: ResolvePurpose,
        deadline: YtxDeadline,
        version: Long,
    ): ResolveResult {
        try {
            val priority = if (purpose == ResolvePurpose.PLAYBACK) YtxPriority.PLAYBACK else YtxPriority.BACKGROUND
            return client.operation(priority, deadline) {
                if (!capabilities.capabilities.value.inAppPlayback) return@operation ResolveResult.Unsupported
                health.awaitLoaded()
                val admission = health.reserveExtraction(clock.now())
                if (admission is ExtractionAdmission.Denied) {
                    return@operation YtDlpErrorMapper.transient(
                        admission.gate.kind,
                    )
                }
                admission as ExtractionAdmission.Admitted
                var outcome: ExtractionOutcome = ExtractionOutcome.Inconclusive
                try {
                    val ctx = synchronized(lock) { context().copy(ipFamily = hints.current()?.name) }
                    val payload = Json.encodeToString(YtxVideoRequest.serializer(), YtxVideoRequest(ctx, key.videoId))
                    val result =
                        try {
                            val wire = YtDlpClient.decode(call(YtxMethod.RESOLVE, payload), YtxResolve.serializer())
                            if (wire.videoId != key.videoId) throw YtxCallException("EXTRACTION")
                            structuredCode(wire)?.let { throw YtxCallException(it) }
                            ResolveResult.Ok(YtDlpAudioMapper.map(wire, key.videoId, key.pref, clock.now()))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: YtxCallException) {
                            errors.map(failure.code, key.videoId)
                        }
                    checkDeadline()
                    outcome =
                        when {
                            result is ResolveResult.Ok -> {
                                ExtractionOutcome.Success
                            }

                            result is ResolveResult.Transient && result.kind == TransientKind.EXTRACTION -> {
                                ExtractionOutcome.ParseFailure(key.videoId)
                            }

                            else -> {
                                ExtractionOutcome.Inconclusive
                            }
                        }
                    if (result is ResolveResult.Transient &&
                        result.kind == TransientKind.RATE_LIMITED
                    ) {
                        health.reportRateLimited(clock.now())
                    }
                    if (result is ResolveResult.Ok) {
                        synchronized(lock) {
                            // Invalidated/network-old flights may satisfy existing waiters, but cannot
                            // repopulate the URL cache or restore a family from the previous network.
                            if (generation == version && capabilities.capabilities.value.inAppPlayback) {
                                cache.put(key.videoId, key.pref, result.audio)
                                hints.set(result.audio.ipFamily)
                            }
                        }
                    }
                    result
                } finally {
                    health.completeExtraction(admission.reservation, outcome)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: YtxCallException) {
            return errors.map(failure.code, key.videoId)
        }
    }

    override fun invalidate(videoId: String) {
        synchronized(lock) {
            generation++
            flights.keys.removeAll { it.videoId == videoId }
            cache.invalidate(videoId)
        }
        // W7/W8 own media 403/429 reports. Invalidating here must not double-count their report.
    }

    override fun invalidateAll() {
        synchronized(lock) {
            generation++
            flights.clear()
            cache.invalidateAll()
        }
    }

    /** Called by the default-network identity adapter; no media/extraction health report. */
    fun defaultNetworkChanged() {
        synchronized(lock) {
            invalidateAll()
            hints.set(null)
        }
    }

    private fun structuredCode(wire: YtxResolve): String? {
        if (wire.videoId.isEmpty()) return "EXTRACTION"
        return when {
            wire.liveStatus == "is_upcoming" -> "UPCOMING"
            wire.liveStatus == "is_live" || wire.liveStatus == "post_live" -> "LIVE"
            wire.formats.isNotEmpty() -> null
            wire.availability == "private" -> "PRIVATE"
            wire.availability == "subscriber_only" || wire.availability == "premium_only" -> "MEMBERS_ONLY"
            (wire.ageLimit ?: 0) >= 18 -> "AGE_RESTRICTED"
            else -> null
        }
    }
}
