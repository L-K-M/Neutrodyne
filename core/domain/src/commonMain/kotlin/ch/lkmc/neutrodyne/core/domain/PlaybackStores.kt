// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import kotlinx.coroutines.flow.Flow

/**
 * Main-safe persistence consumed only by playback core/adapters. Each mutation is one serialized
 * transaction, ensures the singleton, saves departure before arrival and increments generation
 * once. Return only after commit; rollback/failure throws, cancellation propagates. No sync
 * applying flag. Readers never see an intermediate current/queue/anchor combination.
 */
interface PlaySessionStore {
    /** Same coherent shape as QueueRepository.observeVirtualQueue; k >= 0. */
    suspend fun virtualQueue(k: Int): VirtualQueue

    /** Save start.departure before arrival, promote/remove current from Up next, touch last-played,
     * and set context/anchor as requested, all in the same transaction. */
    suspend fun commitStart(
        start: SessionStart,
        now: Long,
    ): Long

    /** Save departure, promote arrival/remove from Up next; advance anchor only for CONTEXT. */
    suspend fun onTransition(
        departure: Departure?,
        arrivedEpisodeId: Long,
        arrivedOrigin: QueueOrigin,
        now: Long,
    ): Long

    /** Previous item becomes current; requeue interrupted item at the head.
     * Restore the anchor only if previous.origin is CONTEXT; history must retain that origin. */
    suspend fun goBack(
        previous: QueueItem,
        departure: Departure,
        now: Long,
    ): Long

    /** Natural end: save departure and clear current/context/anchor, retaining Up next. */
    suspend fun endSession(
        departure: Departure?,
        now: Long,
    ): Long

    /** Explicit dismissal: same durable clearing, no implicit mark-played. */
    suspend fun clearCurrent(
        departure: Departure?,
        now: Long,
    ): Long
}

enum class ContextRetention { REPLACE, KEEP }

/** KEEP ignores context/anchor fields; REPLACE with null context means EXTERNAL, no tail. */
data class SessionStart(
    val currentEpisodeId: Long,
    val context: PlayContextSpec?,
    val contextRetention: ContextRetention,
    val anchorEpisodeId: Long?,
    val anchorSortDate: Long?,
    /** Interrupted current, captured after start gates; save/mark before promoting arrival. */
    val departure: Departure? = null,
)

/** Core-decided save/played flags; pinStartedAt is wall-clock start time, never a tick timestamp. */
data class Departure(
    val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long?,
    val source: PositionSource,
    val pinStartedAt: Long,
    val markPlayed: Boolean,
    val writePosition: Boolean,
)

/**
 * Main-safe position persistence. Writes serialize and return after commit; failures propagate
 * for caller retry. Milliseconds/now are non-negative wall-clock values where timestamps apply.
 * Regular saves never replace non-zero with zero and are rejected when the stored
 * `episode_state.playedAt >= pinStartedAt`; reset bypasses the zero guard.
 */
interface PositionStore {
    fun observe(episodeId: Long): Flow<StoredPosition?>

    /** allowed is a fast thread-safe predicate read INSIDE the transaction. False writes nothing. */
    suspend fun save(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        source: PositionSource,
        now: Long,
        pinStartedAt: Long,
        allowed: () -> Boolean,
    ): Boolean

    /** Explicit zero clears started status while preserving played status. */
    suspend fun reset(
        episodeId: Long,
        now: Long,
    )

    /** Atomic mark-played chain: played timestamp, position reset, remove from Up next. */
    suspend fun markPlayed(
        episodeId: Long,
        now: Long,
    )

    /** Clear played/started at first actual replay, not at prepare or buffering. */
    suspend fun markUnplayedForReplay(
        episodeId: Long,
        now: Long,
    )
}

data class StoredPosition(
    val episodeId: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val playedAt: Long?,
)
