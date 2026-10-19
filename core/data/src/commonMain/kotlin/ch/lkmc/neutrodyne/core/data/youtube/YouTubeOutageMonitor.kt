// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedOutageMonitor
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Counts distinct conclusive channel outcomes, never variant requests or skipped/offline work. */
internal class YouTubeOutageMonitor(
    private val health: YouTubeHealth,
    private val clock: Clock,
    private val totalSubscribed: suspend () -> Int,
    private val checkControls: suspend () -> FeedOutcome,
) : YouTubeFeedOutageMonitor {
    private val mutex = Mutex()
    private val controlsMutex = Mutex()
    private val reportsMutex = Mutex()
    private val outcomes = linkedMapOf<Long, FeedOutcome>()
    private var declared = false
    private var probeId: Long? = null
    private var probeDone: CompletableDeferred<Unit>? = null
    private var controlsAt: Long? = null
    private var controlsResult = FeedOutcome.INCONCLUSIVE

    override suspend fun gate(
        podcastId: Long,
        nowMs: Long,
    ): FeedGate {
        var now = nowMs
        var admitted: FeedGate? = null
        var ownsProbe = false
        try {
            admitted =
                withTimeoutOrNull(WAIT_MS) {
                    while (true) {
                        health.awaitLoaded()
                        val decision =
                            mutex.withLock {
                                val state = health.state.value
                                val until = maxOf(state.feedOutageUntil ?: 0, state.feedRateLimitedUntil ?: 0)
                                if (until > now) return@withLock FeedGate.Deferred(until) to null
                                if (state.feedOutageLevel == 0) return@withLock FeedGate.Fetch to null
                                val pending = probeDone
                                if (pending != null) return@withLock null to pending
                                probeId = podcastId
                                ownsProbe = true
                                probeDone = CompletableDeferred()
                                FeedGate.Probe to null
                            }
                        decision.first?.let { return@withTimeoutOrNull it }
                        decision.second!!.await()
                        now = clock.now()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    FeedGate.Fetch
                }
            return admitted ?: FeedGate.Deferred(clock.now() + WAIT_MS)
        } finally {
            // A timeout can win just as admission returns; do not strand an unreported probe.
            if (ownsProbe && admitted != FeedGate.Probe) {
                withContext(NonCancellable) { mutex.withLock { releaseProbe(podcastId) } }
            }
        }
    }

    override suspend fun report(
        podcastId: Long,
        outcome: FeedOutcome,
        nowMs: Long,
    ) {
        // Cleanup is safe even from a cancelled adapter: INCONCLUSIVE does no IO and must free admission.
        if (outcome == FeedOutcome.INCONCLUSIVE) {
            withContext(NonCancellable) { mutex.withLock { releaseProbe(podcastId) } }
            return
        }
        try {
            reportsMutex.withLock {
                health.awaitLoaded()
                val isProbe =
                    mutex.withLock {
                        if (podcastId !in outcomes) outcomes[podcastId] = outcome
                        probeId == podcastId
                    }
                if (isProbe && outcome == FeedOutcome.FAILURE) {
                    val result =
                        withTimeoutOrNull(WAIT_MS) {
                            if (totalSubscribed() in 1..2) controls(nowMs) else FeedOutcome.FAILURE
                        } ?: FeedOutcome.INCONCLUSIVE
                    when (result) {
                        FeedOutcome.SUCCESS -> health.reportFeedRecovered()
                        FeedOutcome.FAILURE -> declare(nowMs)
                        FeedOutcome.INCONCLUSIVE -> Unit
                    }
                } else if (outcome == FeedOutcome.SUCCESS && (isProbe || health.state.value.feedOutageLevel == 0)) {
                    health.reportFeedRecovered()
                } else {
                    mutex.withLock {
                        if (!declared && outcomes.size <= FIRST_WINDOW &&
                            outcomes.values.count { it == FeedOutcome.FAILURE } >= FIRST_FAILURES &&
                            health.state.value.feedOutageLevel == 0
                        ) {
                            declared = true
                            health.reportFeedOutage(nowMs)
                        }
                    }
                }
                health.awaitLoaded()
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { releaseProbe(podcastId) } }
        }
    }

    override suspend fun onRunFinished(
        totalSubscribed: Int,
        nowMs: Long,
    ) {
        try {
            withTimeoutOrNull(WAIT_MS) {
                health.awaitLoaded()
                val counts = mutex.withLock { outcomes.size to outcomes.values.count { it == FeedOutcome.FAILURE } }
                if (declared || health.state.value.feedOutageLevel > 0) return@withTimeoutOrNull
                val (attempted, failed) = counts
                if (attempted >= MIN_SAMPLE && failed * 2 > attempted) {
                    declare(nowMs)
                } else if (totalSubscribed in 1..2 && attempted == totalSubscribed && failed == attempted &&
                    controls(nowMs) == FeedOutcome.FAILURE
                ) {
                    declare(nowMs)
                }
                health.awaitLoaded()
            }
        } finally {
            // Run cleanup is in-memory only; finalization network work stays cancellable above.
            withContext(NonCancellable) {
                mutex.withLock {
                    outcomes.clear()
                    declared = false
                    probeDone?.complete(Unit)
                    probeDone = null
                    probeId = null
                }
            }
        }
    }

    private suspend fun declare(nowMs: Long) {
        mutex.withLock {
            if (declared) return
            declared = true
            health.reportFeedOutage(nowMs)
        }
    }

    private suspend fun controls(nowMs: Long): FeedOutcome =
        controlsMutex.withLock {
            if ((health.state.value.feedRateLimitedUntil ?: 0) > nowMs) return@withLock FeedOutcome.INCONCLUSIVE
            val at = controlsAt
            if (at != null && nowMs >= at && nowMs - at < CONTROL_CACHE_MS) return@withLock controlsResult
            val result = checkControls()
            // Offline, cancellation and rate limiting cannot prove endpoint failure; do not cache them.
            if (result != FeedOutcome.INCONCLUSIVE) {
                controlsAt = nowMs
                controlsResult = result
            }
            result
        }

    private fun releaseProbe(podcastId: Long) {
        if (probeId != podcastId) return
        probeDone?.complete(Unit)
        probeDone = null
        probeId = null
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val CONTROL_CACHE_MS = 3_600_000L
        const val FIRST_WINDOW = 4
        const val FIRST_FAILURES = 3
        const val MIN_SAMPLE = 3
    }
}
