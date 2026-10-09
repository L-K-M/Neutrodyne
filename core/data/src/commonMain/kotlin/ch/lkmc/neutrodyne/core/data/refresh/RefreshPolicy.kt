// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import kotlin.random.Random

/**
 * The `nextRefreshAt` policy of 03 (one table, applied by the ingest transaction for outcomes with
 * a body and by `FeedRefresher`'s batched fetch-state writes for the rest). `I` is the podcast's
 * effective interval — the global `feeds.refresh_interval_minutes` in M1a (05's resolver takes
 * over in M2).
 */
internal object RefreshPolicy {
    /** `I == null` ("Manual only", a `0` in the setting) schedules here (03 nextRefreshAt policy). */
    const val NEVER: Long = 253_402_300_799_000L

    const val DAY_MS = 86_400_000L

    /**
     * Success row (ingested, 304, unchanged, `NO_MEDIA`): `now + I'`, `I'` = `I` raised to ≥ 24 h
     * when the show is `complete` or its newest episode is ≥ 180 d old, then to ≥ `min(hint, 24 h)`
     * for a publisher hint (the larger of `ttlMinutes` and `Cache-Control: max-age`).
     */
    fun successNextRefreshAt(
        now: Long,
        intervalMinutes: Int?,
        complete: Boolean,
        latestEpisodeAt: Long?,
        ttlMinutes: Int?,
        maxAgeSec: Long?,
    ): Long {
        val intervalMs = intervalMinutes?.toLong()?.times(60_000L) ?: return NEVER
        var i = intervalMs
        if (complete || (latestEpisodeAt != null && latestEpisodeAt < now - 180L * DAY_MS)) {
            i = maxOf(i, DAY_MS)
        }
        val hintMs =
            maxOf(ttlMinutes?.toLong()?.times(60_000L) ?: Long.MIN_VALUE, maxAgeSec?.times(1000L) ?: Long.MIN_VALUE)
        if (hintMs != Long.MIN_VALUE) {
            i = maxOf(i, minOf(hintMs, DAY_MS))
        }
        return now + i
    }

    /**
     * Failure row: `now + min(30 min · 2^(n−1), 24 h) · random(0.8–1.2)` with `n` = the incremented
     * `failureCount`, or `now + Retry-After` (≤ 7 d) if that is later.
     */
    fun failureNextRefreshAt(
        now: Long,
        failureCount: Int,
        retryAfterMs: Long?,
        random: Random,
    ): Long {
        val shift = (failureCount - 1).coerceIn(0, 30)
        val backoff = minOf(30L * 60_000L * (1L shl shift), DAY_MS)
        val jittered = (backoff * (0.8 + random.nextDouble() * 0.4)).toLong()
        val retryAfter = retryAfterMs?.let { minOf(it, 7L * DAY_MS) }
        return now + maxOf(jittered, retryAfter ?: Long.MIN_VALUE)
    }

    /** The `LOCAL_NETWORK_UNSUPPORTED` row: `now + 24 h`, kind recorded (03 policy table). */
    fun localNetworkNextRefreshAt(now: Long): Long = now + DAY_MS

    /** A `feeds.refresh_interval_minutes` value → minutes, or `null` for "Manual only" (PO-21). */
    fun effectiveIntervalMinutes(globalMinutes: Int): Int? = globalMinutes.takeIf { it > 0 }
}
