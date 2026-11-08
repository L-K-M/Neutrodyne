// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState

/** Immutable CAS state. Reservations are process-local and never restored after process death. */
internal data class ExtractionHealthState(
    val revision: Long = 0,
    val engineVersion: String? = null,
    val openUntil: Long? = null,
    val lastOpenedAt: Long? = null,
    val rateUntil: Long? = null,
    val rateLevel: Int = 0,
    val failures: List<Failure> = emptyList(),
    val forbidden: Map<String, Long> = emptyMap(),
    val nextId: Long = 0,
    val reservations: Set<Long> = emptySet(),
    val trial: Long? = null,
    val halfOpen: Boolean = false,
    val invalidating: Boolean = false,
    val cacheEpoch: Long = 0,
) {
    data class Failure(
        val videoId: String?,
        val at: Long,
    )

    fun gate(now: Long): ExtractionGate {
        if (invalidating || trial != null) return ExtractionGate.Deny(now + MINUTE, TransientKind.BREAKER_OPEN)
        if (openUntil != null && now < openUntil) return ExtractionGate.Deny(openUntil, TransientKind.BREAKER_OPEN)
        if (rateUntil != null && now < rateUntil) return ExtractionGate.Deny(rateUntil, TransientKind.RATE_LIMITED)
        return if (openUntil == null) ExtractionGate.Allow else ExtractionGate.AllowTrial
    }

    fun project(feed: YouTubeHealthState): YouTubeHealthState =
        feed.copy(
            breaker =
                when {
                    openUntil == null -> BreakerState.CLOSED
                    halfOpen || trial != null -> BreakerState.HALF_OPEN
                    else -> BreakerState.OPEN
                },
            breakerOpenUntil = openUntil,
            rateLimitedUntil = rateUntil,
        )

    fun report(
        outcome: ExtractionOutcome,
        now: Long,
        isTrial: Boolean,
    ): ExtractionHealthState {
        if (outcome == ExtractionOutcome.Success) {
            // A closed-era success clears rate state, but cannot close a newer breaker.
            if (!isTrial) return copy(rateUntil = null, rateLevel = 0)
            return copy(
                openUntil = null,
                halfOpen = false,
                rateUntil = null,
                rateLevel = 0,
                failures = emptyList(),
                forbidden = emptyMap(),
            )
        }
        if (outcome == ExtractionOutcome.Inconclusive) return this
        if (openUntil != null && !isTrial) return this
        val recent = failures.filter { now - it.at in 0 until HOUR }
        val fresh = forbidden.filterValues { now - it in 0 until HOUR }.toMutableMap()
        val video =
            when (outcome) {
                is ExtractionOutcome.ParseFailure -> {
                    outcome.videoId
                }

                is ExtractionOutcome.ForbiddenFreshUrl -> {
                    fresh[outcome.videoId] = now
                    if (fresh.size < 3) return copy(forbidden = fresh)
                    outcome.videoId
                }

                else -> {
                    return this
                }
            }
        val duplicate = video != null && recent.any { it.videoId == video && now - it.at < 10 * MINUTE }
        val counted = if (duplicate) recent else recent + Failure(video, now)
        if (!isTrial && counted.size < 5) return copy(failures = counted, forbidden = fresh)
        val hours = if (lastOpenedAt != null && now - lastOpenedAt in 0 until 24 * HOUR) 12 else 6
        return copy(
            openUntil = now + hours * HOUR,
            lastOpenedAt = now,
            halfOpen = false,
            failures = emptyList(),
            forbidden = emptyMap(),
            invalidating = true,
        )
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
