// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.map
import ch.lkmc.neutrodyne.core.data.add.HostChecks
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DIRECTORY_PROVIDER_ORDER
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderFailure
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.feeds.directory.AppleGenres
import ch.lkmc.neutrodyne.feeds.discovery.AddInputNormalizer
import ch.lkmc.neutrodyne.feeds.discovery.NormalizedInput
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<SearchRepository>())
@Inject
internal class SearchRepositoryImpl(
    private val apple: AppleDirectoryClient,
    private val fyyd: FyydSearchProvider,
    private val pi: PodcastIndexSearchProvider,
    private val settings: DirectorySettings,
    private val metadata: DirectoryMetadataCache,
    private val secrets: () -> SecretStore,
    private val db: () -> NeutrodyneDatabase,
    @Dispatcher(NeutrodyneDispatchers.Default) private val cpu: CoroutineDispatcher,
) : SearchRepository {
    private val lastStatuses = MutableStateFlow<Pair<Long, Map<ProviderId, ProviderStatus>>>(0L to emptyMap())
    private val keyMutex = Mutex()

    override fun search(query: String): Flow<SearchResults> =
        channelFlow {
            val text = query.trim().take(MAX_QUERY_CHARS)
            val config = settings.current()
            val safe = safeDirectoryQuery(query)
            val statuses =
                DIRECTORY_PROVIDER_ORDER
                    .associateWith { id ->
                        if (safe && config.effective(id)) ProviderStatus.Pending else ProviderStatus.Disabled
                    }.toMutableMap()
            val active = statuses.filterValues { it == ProviderStatus.Pending }.keys
            send(SearchResults(text, persistentListOf(), statuses.toImmutableMap(), active.isEmpty()))
            if (active.isEmpty()) return@channelFlow

            val guard =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    settings.changes.first { it.version != config.version }
                    this@channelFlow.cancel("Directory configuration changed")
                }
            val responses = kotlinx.coroutines.channels.Channel<SearchAnswer>(active.size)
            try {
                active.forEach { id ->
                    launch {
                        var weights = emptySet<String>()
                        val answer =
                            withTimeoutOrNull(DirectoryHttp.DEADLINE_MS) {
                                when (id) {
                                    ProviderId.APPLE -> {
                                        apple.search(text, config.country)
                                    }

                                    ProviderId.FYYD -> {
                                        fyyd.search(text, config)
                                    }

                                    ProviderId.PODCAST_INDEX -> {
                                        pi.search(text, config).map {
                                            weights = it.downranked
                                            it.hits
                                        }
                                    }
                                }
                            } ?: Outcome.Failure(ProviderStatus.Failed(NetError.Timeout))
                        responses.send(SearchAnswer(id, answer, weights))
                    }
                }
                // Only the collector owns mutable aggregation state and database annotations.
                val answers = mutableMapOf<ProviderId, ImmutableList<DirectoryHit>>()
                var indexWeights = emptySet<String>()
                repeat(active.size) {
                    val (id, answer, weights) = responses.receive()
                    if (settings.current().version !=
                        config.version
                    ) {
                        throw CancellationException("Directory configuration changed")
                    }
                    statuses[id] =
                        when (answer) {
                            is Outcome.Success -> {
                                answers[id] = answer.value
                                ProviderStatus.Ok
                            }

                            is Outcome.Failure -> {
                                answer.error
                            }
                        }
                    if (id == ProviderId.PODCAST_INDEX) indexWeights = weights
                    val complete = statuses.values.none { it == ProviderStatus.Pending }
                    val hits = annotate(fuseDirectoryHits(answers, indexWeights))
                    metadata.remember(config.version, hits)
                    send(SearchResults(text, hits, statuses.toImmutableMap(), complete))
                    lastStatuses.value = config.version to statuses.toMap()
                }
            } finally {
                responses.close()
                guard.cancel()
            }
        }.flowOn(cpu)

    private data class SearchAnswer(
        val id: ProviderId,
        val answer: Outcome<ImmutableList<DirectoryHit>, ProviderStatus>,
        val weights: Set<String>,
    )

    override suspend fun charts(genre: ChartGenre?): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> =
        withContext(cpu) {
            val config = settings.current()
            when (val result = apple.charts(genre, config.country)) {
                is Outcome.Success -> {
                    if (settings.current().version ==
                        config.version
                    ) {
                        val hits =
                            result.value
                                .map {
                                    it.copy(
                                        title = it.title.ifBlank { it.feedUrl },
                                    )
                                }.toImmutableList()
                        metadata.remember(config.version, hits)
                        Outcome.Success(annotate(hits))
                    } else {
                        Outcome.Failure(ProviderStatus.Disabled)
                    }
                }

                is Outcome.Failure -> {
                    result
                }
            }
        }

    private suspend fun annotate(hits: ImmutableList<DirectoryHit>): ImmutableList<DirectoryHit> {
        val keys = hits.mapNotNull { UrlNormalizer.forIdentity(it.feedUrl) }.distinct().take(MAX_KEYS)
        if (keys.isEmpty()) return hits
        val subscribed =
            db()
                .podcastDao()
                .directorySubscriptions(
                    keys,
                ).asReversed()
                .associate { it.url to it.podcastId }
        return hits
            .map {
                it.copy(
                    subscribedPodcastId = subscribed[UrlNormalizer.forIdentity(it.feedUrl)],
                )
            }.toImmutableList()
    }

    override fun genreForGroupName(name: String): ChartGenre? =
        AppleGenres.idForGroupName(name)?.let(ChartGenre::fromId)

    override fun observeProviders(): Flow<ImmutableList<ProviderInfo>> =
        combine(settings.changes, lastStatuses) { config, statuses ->
            DIRECTORY_PROVIDER_ORDER
                .map { id ->
                    ProviderInfo(
                        id,
                        when (id) {
                            ProviderId.APPLE -> config.apple
                            ProviderId.FYYD -> config.fyyd
                            ProviderId.PODCAST_INDEX -> config.pi
                        },
                        if (id == ProviderId.PODCAST_INDEX) config.source else DirectoryKeySource.NONE,
                        if (config.effective(id)) {
                            statuses.second.takeIf { statuses.first == config.version }?.get(id)
                                ?: ProviderStatus.Pending
                        } else {
                            ProviderStatus.Disabled
                        },
                        config.version,
                    )
                }.toImmutableList()
        }.distinctUntilChanged()

    override suspend fun setPodcastIndexKey(
        key: String,
        secret: CharArray,
    ): Outcome<Unit, ProviderStatus> =
        withContext(cpu) {
            keyMutex.withLock {
                if (key.isBlank() || secret.isEmpty() || key.any { it.isISOControl() } ||
                    secret.any { it.isISOControl() }
                ) {
                    return@withLock Outcome.Failure(ProviderStatus.Failed(null, ProviderFailure.CREDENTIALS))
                }
                when (val result = pi.verify(key to secret.concatToString())) {
                    is Outcome.Failure -> {
                        result
                    }

                    is Outcome.Success -> {
                        currentCoroutineContext().ensureActive()
                        try {
                            secrets().put("podcastindex", key, secret)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            return@withLock Outcome.Failure(ProviderStatus.Failed(NetError.Other("storage")))
                        }
                        lastStatuses.update { it.first to (it.second - ProviderId.PODCAST_INDEX) }
                        settings.keyChanged()
                        Outcome.Success(Unit)
                    }
                }
            }
        }

    override suspend fun clearPodcastIndexKey() =
        keyMutex.withLock {
            secrets().remove("podcastindex")
            lastStatuses.update { it.first to (it.second - ProviderId.PODCAST_INDEX) }
            settings.keyChanged()
        }

    private companion object {
        const val MAX_QUERY_CHARS = 4096
        const val MAX_KEYS = 150
    }
}

/** Guard the repository boundary as well as W5's input UI: no automatic URL/private searches. */
internal fun safeDirectoryQuery(query: String): Boolean =
    query.isNotBlank() && query.length <= 4096 &&
        query.none { it == '@' || it == '/' || it == '\\' || it == ':' || it.isISOControl() } &&
        query.split(Regex("\\s+")).all {
            !it.equals("localhost", true) && !HostChecks.isYouTube(it) &&
                AddInputNormalizer.normalize(it) is NormalizedInput.NotAUrl
        }

/** Connected components handle URL↔GUID bridges independently of arrival/provider order. */
internal fun fuseDirectoryHits(
    answers: Map<ProviderId, ImmutableList<DirectoryHit>>,
    indexDownranked: Set<String> = emptySet(),
): ImmutableList<DirectoryHit> {
    val groups = mutableListOf<MutableList<DirectoryHit>>()
    for (id in DIRECTORY_PROVIDER_ORDER) {
        for (hit in answers[id].orEmpty()) {
            val matching =
                groups.filter { rows ->
                    rows.any {
                        UrlNormalizer.forIdentity(it.feedUrl) ==
                            UrlNormalizer.forIdentity(hit.feedUrl) ||
                            (hit.podcastGuid != null && it.podcastGuid == hit.podcastGuid)
                    }
                }
            val group = matching.firstOrNull() ?: mutableListOf<DirectoryHit>().also { groups += it }
            matching.drop(1).forEach {
                group.addAll(it)
                groups.remove(it)
            }
            group += hit
        }
    }
    val weighted =
        groups.map { rows ->
            val first = rows.first()
            val ranks =
                DIRECTORY_PROVIDER_ORDER
                    .mapNotNull { id ->
                        rows.mapNotNull { it.ranks[id] }.minOrNull()?.let { id to it }
                    }.toMap()
                    .toImmutableMap()
            val merged =
                first.copy(
                    title = rows.firstOrNull { it.title.isNotBlank() }?.title ?: first.feedUrl,
                    artworkUrl = rows.firstNotNullOfOrNull { it.artworkUrl },
                    author = rows.firstNotNullOfOrNull { it.author },
                    podcastGuid = rows.firstNotNullOfOrNull { it.podcastGuid },
                    episodeCount = rows.mapNotNull { it.episodeCount }.maxOrNull(),
                    providers = ranks.keys.toImmutableSet(),
                    ranks = ranks,
                )
            val score =
                ranks.entries.sumOf { (id, rank) ->
                    val half =
                        id == ProviderId.PODCAST_INDEX &&
                            rows.any {
                                it.ranks[id] == rank && it.feedUrl in indexDownranked
                            }
                    (if (half) OTHER_MEDIUM_WEIGHT else 1.0) / (RRF_BASE + rank)
                }
            merged to score
        }
    return weighted
        .sortedWith(
            compareByDescending<Pair<DirectoryHit, Double>> { it.second }
                .thenBy { pair -> DIRECTORY_PROVIDER_ORDER.indexOfFirst { it in pair.first.providers } }
                .thenBy {
                    it.first.ranks.values
                        .minOrNull()
                }.thenBy { it.first.feedUrl },
        ).map { it.first }
        .toImmutableList()
}

private const val RRF_BASE = 10
private const val OTHER_MEDIUM_WEIGHT = 0.5
