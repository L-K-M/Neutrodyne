// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** In-memory source isolation/selection; loading is recorded, never fakes network success. */
class FakeChapterRepository : ChapterRepository {
    val rows = MutableStateFlow<Map<Long, List<EpisodeChapter>>>(emptyMap())
    val loads = mutableListOf<Long>()
    val writes = mutableListOf<Write>()
    var failure: Throwable? = null

    data class Write(
        val episodeId: Long,
        val source: ChapterSource,
        val chapters: List<EpisodeChapter>,
    )

    override fun observe(episodeId: Long): Flow<List<EpisodeChapter>> =
        rows.map { ChapterSource.winning(it[episodeId].orEmpty()) }.distinctUntilChanged()

    override suspend fun ensureLoaded(episodeId: Long) {
        loads += episodeId
        failure?.let { throw it }
    }

    override suspend fun replace(
        episodeId: Long,
        source: ChapterSource,
        chapters: List<EpisodeChapter>,
    ) {
        require(chapters.all { it.source == source })
        failure?.let { throw it }
        val normalized = ChapterSource.normalize(chapters)
        val old = rows.value[episodeId].orEmpty()
        if (ChapterSource.normalize(old.filter { it.source == source }) == normalized) return
        writes += Write(episodeId, source, normalized)
        rows.value = rows.value + (episodeId to (old.filterNot { it.source == source } + normalized))
    }
}
