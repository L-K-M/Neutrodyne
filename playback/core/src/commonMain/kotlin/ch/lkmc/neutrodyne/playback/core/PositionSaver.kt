// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.model.PositionSource
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** User seeks can reset; setup/pickup seeks must never overwrite durable progress. */
enum class SeekOrigin { USER, CORE }

sealed interface PositionSaveResult {
    data object Saved : PositionSaveResult

    data object Skipped : PositionSaveResult

    data class Failed(
        val cause: Exception,
    ) : PositionSaveResult
}

/** Immutable teardown/transition capture, safe to pass to an application-scope writer. */
class PositionCapture internal constructor(
    val departure: Departure,
    internal val allowed: () -> Boolean,
)

/**
 * One instance per player. The adapter applies the start seek before [startPositionApplied], captures
 * departure before changing current, then commits it through PlaySessionStore before loading arrival.
 * Failed regular writes return evidence to the adapter and leave the tick due for retry.
 */
@Inject
class PositionSaver(
    private val store: PositionStore,
    private val clock: Clock,
    private val playedRule: PlayedRule,
) {
    private data class Current(
        val episodeId: Long,
        val since: Long,
        val source: PositionSource,
        val replay: Boolean,
        val allowed: () -> Boolean,
        var ready: Boolean = false,
        var hasPlayed: Boolean = false,
        var replayCleared: Boolean = false,
        var resetPending: Boolean = false,
        var lastSavedElapsed: Long,
        /** The last position written or applied for this item; the paused-item pickup compares it. */
        var lastKnownMs: Long? = null,
    )

    private var current: Current? = null

    /** allowed is re-read inside storage; a later freeze can reject an already queued save. */
    fun load(
        episodeId: Long,
        currentSinceMs: Long,
        source: PositionSource,
        replay: Boolean = false,
        allowed: () -> Boolean = { true },
    ) {
        current =
            Current(episodeId, currentSinceMs, source, replay, allowed, lastSavedElapsed = clock.elapsedRealtime())
    }

    /** The adapter applies its start seek (positionMs) before or without this when it is unknown. */
    fun startPositionApplied(appliedPositionMs: Long? = null) {
        current?.ready = true
        appliedPositionMs?.let { ms -> current?.lastKnownMs = ms.coerceAtLeast(0) }
    }

    /**
     * 06 Sync interplay rule 4: the stored position to take up at the next resume of the paused
     * current item — only when a row exists (a written reset to 0 counts) and it diverges from the
     * last position written or applied by more than [PICKUP_BOUND_MS]. Null when nothing is
     * loaded, nothing was written or applied yet, no row exists, or the row agrees. An absent row
     * is "nothing was written", never a reset to pick up.
     */
    fun pickupPositionMs(storedPositionMs: Long?): Long? {
        val item = current ?: return null
        val known = item.lastKnownMs ?: return null
        val stored = storedPositionMs ?: return null
        return if (abs(stored - known) > PICKUP_BOUND_MS) stored else null
    }

    /** A pickup seek the core requested: it writes nothing, but becomes the known position. */
    fun pickupApplied(pickedUpMs: Long) {
        current?.lastKnownMs = pickedUpMs.coerceAtLeast(0)
    }

    /** Replay is cleared at first actual playback, with retries if that transaction fails. */
    suspend fun onPlaying(): PositionSaveResult {
        val item = current ?: return PositionSaveResult.Skipped
        if (!item.ready || !item.allowed()) return PositionSaveResult.Skipped

        item.hasPlayed = true
        if (!item.replay || item.replayCleared) return PositionSaveResult.Skipped

        return attempt {
            store.markUnplayedForReplay(item.episodeId, clock.now())
            item.replayCleared = true
            PositionSaveResult.Saved
        }
    }

    suspend fun onTick(
        positionMs: Long,
        durationMs: Long?,
        advancing: Boolean,
    ): PositionSaveResult {
        val item = current ?: return PositionSaveResult.Skipped
        if (!advancing ||
            clock.elapsedRealtime() - item.lastSavedElapsed < SAVE_INTERVAL_MS
        ) {
            return PositionSaveResult.Skipped
        }

        // A failed replay-clear must retry before a save meets the played-after-start guard.
        if (item.replay && !item.replayCleared && item.hasPlayed) {
            val replayResult = onPlaying()
            if (replayResult is PositionSaveResult.Failed) return replayResult
        }
        if (current !== item) return PositionSaveResult.Skipped
        return save(positionMs, durationMs)
    }

    suspend fun onPause(
        positionMs: Long,
        durationMs: Long?,
    ): PositionSaveResult = save(positionMs, durationMs)

    suspend fun onSeek(
        positionMs: Long,
        durationMs: Long?,
        origin: SeekOrigin,
    ): PositionSaveResult {
        require(positionMs >= 0)
        if (origin == SeekOrigin.CORE) return PositionSaveResult.Skipped
        val item = current ?: return PositionSaveResult.Skipped
        if (!item.ready || !item.allowed()) return PositionSaveResult.Skipped
        if (positionMs >= RESET_BOUND_MS) return save(positionMs, durationMs)

        item.resetPending = true
        return reset(item)
    }

    fun capture(
        positionMs: Long,
        durationMs: Long?,
        exit: PlaybackExit = PlaybackExit.PAUSE,
    ): PositionCapture? {
        require(positionMs >= 0)
        val item = current ?: return null
        val allowed = item.allowed()
        return PositionCapture(
            Departure(
                item.episodeId,
                positionMs,
                durationMs?.takeIf { it > 0 },
                item.source,
                item.since,
                allowed && playedRule.shouldMarkPlayed(exit, positionMs, durationMs, item.hasPlayed),
                allowed && item.ready,
            ),
            item.allowed,
        )
    }

    /** Position-only flush. Session transactions consume departure to persist its played decision. */
    suspend fun flush(capture: PositionCapture): PositionSaveResult {
        val departure = capture.departure
        if (!departure.writePosition) return PositionSaveResult.Skipped

        return attempt {
            val saved =
                store.save(
                    departure.episodeId,
                    departure.positionMs,
                    departure.durationMs,
                    departure.source,
                    clock.now(),
                    departure.pinStartedAt,
                    capture.allowed,
                )
            if (saved) {
                current?.takeIf { it.episodeId == departure.episodeId }?.lastKnownMs =
                    departure.positionMs.coerceAtLeast(0)
                PositionSaveResult.Saved
            } else {
                PositionSaveResult.Skipped
            }
        }
    }

    suspend fun flush(
        positionMs: Long,
        durationMs: Long?,
    ): PositionSaveResult = save(positionMs, durationMs)

    fun clear() {
        current = null
    }

    private suspend fun reset(item: Current): PositionSaveResult {
        if (!item.allowed()) return PositionSaveResult.Skipped

        return attempt {
            store.reset(item.episodeId, clock.now())
            item.resetPending = false
            item.lastSavedElapsed = clock.elapsedRealtime()
            item.lastKnownMs = 0L
            PositionSaveResult.Saved
        }
    }

    private suspend fun save(
        positionMs: Long,
        durationMs: Long?,
    ): PositionSaveResult {
        val item = current
        if (item?.resetPending == true) {
            val resetResult = reset(item)
            if (resetResult != PositionSaveResult.Saved || positionMs < RESET_BOUND_MS) return resetResult
            if (current !== item) return PositionSaveResult.Skipped
        }
        val capture = capture(positionMs, durationMs) ?: return PositionSaveResult.Skipped
        val result = flush(capture)
        if (result == PositionSaveResult.Saved && current === item) item?.lastSavedElapsed = clock.elapsedRealtime()
        return result
    }

    private suspend fun attempt(block: suspend () -> PositionSaveResult): PositionSaveResult =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PositionSaveResult.Failed(failure)
        }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val RESET_BOUND_MS = 1_000L

        /** 06 Positions guard 6: a divergence beyond this (reset to 0 included) is picked up. */
        const val PICKUP_BOUND_MS = 1_000L
    }
}
