// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryAccess
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderFailure
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.feeds.directory.DirectoryIds
import ch.lkmc.neutrodyne.feeds.directory.DirectoryParsers
import ch.lkmc.neutrodyne.feeds.discovery.DirectoryRecord
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.HttpStatusCode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import kotlin.math.abs

internal fun directoryHit(
    row: DirectoryRecord,
    provider: ProviderId,
): DirectoryHit? {
    val url = row.feedUrl?.let(::directoryUrl) ?: return null
    if (row.dead) return null
    return DirectoryHit(
        feedUrl = url,
        title = row.title.orEmpty(),
        author = row.author,
        artworkUrl = row.artworkUrl?.let(::directoryUrl),
        description = row.description,
        episodeCount = row.episodeCount?.coerceAtLeast(0),
        lastEpisodeAt = row.lastEpisodeAt,
        podcastGuid = row.podcastGuid?.let(PodcastGuid::parse),
        providers = persistentSetOf(provider),
        genres = row.genres.toImmutableList(),
        explicit = row.explicit,
        ranks = persistentMapOf(provider to row.rank),
    )
}

internal fun validDirectoryUrl(url: String): Boolean {
    val authority =
        url
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore('?')
            .substringBefore('#')
    return (url.startsWith("https://", true) || url.startsWith("http://", true)) && authority.isNotBlank() &&
        '@' !in authority && url.none { it.isISOControl() } && UrlNormalizer.forIdentity(url) != null
}

private fun directoryUrl(url: String): String? {
    if (!validDirectoryUrl(url)) return null
    return url.substringBefore("://").lowercase() + "://" + url.substringAfter("://")
}

private fun failure(code: Int): ProviderStatus =
    when (code) {
        HttpStatusCode.Forbidden.value, HttpStatusCode.TooManyRequests.value -> ProviderStatus.RateLimited
        HttpStatusCode.Unauthorized.value -> ProviderStatus.Failed(null, ProviderFailure.CREDENTIALS)
        else -> ProviderStatus.Failed(null)
    }

private fun <T> parseReply(
    reply: DirectoryHttp.Reply,
    parse: (String) -> T,
): Outcome<T, ProviderStatus> {
    if (reply.code !in 200..299) return Outcome.Failure(failure(reply.code))
    return try {
        Outcome.Success(parse(reply.text))
    } catch (
        _: Exception,
    ) {
        Outcome.Failure(ProviderStatus.Failed(null))
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<AppleDirectoryClient>())
@Inject
internal class AppleSearchProvider(
    private val http: DirectoryHttp,
    private val settings: DirectorySettings,
    private val limiter: TokenBucket,
    clock: Clock,
) : AppleDirectoryClient {
    private val searches = DirectoryCache<ImmutableList<DirectoryHit>>(clock, SEARCH_CAPACITY, SEARCH_TTL)
    private val lookups = DirectoryCache<ImmutableList<DirectoryHit>>(clock, SEARCH_CAPACITY, SEARCH_TTL)
    private val chartCache = DirectoryCache<ImmutableList<DirectoryHit>>(clock, CHART_CAPACITY, CHART_TTL)

    override suspend fun search(
        query: String,
        country: String,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> {
        val config = settings.current()
        if (!config.apple) return Outcome.Failure(ProviderStatus.Disabled)
        return searches.load("${config.version}:$country:${query.trim().lowercase()}") {
            request(
                "https://itunes.apple.com/search",
                mapOf(
                    "media" to "podcast",
                    "entity" to "podcast",
                    "term" to query,
                    "country" to country,
                    "limit" to "50",
                ),
            ) { text ->
                DirectoryParsers.apple(text).mapNotNull { directoryHit(it, ProviderId.APPLE) }.toImmutableList()
            }.takeIf { settings.current().version == config.version } ?: Outcome.Failure(ProviderStatus.Disabled)
        }
    }

    override suspend fun lookup(
        ids: ImmutableList<String>,
        access: DirectoryAccess,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> {
        val config = settings.current()
        if (!config.apple && access != DirectoryAccess.ONE_TIME_LOOKUP) return Outcome.Failure(ProviderStatus.Disabled)
        if (ids.size > MAX_LOOKUP_IDS || ids.any { !DirectoryIds.isValid(it) }) {
            return Outcome.Failure(ProviderStatus.Failed(null))
        }
        if (ids.isEmpty()) return Outcome.Success(persistentListOf())
        return lookups.load("${config.version}:${ids.joinToString(",")}") {
            request(
                "https://itunes.apple.com/lookup",
                mapOf("id" to ids.joinToString(","), "entity" to "podcast"),
            ) { text ->
                val rows = DirectoryParsers.apple(text).associateBy { it.directoryId }
                ids
                    .mapIndexedNotNull {
                        i,
                        id,
                        ->
                        rows[id]?.copy(rank = i + 1)?.let { directoryHit(it, ProviderId.APPLE) }
                    }.toImmutableList()
            }.takeIf { settings.current().version == config.version } ?: Outcome.Failure(ProviderStatus.Disabled)
        }
    }

    override suspend fun charts(
        genre: ChartGenre?,
        country: String,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> {
        val config = settings.current()
        if (!config.apple) return Outcome.Failure(ProviderStatus.Disabled)
        return chartCache.load("${config.version}:$country:${genre?.id}") {
            val url =
                if (genre ==
                    null
                ) {
                    "https://rss.marketingtools.apple.com/api/v2/${country.lowercase()}/podcasts/top/100/podcasts.json"
                } else {
                    "https://itunes.apple.com/${country.lowercase()}/rss/toppodcasts/limit=100/genre=${genre.id}/json"
                }
            when (val ids = request(url, emptyMap(), DirectoryParsers::chartIds)) {
                is Outcome.Failure -> ids
                is Outcome.Success -> lookup(ids.value.toImmutableList(), DirectoryAccess.ENABLED_ONLY)
            }.takeIf { settings.current().version == config.version } ?: Outcome.Failure(ProviderStatus.Disabled)
        }
    }

    private suspend fun <T> request(
        url: String,
        params: Map<String, String>,
        parse: (String) -> T,
    ): Outcome<T, ProviderStatus> {
        if (!limiter.take()) return Outcome.Failure(ProviderStatus.RateLimited)
        return when (val result = http.get(url, params)) {
            is Outcome.Failure -> {
                result
            }

            is Outcome.Success -> {
                if (result.value.code == HttpStatusCode.Forbidden.value ||
                    result.value.code == HttpStatusCode.TooManyRequests.value
                ) {
                    limiter.cooldown()
                }
                parseReply(result.value, parse)
            }
        }
    }

    private companion object {
        const val CHART_CAPACITY = 20
        const val CHART_TTL = 6 * 60 * 60 * 1000L
    }
}

@SingleIn(AppScope::class)
@Inject
internal class FyydSearchProvider(
    private val http: DirectoryHttp,
    private val settings: DirectorySettings,
    clock: Clock,
) {
    private val cache = DirectoryCache<ImmutableList<DirectoryHit>>(clock, SEARCH_CAPACITY, SEARCH_TTL)

    suspend fun search(
        query: String,
        config: DirectoryConfiguration,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> =
        cache.load("${config.version}:${query.lowercase()}") {
            when (
                val result =
                    http.get(
                        "https://api.fyyd.de/0.2/search/podcast",
                        mapOf(
                            "title" to query,
                            "count" to "50",
                        ),
                    )
            ) {
                is Outcome.Failure -> {
                    result
                }

                is Outcome.Success -> {
                    parseReply(result.value) {
                        DirectoryParsers
                            .fyyd(it)
                            .mapNotNull { row ->
                                directoryHit(row, ProviderId.FYYD)
                            }.toImmutableList()
                    }
                }
            }.takeIf { settings.current().version == config.version } ?: Outcome.Failure(ProviderStatus.Disabled)
        }
}

internal class PodcastIndexSearchProvider(
    private val http: DirectoryHttp,
    private val settings: DirectorySettings,
    private val clock: Clock,
) : PodcastIndexDirectoryClient {
    private val cache = DirectoryCache<IndexAnswer>(clock, SEARCH_CAPACITY, SEARCH_TTL)
    private val skewMutex = kotlinx.coroutines.sync.Mutex()
    private var offsetMs = 0L

    suspend fun search(
        query: String,
        config: DirectoryConfiguration,
    ): Outcome<IndexAnswer, ProviderStatus> =
        cached("search:$query", "search/byterm", mapOf("q" to query, "max" to "50"), config)

    override suspend fun lookup(feedId: String): Outcome<DirectoryHit?, ProviderStatus> {
        val config = settings.current()
        if (!config.effective(ProviderId.PODCAST_INDEX)) return Outcome.Failure(ProviderStatus.Disabled)
        if (!DirectoryIds.isValid(feedId)) return Outcome.Failure(ProviderStatus.Failed(null))
        return when (val result = cached("lookup:$feedId", "podcasts/byfeedid", mapOf("id" to feedId), config)) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.Success(result.value.hits.firstOrNull())
        }
    }

    private suspend fun cached(
        key: String,
        route: String,
        params: Map<String, String>,
        config: DirectoryConfiguration,
    ): Outcome<IndexAnswer, ProviderStatus> {
        if (!config.effective(ProviderId.PODCAST_INDEX)) return Outcome.Failure(ProviderStatus.Disabled)
        return cache.load("${config.version}:$key") {
            when (val result = request(route, params, checkNotNull(config.pair))) {
                is Outcome.Failure -> {
                    result
                }

                is Outcome.Success -> {
                    parseReply(result.value) {
                        val rows = DirectoryParsers.podcastIndex(it)
                        IndexAnswer(
                            rows
                                .mapNotNull { row ->
                                    directoryHit(row, ProviderId.PODCAST_INDEX)
                                }.toImmutableList(),
                            rows
                                .filter { row ->
                                    row.medium !in listOf(null, "podcast", "video")
                                }.mapNotNull { row ->
                                    row.feedUrl
                                }.toSet(),
                        )
                    }
                }
            }.takeIf { settings.current().version == config.version } ?: Outcome.Failure(ProviderStatus.Disabled)
        }
    }

    suspend fun verify(pair: Pair<String, String>): Outcome<Unit, ProviderStatus> =
        when (
            val result =
                request(
                    "search/byterm",
                    mapOf("q" to "podcast", "max" to "1"),
                    pair,
                )
        ) {
            is Outcome.Failure -> {
                result
            }

            is Outcome.Success -> {
                parseReply(result.value) {
                    DirectoryParsers.podcastIndex(it)
                    Unit
                }
            }
        }

    private suspend fun request(
        route: String,
        params: Map<String, String>,
        pair: Pair<String, String>,
    ): Outcome<DirectoryHttp.Reply, ProviderStatus> =
        kotlinx.coroutines.withTimeoutOrNull(DirectoryHttp.DEADLINE_MS) {
            requestWithSkew(route, params, pair)
        } ?: Outcome.Failure(ProviderStatus.Failed(ch.lkmc.neutrodyne.core.model.NetError.Timeout))

    private suspend fun requestWithSkew(
        route: String,
        params: Map<String, String>,
        pair: Pair<String, String>,
    ): Outcome<DirectoryHttp.Reply, ProviderStatus> {
        var offset = skewMutex.withLock { offsetMs }
        repeat(2) { attempt ->
            val date = ((clock.now() + offset) / 1000).toString()
            val auth = (pair.first + pair.second + date).encodeUtf8().sha1().hex()
            val result =
                http.get(
                    "https://api.podcastindex.org/api/1.0/$route",
                    params,
                    mapOf("X-Auth-Key" to pair.first, "X-Auth-Date" to date, "Authorization" to auth),
                )
            if (result !is Outcome.Success) return result
            val serverDate = result.value.dateMs
            if (attempt == 0 && result.value.code == HttpStatusCode.Unauthorized.value && serverDate != null &&
                abs(serverDate - clock.now() - offset) > SKEW_THRESHOLD_MS
            ) {
                offset = serverDate - clock.now()
                skewMutex.withLock { offsetMs = offset }
            } else {
                if (result.value.code == HttpStatusCode.Unauthorized.value ||
                    result.value.code == HttpStatusCode.Forbidden.value
                ) {
                    return Outcome.Failure(ProviderStatus.Failed(null, ProviderFailure.CREDENTIALS))
                }
                return result
            }
        }
        return Outcome.Failure(ProviderStatus.Failed(null))
    }

    private companion object {
        const val SKEW_THRESHOLD_MS = 60_000L
    }
}

/** Private fusion metadata keeps the frozen public hit/rank contract unchanged. */
internal class IndexAnswer(
    val hits: ImmutableList<DirectoryHit>,
    val downranked: Set<String>,
)

private const val SEARCH_CAPACITY = 50
private const val SEARCH_TTL = 30 * 60 * 1000L

private const val MAX_LOOKUP_IDS = 100
