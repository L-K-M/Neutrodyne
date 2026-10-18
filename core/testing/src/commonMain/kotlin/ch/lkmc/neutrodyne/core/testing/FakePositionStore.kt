// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.PositionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** In-memory guard/save seam; tests seed played timestamps and can inject a failed transaction. */
class FakePositionStore : PositionStore {
    val positions = MutableStateFlow<Map<Long, StoredPosition>>(emptyMap())
    val calls = mutableListOf<Call>()
    var failure: Throwable? = null

    enum class Operation { SAVE, RESET, PLAYED, REPLAY }

    data class Call(
        val operation: Operation,
        val id: Long,
        val now: Long,
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val source: PositionSource? = null,
        val pinStartedAt: Long? = null,
    )

    override fun observe(episodeId: Long): Flow<StoredPosition?> =
        positions.map { it[episodeId] }.distinctUntilChanged()

    private fun record(call: Call) {
        calls += call
        failure?.let { throw it }
    }

    override suspend fun save(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        source: PositionSource,
        now: Long,
        pinStartedAt: Long,
        allowed: () -> Boolean,
    ): Boolean {
        record(Call(Operation.SAVE, episodeId, now, positionMs, durationMs, source, pinStartedAt))
        val old = positions.value[episodeId]
        if (!allowed() || (positionMs == 0L && (old?.positionMs ?: 0) > 0) ||
            (old?.playedAt?.let { it >= pinStartedAt } == true)
        ) {
            return false
        }
        positions.value = positions.value + (episodeId to StoredPosition(episodeId, positionMs, now, old?.playedAt))
        return true
    }

    override suspend fun reset(
        episodeId: Long,
        now: Long,
    ) {
        record(Call(Operation.RESET, episodeId, now))
        positions.value =
            positions.value + (episodeId to StoredPosition(episodeId, 0, now, positions.value[episodeId]?.playedAt))
    }

    override suspend fun markPlayed(
        episodeId: Long,
        now: Long,
    ) {
        record(Call(Operation.PLAYED, episodeId, now))
        positions.value = positions.value + (episodeId to StoredPosition(episodeId, 0, now, now))
    }

    override suspend fun markUnplayedForReplay(
        episodeId: Long,
        now: Long,
    ) {
        record(Call(Operation.REPLAY, episodeId, now))
        val old = positions.value[episodeId]
        positions.value =
            positions.value +
            (episodeId to StoredPosition(episodeId, old?.positionMs ?: 0, old?.updatedAt ?: now, null))
    }
}
