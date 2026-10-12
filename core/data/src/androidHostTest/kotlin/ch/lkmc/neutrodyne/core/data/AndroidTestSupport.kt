// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import android.content.Context
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.SourceAdapter
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import java.util.UUID
import kotlin.random.Random

/**
 * The androidHostTest mirror of `desktopTest/TestSupport.kt` — the shared fixture builders land
 * in `:core:testing` with a later milestone; duplicating keeps the source sets independent
 * (documented in 03's deviation note for the golden helper).
 */
internal fun newDb(
    context: Context,
    clock: Clock = TestClock(),
): NeutrodyneDatabase = TestDb.inMemory(context, clock = clock)

internal fun newIngestor(
    db: NeutrodyneDatabase,
    clock: Clock,
    settings: SettingsRepository = FakeSettingsRepository(),
) = FeedIngestor(db, JsoupShowNotesSanitizer(), settings, clock, Dispatchers.Default)

internal fun newRefresher(
    context: Context,
    db: NeutrodyneDatabase,
    adapters: Map<SourceType, SourceAdapter>,
    clock: Clock,
    settings: SettingsRepository = FakeSettingsRepository(),
    eventBus: IngestionEventBus = IngestionEventBus(),
    syncHook: SyncIngestHook = SyncIngestHook.None,
    tempFiles: FeedTempFiles = FeedTempFiles(StoragePaths(context), FileSystem.SYSTEM),
    random: Random = Random(1),
    ingestor: FeedIngestor = newIngestor(db, clock, settings),
): FeedRefresher = FeedRefresher(db, adapters, ingestor, eventBus, syncHook, tempFiles, settings, clock, random)

/** A subscribed podcast row whose `feedKey` matches [feedUrl]; returns the new id. */
internal suspend fun seedPodcast(
    db: NeutrodyneDatabase,
    feedUrl: String,
    feedKey: String = feedUrl,
    title: String = "Seeded",
    status: PodcastStatus = PodcastStatus.ACTIVE,
    initialFetch: Boolean = false,
    subscribedAt: Long = 1_791_000_000_000L,
    nextRefreshAt: Long? = 0L,
    lastSuccessAt: Long? = null,
    lastAttemptAt: Long? = null,
    lastFullFetchAt: Long? = null,
    etag: String? = null,
    lastModified: String? = null,
    contentSha256: String? = null,
    parserVersion: Int = 2,
    lastParseOk: Boolean = true,
    latestEpisodeAt: Long? = null,
    complete: Boolean = false,
    ttlMinutes: Int? = null,
    pagingNextUrl: String? = null,
    pagingComplete: Boolean = true,
    failureCount: Int = 0,
    gone: Boolean = false,
    needsCredentials: Boolean = false,
    podcastGuid: String? = null,
    podcastGuidDerived: Boolean = false,
    syncId: String = UUID.randomUUID().toString(),
    block: PodcastEntity.() -> PodcastEntity = { this },
): Long =
    db.podcastDao().insertPodcast(
        PodcastEntity(
            syncId = syncId,
            sourceType = SourceType.RSS,
            feedUrl = feedUrl,
            feedKey = feedKey,
            title = title,
            author = null,
            link = "https://example.com",
            artworkUrl = null,
            artworkKey = "key-$feedKey",
            status = status,
            initialFetch = initialFetch,
            subscribedAt = subscribedAt,
            latestEpisodeAt = latestEpisodeAt,
            etag = etag,
            lastModified = lastModified,
            contentSha256 = contentSha256,
            parserVersion = parserVersion,
            lastParseOk = lastParseOk,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            lastFullFetchAt = lastFullFetchAt,
            nextRefreshAt = nextRefreshAt,
            failureCount = failureCount,
            complete = complete,
            ttlMinutes = ttlMinutes,
            pagingNextUrl = pagingNextUrl,
            pagingComplete = pagingComplete,
            gone = gone,
            needsCredentials = needsCredentials,
            podcastGuid = podcastGuid,
            podcastGuidDerived = podcastGuidDerived,
        ).block(),
    )

/** A scripted `SourceAdapter` (03 Source adapters): [results] answers `fetchAndParse` per feed. */
internal class StubSourceAdapter(
    val results: MutableMap<String, AdapterResult> = mutableMapOf(),
    private val onFetch: suspend (DueFeed, FetchMode) -> AdapterResult? = { _, _ -> null },
) : SourceAdapter {
    override val sourceType: SourceType = SourceType.RSS

    /** `(podcastId, mode)` of every `fetchAndParse`, in call order. */
    val calls = mutableListOf<Pair<Long, FetchMode>>()

    override fun hostKey(feed: DueFeed): String =
        feed.feedUrl
            .substringAfter("://", feed.feedUrl)
            .substringBefore("/")
            .lowercase()

    override suspend fun fetchAndParse(
        feed: DueFeed,
        mode: FetchMode,
    ): AdapterResult {
        calls += feed.id to mode
        onFetch(feed, mode)?.let { return it }
        val key = if (mode == FetchMode.OLDER_PAGE) feed.pagingNextUrl else feed.feedUrl
        return results[key]
            ?: AdapterResult.Failed(
                FeedErrorKind.UNKNOWN,
                http = null,
                retryAfterMs = null,
                transient = true,
            )
    }

    override fun nextRefreshAt(
        feed: DueFeed,
        result: AdapterResult,
        base: Long,
    ): Long = base

    override suspend fun afterIngest(
        podcastId: Long,
        inserted: List<Long>,
        newIds: List<Long>,
    ): List<Long> = newIds
}

internal fun stubAdapter(
    results: MutableMap<String, AdapterResult> = mutableMapOf(),
    onFetch: suspend (DueFeed, FetchMode) -> AdapterResult? = { _, _ -> null },
) = StubSourceAdapter(results, onFetch)

internal fun adapterFailed(kind: FeedErrorKind = FeedErrorKind.UNKNOWN) =
    AdapterResult.Failed(kind = kind, http = null, retryAfterMs = null, transient = true)

/** A scheduler fake: records every call; `reschedulePeriodic` still bumps a counter. */
internal class FakeRefreshScheduler : RefreshScheduler {
    data class NowRequest(
        val scope: RefreshScope,
        val force: Boolean,
        val pagesOnly: Boolean,
        val origin: RefreshOrigin,
    )

    val nowRequests = mutableListOf<NowRequest>()
    var rescheduleCount = 0
    var continuationCount = 0
    var firstFetchCount = 0
    var continuationResult = true

    override fun enqueueNow(
        scope: RefreshScope,
        force: Boolean,
        pagesOnly: Boolean,
        origin: RefreshOrigin,
    ) {
        nowRequests += NowRequest(scope, force, pagesOnly, origin)
    }

    override suspend fun reschedulePeriodic() {
        rescheduleCount++
    }

    override suspend fun enqueueContinuation(): Boolean {
        continuationCount++
        return continuationResult
    }

    override fun requestFirstFetch() {
        firstFetchCount++
    }
}
