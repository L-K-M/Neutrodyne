// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.DownloadLifecycle
import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterReader
import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterResult
import ch.lkmc.neutrodyne.core.domain.MissingLocalMediaReporter

class FakeDownloadLifecycle : DownloadLifecycle {
    val removals = mutableListOf<List<Long>>()
    val merges = mutableListOf<Pair<Long, Long>>()
    var replacements = 0
        private set
    var failure: Throwable? = null

    override suspend fun beforeEpisodeRemoval(episodeIds: List<Long>) {
        failure?.let { throw it }
        removals += episodeIds.toList()
    }

    override suspend fun beforeEpisodeMerge(
        loserEpisodeId: Long,
        winnerEpisodeId: Long,
    ) {
        failure?.let { throw it }
        merges += loserEpisodeId to winnerEpisodeId
    }

    override suspend fun beforeLibraryReplace() {
        failure?.let { throw it }
        replacements++
    }
}

class FakeMissingLocalMediaReporter : MissingLocalMediaReporter {
    val reports = mutableListOf<Long>()
    var failure: Throwable? = null

    override suspend fun reportFileMissing(episodeId: Long) {
        failure?.let { throw it }
        reports += episodeId
    }
}

class FakeEmbeddedChapterReader : EmbeddedChapterReader {
    val reads = mutableListOf<String>()
    var result: EmbeddedChapterResult = EmbeddedChapterResult.Unavailable
    var failure: Throwable? = null

    override suspend fun read(fileUri: String): EmbeddedChapterResult {
        reads += fileUri
        failure?.let { throw it }
        return result
    }
}
