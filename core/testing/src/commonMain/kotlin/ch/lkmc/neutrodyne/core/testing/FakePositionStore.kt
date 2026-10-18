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

    // Episode played state can exist before the first position row, just as it does in Room.
    private val playedWithoutPosition = mutableMapOf<Long, Long>()
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
        val playedAt = old?.playedAt ?: playedWithoutPosition[episodeId]
        if (!allowed() || (positionMs == 0L && (old?.positionMs ?: 0) > 0) ||
            (playedAt?.let { it >= pinStartedAt } == true)
        ) {
            return false
        }
        positions.value = positions.value + (episodeId to StoredPosition(episodeId, positionMs, now, playedAt))
        playedWithoutPosition.remove(episodeId)
        return true
    }

    override suspend fun reset(
        episodeId: Long,
        now: Long,
    ) {
        record(Call(Operation.RESET, episodeId, now))
        val old = positions.value[episodeId] ?: return
        positions.value = positions.value + (episodeId to old.copy(positionMs = 0, updatedAt = now))
    }

    override suspend fun markPlayed(
        episodeId: Long,
        now: Long,
    ) {
        record(Call(Operation.PLAYED, episodeId, now))
        val old = positions.value[episodeId]
        if (old == null) {
            playedWithoutPosition.getOrPut(episodeId) { now }
            return
        }
        positions.value = positions.value + (episodeId to old.copy(positionMs = 0, updatedAt = now, playedAt = now))
    }

    override suspend fun markUnplayedForReplay(
        episodeId: Long,
        now: Long,
    ) {
        record(Call(Operation.REPLAY, episodeId, now))
        playedWithoutPosition.remove(episodeId)
        val old = positions.value[episodeId] ?: return
        positions.value = positions.value + (episodeId to old.copy(playedAt = null))
    }
}
