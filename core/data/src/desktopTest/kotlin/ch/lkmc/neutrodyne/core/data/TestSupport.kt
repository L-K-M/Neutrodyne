// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.data.add.AddPodcastResolverImpl
import ch.lkmc.neutrodyne.core.data.add.PreviewCache
import ch.lkmc.neutrodyne.core.data.add.SubscribeUseCaseImpl
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.RssSourceAdapter
import ch.lkmc.neutrodyne.core.data.refresh.SourceAdapter
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastFetchState
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.model.Enclosure
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

// --- Feed documents -------------------------------------------------------------------------------

internal fun enclosure(
    url: String,
    type: String? = "audio/mpeg",
    length: Long? = 12345,
): Enclosure = Enclosure(url = url, type = type, length = length, effectiveType = type)

internal fun parsedEpisode(
    order: Int,
    guid: String? = null,
    title: String? = "Episode $order",
    pubDate: Long? = null,
    enclosureUrl: String? = "https://cdn.example.com/ep$order.mp3",
    durationMs: Long? = null,
    externalMediaId: String? = null,
    chaptersUrl: String? = null,
    descriptionHtml: String? = null,
    episodeType: String? = null,
): ParsedEpisode =
    ParsedEpisode(
        feedOrder = order,
        guid = guid,
        title = title,
        pubDate = pubDate,
        descriptionHtml = descriptionHtml,
        primaryEnclosure = enclosureUrl?.let { enclosure(it) },
        durationMs = durationMs,
        externalMediaId = externalMediaId,
        chaptersUrl = chaptersUrl,
        episodeType = episodeType,
    )

internal fun parsedFeed(
    title: String? = "Test Show",
    items: List<ParsedEpisode> = emptyList(),
    podcastGuid: String? = null,
    complete: Boolean = false,
    ttlMinutes: Int? = null,
    medium: String? = null,
): ParsedFeed =
    ParsedFeed(
        format = FeedFormat.RSS2,
        title = title,
        items = items,
        podcastGuid = podcastGuid,
        complete = complete,
        ttlMinutes = ttlMinutes,
        medium = medium,
    )

// --- Database / engine builders --------------------------------------------------------------------

internal fun newDb(
    clock: TestClock = TestClock(),
    queryContext: CoroutineContext = Dispatchers.Default,
): NeutrodyneDatabase = TestDb.inMemory(clock = clock, queryContext = queryContext)

internal fun newIngestor(
    db: NeutrodyneDatabase,
    clock: TestClock,
    settings: SettingsRepository = FakeSettingsRepository(),
    sanitizer: ShowNotesSanitizer = JsoupShowNotesSanitizer(),
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
): FeedIngestor = FeedIngestor(db, sanitizer, settings, clock, defaultDispatcher)

internal fun newRefresher(
    db: NeutrodyneDatabase,
    adapters: Map<SourceType, SourceAdapter>,
    clock: TestClock,
    settings: SettingsRepository = FakeSettingsRepository(),
    eventBus: IngestionEventBus = IngestionEventBus(),
    syncHook: SyncIngestHook = SyncIngestHook.None,
    tempFiles: FeedTempFiles = FeedTempFiles(storagePathsFor(File("build/tmp/m1a-refresh")), FileSystem.SYSTEM),
    random: Random = Random(1),
    ingestor: FeedIngestor = newIngestor(db, clock, settings),
): FeedRefresher = FeedRefresher(db, adapters, ingestor, eventBus, syncHook, tempFiles, settings, clock, random)

internal fun fetchMeta(
    finalUrl: String = "https://example.com/feed.xml",
    sha256Hex: String = "a".repeat(64),
    etag: String? = null,
    lastModified: String? = null,
    permanentUrl: String? = null,
    unconditional: Boolean = false,
    serverDateMs: Long? = null,
    maxAgeSec: Long? = null,
): FetchMeta =
    FetchMeta(
        finalUrl = finalUrl,
        requestedUrl = finalUrl,
        permanentUrl = permanentUrl,
        etag = etag,
        lastModified = lastModified,
        sha256Hex = sha256Hex,
        serverDateMs = serverDateMs,
        maxAgeSec = maxAgeSec,
        unconditional = unconditional,
    )

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
    etag: String? = null,
    lastModified: String? = null,
    contentSha256: String? = null,
    parserVersion: Int = 2,
    lastParseOk: Boolean = true,
    lastFullFetchAt: Long? = null,
    latestEpisodeAt: Long? = null,
    complete: Boolean = false,
    ttlMinutes: Int? = null,
    pagingNextUrl: String? = null,
    pagingComplete: Boolean = true,
    lastSuccessAt: Long? = null,
    failureCount: Int = 0,
    lastAttemptAt: Long? = null,
    gone: Boolean = false,
    needsCredentials: Boolean = false,
    podcastGuid: String? = null,
    podcastGuidDerived: Boolean = false,
    // D98: seeded ingest-test podcasts are covered (subscribed on a provenance-aware build);
    // pass `null` to model a V1-migrated / coverage-less row.
    guidCoverageSince: Long? = subscribedAt,
    syncId: String =
        java.util.UUID
            .randomUUID()
            .toString(),
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
            guidCoverageSince = guidCoverageSince,
        ).block(),
    )

internal suspend fun dueFeedOf(
    db: NeutrodyneDatabase,
    id: Long,
): DueFeed = checkNotNull(db.podcastDao().dueFeedById(id)) { "podcast $id missing" }

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
        return true
    }

    override fun requestFirstFetch() {
        firstFetchCount++
    }
}

internal fun fetchState(
    id: Long,
    nextRefreshAt: Long?,
): PodcastFetchState =
    PodcastFetchState(
        id = id,
        lastAttemptAt = null,
        lastSuccessAt = null,
        nextRefreshAt = nextRefreshAt,
        failureCount = 0,
        lastErrorKind = null,
        lastErrorDetail = null,
        gone = false,
        needsCredentials = false,
        etag = null,
        lastModified = null,
        lastFullFetchAt = null,
        lastParseOk = true,
    )

// --- Refresh-engine doubles -----------------------------------------------------------------------

/**
 * A scripted `SourceAdapter` (03 Source adapters): [result] answers `fetchAndParse` per feed —
 * `OLDER_PAGE` requests key on `feed.pagingNextUrl`, the rest on `feed.feedUrl`. `afterIngest`
 * defaults to the RSS behaviour (newIds unchanged) and every call is recorded.
 */
internal class StubSourceAdapter(
    val results: MutableMap<String, AdapterResult> = mutableMapOf(),
    private val onFetch: suspend (DueFeed, FetchMode) -> AdapterResult? = { _, _ -> null },
    private val announce: suspend (Long, List<Long>, List<Long>) -> List<Long> = { _, _, newIds -> newIds },
) : SourceAdapter {
    override val sourceType: SourceType = SourceType.RSS

    /** `(podcastId, mode)` of every `fetchAndParse`, in call order. */
    val calls = mutableListOf<Pair<Long, FetchMode>>()

    /** `(podcastId, inserted, newIds)` of every `afterIngest`, in call order. */
    val afterIngestCalls = mutableListOf<Triple<Long, List<Long>, List<Long>>>()

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
        return results[key] ?: AdapterResult.Failed(
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
    ): List<Long> {
        afterIngestCalls += Triple(podcastId, inserted, newIds)
        return announce(podcastId, inserted, newIds)
    }
}

internal fun stubAdapter(
    results: MutableMap<String, AdapterResult> = mutableMapOf(),
    onFetch: suspend (DueFeed, FetchMode) -> AdapterResult? = { _, _ -> null },
    announce: suspend (Long, List<Long>, List<Long>) -> List<Long> = { _, _, newIds -> newIds },
) = StubSourceAdapter(results, onFetch, announce)

/** A parsed-document adapter result (03 `AdapterResult.Parsed`). */
internal fun adapterParsed(
    feed: ParsedFeed,
    partial: Boolean = false,
    meta: FetchMeta = fetchMeta(),
) = AdapterResult.Parsed(feed = feed, partial = partial, meta = meta)

/** A real `RssSourceAdapter` over the production fetcher stack for the engine end-to-end tests. */
internal class RssAdapterBundle(
    val adapter: RssSourceAdapter,
    val tempFiles: FeedTempFiles,
    private val fetcher: FetcherBundle,
) : AutoCloseable by fetcher

internal fun newRssAdapter(
    root: File,
    clock: TestClock,
    network: FakeNetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
    credentials: CredentialLookup = CredentialLookup.None,
    io: CoroutineDispatcher = Dispatchers.IO,
): RssAdapterBundle {
    val bundle = newFetcher(root, credentials, clock)
    val adapter =
        RssSourceAdapter(
            fetcher = bundle.fetcher,
            parser = XmlPullFeedParser.discovered(),
            tempFiles = bundle.tempFiles,
            fileSystem = FileSystem.SYSTEM,
            network = network,
            clock = clock,
            io = io,
        )
    return RssAdapterBundle(adapter, bundle.tempFiles, bundle)
}

// --- Add-podcast builders -----------------------------------------------------------------------

/** A real `AddPodcastResolverImpl` over the production fetch/parse stack plus its preview cache. */
internal class ResolverBundle(
    val resolver: AddPodcastResolverImpl,
    val cache: PreviewCache,
    val tempFiles: FeedTempFiles,
    private val fetcher: FetcherBundle,
) : AutoCloseable by fetcher

internal fun newResolver(
    root: File,
    db: NeutrodyneDatabase,
    clock: TestClock,
    credentials: CredentialLookup = CredentialLookup.None,
    io: CoroutineDispatcher = Dispatchers.IO,
): ResolverBundle {
    val bundle = newFetcher(root, credentials, clock)
    val cache = PreviewCache(clock)
    val resolver =
        AddPodcastResolverImpl(
            fetcher = bundle.fetcher,
            parser = XmlPullFeedParser.discovered(),
            sanitizer = JsoupShowNotesSanitizer(),
            tempFiles = bundle.tempFiles,
            fileSystem = FileSystem.SYSTEM,
            cache = cache,
            db = db,
            io = io,
        )
    return ResolverBundle(resolver, cache, bundle.tempFiles, bundle)
}

/** The subscribe transaction over real collaborators; [eventBus] stays reachable for collects. */
internal class SubscribeBundle(
    val useCase: SubscribeUseCaseImpl,
    val eventBus: IngestionEventBus,
)

internal fun newSubscribe(
    db: NeutrodyneDatabase,
    cache: PreviewCache,
    resolver: AddPodcastResolverImpl,
    scheduler: RefreshScheduler,
    settings: SettingsRepository,
    clock: TestClock,
    eventBus: IngestionEventBus = IngestionEventBus(),
    orderKeys: OrderKeys = OrderKeys { last -> if (last == null) "a" else "$last~" },
): SubscribeBundle =
    SubscribeBundle(
        SubscribeUseCaseImpl(
            db = db,
            cache = cache,
            resolver = resolver,
            ingestor = newIngestor(db, clock, settings),
            eventBus = eventBus,
            scheduler = scheduler,
            settings = settings,
            orderKeys = orderKeys,
            clock = clock,
        ),
        eventBus,
    )

/** `UnsubscribeUseCase` over the real `PodcastRepositoryImpl` (and a real `FeedRefresher`). */
internal fun newUnsubscribe(
    db: NeutrodyneDatabase,
    scheduler: RefreshScheduler,
    clock: TestClock,
): UnsubscribeUseCase =
    UnsubscribeUseCase(
        PodcastRepositoryImpl(
            db = db,
            refresher = newRefresher(db, emptyMap(), clock),
            scheduler = scheduler,
            sanitizer = JsoupShowNotesSanitizer(),
            clock = clock,
        ),
    )
