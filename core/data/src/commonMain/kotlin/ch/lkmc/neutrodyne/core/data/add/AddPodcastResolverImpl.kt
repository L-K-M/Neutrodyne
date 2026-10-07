// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.add

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.fetch.Sniff
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.resolvedTitle
import ch.lkmc.neutrodyne.core.data.repo.toModel
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.AlreadySubscribed
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.PreviewEpisode
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.parse.EnclosureTypes
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer

/**
 * `AddPodcastResolver` (03 Add podcast flow): normalises input, runs the M1a host checks
 * (YouTube → `YouTubeNotYetSupported`, Spotify → `SpotifyShow`), fetches and sniffs the URL,
 * parses a never-persisted preview, dedupes and caches it. HTML autodiscovery, directory
 * lookups and `Choose` are M7; the `AddInputNormalizer`/`HostRecognizer` split of `:feeds` lands
 * with them — for M1a both live here (deviation 2026-10-07).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AddPodcastResolverImpl(
    private val fetcher: FeedFetcher,
    private val parser: FeedParser,
    private val sanitizer: ShowNotesSanitizer,
    private val tempFiles: FeedTempFiles,
    private val fileSystem: FileSystem,
    private val cache: PreviewCache,
    private val db: NeutrodyneDatabase,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : AddPodcastResolver {
    /**
     * The shared bounded parse lane (03's threading table): capped at [PARSE_PARALLELISM]
     * concurrent parses — a per-call `limitedParallelism` view would cap each call separately.
     */
    private val parseDispatcher = io.limitedParallelism(PARSE_PARALLELISM)

    override suspend fun resolve(input: String): AddResolution = resolveInner(input, null)

    override suspend fun resolve(
        input: String,
        credentials: BasicCredentials,
    ): AddResolution = resolveInner(input, credentials)

    /**
     * `PodcastPreviewKey(feedUrl)`: a directory hit's direct preview (03 Preview and dedupe) —
     * a live cached entry is reused, else the URL is fetched and parsed anew.
     */
    override suspend fun preview(feedUrl: String): Outcome<FeedPreview, AddPodcastError> {
        val entry = cache.findByUrl(feedUrl)
        if (entry != null) return Outcome.Success(buildPreview(entry))
        if (HostChecks.isYouTube(feedUrl)) return Outcome.Failure(AddPodcastError.YouTubeNotYetSupported)
        return when (val r = fetchAndParse(feedUrl, feedUrl, null, schemeGuessed = false)) {
            is PreviewOutcome.Done -> Outcome.Success(buildPreview(r.entry))
            is PreviewOutcome.Failed -> Outcome.Failure(r.error)
        }
    }

    private suspend fun resolveInner(
        input: String,
        credentials: BasicCredentials?,
    ): AddResolution {
        // YouTube pre-check on the raw text and each token (the M1–M2 host check, 03).
        if (input.split(Regex("""\s+""")).any { HostChecks.isYouTube(it) }) {
            return AddResolution.Failure(AddPodcastError.YouTubeNotYetSupported)
        }
        val url =
            when (val n = AddInputNormalizer.normalize(input)) {
                is NormalizedInput.Url -> {
                    n
                }

                NormalizedInput.Invalid -> {
                    return AddResolution.Failure(AddPodcastError.InvalidUrl)
                }

                is NormalizedInput.NotAUrl -> {
                    return AddResolution.Failure(AddPodcastError.NotAUrl(n.query))
                }
            }

        // Every URL the pipeline fetches passes the YouTube check (03 Host recognition).
        if (HostChecks.isYouTube(url.url)) {
            return AddResolution.Failure(AddPodcastError.YouTubeNotYetSupported)
        }
        if (HostChecks.isSpotifyShow(url.url)) return AddResolution.Failure(AddPodcastError.SpotifyShow)

        val creds = credentials ?: url.credentials?.let { BasicCredentials(it.username, it.password) }
        return when (val r = fetchAndParse(url.url, url.url, creds, url.schemeGuessed)) {
            is PreviewOutcome.Done -> AddResolution.Feed(buildPreview(r.entry))
            is PreviewOutcome.Failed -> AddResolution.Failure(r.error)
        }
    }

    /** Fetch → sniff → parse → dedupe → cache. Never persists the parsed feed (D24). */
    private suspend fun fetchAndParse(
        url: String,
        inputUrl: String,
        credentials: BasicCredentials?,
        schemeGuessed: Boolean,
    ): PreviewOutcome {
        var outcome =
            fetcher.fetch(
                FeedRequest(url, etag = null, lastModified = null, conditional = false, credentials = credentials),
            )

        // Scheme-guessed input retries once on `http://` (03 step 5).
        if (schemeGuessed && outcome is FetchOutcome.Network && outcome.error.isTransport) {
            outcome =
                fetcher.fetch(
                    FeedRequest(
                        url.replaceFirst("https://", "http://"),
                        null,
                        null,
                        conditional = false,
                        credentials = credentials,
                    ),
                )
        }
        return when (outcome) {
            is FetchOutcome.Network -> {
                PreviewOutcome.Failed(AddPodcastError.Network(outcome.error))
            }

            is FetchOutcome.Http -> {
                when {
                    (outcome.code == HTTP_UNAUTHORIZED || outcome.code == HTTP_FORBIDDEN) &&
                        outcome.basicChallenge -> {
                        PreviewOutcome.Failed(AddPodcastError.AuthRequired(outcome.realm))
                    }

                    else -> {
                        PreviewOutcome.Failed(AddPodcastError.Http(outcome.code))
                    }
                }
            }

            is FetchOutcome.Storage -> {
                PreviewOutcome.Failed(AddPodcastError.Network(NetError.Other("storage")))
            }

            FetchOutcome.TooLarge -> {
                PreviewOutcome.Failed(AddPodcastError.TooLarge)
            }

            FetchOutcome.RedirectLoop -> {
                PreviewOutcome.Failed(AddPodcastError.Network(NetError.Other("redirect_loop")))
            }

            // A 304 to an unconditional request is a server quirk, not a feed document.
            is FetchOutcome.NotModified -> {
                PreviewOutcome.Failed(AddPodcastError.Http(HTTP_NOT_MODIFIED))
            }

            is FetchOutcome.Body -> {
                bodyOutcome(outcome, inputUrl, credentials)
            }
        }
    }

    private suspend fun bodyOutcome(
        body: FetchOutcome.Body,
        inputUrl: String,
        credentials: BasicCredentials?,
    ): PreviewOutcome {
        when (body.sniff) {
            Sniff.RSS, Sniff.ATOM, Sniff.RDF -> {}

            Sniff.OPML -> {
                tempFiles.delete(body.file)
                return PreviewOutcome.Failed(AddPodcastError.SubscriptionList(body.finalUrl))
            }

            // HTML autodiscovery is M7; JSON and OTHER are never feeds.
            Sniff.HTML, Sniff.JSON, Sniff.OTHER -> {
                tempFiles.delete(body.file)
                return PreviewOutcome.Failed(AddPodcastError.NotAFeed)
            }
        }
        val result =
            try {
                withContext(parseDispatcher) {
                    parser.parse(
                        open = { fileSystem.source(body.file).buffer() },
                        httpCharset = body.charset,
                        baseUrl = body.finalUrl,
                    )
                }
            } finally {
                tempFiles.delete(body.file)
            }
        val feed =
            when (result) {
                is ParseResult.Ok -> {
                    result.feed
                }

                is ParseResult.Failed -> {
                    return PreviewOutcome.Failed(
                        if (result.reason == ParseFailure.NOT_A_FEED) {
                            AddPodcastError.NotAFeed
                        } else {
                            AddPodcastError.Malformed
                        },
                    )
                }
            }

        // Items but nothing playable (03 Accepted items → NoMedia); an empty feed previews fine —
        // except an empty `podcast:medium*L` list feed, which is rejected up front.
        if (feed.items.isNotEmpty() && feed.items.none { it.isAccepted() }) {
            return PreviewOutcome.Failed(AddPodcastError.NoMedia)
        }
        if (feed.items.isEmpty() && feed.medium?.endsWith("L", ignoreCase = true) == true) {
            return PreviewOutcome.Failed(AddPodcastError.UnsupportedListFeed)
        }
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
                unconditional = true,
            )
        val id = cache.put(meta.permanentUrl ?: meta.requestedUrl, inputUrl, feed, meta, body.hops, credentials)
        return PreviewOutcome.Done(cache.get(id) ?: error("fresh preview entry vanished"))
    }

    /**
     * `SubscribeUseCase`'s re-fetch of an evicted preview (03 Subscribe step 1): [feedUrl] is the
     * previewId. Same fetch/parse/cache path as `preview`, returning the cache entry itself.
     */
    internal suspend fun resolveEntry(feedUrl: String): Outcome<PreviewEntry, AddPodcastError> {
        cache.get(feedUrl)?.let { return Outcome.Success(it) }
        return when (val r = fetchAndParse(feedUrl, feedUrl, null, schemeGuessed = false)) {
            is PreviewOutcome.Done -> Outcome.Success(r.entry)
            is PreviewOutcome.Failed -> Outcome.Failure(r.error)
        }
    }

    /** `FeedPreview` of a live cache entry (03 Preview and dedupe). */
    private suspend fun buildPreview(entry: PreviewEntry): FeedPreview {
        val feed = entry.feed
        return FeedPreview(
            previewId = entry.previewId,
            feedUrl = entry.meta.permanentUrl ?: entry.meta.requestedUrl,
            title = feed.title?.takeUnless { it.isBlank() } ?: entry.meta.finalUrl,
            author = feed.author,
            description =
                feed.descriptionHtml?.let {
                    sanitizer.toDocument(it, isHtml = true, baseUri = entry.meta.finalUrl).toModel()
                },
            artworkUrl = feed.artwork.firstOrNull()?.url,
            link = feed.link,
            categories = feed.categories,
            language = feed.language,
            explicit = feed.explicit,
            episodeCount = feed.items.size,
            latestEpisodeAt = feed.items.mapNotNull { it.pubDate }.maxOrNull(),
            episodes = feed.items.take(PREVIEW_EPISODE_LIMIT).map(::previewEpisode),
            hasOlderPages = feed.paging.next != null || feed.paging.prevArchive != null,
            // The chip covers the stored subscription URL AND the fetched terminal URL: a private
            // input behind a public-looking redirect target still marks the feed private.
            isPrivate =
                PrivateFeedUrls.looksPrivate(entry.meta.permanentUrl ?: entry.meta.requestedUrl) ||
                    PrivateFeedUrls.looksPrivate(entry.meta.finalUrl),
            alreadySubscribed = dedupe(entry),
            emptyFeed = feed.items.isEmpty(),
        )
    }

    private fun previewEpisode(e: ParsedEpisode): PreviewEpisode =
        PreviewEpisode(
            title = resolvedTitle(e),
            pubDate = e.pubDate,
            durationMs = e.durationMs,
            snippet = e.descriptionHtml?.let { sanitizer.snippet(it, e.descriptionIsHtml) },
            imageUrl = e.artwork.firstOrNull()?.url,
            isVideo = EnclosureTypes.isVideo(e.primaryEnclosure?.effectiveType),
        )

    /** Exact feedKey/alias match first, then the soft real-`podcastGuid` match (03 dedupe). */
    private suspend fun dedupe(entry: PreviewEntry): AlreadySubscribed? {
        val dao = db.podcastDao()
        // Every URL the fetch produced is a candidate: the normalised input, the first request,
        // each redirect hop, the final URL and a permanent-move target (03 dedupe).
        val keys =
            (
                listOf(entry.inputUrl, entry.meta.requestedUrl, entry.meta.finalUrl, entry.meta.permanentUrl)
                    .filterNotNull() + entry.hops.map { it.url }
            ).mapNotNull(UrlNormalizer::forIdentity)
                .distinct()
        for (key in keys) {
            dao.byFeedKey(key)?.let { return AlreadySubscribed(it.id, exact = true) }
            dao.aliasOwner(key)?.let { return AlreadySubscribed(it, exact = true) }
        }
        val guid = entry.feed.podcastGuid?.let(PodcastGuid::parse) ?: return null
        return dao.byRealGuid(guid).firstOrNull()?.let { AlreadySubscribed(it.id, exact = false) }
    }

    private sealed interface PreviewOutcome {
        data class Done(
            val entry: PreviewEntry,
        ) : PreviewOutcome

        data class Failed(
            val error: AddPodcastError,
        ) : PreviewOutcome
    }

    /** 03's accepted-item rule: a primary enclosure or an `externalMediaId` (04's rows). */
    private fun ParsedEpisode.isAccepted(): Boolean = primaryEnclosure != null || externalMediaId != null

    private val NetError.isTransport: Boolean
        get() =
            when (this) {
                NetError.ConnectionFailed, NetError.Timeout, is NetError.Tls -> true
                else -> false
            }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_MODIFIED = 304
        const val PARSE_PARALLELISM = 2

        /** "the newest 200 items, display-mapped" (03 `FeedPreview.episodes`). */
        const val PREVIEW_EPISODE_LIMIT = 200
    }
}

/**
 * The M1a subset of 03's `HostRecognizer` host table: the YouTube host check that stands in for
 * `YouTubeUrlClassifier` until M3 and the Spotify explanation. Apple/pod.link/Podcast-Index
 * lookups are M7; everything else is fetched and sniffed.
 */
internal object HostChecks {
    fun isYouTube(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be")
    }

    fun isSpotifyShow(url: String): Boolean =
        hostOf(url) == "open.spotify.com" && url.substringAfter("://", "").substringAfter('/').startsWith("show/")

    /** The lowercase host of an http(s) URL or bare `host.tld/…` token, or null. */
    private fun hostOf(url: String): String? {
        val cleaned =
            url
                .removePrefix("feed:")
                .substringAfter("://", url)
                .substringBefore('/')
                .substringAfterLast('@')
                .substringBefore(':')
                .substringBefore('?')
        return cleaned.takeIf { it.isNotEmpty() }?.lowercase()
    }
}
