// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.model.Enclosure
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

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
        ).block(),
    )

internal suspend fun dueFeedOf(
    db: NeutrodyneDatabase,
    id: Long,
): DueFeed = checkNotNull(db.podcastDao().dueFeedById(id)) { "podcast $id missing" }
