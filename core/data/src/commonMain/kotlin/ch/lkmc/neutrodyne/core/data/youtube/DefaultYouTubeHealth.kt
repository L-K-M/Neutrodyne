// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys as Keys

/** One ordered writer for non-suspending health reports and device persistence. No engine in M8. */
internal class DefaultYouTubeHealth(
    private val settings: SettingsRepository,
    private val clock: Clock,
    scope: CoroutineScope,
    private val scheduleRetry: suspend () -> Unit,
) : YouTubeHealth {
    private val mutableState = MutableStateFlow(YouTubeHealthState())
    override val state = mutableState.asStateFlow()
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

    override fun retryNow() =
        submit {
            mutableState.value =
                state.value.copy(
                    feedOutageUntil = clock.now().takeIf { state.value.feedOutageLevel > 0 },
                    feedRateLimitedUntil = null,
                )
            persist()
            scheduleRetry()
        }

    // External mode never starts extraction. The engine breaker and engine keys belong to M9.
    override fun extractionGate(nowMs: Long): ExtractionGate = ExtractionGate.Allow

    override fun reportExtraction(outcome: ExtractionOutcome) = Unit

    override fun reportRateLimited(nowMs: Long) = Unit

    override fun reportEngineVersion(version: String?) = Unit

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
