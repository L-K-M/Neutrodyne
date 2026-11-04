// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.domain.YouTubeAvailabilityRecorder
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.youtube.api.VideoFacts
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class YouTubeFactsWriterImpl(
    private val db: NeutrodyneDatabase,
    private val sanitizer: ShowNotesSanitizer,
) : YouTubeFactsWriter {
    override suspend fun applyFacts(
        podcastId: Long,
        facts: List<VideoFacts>,
    ) {
        require(facts.size <= 90)
        val descriptions = facts.map { prepare(it.description, it.descriptionTruncated) }
        currentCoroutineContext().ensureActive()
        db.withWriteTransaction {
            if (db.podcastDao().byId(podcastId)?.sourceType != SourceType.YOUTUBE_CHANNEL) return@withWriteTransaction
            for ((index, fact) in facts.withIndex()) {
                if (!YouTubeIds.VIDEO.matches(fact.videoId)) continue
                currentCoroutineContext().ensureActive()
                db.ingestDao().applyYouTubeFacts(
                    podcastId,
                    fact.videoId,
                    fact.durationMs?.takeIf { it > 0 },
                    fact.availability,
                    fact.isShort == true,
                )
                val description = descriptions[index] ?: continue
                val row = db.episodeDao().youTubeByVideoId(podcastId, fact.videoId) ?: continue
                fill(row.id, description)
            }
        }
    }

    override suspend fun fillDescriptionIfAbsent(
        episodeId: Long,
        description: String,
        completeness: DescriptionCompleteness,
    ) {
        val prepared = prepare(description, completeness != DescriptionCompleteness.COMPLETE) ?: return
        currentCoroutineContext().ensureActive()
        db.withWriteTransaction {
            val row = db.episodeDao().byId(episodeId) ?: return@withWriteTransaction
            if (db.podcastDao().byId(row.podcastId)?.sourceType !=
                SourceType.YOUTUBE_CHANNEL
            ) {
                return@withWriteTransaction
            }
            fill(episodeId, prepared)
        }
    }

    private fun prepare(
        text: String?,
        truncated: Boolean,
    ): Pair<ByteArray, String>? {
        if (text.isNullOrBlank() || truncated || text.encodeToByteArray().size > 32 * 1024) return null
        return EpisodeDescriptionCodec.encode(text, DescriptionFormat.PLAIN_TEXT, DescriptionCompleteness.COMPLETE) to
            sanitizer.snippet(text, false)
    }

    private suspend fun fill(
        id: Long,
        prepared: Pair<ByteArray, String>,
    ) {
        val stored = db.episodeDao().description(id)
        if (stored != null) {
            val snapshot = EpisodeDescriptionCodec.decode(stored)
            // Unknown legacy blobs stay byte-identical, even when their text is blank.
            if (snapshot.completeness == DescriptionCompleteness.UNKNOWN || snapshot.text.isNotBlank()) return
        }
        currentCoroutineContext().ensureActive()
        db.ingestDao().fillYouTubeDescription(id, prepared.first, prepared.second)
    }
}

/** Playback/download share this recorder; media failures never rewrite Atom or user state. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class YouTubeAvailabilityRecorderImpl(
    private val db: NeutrodyneDatabase,
) : YouTubeAvailabilityRecorder {
    override suspend fun record(
        episodeId: Long,
        reason: Availability,
    ) {
        db.withWriteTransaction {
            val row = db.episodeDao().byId(episodeId) ?: return@withWriteTransaction
            if (db.podcastDao().byId(row.podcastId)?.sourceType !=
                SourceType.YOUTUBE_CHANNEL
            ) {
                return@withWriteTransaction
            }
            currentCoroutineContext().ensureActive()
            db.episodeDao().setAvailability(episodeId, reason)
        }
    }
}
