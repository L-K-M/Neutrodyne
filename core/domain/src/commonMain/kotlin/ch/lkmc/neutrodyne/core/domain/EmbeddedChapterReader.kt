// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.EpisodeChapter

/** Main-safe local-only reader. No network, including files without a chapters URL. */
interface EmbeddedChapterReader {
    suspend fun read(fileUri: String): EmbeddedChapterResult
}

sealed interface EmbeddedChapterResult {
    data class Read(
        val chapters: List<EpisodeChapter>,
    ) : EmbeddedChapterResult

    data object Unavailable : EmbeddedChapterResult

    data object MissingFile : EmbeddedChapterResult
}
