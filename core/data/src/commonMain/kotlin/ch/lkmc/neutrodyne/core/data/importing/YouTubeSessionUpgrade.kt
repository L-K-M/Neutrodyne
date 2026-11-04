// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier

/** Upgrade only reopened M3 previews. Finished reports remain a record of the build that ran them. */
internal suspend fun upgradeYouTubePreview(
    db: NeutrodyneDatabase,
    sessionId: Long,
    entries: List<ImportEntry>? = null,
) {
    db.withWriteTransaction<Unit> {
        val dao = db.importDao()
        val session = dao.sessionById(sessionId) ?: return@withWriteTransaction
        if (session.state != ImportState.PREVIEW || session.hasRestoreIntent()) return@withWriteTransaction
        val options = session.optionsJson?.let(ImportOptionsJson::decodeOrNull) ?: ImportOptions()
        val rows =
            dao.itemsByStatus(sessionId, listOf(ImportItemStatus.YOUTUBE_UNSUPPORTED_YET)).filter {
                it.errorDetail !=
                    "playlist"
            }
        if (rows.isEmpty()) return@withWriteTransaction
        val parsed = entries?.associateBy { it.ordinal }.orEmpty()
        val classified =
            ImportClassifier(db, YouTubeUrlClassifier())
                .classify(
                    rows.map {
                        parsed[it.ordinal] ?: ImportEntry(
                            it.ordinal,
                            options.itemYouTubeInputs[it.ordinal] ?: it.originalUrl,
                            it.title,
                            htmlUrl = it.normalizedUrl,
                            groupNames =
                                kotlinx.serialization.json.Json.decodeFromString<List<String>>(
                                    it.groupNamesJson,
                                ),
                            youtubeVariantsHint = options.itemYouTubeVariants[it.ordinal],
                        )
                    },
                ).items
                .filter { it.youTubeInput != null }
        val byOrdinal = rows.associateBy { it.ordinal }
        for (item in classified) {
            val row = byOrdinal.getValue(item.ordinal)
            dao.updateItem(
                row.copy(
                    normalizedUrl = item.normalizedUrl,
                    groupNamesJson =
                        kotlinx.serialization.json.Json
                            .encodeToString(item.groupNames),
                    status = item.status,
                    selected = item.selected,
                    podcastId = item.podcastId,
                    errorDetail = item.errorDetail,
                ),
            )
        }
        dao.setOptionsJson(
            sessionId,
            ImportOptionsJson.encode(
                options.copy(
                    itemYouTubeInputs =
                        options.itemYouTubeInputs +
                            classified.mapNotNull { it.youTubeInput?.let { input -> it.ordinal to input } }.toMap(),
                    itemYouTubeVariants =
                        options.itemYouTubeVariants +
                            classified.mapNotNull { it.youTubeVariants?.let { mask -> it.ordinal to mask } }.toMap(),
                ),
            ),
        )
    }
}
