// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.StartGate
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakePlaybackMediaLookup : PlaybackMediaLookup {
    val items = MutableStateFlow<Map<Long, PlaybackMediaInfo>>(emptyMap())
    val reads = mutableListOf<Long>()
    var failure: Throwable? = null
    var beforeGet: (suspend (Long) -> Unit)? = null

    override suspend fun get(episodeId: Long): PlaybackMediaInfo? {
        reads += episodeId
        beforeGet?.invoke(episodeId)
        failure?.let { throw it }
        return items.value[episodeId]
    }

    override fun observe(episodeIds: Set<Long>): Flow<Map<Long, PlaybackMediaInfo>> =
        items.map { values ->
            failure?.let { throw it }
            values.filterKeys { it in episodeIds }
        }
}

class FakeStartGate : StartGate {
    val calls = mutableListOf<Pair<PlaybackMediaInfo, String?>>()
    var refusal: PlayResult? = null

    override suspend fun check(
        item: PlaybackMediaInfo,
        localUri: String?,
    ): PlayResult? {
        calls += item to localUri
        return refusal
    }
}
