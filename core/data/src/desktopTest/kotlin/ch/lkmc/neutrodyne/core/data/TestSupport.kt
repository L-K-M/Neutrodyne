// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.add.AddPodcastResolverImpl
import ch.lkmc.neutrodyne.core.data.add.PreviewCache
import ch.lkmc.neutrodyne.core.data.add.PreviewEntry
import ch.lkmc.neutrodyne.core.data.add.PreviewEntryResolver
import ch.lkmc.neutrodyne.core.data.add.SubscribeUseCaseImpl
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.data.notifications.NewEpisodeNotifier
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FeedMoveCommitter
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.NewFeedUrlProbe
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.RssSourceAdapter
import ch.lkmc.neutrodyne.core.data.refresh.SourceAdapter
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastFetchState
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.model.Enclosure
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import dev.zacsweers.metro.Provider
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
    // The parser marks RSS descriptions and Atom `type="html"` bodies as markup; the helpers
    // hand it HTML, so the stored default matches what a real parse would emit.
    descriptionIsHtml: Boolean = true,
    episodeType: String? = null,
): ParsedEpisode =
    ParsedEpisode(
        feedOrder = order,
        guid = guid,
        title = title,
        pubDate = pubDate,
        descriptionHtml = descriptionHtml,
        descriptionIsHtml = descriptionIsHtml,
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
    paging: Paging = Paging(),
    warnings: List<ParseWarning> = emptyList(),
): ParsedFeed =
    ParsedFeed(
        format = FeedFormat.RSS2,
        title = title,
        items = items,
        podcastGuid = podcastGuid,
        complete = complete,
        ttlMinutes = ttlMinutes,
        medium = medium,
        paging = paging,
        warnings = warnings,
    )

/**
 * Deterministic `OrderKeys` for tests: decimal midpoints inside (0, 1), no jitter — `after` walks
 * `0.5, 0.75, 0.875, …`, `rewrite(n)` hands out `i / (n + 1)`. Equal bounds fail like the real port.
 */
internal object TestOrderKeys : OrderKeys {
    override fun after(last: String?): String = between(last, null)

    override fun between(
        previous: String?,
        next: String?,
    ): String {
        val lower = previous?.toBigDecimal() ?: java.math.BigDecimal.ZERO
        val upper = next?.toBigDecimal() ?: java.math.BigDecimal.ONE
        require(lower < upper) { "unordered bounds: $previous !< $next" }
        return lower
            .add(upper)
            .divide(java.math.BigDecimal("2"))
            .stripTrailingZeros()
            .toPlainString()
    }

    override fun rewrite(count: Int): List<String> =
        (1..count).map {
            java.math
                .BigDecimal(it)
                .divide(java.math.BigDecimal(count + 1), java.math.MathContext(20))
                .stripTrailingZeros()
                .toPlainString()
        }
}

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
): FeedIngestor = FeedIngestor(db, sanitizer, settings, clock, newFeedMoves(db, clock), defaultDispatcher)

internal fun newRefresher(
    db: NeutrodyneDatabase,
    adapters: Map<SourceType, SourceAdapter>,
    clock: TestClock,
    settings: SettingsRepository = FakeSettingsRepository(),
    eventBus: IngestionEventBus = IngestionEventBus(),
    syncHook: SyncIngestHook = SyncIngestHook.None,
    // A unique root per refresher instance — a shared default would leak temp bodies across
    // tests that run in the same JVM.
    tempFiles: FeedTempFiles =
        FeedTempFiles(
            storagePathsFor(
                File("build/tmp/m1a-refresh").resolve(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                ),
            ),
            FileSystem.SYSTEM,
        ),
    random: Random = Random(1),
    ingestor: FeedIngestor = newIngestor(db, clock, settings),
    scheduler: RefreshScheduler = FakeRefreshScheduler(),
    secrets: SecretStore = FakeSecretStore(),
    coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    moves: FeedMoveCommitter = newFeedMoves(db, clock, coordinator),
    effectiveSettings: EffectiveSettingsResolver = EffectiveSettingsResolverImpl(db, settings),
    notifier: NewEpisodeNotifier = NewEpisodeNotifier(db, effectiveSettings, FakeNewEpisodePoster()),
): FeedRefresher =
    FeedRefresher(
        db,
        adapters,
        ingestor,
        eventBus,
        syncHook,
        tempFiles,
        settings,
        effectiveSettings,
        clock,
        random,
        moves,
        ch.lkmc.neutrodyne.core.data.refresh
            .NewFeedUrlProbe(db, clock),
        { scheduler },
        secrets,
        coordinator,
        notifier,
    )

internal fun fetchMeta(
    finalUrl: String = "https://example.com/feed.xml",
    requestedUrl: String = finalUrl,
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
        requestedUrl = requestedUrl,
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
    feedKey: String = UrlNormalizer.forIdentity(feedUrl) ?: feedUrl,
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

    /** Returned by the next [enqueueContinuation]; lets a test simulate a rejected enqueue. */
    var continuationEnqueued = true

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
        return continuationEnqueued
    }

    override fun requestFirstFetch() {
        firstFetchCount++
    }
}

/** Executes one statement inside a `useWriterConnection`/`useReaderConnection` block. */
internal suspend fun androidx.room3.PooledConnection.execSql(sql: String) {
    usePrepared(sql) { it.step() }
}

/** The `Authorization: Basic …` header value 03's auth flows send for `u:p` pairs. */
internal fun basicAuthHeader(
    username: String,
    secret: String,
): String =
    "Basic " +
        kotlin.io.encoding.Base64
            .encode("$username:$secret".encodeToByteArray())

/**
 * Parks the first `put` after the delegate's row commits so a test can run code inside the
 * put→reference gap while the caller still holds `CredentialCommitCoordinator` (03's commit
 * ordering). `putCommitted` signals the gap is open; `release` ends it.
 */
internal class GatedSecretStore(
    private val delegate: SecretStore,
) : SecretStore by delegate {
    val putCommitted = kotlinx.coroutines.CompletableDeferred<Unit>()
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var gated = true

    override suspend fun put(
        origin: String,
        username: String,
        secret: CharArray,
        attributes: Map<String, String>,
    ): Long? {
        val id = delegate.put(origin, username, secret, attributes)
        if (gated) {
            gated = false
            putCommitted.complete(Unit)
            release.await()
        }
        return id
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

    /** Keys `fetchAndParse` looked up with no scripted result — assert this stays empty. */
    val unscriptedKeys = mutableListOf<String?>()

    override fun hostKey(
        feed: DueFeed,
        requestUrl: String,
    ): String =
        requestUrl
            .substringAfter("://", requestUrl)
            .substringBefore("/")
            .substringBefore("?")
            .substringAfterLast("@")
            .lowercase()

    override suspend fun fetchAndParse(
        feed: DueFeed,
        mode: FetchMode,
    ): AdapterResult {
        calls += feed.id to mode
        onFetch(feed, mode)?.let { return it }
        val key = if (mode == FetchMode.OLDER_PAGE) feed.pagingNextUrl else feed.feedUrl
        val scripted = results[key]
        if (scripted == null) unscriptedKeys += key
        return scripted ?: AdapterResult.Failed(
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
    parser: FeedParser = XmlPullFeedParser.discovered(),
): RssAdapterBundle {
    val bundle = newFetcher(root, credentials, clock)
    val adapter =
        RssSourceAdapter(
            fetcher = bundle.fetcher,
            parser = parser,
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
    parser: FeedParser = XmlPullFeedParser.discovered(),
): ResolverBundle {
    val bundle = newFetcher(root, credentials, clock)
    val cache = PreviewCache(clock)
    val resolver =
        AddPodcastResolverImpl(
            fetcher = bundle.fetcher,
            parser = parser,
            sanitizer = JsoupShowNotesSanitizer(),
            tempFiles = bundle.tempFiles,
            fileSystem = FileSystem.SYSTEM,
            cache = cache,
            db = db,
            clock = clock,
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
    secrets: SecretStore = FakeSecretStore(),
    coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    eventBus: IngestionEventBus = IngestionEventBus(),
    orderKeys: OrderKeys = TestOrderKeys,
): SubscribeBundle =
    SubscribeBundle(
        SubscribeUseCaseImpl(
            db = db,
            cache = cache,
            resolver = resolver,
            secrets = secrets,
            coordinator = coordinator,
            ingestor = newIngestor(db, clock, settings),
            eventBus = eventBus,
            scheduler = scheduler,
            settings = settings,
            orderKeys = orderKeys,
            clock = clock,
        ),
        eventBus,
    )

/**
 * `UnsubscribeUseCase` over the real `PodcastRepositoryImpl` (and a real `FeedRefresher`). The
 * `fetcher` provider is never invoked on the unsubscribe path — a fixture reaching it signals a
 * `setCredentials` path exercised without a wired probe.
 */
internal fun newUnsubscribe(
    db: NeutrodyneDatabase,
    scheduler: RefreshScheduler,
    clock: TestClock,
    secrets: SecretStore = FakeSecretStore(),
    coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    fetcher: Provider<FeedFetcher> = Provider { error("setCredentials is not wired in this fixture") },
    tempFiles: FeedTempFiles =
        FeedTempFiles(
            storagePathsFor(
                File("build/tmp/m1b-setcreds").resolve(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                ),
            ),
            FileSystem.SYSTEM,
        ),
): UnsubscribeUseCase =
    UnsubscribeUseCase(
        PodcastRepositoryImpl(
            db = db,
            refresher = newRefresher(db, emptyMap(), clock),
            scheduler = scheduler,
            sanitizer = JsoupShowNotesSanitizer(),
            clock = clock,
            merger = PodcastMerger(db, coordinator, clock),
            resolver = unusedPreviewResolver,
            secrets = secrets,
            coordinator = coordinator,
            fetcher = fetcher,
            tempFiles = tempFiles,
        ),
    )

private fun newFeedMoves(
    db: NeutrodyneDatabase,
    clock: TestClock,
    coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
) = FeedMoveCommitter(db, PodcastMerger(db, coordinator, clock), clock)

/** `setCredentials`'s probe fetcher for fixtures that never set credentials. */
internal val unusedProbeFetcher: Provider<FeedFetcher> =
    Provider { error("setCredentials is not wired in this fixture") }

/** A fresh `FeedTempFiles` under `build/tmp/<tag>` for repository fixtures. */
internal fun scratchTempFiles(tag: String): FeedTempFiles =
    FeedTempFiles(
        storagePathsFor(
            File("build/tmp/$tag").resolve(
                java.util.UUID
                    .randomUUID()
                    .toString(),
            ),
        ),
        FileSystem.SYSTEM,
    )

/** Read/merge-only repository tests must never fetch a preview. */
internal val unusedPreviewResolver =
    object : PreviewEntryResolver {
        override suspend fun probe(input: String): Outcome<PreviewEntry, AddPodcastError> =
            error("Unexpected feed probe")

        override suspend fun resolveEntry(feedUrl: String): Outcome<PreviewEntry, AddPodcastError> =
            error("Unexpected preview resolution")
    }
