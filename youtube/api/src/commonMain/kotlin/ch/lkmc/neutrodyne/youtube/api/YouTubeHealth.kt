// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import kotlinx.coroutines.flow.StateFlow

/** App-scoped serialized state; timestamps are epoch ms. retryNow retains outage level and schedules channels. */
interface YouTubeHealth {
    val state: StateFlow<YouTubeHealthState>

    suspend fun awaitLoaded()

    /** Non-reserving view; AllowTrial never consumes the sole half-open trial. */
    fun extractionGate(nowMs: Long): ExtractionGate

    fun reportExtraction(outcome: ExtractionOutcome)

    fun reportRateLimited(nowMs: Long)

    fun reportFeedOutage(nowMs: Long)

    fun reportFeedRecovered()

    fun reportFeedRateLimited(
        nowMs: Long,
        retryAfterMs: Long?,
    )

    fun reportEngineVersion(version: String?)

    fun retryNow()
}

data class YouTubeHealthState(
    val breaker: BreakerState = BreakerState.CLOSED,
    val breakerOpenUntil: Long? = null,
    val rateLimitedUntil: Long? = null,
    val feedOutageUntil: Long? = null,
    val feedOutageLevel: Int = 0,
    val feedRateLimitedUntil: Long? = null,
)

enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

sealed interface ExtractionGate {
    data object Allow : ExtractionGate

    data object AllowTrial : ExtractionGate

    data class Deny(
        val untilMs: Long,
        val kind: TransientKind,
    ) : ExtractionGate
}

sealed interface ExtractionOutcome {
    data object Success : ExtractionOutcome

    data class ParseFailure(
        val videoId: String?,
    ) : ExtractionOutcome

    data class ForbiddenFreshUrl(
        val videoId: String,
    ) : ExtractionOutcome

    data object Inconclusive : ExtractionOutcome
}

/**
 * One refresh run at a time. Gate before taking the host permit; controls use the same host limit.
 * Concurrent probe wait and run-finalization network work are cancellable and bounded to 20 s.
 * Every admitted fetch must report, including cancellation/offline as INCONCLUSIVE, releasing probes.
 * INCONCLUSIVE releases admission without IO; callers complete this cleanup even on cancellation.
 * onRunFinished evaluates distinct channel counters against the whole library, then resets them.
 */
interface YouTubeFeedOutageMonitor {
    suspend fun gate(
        podcastId: Long,
        nowMs: Long,
    ): FeedGate

    suspend fun report(
        podcastId: Long,
        outcome: FeedOutcome,
        nowMs: Long,
    )

    suspend fun onRunFinished(
        totalSubscribed: Int,
        nowMs: Long,
    )
}

sealed interface FeedGate {
    data object Fetch : FeedGate

    data object Probe : FeedGate

    data class Deferred(
        val untilMs: Long,
    ) : FeedGate
}

enum class FeedOutcome { SUCCESS, FAILURE, INCONCLUSIVE }
