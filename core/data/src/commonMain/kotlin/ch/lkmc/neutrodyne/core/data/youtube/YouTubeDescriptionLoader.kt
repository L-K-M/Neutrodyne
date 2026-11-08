// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** W7 receives an immutable complete facts description, never a later read of an unmarked blob.
 * Existing blob completeness is unknown until the parent freezes read provenance; keep its text
 * unchanged and return null. Last waiter owns cancellation; incomplete facts never reach storage.
 */
@SingleIn(AppScope::class)
@Inject
internal class YouTubeDescriptionLoader(
    private val db: NeutrodyneDatabase,
    private val enricher: YouTubeEnricher,
    private val writer: YouTubeFactsWriter,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class Flight(
        val result: Deferred<VideoFactsResult>,
        var waiters: Int = 0,
    )

    private val lock = Mutex()
    private val flights = mutableMapOf<String, Flight>()

    suspend fun load(episodeId: Long): String? {
        val deadline = clock.elapsedRealtime() + 45_000
        return withTimeoutOrNull(45_000) {
            val row = db.episodeDao().byId(episodeId) ?: return@withTimeoutOrNull null
            val channel = db.podcastDao().byId(row.podcastId) ?: return@withTimeoutOrNull null
            val video = row.externalMediaId ?: return@withTimeoutOrNull null
            if (channel.sourceType != SourceType.YOUTUBE_CHANNEL) return@withTimeoutOrNull null
            db.episodeDao().description(episodeId)?.let {
                if (EpisodeDescriptionCodec.decode(it).text.isNotBlank()) return@withTimeoutOrNull null
            }
            val flight =
                lock.withLock {
                    val selected =
                        flights[video]
                            ?: Flight(scope.async(start = CoroutineStart.LAZY) { enricher.facts(video) }).also {
                                flights[video] =
                                    it
                            }
                    selected.waiters++
                    selected
                }
            try {
                flight.result.start()
                val result = flight.result.await() as? VideoFactsResult.Ok ?: return@withTimeoutOrNull null
                currentCoroutineContext().ensureActive()
                if (clock.elapsedRealtime() >= deadline || result.facts.videoId != video ||
                    result.facts.descriptionTruncated
                ) {
                    return@withTimeoutOrNull null
                }
                val text = result.facts.description?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull null
                if (text.encodeToByteArray().size > 32 * 1024) return@withTimeoutOrNull null
                val current = db.episodeDao().byId(episodeId) ?: return@withTimeoutOrNull null
                if (current.externalMediaId != video ||
                    current.podcastId != row.podcastId
                ) {
                    return@withTimeoutOrNull null
                }
                if (clock.elapsedRealtime() >= deadline) return@withTimeoutOrNull null
                writer.fillDescriptionIfAbsent(episodeId, text, DescriptionCompleteness.COMPLETE)
                currentCoroutineContext().ensureActive()
                if (clock.elapsedRealtime() >= deadline) return@withTimeoutOrNull null
                val after = db.episodeDao().byId(episodeId) ?: return@withTimeoutOrNull null
                if (after.podcastId != row.podcastId || after.externalMediaId != video) return@withTimeoutOrNull null
                text
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        flight.waiters--
                        if (flight.waiters == 0) {
                            if (flights[video] === flight) flights.remove(video)
                            flight.result.cancel()
                        }
                    }
                }
            }
        }
    }
}
