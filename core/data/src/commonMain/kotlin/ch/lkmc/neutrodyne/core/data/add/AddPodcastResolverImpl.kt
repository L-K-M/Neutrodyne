// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.add

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.discovery.AppleDirectoryClient
import ch.lkmc.neutrodyne.core.data.discovery.DirectoryMetadataCache
import ch.lkmc.neutrodyne.core.data.discovery.PodcastIndexDirectoryClient
import ch.lkmc.neutrodyne.core.data.fetch.ADD_YOUTUBE_REJECTED
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequestPurpose
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
import ch.lkmc.neutrodyne.core.model.DirectoryAccess
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.PreviewEpisode
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.feeds.discovery.AddInputNormalizer
import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import ch.lkmc.neutrodyne.feeds.discovery.HostAction
import ch.lkmc.neutrodyne.feeds.discovery.HostRecognizer
import ch.lkmc.neutrodyne.feeds.discovery.NormalizedInput
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.ArtworkCandidate
import ch.lkmc.neutrodyne.feeds.model.ArtworkSource
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.parse.EnclosureTypes
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.buffer

/** Full Add discovery and direct-only Edit probes share fetch/parse/preview ownership (03, D24). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<AddPodcastResolver>())
@ContributesBinding(AppScope::class, binding<PreviewEntryResolver>())
@Inject
internal class AddPodcastResolverImpl(
    private val fetcher: FeedFetcher,
    private val parser: FeedParser,
    private val sanitizer: ShowNotesSanitizer,
    private val tempFiles: FeedTempFiles,
    private val fileSystem: FileSystem,
    private val cache: PreviewCache,
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val apple: AppleDirectoryClient,
    private val podcastIndex: PodcastIndexDirectoryClient,
    private val autodiscovery: Autodiscovery,
    private val metadata: DirectoryMetadataCache,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : AddPodcastResolver,
    PreviewEntryResolver {
    /**
     * The shared bounded parse lane (03's threading table): capped at [PARSE_PARALLELISM]
     * concurrent parses — a per-call `limitedParallelism` view would cap each call separately.
     */
    private val parseDispatcher = io.limitedParallelism(PARSE_PARALLELISM)

    override suspend fun resolve(input: String): AddResolution = withContext(io) { resolveInner(input, null) }

    override suspend fun lookupOnce(input: String): AddResolution =
        withContext(io) { resolveInner(input, null, DirectoryAccess.ONE_TIME_LOOKUP) }

    override suspend fun resolve(
        input: String,
        credentials: BasicCredentials,
    ): AddResolution = withContext(io) { resolveInner(input, credentials) }

    /**
     * `PodcastPreviewKey(feedUrl)`: a directory hit's direct preview (03 Preview and dedupe) —
     * a live cached entry is reused, else the URL is fetched and parsed anew.
     */
    override suspend fun preview(feedUrl: String): Outcome<FeedPreview, AddPodcastError> =
        withContext(io) {
            val entry = cache.findByUrl(feedUrl)
            if (entry != null) return@withContext Outcome.Success(buildPreview(entry))
            if (HostChecks.isYouTube(
                    feedUrl,
                )
            ) {
                return@withContext Outcome.Failure(AddPodcastError.YouTubeNotYetSupported)
            }
            when (val r = fetchAndParse(feedUrl, feedUrl, null, schemeGuessed = false)) {
                is PreviewOutcome.Done -> Outcome.Success(buildPreview(cacheEntry(r.entry)))
                is PreviewOutcome.Failed -> Outcome.Failure(r.error)
                else -> Outcome.Failure(AddPodcastError.NotAFeed)
            }
        }

    private suspend fun resolveInner(
        input: String,
        credentials: BasicCredentials?,
        access: DirectoryAccess = DirectoryAccess.ENABLED_ONLY,
    ): AddResolution {
        if (input.take(MAX_INPUT_CHARS).split(Regex("\\s+")).any {
                HostChecks.isYouTube(it)
            }
        ) {
            return AddResolution.Failure(AddPodcastError.YouTubeNotYetSupported)
        }
        val normalized =
            when (val value = AddInputNormalizer.normalize(input)) {
                is NormalizedInput.Url -> value
                NormalizedInput.Invalid -> return AddResolution.Failure(AddPodcastError.InvalidUrl)
                is NormalizedInput.NotAUrl -> return AddResolution.Failure(AddPodcastError.NotAUrl(value.query))
            }
        val creds = credentials ?: normalized.credentials?.let { BasicCredentials(it.username, it.password) }
        val result =
            resolveUrl(normalized.url, normalized.url, creds, normalized.schemeGuessed, access, DiscoveryMode.FULL)
        return when (result) {
            is PreviewOutcome.Done -> {
                AddResolution.Feed(buildPreview(cacheEntry(result.entry)))
            }

            is PreviewOutcome.Failed -> {
                AddResolution.Failure(result.error)
            }

            is PreviewOutcome.Consent -> {
                AddResolution.LookupRequired(result.input, ProviderId.APPLE)
            }

            is PreviewOutcome.Choices -> {
                AddResolution.Choose(
                    result.entries.map { (entry, source) ->
                        FeedCandidate(
                            entry.meta.permanentUrl ?: entry.meta.requestedUrl,
                            entry.feed.title,
                            entry.feed.items.size,
                            source,
                        )
                    },
                )
            }
        }
    }

    private suspend fun resolveUrl(
        url: String,
        inputUrl: String,
        credentials: BasicCredentials?,
        schemeGuessed: Boolean,
        access: DirectoryAccess,
        mode: DiscoveryMode,
    ): PreviewOutcome {
        if (HostChecks.isYouTube(url)) return PreviewOutcome.Failed(AddPodcastError.YouTubeNotYetSupported)
        return when (val action = HostRecognizer.recognize(url)) {
            is HostAction.AppleLookup -> {
                when (val result = apple.lookup(persistentListOf(action.id), access)) {
                    is Outcome.Success -> {
                        result.value.firstOrNull()?.let { directoryPreview(it, inputUrl, credentials, url) }
                            ?: PreviewOutcome.Failed(AddPodcastError.AppleOnlyShow)
                    }

                    is Outcome.Failure -> {
                        if (result.error == ProviderStatus.Disabled) {
                            PreviewOutcome.Consent(url)
                        } else {
                            directoryFailure(result.error)
                        }
                    }
                }
            }

            is HostAction.PodcastIndexLookup -> {
                when (val result = podcastIndex.lookup(action.id)) {
                    is Outcome.Success -> {
                        result.value?.let { directoryPreview(it, inputUrl, credentials, url) }
                            ?: PreviewOutcome.Failed(AddPodcastError.NotAFeed)
                    }

                    is Outcome.Failure -> {
                        if (result.error ==
                            ProviderStatus.Disabled
                        ) {
                            fetchAndParse(url, inputUrl, credentials, schemeGuessed, mode)
                        } else {
                            directoryFailure(result.error)
                        }
                    }
                }
            }

            HostAction.SpotifyShow -> {
                PreviewOutcome.Failed(AddPodcastError.SpotifyShow)
            }

            else -> {
                fetchAndParse(url, inputUrl, credentials, schemeGuessed, mode)
            }
        }
    }

    private fun directoryFailure(status: ProviderStatus): PreviewOutcome =
        PreviewOutcome.Failed(
            if (status ==
                ProviderStatus.RateLimited
            ) {
                AddPodcastError.DirectoryBusy
            } else {
                AddPodcastError.Network(NetError.Other("directory"))
            },
        )

    private suspend fun directoryPreview(
        hit: DirectoryHit,
        inputUrl: String,
        credentials: BasicCredentials?,
        credentialUrl: String,
    ): PreviewOutcome {
        // An ID lookup does not grant consent to transmit publisher credentials elsewhere.
        val scoped = credentials.takeIf { UrlNormalizer.origin(hit.feedUrl) == UrlNormalizer.origin(credentialUrl) }
        if (HostChecks.isYouTube(hit.feedUrl)) return PreviewOutcome.Failed(AddPodcastError.YouTubeNotYetSupported)
        val result = fetchAndParse(hit.feedUrl, inputUrl, scoped, false)
        if (result !is PreviewOutcome.Done) return result
        val entry = result.entry
        val feed = entry.feed.withDirectoryMetadata(hit)
        return PreviewOutcome.Done(
            PreviewEntry(
                entry.previewId,
                entry.inputUrl,
                feed,
                entry.meta,
                entry.hops,
                entry.credentials,
                entry.createdAt,
            ),
        )
    }

    /** Edit URL always probes afresh; it neither reuses nor retains preview credentials. */
    override suspend fun probe(input: String): Outcome<PreviewEntry, AddPodcastError> =
        withContext(io) { probe(input, null) }

    private suspend fun probe(
        input: String,
        credentials: BasicCredentials?,
    ): Outcome<PreviewEntry, AddPodcastError> {
        // YouTube pre-check on the raw text and each token (the M1–M2 host check, 03).
        if (input.take(MAX_INPUT_CHARS).split(Regex("""\s+""")).any { HostChecks.isYouTube(it) }) {
            return Outcome.Failure(AddPodcastError.YouTubeNotYetSupported)
        }
        val url =
            when (val n = AddInputNormalizer.normalize(input)) {
                is NormalizedInput.Url -> {
                    n
                }

                NormalizedInput.Invalid -> {
                    return Outcome.Failure(AddPodcastError.InvalidUrl)
                }

                is NormalizedInput.NotAUrl -> {
                    return Outcome.Failure(AddPodcastError.NotAUrl(n.query))
                }
            }

        // Every URL the pipeline fetches passes the YouTube check (03 Host recognition).
        if (HostChecks.isYouTube(url.url)) {
            return Outcome.Failure(AddPodcastError.YouTubeNotYetSupported)
        }
        if (HostChecks.isSpotifyShow(url.url)) return Outcome.Failure(AddPodcastError.SpotifyShow)

        val creds = credentials ?: url.credentials?.let { BasicCredentials(it.username, it.password) }
        return when (val r = fetchAndParse(url.url, url.url, creds, url.schemeGuessed)) {
            is PreviewOutcome.Done -> Outcome.Success(r.entry)
            is PreviewOutcome.Failed -> Outcome.Failure(r.error)
            else -> Outcome.Failure(AddPodcastError.NotAFeed)
        }
    }

    /** Shared unconditional fetch → sniff → parse; callers choose whether to cache (D24). */
    private suspend fun fetchAndParse(
        url: String,
        inputUrl: String,
        credentials: BasicCredentials?,
        schemeGuessed: Boolean,
        mode: DiscoveryMode = DiscoveryMode.DIRECT,
    ): PreviewOutcome {
        if (HostChecks.isYouTube(url)) return PreviewOutcome.Failed(AddPodcastError.YouTubeNotYetSupported)
        var outcome =
            fetcher.fetch(
                FeedRequest(
                    url,
                    purpose = FeedRequestPurpose.ADD,
                    etag = null,
                    lastModified = null,
                    conditional = false,
                    credentials = credentials,
                    htmlReadLimit = Autodiscovery.MAX_HTML_BYTES,
                ),
            )

        // A guessed scheme may retry cleartext only without credentials (03 step 5).
        if (schemeGuessed && credentials == null && outcome is FetchOutcome.Network && outcome.error.isTransport) {
            outcome =
                fetcher.fetch(
                    FeedRequest(
                        url.replaceFirst("https://", "http://"),
                        null,
                        null,
                        conditional = false,
                        credentials = credentials,
                        purpose = FeedRequestPurpose.ADD,
                        htmlReadLimit = Autodiscovery.MAX_HTML_BYTES,
                    ),
                )
        }
        return when (outcome) {
            is FetchOutcome.Network -> {
                PreviewOutcome.Failed(
                    if (outcome.error ==
                        ADD_YOUTUBE_REJECTED
                    ) {
                        AddPodcastError.YouTubeNotYetSupported
                    } else {
                        AddPodcastError.Network(outcome.error)
                    },
                )
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
                if (HostChecks.isYouTube(outcome.finalUrl) || outcome.hops.any { HostChecks.isYouTube(it.url) }) {
                    tempFiles.delete(outcome.file)
                    PreviewOutcome.Failed(AddPodcastError.YouTubeNotYetSupported)
                } else if (outcome.sniff == Sniff.HTML && mode == DiscoveryMode.FULL) {
                    discover(outcome, inputUrl, credentials)
                } else {
                    val parsed = bodyOutcome(outcome, inputUrl, credentials)
                    if (mode != DiscoveryMode.DIRECT && parsed is PreviewOutcome.Failed &&
                        parsed.error == AddPodcastError.NoMedia &&
                        outcome.finalUrl
                            .substringBefore('?')
                            .trimEnd('/')
                            .endsWith("/feed")
                    ) {
                        val fallback = outcome.finalUrl.substringBefore('?').trimEnd('/') + "/podcast"
                        fetchAndParse(
                            fallback,
                            inputUrl,
                            credentials.takeIf {
                                UrlNormalizer.origin(fallback) ==
                                    UrlNormalizer.origin(url)
                            },
                            false,
                        )
                    } else {
                        parsed
                    }
                }
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

            // Full Add handles HTML before this direct-feed parse path.
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
            } catch (e: IOException) {
                // A throw from `open` or the buffering read propagates out of `parse` (03
                // Parser): a source fault — the same class `FetchOutcome.Storage` reports.
                return PreviewOutcome.Failed(AddPodcastError.Network(NetError.Other("storage")))
            } finally {
                tempFiles.delete(body.file)
            }
        val parsedFeed =
            when (result) {
                is ParseResult.Ok -> {
                    result.feed
                }

                is ParseResult.Failed -> {
                    return PreviewOutcome.Failed(
                        when (result.reason) {
                            ParseFailure.NOT_A_FEED -> AddPodcastError.NotAFeed
                            ParseFailure.TOO_LARGE -> AddPodcastError.TooLarge
                            else -> AddPodcastError.Malformed
                        },
                    )
                }
            }

        val feed = parsedFeed.withDirectoryMetadata(metadata.forFeed(body.requestedUrl))

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
        // Reject a null subscribe identity before offering the preview (03 Host recognition):
        // the normalizer rejects some parser-accepted hosts, and a feed without an identity can
        // never be subscribed — better to fail here than at the subscribe write path.
        if (UrlNormalizer.forIdentity(meta.permanentUrl ?: meta.requestedUrl) == null) {
            return PreviewOutcome.Failed(AddPodcastError.InvalidUrl)
        }
        val entry =
            PreviewEntry(
                previewId = meta.permanentUrl ?: meta.requestedUrl,
                inputUrl = inputUrl,
                feed = feed,
                meta = meta,
                hops = body.hops,
                credentials = credentials,
                createdAt = clock.now(),
            )
        return PreviewOutcome.Done(entry)
    }

    /**
     * `SubscribeUseCase`'s re-fetch of an evicted preview (03 Subscribe step 1): [feedUrl] is the
     * previewId. Same fetch/parse/cache path as `preview`, returning the cache entry itself.
     */
    override suspend fun resolveEntry(feedUrl: String): Outcome<PreviewEntry, AddPodcastError> =
        withContext(io) {
            cache.get(feedUrl)?.let { return@withContext Outcome.Success(it) }
            when (val r = fetchAndParse(feedUrl, feedUrl, null, schemeGuessed = false)) {
                is PreviewOutcome.Done -> Outcome.Success(cacheEntry(r.entry))
                is PreviewOutcome.Failed -> Outcome.Failure(r.error)
                else -> Outcome.Failure(AddPodcastError.NotAFeed)
            }
        }

    private suspend fun cacheEntry(entry: PreviewEntry): PreviewEntry =
        cache.put(entry.previewId, entry.inputUrl, entry.feed, entry.meta, entry.hops, entry.credentials)

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

    private suspend fun discover(
        body: FetchOutcome.Body,
        inputUrl: String,
        credentials: BasicCredentials?,
    ): PreviewOutcome {
        val candidates =
            try {
                withContext(parseDispatcher) {
                    fileSystem.source(body.file).use { autodiscovery.candidates(it, body.charset, body.finalUrl) }
                }
            } finally {
                tempFiles.delete(body.file)
            }
        if (candidates.isEmpty()) return probes(body.finalUrl, inputUrl, credentials, body.requestedUrl)
        val semaphore = Semaphore(Autodiscovery.VERIFICATION_CONCURRENCY)
        val results =
            coroutineScope {
                candidates
                    .distinctBy { UrlNormalizer.forIdentity(it.url) }
                    .take(Autodiscovery.MAX_CANDIDATES)
                    .map { candidate ->
                        async {
                            semaphore.withPermit {
                                val scoped =
                                    credentials.takeIf {
                                        UrlNormalizer.origin(candidate.url) ==
                                            UrlNormalizer.origin(body.requestedUrl)
                                    }
                                resolveUrl(
                                    candidate.url,
                                    inputUrl,
                                    scoped,
                                    false,
                                    DirectoryAccess.ENABLED_ONLY,
                                    DiscoveryMode.VERIFY,
                                ) to
                                    candidate.source.name
                            }
                        }
                    }.awaitAll()
            }
        results.firstOrNull { (r, _) -> r is PreviewOutcome.Consent }?.let { return it.first }
        results
            .firstOrNull { (r, _) ->
                r is PreviewOutcome.Failed && r.error == AddPodcastError.YouTubeNotYetSupported
            }?.let { return it.first }
        val entries =
            results
                .mapNotNull { (result, source) ->
                    (result as? PreviewOutcome.Done)?.entry?.let {
                        it to
                            source
                    }
                }.distinctBy { UrlNormalizer.forIdentity(it.first.previewId) }
        return when (entries.size) {
            0 -> PreviewOutcome.Failed(AddPodcastError.NotAFeed)
            1 -> PreviewOutcome.Done(entries.single().first)
            else -> PreviewOutcome.Choices(entries)
        }
    }

    private suspend fun probes(
        pageUrl: String,
        inputUrl: String,
        credentials: BasicCredentials?,
        credentialUrl: String,
    ): PreviewOutcome {
        val root = UrlNormalizer.origin(pageUrl) ?: return PreviewOutcome.Failed(AddPodcastError.NotAFeed)
        return withTimeoutOrNull(Autodiscovery.PROBE_BUDGET_MS) {
            for (path in PROBE_PATHS) {
                val url = root + path
                val scoped = credentials.takeIf { UrlNormalizer.origin(url) == UrlNormalizer.origin(credentialUrl) }
                val body =
                    fetcher.fetch(
                        FeedRequest(
                            url,
                            null,
                            null,
                            false,
                            purpose = FeedRequestPurpose.ADD,
                            sniffOnlyBytes = Autodiscovery.PROBE_BYTES.toInt(),
                            credentials = scoped,
                        ),
                    )
                if (body is FetchOutcome.Network && body.error == ADD_YOUTUBE_REJECTED) {
                    return@withTimeoutOrNull PreviewOutcome.Failed(AddPodcastError.YouTubeNotYetSupported)
                }
                if (body !is FetchOutcome.Body) continue
                val feed = body.sniff in setOf(Sniff.RSS, Sniff.ATOM, Sniff.RDF)
                val youtube = HostChecks.isYouTube(body.finalUrl)
                tempFiles.delete(body.file)
                if (youtube) return@withTimeoutOrNull PreviewOutcome.Failed(AddPodcastError.YouTubeNotYetSupported)
                if (feed) return@withTimeoutOrNull fetchAndParse(url, inputUrl, scoped, false)
            }
            PreviewOutcome.Failed(AddPodcastError.NotAFeed)
        } ?: PreviewOutcome.Failed(AddPodcastError.NotAFeed)
    }

    private enum class DiscoveryMode { DIRECT, FULL, VERIFY }

    private sealed interface PreviewOutcome {
        data class Choices(
            val entries: List<Pair<PreviewEntry, String>>,
        ) : PreviewOutcome

        data class Consent(
            val input: String,
        ) : PreviewOutcome

        data class Done(
            val entry: PreviewEntry,
        ) : PreviewOutcome

        data class Failed(
            val error: AddPodcastError,
        ) : PreviewOutcome
    }

    private val NetError.isTransport: Boolean
        get() =
            when (this) {
                NetError.ConnectionFailed, NetError.Timeout, is NetError.Tls -> true
                else -> false
            }

    private companion object {
        const val MAX_INPUT_CHARS = 4096
        val PROBE_PATHS = listOf("/feed/podcast", "/podcast.xml", "/feed", "/rss", "/rss.xml")
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_MODIFIED = 304
        const val PARSE_PARALLELISM = 2

        /** "the newest 200 items, display-mapped" (03 `FeedPreview.episodes`). */
        const val PREVIEW_EPISODE_LIMIT = 200
    }
}

/** 03's accepted-item rule, shared by add previews and explicit moves. */
internal fun ParsedEpisode.isAccepted(): Boolean = primaryEnclosure != null || externalMediaId != null

/** Publisher metadata wins; directory art is the final subscribe fallback (03 Artwork). */
private fun ParsedFeed.withDirectoryMetadata(hit: DirectoryHit?): ParsedFeed {
    if (hit == null) return this
    return copy(
        title = title?.takeIf(String::isNotBlank) ?: hit.title,
        author = author ?: hit.author,
        artwork =
            artwork.ifEmpty {
                hit.artworkUrl?.let { listOf(ArtworkCandidate(it, ArtworkSource.DIRECTORY)) } ?: emptyList()
            },
    )
}

/** Compatibility guard shared with subscribe/edit; classification also covers bare handles/IDs. */
internal object HostChecks {
    private val classifier = YouTubeUrlClassifier()

    fun isYouTube(url: String): Boolean = classifier.classify(url) != null || classifier.isYouTubeHost(url)

    fun isSpotifyShow(url: String): Boolean = HostRecognizer.recognize(url) == HostAction.SpotifyShow
}
