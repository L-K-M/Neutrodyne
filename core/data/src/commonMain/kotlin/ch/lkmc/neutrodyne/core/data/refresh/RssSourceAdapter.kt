// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.fetch.Sniff
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.TlsKind
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer

/** M1a's interval hint: the fortnightly full-fetch window (03 Validators → full-fetch rule). */
private const val FULL_FETCH_INTERVAL_MS: Long = 14L * 24 * 60 * 60 * 1000

/**
 * The RSS/Atom/RDF adapter of 03 Source adapters (`:core:data` internal, multibound under
 * [SourceType.RSS]): fetches conditionally, hashes, sniffs and parses the body file;
 * metadata/write columns stay the engine's.
 */
@SourceTypeKey(SourceType.RSS)
@ContributesIntoMap(AppScope::class, binding = binding<SourceAdapter>())
@Inject
internal class RssSourceAdapter(
    private val fetcher: FeedFetcher,
    private val parser: FeedParser,
    private val tempFiles: FeedTempFiles,
    private val fileSystem: FileSystem,
    private val network: NetworkMonitor,
    private val clock: Clock,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : SourceAdapter {
    override val sourceType: SourceType = SourceType.RSS

    /**
     * The shared bounded view of 03's threading table ("Parse from temp file"): capped at
     * [PARSE_PARALLELISM] concurrent parses for the adapter's lifetime — a per-call
     * `limitedParallelism` view would cap each call separately.
     */
    private val parseDispatcher = io.limitedParallelism(PARSE_PARALLELISM)

    /** The per-host semaphore key of the fan-out limit (lowercase request host). */
    override fun hostKey(feed: DueFeed): String =
        feed.feedUrl
            .substringAfter("://", feed.feedUrl)
            .substringBefore("/")
            .lowercase()

    override suspend fun fetchAndParse(
        feed: DueFeed,
        mode: FetchMode,
    ): AdapterResult {
        val unconditional =
            when (mode) {
                FetchMode.OLDER_PAGE -> true
                FetchMode.FULL -> true
                FetchMode.REFRESH -> needsFullFetch(feed)
            }
        val url =
            when (mode) {
                FetchMode.OLDER_PAGE -> feed.pagingNextUrl ?: feed.feedUrl
                else -> feed.feedUrl
            }
        val outcome =
            fetcher.fetch(
                FeedRequest(
                    url = url,
                    etag = feed.etag,
                    lastModified = feed.lastModified,
                    conditional = !unconditional,
                ),
            )
        return when (outcome) {
            is FetchOutcome.NotModified -> {
                AdapterResult.NotModified(
                    meta =
                        FetchMeta(
                            finalUrl = feed.feedUrl,
                            requestedUrl = feed.feedUrl,
                            permanentUrl = null,
                            etag = outcome.etag ?: feed.etag,
                            lastModified = outcome.lastModified ?: feed.lastModified,
                            sha256Hex = feed.contentSha256.orEmpty(),
                            serverDateMs = outcome.serverDateMs,
                            maxAgeSec = outcome.maxAgeSec,
                        ),
                )
            }

            is FetchOutcome.Network -> {
                networkOutcome(outcome.error)
            }

            is FetchOutcome.Http -> {
                AdapterResult.Failed(
                    kind = httpErrorKind(outcome.code, outcome.basicChallenge),
                    http = outcome.code,
                    retryAfterMs = outcome.retryAfterMs,
                    // `transient` only gates `gone`: the RSS adapter asks for it on a real 410.
                    transient = outcome.code != HTTP_GONE,
                )
            }

            is FetchOutcome.Storage -> {
                AdapterResult.Failed(
                    FeedErrorKind.STORAGE,
                    http = null,
                    retryAfterMs = null,
                    transient = true,
                    detail = outcome.detail,
                )
            }

            FetchOutcome.TooLarge -> {
                AdapterResult.Failed(FeedErrorKind.TOO_LARGE, http = null, retryAfterMs = null, transient = true)
            }

            FetchOutcome.RedirectLoop -> {
                AdapterResult.Failed(FeedErrorKind.REDIRECT_LOOP, http = null, retryAfterMs = null, transient = true)
            }

            is FetchOutcome.Body -> {
                bodyOutcome(feed, outcome, unconditional)
            }
        }
    }

    /** `NetError.Cancelled` produces no outcome: it propagates so the feed counts as unattempted. */
    private fun networkOutcome(error: NetError): AdapterResult =
        when (error) {
            NetError.Cancelled -> {
                throw CancellationException("feed fetch cancelled")
            }

            else -> {
                AdapterResult.Failed(
                    kind = kindOf(error),
                    http = null,
                    retryAfterMs = null,
                    transient = true,
                    detail = if (error is NetError.Other) error.type else null,
                )
            }
        }

    private suspend fun bodyOutcome(
        feed: DueFeed,
        body: FetchOutcome.Body,
        unconditional: Boolean,
    ): AdapterResult {
        val meta =
            FetchMeta(
                finalUrl = body.finalUrl,
                requestedUrl = body.requestedUrl,
                permanentUrl = body.permanentUrl,
                etag = body.etag,
                lastModified = body.lastModified,
                sha256Hex = body.sha256Hex,
                serverDateMs = body.serverDateMs,
                maxAgeSec = body.maxAgeSec,
                unconditional = unconditional,
            )
        // Byte-identical body: the stored parse still holds — but only when the stored parse is
        // current; a parser-version bump or a failed parse re-parses the same bytes (03).
        if (body.sha256Hex == feed.contentSha256 &&
            feed.parserVersion == FeedParser.VERSION &&
            feed.lastParseOk
        ) {
            tempFiles.delete(body.file)
            return AdapterResult.Unchanged(meta)
        }
        return when (body.sniff) {
            Sniff.RSS, Sniff.ATOM, Sniff.RDF -> {
                parseOutcome(body, meta)
            }

            // An HTML page where a feed was: keep the body for M3's autodiscovery.
            Sniff.HTML -> {
                AdapterResult.Failed(
                    FeedErrorKind.NOT_A_FEED,
                    http = null,
                    retryAfterMs = null,
                    transient = true,
                    htmlBody = body.file,
                )
            }

            Sniff.OPML, Sniff.JSON, Sniff.OTHER -> {
                tempFiles.delete(body.file)
                AdapterResult.Failed(FeedErrorKind.NOT_A_FEED, http = null, retryAfterMs = null, transient = true)
            }
        }
    }

    /** `Parse from temp file` (03 threading): on the shared `IO` lane capped at 2 concurrent parses. */
    private suspend fun parseOutcome(
        body: FetchOutcome.Body,
        meta: FetchMeta,
    ): AdapterResult =
        try {
            val result =
                withContext(parseDispatcher) {
                    parser.parse(
                        open = { fileSystem.source(body.file).buffer() },
                        httpCharset = body.charset,
                        baseUrl = body.finalUrl,
                    )
                }
            when (result) {
                is ParseResult.Ok -> {
                    AdapterResult.Parsed(
                        feed = result.feed,
                        // A page-1 link means the stored window is incomplete until paging ends;
                        // `fh:complete` overrides it — the document is the whole feed (03).
                        partial =
                            (result.feed.paging.next != null || result.feed.paging.prevArchive != null) &&
                                !result.feed.complete,
                        meta = meta,
                    )
                }

                is ParseResult.Failed -> {
                    AdapterResult.Failed(
                        FeedErrorKind.PARSE_ERROR,
                        http = null,
                        retryAfterMs = null,
                        transient = true,
                        detail = "${result.reason}: ${result.detail}",
                    )
                }
            }
        } finally {
            // `tempFile` is deleted when handled, cancelled or superseded.
            tempFiles.delete(body.file)
        }

    /**
     * The unconditional-fetch rule of 03 Validators → full-fetch rule: a parser-version bump, a
     * failed parse or a fortnight without a full fetch (the last skipped while metered).
     */
    private fun needsFullFetch(feed: DueFeed): Boolean =
        feed.parserVersion < FeedParser.VERSION ||
            !feed.lastParseOk ||
            ((feed.lastFullFetchAt ?: 0L) < clock.now() - FULL_FETCH_INTERVAL_MS && !network.status.value.isMetered)

    /** RSS keeps the engine's base schedule (no adapter override for M1a). */
    override fun nextRefreshAt(
        feed: DueFeed,
        result: AdapterResult,
        base: Long,
    ): Long = base

    private fun httpErrorKind(
        code: Int,
        basicChallenge: Boolean,
    ): FeedErrorKind =
        when {
            code == HTTP_GONE -> FeedErrorKind.HTTP_GONE

            code == HTTP_RATE_LIMITED -> FeedErrorKind.HTTP_RATE_LIMITED

            (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN_CODE) && basicChallenge -> FeedErrorKind.HTTP_AUTH

            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN_CODE -> FeedErrorKind.HTTP_FORBIDDEN

            code == HTTP_NOT_FOUND_CODE -> FeedErrorKind.HTTP_NOT_FOUND

            code >= 500 -> FeedErrorKind.HTTP_SERVER

            // A 3xx the chain could not follow (no usable Location) is the final response (03).
            code >= 300 -> FeedErrorKind.HTTP_CLIENT

            else -> FeedErrorKind.HTTP_CLIENT
        }

    private fun kindOf(error: NetError): FeedErrorKind =
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

            // Cancelled is thrown out in `networkOutcome`; Other lands here only via that branch.
            NetError.Cancelled, is NetError.Other -> {
                FeedErrorKind.UNKNOWN
            }
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN_CODE = 403
        const val HTTP_NOT_FOUND_CODE = 404
        const val HTTP_GONE = 410
        const val HTTP_RATE_LIMITED = 429

        /** The `limitedParallelism` cap of 03's threading table ("Parse from temp file"). */
        const val PARSE_PARALLELISM = 2
    }
}
