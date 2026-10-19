// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import android.content.Context
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.StoragePaths
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
import ch.lkmc.neutrodyne.core.data.refresh.SourceAdapter
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.model.Enclosure
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
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
    driver: SQLiteDriver = AndroidSQLiteDriver(),
): NeutrodyneDatabase = TestDb.inMemory(context, driver = driver, clock = clock)

internal fun newIngestor(
    db: NeutrodyneDatabase,
    clock: Clock,
    settings: SettingsRepository = FakeSettingsRepository(),
) = FeedIngestor(db, JsoupShowNotesSanitizer(), settings, clock, newFeedMoves(db, clock), Dispatchers.Default)

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
    scheduler: RefreshScheduler = FakeRefreshScheduler(),
    secrets: SecretStore = FakeSecretStore(),
    coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    effectiveSettings: EffectiveSettingsResolver = EffectiveSettingsResolverImpl(db, settings),
    notifier: NewEpisodeNotifier = NewEpisodeNotifier(db, effectiveSettings, FakeNewEpisodePoster()),
    youTubeMonitor: ch.lkmc.neutrodyne.youtube.api.YouTubeFeedOutageMonitor =
        ch.lkmc.neutrodyne.core.testing
            .FakeYouTubeFeedOutageMonitor(),
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
        newFeedMoves(
            db,
            clock,
            coordinator,
        ),
        ch.lkmc.neutrodyne.core.data.refresh
            .NewFeedUrlProbe(db, clock),
        {
            scheduler
        },
        secrets,
        coordinator,
        notifier,
        youTubeMonitor,
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

// --- Feed documents (mirror of desktopTest/TestSupport.kt) ----------------------------------------

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
    descriptionHtml: String? = null,
    // The parser marks RSS descriptions and Atom `type="html"` bodies as markup; the helpers
    // hand it HTML, so the stored default matches what a real parse would emit.
    descriptionIsHtml: Boolean = true,
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
    )

internal fun parsedFeed(
    title: String? = "Test Show",
    items: List<ParsedEpisode> = emptyList(),
    complete: Boolean = false,
    ttlMinutes: Int? = null,
    paging: Paging = Paging(),
    warnings: List<ParseWarning> = emptyList(),
): ParsedFeed =
    ParsedFeed(
        format = FeedFormat.RSS2,
        title = title,
        items = items,
        complete = complete,
        ttlMinutes = ttlMinutes,
        paging = paging,
        warnings = warnings,
    )

internal fun fetchMeta(
    finalUrl: String,
    etag: String? = null,
    lastModified: String? = null,
): FetchMeta =
    FetchMeta(
        finalUrl = finalUrl,
        requestedUrl = finalUrl,
        permanentUrl = null,
        etag = etag,
        lastModified = lastModified,
        sha256Hex = "a".repeat(64),
        serverDateMs = null,
        maxAgeSec = null,
    )

/** A parsed-document adapter result (03 `AdapterResult.Parsed`). */
internal fun adapterParsed(
    feed: ParsedFeed,
    partial: Boolean = false,
    meta: FetchMeta,
) = AdapterResult.Parsed(feed = feed, partial = partial, meta = meta)

/** The `DueFeed` row the engine selects for [id] — `dueForRefresh`'s projection. */
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

private fun newFeedMoves(
    db: NeutrodyneDatabase,
    clock: Clock,
    coordinator: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
) = FeedMoveCommitter(db, PodcastMerger(db, coordinator, clock), clock)
