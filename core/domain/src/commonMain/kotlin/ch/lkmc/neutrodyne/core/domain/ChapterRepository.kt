// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import kotlinx.coroutines.flow.Flow

/** Main-safe local enrichment, implemented/bound in core:data (M5). No player dependency. */
interface ChapterRepository {
    /** Normalized winning source, hidden rows excluded; empty for absent/deleted episodes. */
    fun observe(episodeId: Long): Flow<List<EpisodeChapter>>

    /** Best-effort JSON load; coalesce calls, preserve cache on failure, propagate cancellation.
     * Offline skips consume no attempt; failures back off 24 h per episode/URL. URL changes reset
     * retry state; check URL/existence inside save transaction. Empty success completes occurrence.
     * Local-file/EmbeddedChapterReader support is M6. */
    suspend fun ensureLoaded(episodeId: Long)

    /** Atomic source-only replacement including hidden rows; require matching sources. Identical
     * normalized lists and deleted episodes are no-ops; failure rolls back/propagates. Caller must
     * reject stale occurrences. JSON additionally guards URL in its save transaction. */
    suspend fun replace(
        episodeId: Long,
        source: ChapterSource,
        chapters: List<EpisodeChapter>,
    )
}
