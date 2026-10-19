// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.RowHint
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.LoadOlderResult
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
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

@SingleIn(AppScope::class)
@Inject
internal class YouTubeOlderLoader(
    private val db: NeutrodyneDatabase,
    private val enricher: YouTubeEnricher,
    private val ingestor: FeedIngestor,
    private val writer: YouTubeFactsWriter,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class State(
        val channel: String,
        val variants: Int,
    ) {
        var cursor: UploadsCursor? = null
        var pages = 0
        var complete = false
        var previousDate: Long? = null
    }

    private class Flight(
        val result: Deferred<LoadOlderResult>,
        var waiters: Int = 0,
    )

    private val lock = Mutex()

    // Cancellation must never wait for the transaction it needs to interrupt.
    private val flightLock = Mutex()
    private val states = mutableMapOf<Long, State>()
    private val pageCounts = mutableMapOf<Long, Int>()
    private val flights = mutableMapOf<Long, Flight>()

    suspend fun setVariants(
        id: Long,
        variants: Int,
    ): Boolean {
        val selected = db.podcastDao().byId(id) ?: return false
        require(selected.sourceType == SourceType.YOUTUBE_CHANNEL)
        if (selected.youtubeVariants == variants) return false
        flightLock.withLock { flights.remove(id)?.result?.cancel() }
        return lock.withLock {
            db.withWriteTransaction {
                val row = db.podcastDao().byId(id) ?: return@withWriteTransaction false
                require(row.sourceType == SourceType.YOUTUBE_CHANNEL)
                if (row.youtubeVariants == variants) return@withWriteTransaction false
                states.remove(id)
                db.podcastDao().setYouTubeVariants(id, variants)
                true
            }
        }
    }

    suspend fun load(id: Long): LoadOlderResult {
        val flight =
            flightLock.withLock {
                val selected =
                    flights[id]
                        ?: Flight(scope.async(start = CoroutineStart.LAZY) { loadOwned(id) }).also { flights[id] = it }
                selected.waiters++
                selected
            }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            withContext(NonCancellable) {
                flightLock.withLock {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        if (flights[id] === flight) flights.remove(id)
                        flight.result.cancel()
                    }
                }
            }
        }
    }

    private suspend fun loadOwned(id: Long): LoadOlderResult {
        val deadline = clock.elapsedRealtime() + 45_000
        return withTimeoutOrNull(45_000) {
            val row = db.podcastDao().byId(id) ?: return@withTimeoutOrNull LoadOlderResult.Unsupported
            val channel = row.youtubeChannelId ?: return@withTimeoutOrNull LoadOlderResult.Unsupported
            if (row.sourceType != SourceType.YOUTUBE_CHANNEL) return@withTimeoutOrNull LoadOlderResult.Unsupported
            val state =
                lock.withLock {
                    states[id]?.takeIf { it.channel == channel && it.variants == row.youtubeVariants }
                        ?: State(channel, row.youtubeVariants).also {
                            it.pages = pageCounts[id] ?: 0
                            states[id] = it
                        }
                }
            val variant =
                listOf(1, 2, 4).firstOrNull { row.youtubeVariants and it != 0 }
                    ?: return@withTimeoutOrNull LoadOlderResult.Unsupported
            while (state.pages < 20 && !state.complete) {
                lock.withLock {
                    if (states[id] !== state) {
                        return@withTimeoutOrNull LoadOlderResult.Failed(TransientKind.ENGINE_UNAVAILABLE)
                    }
                }
                val result = enricher.uploadsPage(channel, variant, state.cursor)
                currentCoroutineContext().ensureActive()
                if (clock.elapsedRealtime() >= deadline) {
                    return@withTimeoutOrNull LoadOlderResult.Failed(TransientKind.TIMEOUT)
                }
                when (result) {
                    UploadsPageResult.Unsupported -> {
                        return@withTimeoutOrNull LoadOlderResult.Unsupported
                    }

                    is UploadsPageResult.Failed -> {
                        return@withTimeoutOrNull LoadOlderResult.Failed(result.kind)
                    }

                    is UploadsPageResult.Ok -> {
                        if (result.items.size > 30 || result.items.any { !YouTubeIds.VIDEO.matches(it.videoId) }) {
                            return@withTimeoutOrNull LoadOlderResult.Failed(TransientKind.EXTRACTION)
                        }
                        val applied =
                            lock.withLock {
                                if (states[id] !== state) return@withLock null
                                state.pages++
                                pageCounts[id] = state.pages
                                currentCoroutineContext().ensureActive()
                                db.withWriteTransaction<Triple<Int, Long, UploadsCursor?>?> {
                                    val current = db.podcastDao().dueFeedById(id)
                                    if (current?.youtubeChannelId != channel ||
                                        current.youtubeVariants != state.variants
                                    ) {
                                        return@withWriteTransaction null
                                    }
                                    val known =
                                        db
                                            .ingestDao()
                                            .existing(id)
                                            .mapNotNullTo(hashSetOf()) { it.externalMediaId }
                                    var date = state.previousDate ?: clock.now() / DAY * DAY
                                    val items =
                                        result.items.mapIndexed { index, fact ->
                                            date = fact.publishedAtMs?.let { it / DAY * DAY } ?: date
                                            ParsedEpisode(
                                                feedOrder = index,
                                                guid = "yt:video:${fact.videoId}",
                                                externalMediaId = fact.videoId,
                                                title = fact.title ?: fact.videoId,
                                                pubDate = date,
                                                rawPubDate = "approx",
                                                link = YouTubeFeedUrls.watch(fact.videoId),
                                            )
                                        }
                                    val url = YouTubeFeedUrls.canonical(channel)
                                    currentCoroutineContext().ensureActive()
                                    if (clock.elapsedRealtime() >= deadline) return@withWriteTransaction null
                                    val hints =
                                        result.items.associate { fact ->
                                            fact.videoId to
                                                RowHint(
                                                    fact.availability,
                                                    fact.isShort == true || variant == YouTubeVariantBits.SHORTS,
                                                    false,
                                                )
                                        }
                                    val fetch = FetchMeta(url, url, null, null, null, "", null, null)
                                    val ingest =
                                        ingestor.ingestInTransaction(
                                            current,
                                            ParsedFeed(format = FeedFormat.ATOM, items = items),
                                            IngestContext(IngestMode.OLDER_PAGE, true, fetch, rowHints = hints),
                                        )
                                    // Existing Atom rows keep their feed fields and enrichment; OLDER_PAGE
                                    // only applies facts to rows this page actually introduced.
                                    writer.applyFacts(id, result.items.filter { it.videoId !in known })
                                    Triple(ingest.inserted.size, date, result.next)
                                }
                            } ?: return@withTimeoutOrNull LoadOlderResult.Failed(
                                if (clock.elapsedRealtime() >= deadline) {
                                    TransientKind.TIMEOUT
                                } else {
                                    TransientKind.ENGINE_UNAVAILABLE
                                },
                            )
                        currentCoroutineContext().ensureActive()
                        lock.withLock {
                            if (states[id] !== state) {
                                return@withTimeoutOrNull LoadOlderResult.Failed(TransientKind.ENGINE_UNAVAILABLE)
                            }
                            state.previousDate = applied.second
                            state.cursor = applied.third
                            state.complete = applied.third == null
                        }
                        if (applied.first > 0 || state.complete) {
                            return@withTimeoutOrNull LoadOlderResult.Loaded(
                                applied.first,
                                !state.complete && state.pages < 20,
                            )
                        }
                    }
                }
            }
            LoadOlderResult.Loaded(0, false)
        } ?: LoadOlderResult.Failed(TransientKind.TIMEOUT)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
