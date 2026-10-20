// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.RowHint
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import ch.lkmc.neutrodyne.core.data.refresh.SourceAdapter
import ch.lkmc.neutrodyne.core.data.refresh.SourceTypeKey
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.ExistingEpisodeKey
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.TlsKind
import ch.lkmc.neutrodyne.feeds.model.ArtworkCandidate
import ch.lkmc.neutrodyne.feeds.model.ArtworkSource
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import ch.lkmc.neutrodyne.youtube.api.MergedEntry
import ch.lkmc.neutrodyne.youtube.api.VariantResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeEntry
import ch.lkmc.neutrodyne.youtube.api.YouTubeEntryRules
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedOutageMonitor
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.buffer
import kotlin.math.abs

/** All selected variants stay sequential within the refresher's existing youtube.com permit. */
@SourceTypeKey(SourceType.YOUTUBE_CHANNEL)
@ContributesIntoMap(AppScope::class, binding = binding<SourceAdapter>())
@SingleIn(AppScope::class)
@Inject
internal class YouTubeSourceAdapter(
    private val fetcher: FeedFetcher,
    private val parser: FeedParser,
    private val tempFiles: FeedTempFiles,
    private val fileSystem: FileSystem,
    private val db: NeutrodyneDatabase,
    private val health: YouTubeHealth,
    private val monitor: YouTubeFeedOutageMonitor,
    private val metadata: YouTubeChannelMetadata,
    private val clock: Clock,
    private val enrichment: YouTubeEnrichment,
    @Dispatcher(NeutrodyneDispatchers.IO) io: CoroutineDispatcher,
) : SourceAdapter {
    override val sourceType = SourceType.YOUTUBE_CHANNEL
    private val parseDispatcher = io.limitedParallelism(2)

    // Fetches fan out across dispatchers; immutable snapshots keep this best-effort state atomic.
    private val gapRefreshes = MutableStateFlow<Map<Long, Int>>(emptyMap())

    override fun hostKey(
        feed: DueFeed,
        requestUrl: String,
    ): String = "www.youtube.com"

    override suspend fun fetchAndParse(
        feed: DueFeed,
        mode: FetchMode,
    ): AdapterResult {
        require(mode != FetchMode.OLDER_PAGE) { "YouTube has no Atom paging cursor" }
        val gate = monitor.gate(feed.id, clock.now())
        if (gate is FeedGate.Deferred) return AdapterResult.Deferred(gate.untilMs)
        var report = FeedOutcome.INCONCLUSIVE
        try {
            val result = fetchChannel(feed)
            report =
                when (result) {
                    is AdapterResult.Parsed, is AdapterResult.Unchanged -> {
                        FeedOutcome.SUCCESS
                    }

                    is AdapterResult.Failed -> {
                        if (result.kind in
                            OUTAGE_FAILURES
                        ) {
                            FeedOutcome.FAILURE
                        } else {
                            FeedOutcome.INCONCLUSIVE
                        }
                    }

                    else -> {
                        FeedOutcome.INCONCLUSIVE
                    }
                }
            return result
        } finally {
            // An admitted probe must be released even if fetch, parsing or the deadline cancels.
            if (report == FeedOutcome.INCONCLUSIVE) {
                withContext(NonCancellable) { monitor.report(feed.id, report, clock.now()) }
            } else {
                try {
                    monitor.report(feed.id, report, clock.now())
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { monitor.report(feed.id, FeedOutcome.INCONCLUSIVE, clock.now()) }
                    throw cancelled
                }
            }
        }
    }

    private suspend fun fetchChannel(feed: DueFeed): AdapterResult {
        val channelId = requireNotNull(feed.youtubeChannelId)
        val known =
            db
                .ingestDao()
                .existing(feed.id)
                .mapNotNull { row ->
                    row.externalMediaId?.let { it to row }
                }.toMap()
        val urls = YouTubeFeedUrls.pollUrls(channelId, feed.youtubeVariants)
        val results = mutableListOf<VariantResult>()
        val truncatedEntries = mutableListOf<YouTubeEntry>()
        var maxAgeSec: Long? = null
        var primaryServerDateMs: Long? = null
        val now = clock.now()
        for ((index, variant) in urls.withIndex()) {
            var polled = poll(variant.url)
            var fallback = false
            if (index == 0 && polled is Poll.Failed && polled.failure.http in MISSING_STATUSES) {
                polled = poll(YouTubeFeedUrls.canonical(channelId))
                fallback = true
            }
            when (polled) {
                is Poll.Ok -> {
                    maxAgeSec = maxOf(maxAgeSec ?: 0, polled.maxAgeSec ?: 0)
                    if (index == 0) primaryServerDateMs = polled.serverDateMs
                    val truncatedItems =
                        polled.feed.warnings
                            .filter { it.code == WarningCode.TEXT_TRUNCATED }
                            .mapTo(mutableSetOf()) { it.itemIndex }
                    val entries =
                        polled.feed.items.mapNotNull { item ->
                            toEntry(item, known, now, primaryServerDateMs)?.also { entry ->
                                if (null in truncatedItems ||
                                    item.feedOrder in truncatedItems
                                ) {
                                    truncatedEntries += entry
                                }
                            }
                        }
                    // A malformed/non-video document must not masquerade as a complete empty channel.
                    if (entries.size != polled.feed.items.size) return failure(FeedErrorKind.PARSE_ERROR)
                    results += VariantResult.Ok(variant.bit, entries, polled.feed.author, fallback)
                }

                is Poll.Failed -> {
                    val failed = polled.failure
                    if (failed.http == HTTP_FORBIDDEN || failed.http == HTTP_RATE_LIMITED) {
                        health.reportFeedRateLimited(now, failed.retryAfterMs)
                        health.awaitLoaded()
                        return AdapterResult.Deferred(
                            health.state.value.feedRateLimitedUntil
                                ?: (now + maxOf(MIN_RATE_LIMIT_MS, failed.retryAfterMs ?: 0)),
                        )
                    }
                    if (index == 0) return failed
                    results +=
                        if (failed.http in MISSING_STATUSES) {
                            VariantResult.Empty(variant.bit)
                        } else {
                            VariantResult.Failed(variant.bit, failed.http, null)
                        }
                }
            }
        }
        if (!feed.initialFetch &&
            results.filterIsInstance<VariantResult.Ok>().any { result ->
                result.entries.size == YouTubeEntryRules.WINDOW_SIZE && result.entries.all { it.videoId !in known }
            }
        ) {
            gapRefreshes.update { it + (feed.id to GAP_REFRESH_COUNT) }
        }
        val merged = YouTubeEntryRules.merge(results, feed.youtubeVariants)
        // Variant redirects and validators never become channel identity or RSS move announcements.
        val canonical = YouTubeFeedUrls.canonical(channelId)
        val meta =
            FetchMeta(
                canonical,
                canonical,
                null,
                null,
                null,
                merged.digest,
                primaryServerDateMs,
                maxAgeSec,
                unconditional = true,
            )
        if (merged.digest == feed.contentSha256 && feed.parserVersion == FeedParser.VERSION && feed.lastParseOk &&
            !feed.initialFetch
        ) {
            return AdapterResult.Unchanged(meta)
        }
        return AdapterResult.Parsed(
            feed =
                ParsedFeed(
                    format = FeedFormat.ATOM,
                    title = merged.authorName,
                    author = merged.authorName,
                    items = merged.entries.map(::toParsed),
                    // Merge retains the winning entry object while reordering/ORing Shorts.
                    // Equal retained text from another variant is not provenance for that winner.
                    warnings =
                        merged.entries
                            .filter { selected -> truncatedEntries.any { it === selected.entry } }
                            .map { ParseWarning(WarningCode.TEXT_TRUNCATED, it.feedOrder) },
                ),
            partial = true,
            meta = meta,
            absenceFloor = merged.absenceFloor,
            rowHints = merged.entries.associate { it.entry.videoId to RowHint(null, it.isShort, false) },
        )
    }

    private suspend fun poll(url: String): Poll =
        when (
            val result =
                fetcher.fetch(
                    FeedRequest(url, null, null, conditional = false, maxBytes = MAX_BYTES, accept = ATOM_ACCEPT),
                )
        ) {
            is FetchOutcome.Body -> {
                try {
                    val parsed =
                        withContext(parseDispatcher) {
                            parser.parse(
                                { fileSystem.source(result.file).buffer() },
                                result.charset,
                                result.finalUrl,
                                ParseLimits(maxDocumentBytes = MAX_BYTES),
                            )
                        }
                    if (parsed is ParseResult.Ok && parsed.feed.format == FeedFormat.ATOM) {
                        Poll.Ok(parsed.feed, result.maxAgeSec, result.serverDateMs)
                    } else {
                        Poll.Failed(failure(FeedErrorKind.PARSE_ERROR))
                    }
                } catch (e: IOException) {
                    Poll.Failed(failure(FeedErrorKind.STORAGE))
                } finally {
                    tempFiles.delete(result.file)
                }
            }

            is FetchOutcome.Http -> {
                Poll.Failed(
                    AdapterResult.Failed(
                        httpKind(result.code),
                        result.code,
                        result.retryAfterMs,
                        transient = true,
                    ),
                )
            }

            is FetchOutcome.Network -> {
                if (result.error == NetError.Cancelled) throw CancellationException("YouTube feed cancelled")
                Poll.Failed(failure(networkKind(result.error)))
            }

            is FetchOutcome.Storage -> {
                Poll.Failed(failure(FeedErrorKind.STORAGE))
            }

            FetchOutcome.TooLarge -> {
                Poll.Failed(failure(FeedErrorKind.TOO_LARGE))
            }

            FetchOutcome.RedirectLoop -> {
                Poll.Failed(failure(FeedErrorKind.REDIRECT_LOOP))
            }

            is FetchOutcome.NotModified -> {
                Poll.Failed(failure(FeedErrorKind.HTTP_CLIENT))
            }
        }

    private fun toEntry(
        item: ParsedEpisode,
        known: Map<String, ExistingEpisodeKey>,
        now: Long,
        serverDateMs: Long?,
    ): YouTubeEntry? {
        val id = item.externalMediaId?.takeIf(YouTubeIds.VIDEO::matches) ?: return null
        val published = item.atomDates?.publishedAtMs
        val valid = published?.takeIf { it in MIN_PUBLISHED_MS..(now + YEAR_MS) }
        val firstSeen =
            known[id]?.firstSeenAt
                ?: if (serverDateMs != null && abs(now - serverDateMs) > DAY_MS) serverDateMs else now
        return YouTubeEntry(
            id,
            item.title.orEmpty(),
            published,
            item.atomDates?.rawPublished,
            minOf(valid ?: firstSeen, firstSeen + DAY_MS),
            item.descriptionHtml.orEmpty(),
            item.link,
        )
    }

    private fun toParsed(merged: MergedEntry): ParsedEpisode {
        val entry = merged.entry
        return ParsedEpisode(
            feedOrder = merged.feedOrder,
            guid = "yt:video:${entry.videoId}",
            externalMediaId = entry.videoId,
            title = entry.title,
            pubDate = entry.publishedAtMs,
            rawPubDate = entry.rawPublished,
            descriptionHtml = entry.description,
            descriptionIsHtml = false,
            link = YouTubeFeedUrls.watch(entry.videoId),
            artwork =
                listOf(
                    ArtworkCandidate(YouTubeEntryRules.thumbnail(entry.videoId), ArtworkSource.MEDIA_THUMBNAIL),
                ),
        )
    }

    override fun nextRefreshAt(
        feed: DueFeed,
        result: AdapterResult,
        base: Long,
    ): Long {
        if (result is AdapterResult.Deferred) return result.untilMs
        val now = clock.now()
        val floor = now + MIN_INTERVAL_MS
        if (result !is AdapterResult.Parsed && result !is AdapterResult.Unchanged) return maxOf(base, floor)
        val left =
            gapRefreshes.getAndUpdate { counts ->
                val count = counts[feed.id] ?: return@getAndUpdate counts
                if (count <= 1) counts - feed.id else counts + (feed.id to count - 1)
            }[feed.id] ?: 0
        if (base == RefreshPolicy.NEVER) return base
        if (left == 0) return maxOf(base, floor)
        val maxAge =
            when (result) {
                is AdapterResult.Parsed -> result.meta.maxAgeSec
                is AdapterResult.Unchanged -> result.meta.maxAgeSec
                else -> null
            }
        val serverFloor = now + (maxAge ?: 0).coerceIn(0, DAY_MS / 1000L) * 1000L
        return maxOf(floor, serverFloor, now + (base - now) / 2)
    }

    override suspend fun afterIngest(
        podcastId: Long,
        inserted: List<Long>,
        newIds: List<Long>,
    ): List<Long> {
        val pending =
            db
                .episodeDao()
                .youTubeCandidates(podcastId, clock.now())
                .filter { it.availability != ch.lkmc.neutrodyne.core.model.Availability.AVAILABLE }
                .map { it.id }
        try {
            enrichment.refresh(podcastId)
            metadata.refresh(podcastId, ChannelMetadataReason.AFTER_INGEST)
        } catch (e: CancellationException) {
            // Preserve a promotion already committed before cancellation; no further extraction.
        } catch (e: Exception) {
            ch.lkmc.neutrodyne.core.common.Log
                .w("YouTubeSourceAdapter", e) { "post-ingest enrichment failed" }
        }
        val ids = (newIds + pending).distinct()
        return withContext(NonCancellable) {
            if (ids.isEmpty()) emptyList() else db.episodeDao().youTubeAnnounceable(podcastId, ids)
        }
    }

    private fun failure(kind: FeedErrorKind) = AdapterResult.Failed(kind, null, null, transient = true)

    private fun httpKind(code: Int): FeedErrorKind =
        when (code) {
            404 -> FeedErrorKind.HTTP_NOT_FOUND
            410 -> FeedErrorKind.HTTP_GONE
            HTTP_FORBIDDEN -> FeedErrorKind.HTTP_FORBIDDEN
            HTTP_RATE_LIMITED -> FeedErrorKind.HTTP_RATE_LIMITED
            in 500..599 -> FeedErrorKind.HTTP_SERVER
            else -> FeedErrorKind.HTTP_CLIENT
        }

    private fun networkKind(error: NetError): FeedErrorKind =
        when (error) {
            NetError.Offline -> {
                FeedErrorKind.OFFLINE
            }

            NetError.Timeout -> {
                FeedErrorKind.TIMEOUT
            }

            NetError.DnsFailure -> {
                FeedErrorKind.DNS
            }

            NetError.ConnectionFailed -> {
                FeedErrorKind.CONNECTION
            }

            NetError.LocalNetworkUnsupported -> {
                FeedErrorKind.LOCAL_NETWORK_UNSUPPORTED
            }

            is NetError.Tls -> {
                when (error.kind) {
                    TlsKind.UNTRUSTED_CERTIFICATE -> FeedErrorKind.TLS_UNTRUSTED
                    TlsKind.CERTIFICATE_TRANSPARENCY -> FeedErrorKind.TLS_CERTIFICATE_TRANSPARENCY
                    TlsKind.HANDSHAKE -> FeedErrorKind.TLS_HANDSHAKE
                }
            }

            else -> {
                FeedErrorKind.UNKNOWN
            }
        }

    private sealed interface Poll {
        data class Ok(
            val feed: ParsedFeed,
            val maxAgeSec: Long?,
            val serverDateMs: Long?,
        ) : Poll

        data class Failed(
            val failure: AdapterResult.Failed,
        ) : Poll
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        const val ATOM_ACCEPT = "application/atom+xml, application/xml;q=0.9, */*;q=0.1"
        const val MIN_INTERVAL_MS = 15L * 60_000
        const val MIN_RATE_LIMIT_MS = 30L * 60_000
        const val HTTP_FORBIDDEN = 403
        const val HTTP_RATE_LIMITED = 429
        const val GAP_REFRESH_COUNT = 3
        const val DAY_MS = 86_400_000L
        const val YEAR_MS = 365L * DAY_MS
        const val MIN_PUBLISHED_MS = 631_152_000_000L
        val MISSING_STATUSES = setOf(404, 410)
        val OUTAGE_FAILURES =
            setOf(
                FeedErrorKind.HTTP_NOT_FOUND,
                FeedErrorKind.HTTP_GONE,
                FeedErrorKind.HTTP_SERVER,
                FeedErrorKind.TIMEOUT,
                FeedErrorKind.DNS,
                FeedErrorKind.CONNECTION,
                FeedErrorKind.TLS_UNTRUSTED,
                FeedErrorKind.TLS_CERTIFICATE_TRANSPARENCY,
                FeedErrorKind.TLS_HANDSHAKE,
            )
    }
}
