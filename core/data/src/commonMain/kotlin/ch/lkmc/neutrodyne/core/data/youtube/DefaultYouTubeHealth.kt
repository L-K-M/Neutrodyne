// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractionReservation
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys as Keys

/** Feed commands retain M8 ordering; extraction publishes by CAS before queued snapshot IO. */
internal class DefaultYouTubeHealth(
    private val settings: SettingsRepository,
    private val clock: Clock,
    scope: CoroutineScope,
    private val appVersionCode: Int = 0,
    private val invalidateUrls: () -> Unit = {},
    private val scheduleRetry: suspend () -> Unit,
) : YouTubeExtractionHealth {
    private val mutableState = MutableStateFlow(YouTubeHealthState())
    private val extraction = MutableStateFlow(ExtractionHealthState())

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    override val state: StateFlow<YouTubeHealthState> =
        object : StateFlow<YouTubeHealthState> {
            override val value get() = extraction.value.project(mutableState.value)
            override val replayCache get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<YouTubeHealthState>): Nothing {
                combine(mutableState, extraction) { feed, engine -> engine.project(feed) }
                    .distinctUntilChanged()
                    .collect(collector)
                error("Health state collection ended")
            }
        }
    private var persistedRevision = -1L
    private val loaded = CompletableDeferred<Unit>()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var rateLimitLevel = 0

    init {
        scope.launch {
            try {
                val outageUntil = settings.get(Keys.FEED_OUTAGE_UNTIL).takeIf { it > 0 }
                val rateUntil = settings.get(Keys.FEED_RATE_LIMITED_UNTIL).takeIf { it > 0 }
                rateLimitLevel = settings.get(Keys.FEED_RATE_LIMIT_LEVEL).coerceIn(0, MAX_RATE_LEVEL)
                mutableState.value =
                    YouTubeHealthState(
                        feedOutageUntil = outageUntil,
                        feedOutageLevel =
                            settings
                                .get(Keys.FEED_OUTAGE_LEVEL)
                                .coerceIn(0, MAX_OUTAGE_LEVEL)
                                .coerceAtLeast(if (outageUntil != null) 1 else 0),
                        feedRateLimitedUntil = rateUntil,
                    )
                val savedApp = settings.get(Keys.BREAKER_VERSION_CODE)
                val savedEngine = settings.get(Keys.BREAKER_ENGINE_VERSION).ifEmpty { null }
                val resetApp = appVersionCode > 0 && savedApp != appVersionCode
                val restored =
                    ExtractionHealthState(
                        revision = if (resetApp) 1 else 0,
                        engineVersion = savedEngine,
                        openUntil =
                            settings.get(Keys.BREAKER_OPEN_UNTIL).takeIf {
                                it > 0 && savedApp > 0 &&
                                    savedApp == appVersionCode
                            },
                        lastOpenedAt =
                            settings.get(Keys.BREAKER_LAST_OPENED_AT).takeIf {
                                it > 0 && savedApp > 0 &&
                                    savedApp == appVersionCode
                            },
                        rateUntil = settings.get(Keys.RATE_LIMITED_UNTIL).takeIf { it > 0 && savedApp > 0 },
                        rateLevel =
                            settings
                                .get(Keys.RATE_LIMIT_LEVEL)
                                .coerceIn(0, MAX_RATE_LEVEL)
                                .takeIf { savedApp > 0 } ?: 0,
                    )
                // A synchronous report/reset during loading wins over disk.
                if (extraction.compareAndSet(ExtractionHealthState(), restored) && resetApp) {
                    submit { persistExtraction(restored) }
                }
                loaded.complete(Unit)
            } catch (error: Exception) {
                loaded.completeExceptionally(error)
                throw error
            }
            for (command in commands) {
                try {
                    command()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, error) { "YouTube health persistence or retry failed" }
                }
            }
        }
    }

    /** Also drains earlier reports, so gate decisions cannot overtake a queued outage report. */
    override suspend fun awaitLoaded() {
        loaded.await()
        val barrier = CompletableDeferred<Unit>()
        commands.send { barrier.complete(Unit) }
        barrier.await()
    }

    override fun reportFeedOutage(nowMs: Long) =
        submit {
            val level = (state.value.feedOutageLevel + 1).coerceAtMost(MAX_OUTAGE_LEVEL)
            mutableState.value =
                state.value.copy(
                    feedOutageLevel = level,
                    feedOutageUntil = nowMs + OUTAGE_HOURS[level - 1] * HOUR_MS,
                )
            persist()
        }

    override fun reportFeedRecovered() =
        submit {
            if (state.value.feedOutageLevel == 0 && state.value.feedRateLimitedUntil == null && rateLimitLevel == 0) {
                return@submit
            }
            rateLimitLevel = 0
            mutableState.value =
                state.value.copy(
                    feedOutageLevel = 0,
                    feedOutageUntil = null,
                    feedRateLimitedUntil = null,
                )
            persist()
        }

    override fun reportFeedRateLimited(
        nowMs: Long,
        retryAfterMs: Long?,
    ) = submit {
        rateLimitLevel = (rateLimitLevel + 1).coerceAtMost(MAX_RATE_LEVEL)
        val backoff = minOf(HALF_HOUR_MS * (1L shl (rateLimitLevel - 1)), 6 * HOUR_MS)
        mutableState.value =
            state.value.copy(
                feedRateLimitedUntil =
                    maxOf(
                        state.value.feedRateLimitedUntil ?: 0,
                        nowMs + maxOf(backoff, retryAfterMs?.coerceIn(0, 7 * DAY_MS) ?: 0),
                    ),
            )
        persist()
    }

    override fun retryNow() {
        changeExtraction {
            it.copy(openUntil = it.openUntil?.let { clock.now() }, halfOpen = it.openUntil != null, rateUntil = null)
        }
        submit {
            mutableState.value =
                state.value.copy(
                    feedOutageUntil = clock.now().takeIf { state.value.feedOutageLevel > 0 },
                    feedRateLimitedUntil = null,
                )
            persist()
            scheduleRetry()
        }
    }

    override fun extractionGate(nowMs: Long): ExtractionGate {
        changeExtraction {
            if (it.openUntil != null && nowMs >= it.openUntil && !it.halfOpen) it.copy(halfOpen = true) else it
        }
        return extraction.value.gate(nowMs)
    }

    override fun reserveExtraction(nowMs: Long): ExtractionAdmission {
        while (true) {
            val before = extraction.value
            val gate = before.gate(nowMs)
            if (gate is ExtractionGate.Deny) return ExtractionAdmission.Denied(gate)
            val id = before.nextId + 1
            val after =
                before.copy(
                    nextId = id,
                    reservations = before.reservations + id,
                    trial = if (gate == ExtractionGate.AllowTrial) id else before.trial,
                    halfOpen = before.halfOpen || gate == ExtractionGate.AllowTrial,
                )
            if (extraction.compareAndSet(before, after)) {
                return ExtractionAdmission.Admitted(ExtractionReservation(id))
            }
        }
    }

    override fun completeExtraction(
        reservation: ExtractionReservation,
        outcome: ExtractionOutcome,
    ) {
        changeExtraction { before ->
            if (reservation.id !in before.reservations) return@changeExtraction before
            before.report(outcome, clock.now(), reservation.id == before.trial).copy(
                reservations = before.reservations - reservation.id,
                trial = if (reservation.id == before.trial) null else before.trial,
            )
        }
    }

    // Compatibility/media reports never take or finish a trial. Admitted extraction uses tokens.
    override fun reportExtraction(outcome: ExtractionOutcome) {
        changeExtraction { it.report(outcome, clock.now(), false) }
    }

    override fun reportRateLimited(nowMs: Long) {
        changeExtraction {
            val level = (it.rateLevel + 1).coerceAtMost(MAX_RATE_LEVEL)
            it.copy(
                rateLevel = level,
                rateUntil =
                    maxOf(
                        it.rateUntil ?: 0,
                        nowMs + minOf(HALF_HOUR_MS * (1L shl (level - 1)), 6 * HOUR_MS),
                    ),
            )
        }
    }

    override fun reportEngineVersion(version: String?) {
        changeExtraction {
            if (it.engineVersion == version) {
                it
            } else {
                ExtractionHealthState(
                    engineVersion = version,
                    nextId = it.nextId,
                    rateUntil = it.rateUntil,
                    rateLevel = it.rateLevel,
                )
            }
        }
    }

    private fun changeExtraction(transform: (ExtractionHealthState) -> ExtractionHealthState) {
        while (true) {
            val before = extraction.value
            val next = transform(before)
            if (next == before) return
            val opening = next.invalidating && !before.invalidating
            val after =
                next.copy(
                    revision = before.revision + 1,
                    cacheEpoch = if (opening) before.revision + 1 else next.cacheEpoch,
                )
            if (!extraction.compareAndSet(before, after)) continue
            // Block admission during invalidation, including reentrant cache callbacks. No IO here.
            if (opening) {
                try {
                    invalidateUrls()
                } finally {
                    changeExtraction { if (it.cacheEpoch == after.cacheEpoch) it.copy(invalidating = false) else it }
                }
            }
            submit { persistExtraction(after) }
            return
        }
    }

    private suspend fun persistExtraction(snapshot: ExtractionHealthState) {
        // Concurrent reporters may enqueue out of CAS order. Never write an older/reset-era snapshot.
        if (snapshot.revision <= persistedRevision || snapshot.revision < extraction.value.revision) return
        // Identity is the commit marker: an interrupted multi-key write is not restored as valid.
        checkedSet(Keys.BREAKER_VERSION_CODE, 0)
        checkedSet(Keys.BREAKER_OPEN_UNTIL, snapshot.openUntil ?: 0)
        checkedSet(Keys.BREAKER_LAST_OPENED_AT, snapshot.lastOpenedAt ?: 0)
        checkedSet(Keys.RATE_LIMITED_UNTIL, snapshot.rateUntil ?: 0)
        checkedSet(Keys.RATE_LIMIT_LEVEL, snapshot.rateLevel)
        checkedSet(Keys.BREAKER_ENGINE_VERSION, snapshot.engineVersion ?: "")
        if (snapshot.revision != extraction.value.revision) return
        checkedSet(Keys.BREAKER_VERSION_CODE, appVersionCode)
        if (snapshot.revision != extraction.value.revision) {
            checkedSet(Keys.BREAKER_VERSION_CODE, 0)
            return
        }
        persistedRevision = snapshot.revision
    }

    private suspend fun <T : Any> checkedSet(
        key: ch.lkmc.neutrodyne.core.model.settings.SettingKey<T>,
        value: T,
    ) {
        check(settings.set(key, value) !is Outcome.Failure) { "Could not persist YouTube extraction health" }
    }

    private fun submit(command: suspend () -> Unit) {
        check(commands.trySend(command).isSuccess) { "YouTube health writer stopped" }
    }

    private suspend fun persist() {
        // Write the deadline first: an interrupted pair is conservatively loaded as level one.
        val writes =
            listOf(
                settings.set(Keys.FEED_OUTAGE_UNTIL, state.value.feedOutageUntil ?: 0),
                settings.set(Keys.FEED_OUTAGE_LEVEL, state.value.feedOutageLevel),
                settings.set(Keys.FEED_RATE_LIMITED_UNTIL, state.value.feedRateLimitedUntil ?: 0),
                settings.set(Keys.FEED_RATE_LIMIT_LEVEL, rateLimitLevel),
            )
        check(writes.none { it is Outcome.Failure }) { "Could not persist YouTube feed health" }
    }

    private companion object {
        const val TAG = "YouTubeHealth"
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val HALF_HOUR_MS = HOUR_MS / 2
        const val MAX_OUTAGE_LEVEL = 4
        const val MAX_RATE_LEVEL = 5
        val OUTAGE_HOURS = longArrayOf(1, 2, 4, 6)
    }
}
