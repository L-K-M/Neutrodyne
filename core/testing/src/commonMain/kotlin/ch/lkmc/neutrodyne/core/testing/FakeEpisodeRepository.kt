// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.ShowNotes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [EpisodeRepository] (09 `:core:testing` inventory): per-id [episodes]/[notes] maps
 * the tests push into, and a recorded write log.
 */
class FakeEpisodeRepository : EpisodeRepository {
    val episodes = MutableStateFlow<Map<Long, EpisodeDetail>>(emptyMap())
    val notes = MutableStateFlow<Map<Long, ShowNotes>>(emptyMap())

    val calls = mutableListOf<String>()

    override fun observeEpisode(episodeId: Long): Flow<EpisodeDetail?> = episodes.map { it[episodeId] }

    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> = notes.map { it[episodeId] }

    override suspend fun setPlayed(
        episodeIds: List<Long>,
        played: Boolean,
    ) {
        calls += "setPlayed($episodeIds, $played)"
    }

    override suspend fun markFeedPlayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ) {
        calls += "markFeedPlayed($source, $sortDateBefore)"
    }

    override suspend fun setFavorite(
        episodeId: Long,
        favorite: Boolean,
    ) {
        calls += "setFavorite($episodeId, $favorite)"
    }
}
