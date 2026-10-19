// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.playback

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.ChapterEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ChapterRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val parser: Podcasting20ChaptersParser,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ChapterRepository {
    private data class Load(
        val url: String,
        val work: Deferred<Unit>,
    )

    private data class Failure(
        val url: String,
        val at: Long,
    )

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Long, Load>()
    private val failures = mutableMapOf<Long, Failure>()

    override fun observe(episodeId: Long): Flow<List<EpisodeChapter>> =
        db
            .chapterDao()
            .observe(episodeId)
            .map { rows ->
                ChapterSource.winning(rows.map { it.toModel() })
            }.distinctUntilChanged()

    override suspend fun replace(
        episodeId: Long,
        source: ChapterSource,
        chapters: List<EpisodeChapter>,
    ) {
        require(chapters.all { it.source == source })
        val rows = ChapterSource.normalize(chapters).mapIndexed { ordinal, row -> row.toEntity(episodeId, ordinal) }
        db.withWriteTransaction {
            if (db.episodeDao().byId(episodeId) == null) return@withWriteTransaction
            if (db.chapterDao().ofSource(episodeId, source) != rows) db.chapterDao().replace(episodeId, source, rows)
        }
    }

    override suspend fun ensureLoaded(episodeId: Long): Unit =
        coroutineScope {
            val url = db.episodeDao().byId(episodeId)?.chaptersUrl ?: return@coroutineScope
            val load =
                mutex.withLock {
                    val existing = inFlight[episodeId]
                    if (existing?.url == url && !existing.work.isCancelled) return@withLock existing
                    existing?.work?.cancel()
                    val failure = failures[episodeId]
                    if (failure?.url != url) failures.remove(episodeId)
                    if (db.chapterDao().ofSource(episodeId, ChapterSource.PODCASTING20_JSON).isNotEmpty()) {
                        return@withLock null
                    }
                    val backingOff = failure?.url == url && clock.elapsedRealtime() - failure.at < RETRY_MS
                    if (!network.status.value.isConnected || backingOff) return@withLock null
                    val work =
                        async(start = CoroutineStart.LAZY) {
                            val ownJob = currentCoroutineContext()[Job]
                            try {
                                load(episodeId, url)
                            } finally {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        if (inFlight[episodeId]?.work === ownJob) inFlight.remove(episodeId)
                                    }
                                }
                            }
                        }
                    Load(url, work).also { inFlight[episodeId] = it }
                } ?: return@coroutineScope
            load.work.await()
        }

    private suspend fun load(
        episodeId: Long,
        url: String,
    ): Unit =
        supervisorScope {
            val fetch = async { parser.fetch(url) }
            // Ingest invalidates JSON rows. Cancel its obsolete request, and still guard the commit
            // because cancellation and Room invalidations may arrive after the response completes.
            val changed =
                launch {
                    db.chapterDao().observeUrl(episodeId).first { it != url }
                    fetch.cancel()
                }
            try {
                val rows = fetch.await()
                db.withWriteTransaction {
                    if (db.episodeDao().byId(episodeId)?.chaptersUrl == url) {
                        replace(episodeId, ChapterSource.PODCASTING20_JSON, rows)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // A caller's deadline must not consume the network retry allowance.
                currentCoroutineContext().ensureActive()
                recordFailure(episodeId, url)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (db.episodeDao().byId(episodeId)?.chaptersUrl == url) throw cancelled
            } catch (_: Exception) {
                recordFailure(episodeId, url)
            } finally {
                changed.cancel()
            }
        }

    private suspend fun recordFailure(
        episodeId: Long,
        url: String,
    ) {
        mutex.withLock { failures[episodeId] = Failure(url, clock.elapsedRealtime()) }
    }

    private companion object {
        const val RETRY_MS = 24L * 60 * 60 * 1_000
    }
}

private fun ChapterEntity.toModel() = EpisodeChapter(startMs, endMs, title, imageUrl, linkUrl, source, hidden)

private fun EpisodeChapter.toEntity(
    episodeId: Long,
    ordinal: Int,
) = ChapterEntity(episodeId, source, ordinal, startMs, endMs, title, imageUrl, linkUrl, hidden)
