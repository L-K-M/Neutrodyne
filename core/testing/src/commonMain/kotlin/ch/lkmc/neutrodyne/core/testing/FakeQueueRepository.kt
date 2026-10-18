// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Recording seam, not a second queue algorithm: tests publish committed snapshots explicitly. */
class FakeQueueRepository : QueueRepository {
    val upNext = MutableStateFlow<List<UpNextItem>>(emptyList())
    val session = MutableStateFlow<PlaySessionInfo?>(null)
    val queue = MutableStateFlow(VirtualQueue(0, null, emptyList(), emptyList()))
    val requestedWindows = mutableListOf<Int>()
    val calls = mutableListOf<Call>()
    var addResult: AddResult = AddResult.Added(0)
    var failure: Throwable? = null

    enum class Operation { NEXT, LAST, MOVE, REMOVE, CLEAR, CLEAR_CONTEXT }

    data class Call(
        val operation: Operation,
        val ids: List<Long> = emptyList(),
        val toIndex: Int? = null,
    )

    override fun observeUpNext(): Flow<List<UpNextItem>> = upNext

    override fun observeSession(): Flow<PlaySessionInfo?> = session

    override fun observeVirtualQueue(k: Int): Flow<VirtualQueue> {
        require(k >= 0)
        requestedWindows += k
        return queue.map { it.copy(contextTail = it.contextTail.take(k)) }
    }

    private fun record(call: Call) {
        calls += call
        failure?.let { throw it }
    }

    override suspend fun addNext(episodeIds: List<Long>): AddResult {
        record(Call(Operation.NEXT, episodeIds.toList()))
        return addResult
    }

    override suspend fun addLast(episodeIds: List<Long>): AddResult {
        record(Call(Operation.LAST, episodeIds.toList()))
        return addResult
    }

    override suspend fun move(
        episodeId: Long,
        toIndex: Int,
    ) {
        record(Call(Operation.MOVE, listOf(episodeId), toIndex))
    }

    override suspend fun remove(episodeIds: List<Long>) {
        record(Call(Operation.REMOVE, episodeIds.toList()))
    }

    override suspend fun clearUpNext() {
        record(Call(Operation.CLEAR))
    }

    override suspend fun clearContext() {
        record(Call(Operation.CLEAR_CONTEXT))
    }
}
