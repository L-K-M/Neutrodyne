// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** One app-wide refresh budget. Admission is owned solely by the engine, never by this caller. */
@SingleIn(AppScope::class)
@Inject
internal class YouTubeEnrichment(
    private val db: NeutrodyneDatabase,
    private val enricher: YouTubeEnricher,
    private val writer: YouTubeFactsWriter,
    private val clock: Clock,
    private val random: Random,
    private val capabilities: YouTubeCapabilitiesSource,
) {
    private val permits = Semaphore(2)
    private val lock = Mutex()
    private val active = mutableMapOf<Long, kotlinx.coroutines.Job>()
    private val channels = ArrayDeque<Long>()
    private val checks = ArrayDeque<Long>()
    private var admitted = 0

    suspend fun refresh(podcastId: Long): List<Long> {
        if (!capabilities.capabilities.value.enrichment) return emptyList()
        val job = currentCoroutineContext()[kotlinx.coroutines.Job] ?: return emptyList()
        if (!lock.withLock {
                if (podcastId in active) {
                    false
                } else {
                    active[podcastId] = job
                    true
                }
            }
        ) {
            return emptyList()
        }
        val deadline = clock.elapsedRealtime() + 20_000
        try {
            return withTimeoutOrNull(20_000) {
                permits.withPermit {
                    val row = db.podcastDao().byId(podcastId) ?: return@withPermit emptyList()
                    val channel = row.youtubeChannelId ?: return@withPermit emptyList()
                    if (row.sourceType != SourceType.YOUTUBE_CHANNEL) return@withPermit emptyList()
                    val candidates = db.episodeDao().youTubeCandidates(podcastId, clock.now())
                    if (candidates.isEmpty()) return@withPermit emptyList()
                    val pause =
                        lock.withLock {
                            trim(channels)
                            if (channels.size >= 100) return@withLock null
                            channels.addLast(clock.elapsedRealtime())
                            admitted++
                            if (admitted > 1 && (admitted - 1) % 50 == 0) random.nextLong(6_000, 12_001) else 0L
                        } ?: return@withPermit emptyList()
                    delay(pause + random.nextLong(500, 1501))
                    val needed =
                        candidates.fold(0) { bits, candidate ->
                            val preferred =
                                when {
                                    candidate.isShort -> 2
                                    candidate.availability == Availability.LIVE -> 4
                                    else -> 1
                                }
                            bits or
                                if (row.youtubeVariants and preferred != 0) {
                                    preferred
                                } else {
                                    listOf(1, 2, 4).first { row.youtubeVariants and it != 0 }
                                }
                        }
                    val result =
                        enricher.enrich(
                            channel,
                            candidates.mapTo(linkedSetOf()) { it.externalMediaId },
                            needed,
                        )
                    val facts =
                        (result as? EnrichResult.Ok)
                            ?.facts
                            ?.filter { fact ->
                                candidates.any {
                                    it.externalMediaId ==
                                        fact.videoId
                                }
                            }?.toMutableList()
                            ?: return@withPermit emptyList()
                    // Only disappeared live/premiere items need individual checks. These are
                    // standalone admitted operations after the composite tab operation completes.
                    val missing =
                        candidates
                            .filter {
                                it.availability in
                                    listOf(Availability.LIVE, Availability.UPCOMING) &&
                                    facts.none { fact -> fact.videoId == it.externalMediaId }
                            }.take(5)
                    for (candidate in missing) {
                        val allowed =
                            lock.withLock {
                                trim(checks)
                                if (checks.size >= 20) {
                                    false
                                } else {
                                    checks.addLast(clock.elapsedRealtime())
                                    true
                                }
                            }
                        if (!allowed) break
                        val checked = enricher.facts(candidate.externalMediaId)
                        currentCoroutineContext().ensureActive()
                        if (checked is VideoFactsResult.Ok &&
                            checked.facts.videoId == candidate.externalMediaId
                        ) {
                            facts += checked.facts
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (clock.elapsedRealtime() >= deadline) return@withPermit emptyList()
                    if (facts.size > 90) return@withPermit emptyList()
                    db.withWriteTransaction {
                        val current = db.podcastDao().byId(podcastId)
                        if (current?.youtubeChannelId != channel ||
                            current.youtubeVariants != row.youtubeVariants
                        ) {
                            return@withWriteTransaction emptyList()
                        }
                        currentCoroutineContext().ensureActive()
                        if (clock.elapsedRealtime() >= deadline || !capabilities.capabilities.value.enrichment) {
                            return@withWriteTransaction emptyList()
                        }
                        writer.applyFacts(podcastId, facts)
                        val promoted = candidates.filter { it.availability != Availability.AVAILABLE }.map { it.id }
                        db.episodeDao().youTubeAnnounceable(podcastId, promoted)
                    }
                }
            } ?: emptyList()
        } finally {
            // Mutex cleanup is bounded and must survive the channel deadline.
            kotlinx.coroutines.withContext(
                kotlinx.coroutines.NonCancellable,
            ) { lock.withLock { active.remove(podcastId) } }
        }
    }

    suspend fun cancel(podcastId: Long) {
        lock.withLock { active[podcastId]?.cancel() }
    }

    private fun trim(queue: ArrayDeque<Long>) {
        val now = clock.elapsedRealtime()
        while (queue.firstOrNull()?.let { it <= now - 600_000 || it > now } == true) queue.removeFirst()
    }
}
